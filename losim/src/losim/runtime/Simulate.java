package losim.runtime;

import io.grpc.BindableService;
import io.grpc.Channel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Workload;
import losim.res.InstanceCatalog;
import losim.sim.Simulation;
import losim.sim.Simulation.*;
import losim.time.Clock;
import losim.time.Dispatcher;
import losim.trace.Telemetry;
import losim.trace.Trace;
import losim.trace.Values;
import losim.verify.Trust;

/**
 * A simulation, simulated.
 *
 * <p>Everything the file declared is assembled here in one order that matters:
 * the nodes and their services first, because the retry gate has to be checked
 * against what the system really serves; then {@code losim.Job/Load}, off the
 * clock, which is the last thing that happens before anything is measured; then
 * the failures, which need every node to exist before one can be aimed at; then
 * the sampler; and only then {@code losim.Job/Run}.
 *
 * <p>The simulation ends when {@code Run} returns, fails, or outstays its
 * welcome. All three are recorded — a run that failed is a result, not an absence
 * of one.
 */
public final class Simulate {

    /**
     * What a run produced. A failure is part of the result, not an exception thrown past it.
     *
     * @param trust which machines' figures mean what they say. Never a reason not to
     *              run: everything it can find yields a wrong number rather than a
     *              broken run, so the run happens and the number is marked (D11)
     */
    public record Result(Trace trace, Telemetry telemetry, boolean completed,
                         String failure, double durationRefMs,
                         Map<String, Totals> machines, Trust trust) {

        /**
         * What one machine actually consumed, straight from its own counters.
         *
         * <p>Not read back off the sampled series: that is quantised to the precision
         * a person reads, and fitting a law against numbers rounded to a hundredth of
         * a megabyte fits the rounding rather than the workload.
         */
        public record Totals(String name, long peakRetainedBytes, long allocatedBytes,
                             long diskBytes, long bytesOut, long bytesIn, long crossZoneBytes,
                             long handledCalls, long losimBytes, long losimStops,
                             double memoryCapMb, boolean alive) {}

        public double peakOf(java.util.function.ToDoubleFunction<Totals> of) {
            double peak = 0;
            for (Totals t : machines.values()) peak = Math.max(peak, of.applyAsDouble(t));
            return peak;
        }

        public double sumOf(java.util.function.ToDoubleFunction<Totals> of) {
            double sum = 0;
            for (Totals t : machines.values()) sum += of.applyAsDouble(t);
            return sum;
        }
    }

    private Simulate() {}

    public static Result of(Simulation s) throws Exception {
        return of(s, Thread.currentThread().getContextClassLoader());
    }

    public static Result of(Simulation s, ClassLoader loader) throws Exception {
        return of(s, loader, Telemetry.Level.FULL);
    }

    public static Result of(Simulation s, ClassLoader loader, Telemetry.Level level) throws Exception {
        return of(s, loader, level, Trust.unchecked());
    }

    /**
     * @param trust what the verifier made of the code before any of it ran. Checked
     *              once and handed in, because a probe grid runs the same classes
     *              thirty times and disassembling them thirty times would be thirty
     *              times the answer
     */
    /**
     * Calibrates the clock, and says what it found rather than refusing on it.
     *
     * <p>A calibration can be erratic — a starved host measured 1.74 to 2.19
     * where an idle one measures 1.28, and an x86_64 container under Rosetta
     * measures 6.58 — and its level can sit too high to be a real timer. Neither
     * is grounds to refuse the run.
     *
     * <p>{@link Clock#spend} parks a cost repeatedly until it is actually paid,
     * so the correction decides how many parks a cost takes, not how much time
     * it gets. Measured across corrections from 1.0 to 20.0, and on a host
     * starved of every core, five 200 refMs costs take 1000 ms either way. A
     * refusal on calibration grounds would stop runs that are right — an
     * instructor's Mac, a shared CI runner, a laptop with a build going — to
     * prevent nothing.
     *
     * <p>The figures are written into the trace instead, beside
     * {@code unpaidCosts}, which is the direct measure: how many declared costs
     * ran out of parks with time still owed. That is the thing worth refusing
     * on: a fact about the run rather than a prediction made before it.
     */
    private static Clock.Calibration calibrate() {
        Clock.Calibration c = Clock.calibrate();
        if (!c.usable()) {
            // Said once, on stderr, and never as a reason not to run. A person
            // whose numbers look odd should be able to find this line.
            System.err.printf(
                    "losim: this host's timer measured %.2f (ordinarily about 1.28)%s."
                            + " Costs are parked until they are paid, so durations are still"
                            + " served in full — expect more parks, not less time. The figures"
                            + " are in the trace as parkCorrection and hostNoise.%n",
                    c.correction(),
                    c.quiet() ? "" : String.format(" and %.0f%% of sample parks were wild", c.noise() * 100));
        }
        return c;
    }

    /**
     * How long a run is given in <b>real</b> seconds before it is called stuck.
     *
     * <p>Not a prediction of how long anything takes — nobody can say that in
     * advance, which is what the run is for. It is the line past which a job is no
     * longer slow but hung, and it is in real time because that is the only clock
     * still trustworthy when the simulated one has stopped advancing.
     */
    public static final long WATCHDOG_SECONDS = 120;

    public static Result of(Simulation s, ClassLoader loader, Telemetry.Level level, Trust trust)
            throws Exception {
        // Before anything: the JVM's first gRPC call costs sixty times what the
        // ones after it cost, and whichever handler happens to be first would be
        // billed for it. Paid here, on a cluster of losim's own, in no trace.
        Warm.once();

        var calibration = calibrate();
        var clock = new Clock(s.kTime(), calibration.correction());
        var tel = new Telemetry(clock, level);
        var net = new Net(s.seed())
                .latency(s.net().sameZoneRefMs(), s.net().crossZoneRefMs())
                .jitter(s.net().jitterRefMs())
                .loss(s.net().loss());

        String failure = null;
        boolean completed = false;
        double started;

        try (var machines = new Machines(tel, net, s.seed())) {
            var byName = new LinkedHashMap<String, Machine>();
            for (NodeSpec m : s.nodes()) {
                var spec = InstanceCatalog.get(m.instance());
                var machine = machines.machine(m.name(), m.instance(), m.zone(),
                        m.memoryCapMb() != null ? m.memoryCapMb() : spec.memoryMb(),
                        m.diskCapMb() != null ? m.diskCapMb() : spec.storageGb() * 1024.0);
                for (var svc : m.runs().values())
                    machine.serves(factory(svc.className(), loader, svc.where()),
                                   svc.service(), svc.path(), svc.failures(), svc.where());
                if (m.runs().isEmpty()) machine.serving();     // listening, offering nothing
                byName.put(m.name(), machine);
            }

            // Checked here, before a single call is made: a retry policy the schema
            // does not support is a line to fix, not a duplicate write to discover.
            machines.retrying(s.retries());
            // And what each of them costs, checked the same way and for the same
            // reason: a takes: line naming nothing is a method that quietly takes
            // no time, which comes out as a fast run rather than as an error.
            machines.costing(s.simulatedDuration());


            Machine entry = byName.get(entryNode(s));

            // Off the clock and off the trace, and before either exists. Reading a
            // file is not part of the design being measured, so `Load` runs first,
            // against a system that is up and serving, and then everything it spent
            // is taken back: the ledgers to zero, and the events and spans it wrote
            // thrown away. Only then does `begin()` zero the clock and announce the
            // nodes — which is why it is in this order and not the other one, where
            // the wipe would take the boot events with it.
            Workload work;
            try (var outside = new Outside(entry.name)) {
                work = outside.blocking().load(input(s));
                for (Machine m : machines.all()) m.forget();
                tel.forget();
            } catch (io.grpc.StatusRuntimeException e) {
                throw new IllegalArgumentException("losim.Job/Load failed on " + entry.name
                        + ": " + describe(e) + ". Load runs before anything is measured, so a"
                        + " simulation whose input cannot be built has not started.");
            }

            machines.begin();
            tel.event("-", "simulation", "file", s.file(), "seed", s.seed(), "scale", s.scale(),
                      "nodes", s.nodes().size(), "entry", entry.name,
                      "unit", s.input().unit(), "count", s.input().count(),
                      "loaded", work.getCount());

            // On the nodes, at the start, before anything they do is measured: a
            // figure that is a lower bound should say so beside itself, not in a log.
            trust.recordInto(tel);

            machines.startSampling();

            // Scheduled only now, against a clock that starts at zero, so a failure
            // written at 120 refMs lands at 120 refMs in the trace.
            var dispatcher = new Dispatcher(clock);
            schedule(s, machines, byName, dispatcher, tel);
            dispatcher.start();

            started = tel.now();
            try (var outside = new Outside(entry.name)) {
                var call = outside.futures().run(work);
                losim.pb.Result answer = call.get(WATCHDOG_SECONDS, TimeUnit.SECONDS);
                completed = true;
                tel.event(entry.name, "done", "value", Values.render(answer));
            } catch (ExecutionException e) {
                failure = describe(e.getCause());
                // A node that restarted took its server down with it, and gRPC
                // cancels what was in flight. Saying only CANCELLED leaves the one
                // fact a reader cannot recover: which of the two happened.
                if (!entry.alive() || entry.restarts() > 0)
                    failure += " — " + entry.name + " runs losim.Job and was restarted, which"
                            + " takes its server down and cancels the call it was serving";
                tel.event(entry.name, "failed", "error", failure);
            } catch (TimeoutException e) {
                failure = "losim.Job/Run was still running after " + WATCHDOG_SECONDS
                        + " seconds of real time, which is not a slow run — it is a stuck one";
                tel.event(entry.name, "failed", "error", failure);
            }

            dispatcher.close();
            machines.stopSampling();
            double ended = tel.now();

            // A run can end with work still in flight: a handler whose caller gave up
            // and stopped waiting, a machine killed mid-call. Those spans are real and
            // so is their end — the run ended. Left open they would be indistinguishable
            // from a recorder that lost track, which is the one thing a dangling span is
            // supposed to mean (D8), and "no span dangles" would stop being assertable.
            for (Telemetry.Span open : tel.dangling())
                tel.close(open, "ABANDONED", "why", "the run ended while this was in flight");

            // One last walk, so a machine that filled up in the final tick is not
            // reported at whatever it held eight ticks ago.
            var totals = new LinkedHashMap<String, Result.Totals>();
            for (Machine m : machines.all()) {
                if (m.alive()) m.measureRetained();
                totals.put(m.name(), new Result.Totals(m.name(), m.peakRetainedBytes(),
                        m.allocatedBytes(), m.diskBytes(), m.bytesOut(), m.bytesIn(),
                        m.crossZoneBytes(), m.handledCalls(), m.losimBytes(), m.losimStops(),
                        m.memoryCapMb(), m.alive()));
            }

            var trace = Trace.of(tel)
                    .meta("simulation", s.file())
                    .meta("seed", s.seed())
                    .meta("entry", entry.name)
                    .meta("scale", s.scale())
                    .meta("unit", s.input().unit())
                    .meta("count", s.input().count())
                    // What Load actually built, beside what it was asked for. A
                    // Load that quietly returns a tenth of its count is how a
                    // fitted exponent goes wrong for a reason nobody finds.
                    .meta("loaded", work.getCount())
                    .meta("completed", completed)
                    .meta("durationRefMs", Math.round(ended - started));
            if (failure != null) trace.meta("failure", failure);
            if (trust.checked()) trace.meta("trusted", trust.clean());
            // What the clock was calibrated to, and how still the host was while
            // it was measured. Written always, not only when something is wrong:
            // a trace whose durations look surprising is read afterwards, on a
            // different day, by somebody who cannot re-measure the host it came
            // from. `trusted` answers a question about the code; these two answer
            // the question about the machine, which is the one that was missing.
            trace.meta("parkCorrection", Math.round(calibration.correction() * 1000) / 1000.0);
            trace.meta("hostNoise", Math.round(calibration.noise() * 1000) / 1000.0);
            // The one that is about this run rather than about the machine it
            // ran on: costs that ran out of parks still owing time. Zero on
            // every host measured, busy and translated included — so a non-zero
            // here is a figure in this trace being short, said in the trace.
            trace.meta("unpaidCosts", clock.unpaidCosts());

            // The closing balance, in the same units and under the same names the
            // engine fits its laws on — so an observed figure and a projected one can
            // be put beside each other without a translation table in between.
            for (Machine machine : machines.all()) {
                Result.Totals t = totals.get(machine.name());
                var m = new LinkedHashMap<String, Object>();
                m.put("name", t.name());
                // What it was, not only what it did. A bill and a viewer both need
                // this, and reading it back off a boot event would make both of them
                // depend on a telemetry level being on.
                m.put("instance", machine.instance());
                m.put("zone", machine.zone());
                m.put("vcpu", machine.vcpu());
                m.put("serves", machine.servicesOffered());
                m.put("memoryMb", mb(t.peakRetainedBytes()));
                m.put("allocMb", mb(t.allocatedBytes()));
                m.put("diskMb", mb(t.diskBytes()));
                m.put("wireMb", mb(t.bytesOut()));
                m.put("inMb", mb(t.bytesIn()));
                // Apart from the rest, because traffic between zones is the traffic
                // anyone is billed for and traffic inside one is free.
                m.put("crossZoneMb", mb(t.crossZoneBytes()));
                // And the same bytes again by where they went, because that is
                // what decides the rate. The zone next door, another region, and
                // the other side of an ocean are three prices, and a total that
                // has been added up cannot be charged at three.
                var egress = new LinkedHashMap<String, Object>();
                for (var e : machine.egressByRegion().entrySet())
                    if (e.getValue() > 0) egress.put(e.getKey(), mb(e.getValue()));
                if (!egress.isEmpty()) m.put("egressMb", egress);
                m.put("calls", t.handledCalls());
                // What losim itself cost this machine, and how often it stopped to
                // meter. Published rather than kept private, because "allocMb is the
                // program's own" is a claim, and a reader is entitled to see the size
                // of what was taken off it before believing it.
                m.put("losimMb", mb(t.losimBytes()));
                m.put("losimStops", t.losimStops());
                m.put("memCapMb", Machine.round(t.memoryCapMb()));
                m.put("alive", t.alive());
                trace.node(m);
            }
            return new Result(trace, tel, completed, failure, ended - started, totals, trust);
        }
    }

    /**
     * Bytes, as megabytes, to about a byte.
     *
     * <p>Megabytes because that is what the engine names its laws in, so an observed
     * figure and a projected one sit beside each other without a conversion in
     * between. Six places because three is a kilobyte, and a run small enough to be
     * measured in kilobytes would report every byte it moved as zero.
     */
    private static double mb(long bytes) {
        return Math.round(bytes / 1048576.0 * 1e6) / 1e6;
    }

    // ----------------------------------------------------------------- wiring up

    /** Builds a service by name, so a restarted machine can be given a fresh one. */
    private static Supplier<BindableService> factory(String className, ClassLoader loader,
                                                     String where) {
        Class<?> type;
        try { type = Class.forName(className, true, loader); }
        catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(where + ": no class called '" + className
                    + "' is on the classpath. 'runs:' names a class implementing a generated"
                    + " gRPC service; write it fully qualified if it is in a package.");
        }
        if (!BindableService.class.isAssignableFrom(type))
            throw new IllegalArgumentException(where + ": '" + className + "' is not a gRPC"
                    + " service. It has to extend the ImplBase that protoc generated — machines"
                    + " talk over gRPC and nothing else.");
        try { type.getDeclaredConstructor(); }
        catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(where + ": '" + className + "' needs a"
                    + " no-argument constructor, because losim builds a fresh one when a machine"
                    + " restarts. Whatever it needs, it can ask Losim.current() for.");
        }
        return () -> {
            try {
                var c = type.getDeclaredConstructor();
                c.setAccessible(true);
                return (BindableService) c.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(where + ": could not build '" + className + "'", e);
            }
        };
    }

    /**
     * The one node that runs {@code losim.Job}, which is where the work starts.
     *
     * <p>Exactly one. Zero is a system nothing can start; two is two designs, and
     * two designs are two files compared with {@code losim compare} rather than
     * one file that has to pick. There is no key that picks, which is the point:
     * the system's shape is the design.
     */
    private static String entryNode(Simulation s) {
        var found = new ArrayList<NodeSpec>();
        for (NodeSpec m : s.nodes())
            if (m.runs().containsKey(JobGrpc.SERVICE_NAME)) found.add(m);
        if (found.size() == 1) return found.get(0).name();
        if (found.isEmpty()) throw new IllegalArgumentException(s.nodes().get(0).where()
                + ": no node in this simulation runs " + JobGrpc.SERVICE_NAME + ", so there is"
                + " nothing to start. One node's runs: says { " + JobGrpc.SERVICE_NAME
                + ": <path>.java }, and the file it names implements Load and Run.");
        throw new IllegalArgumentException(found.get(1).where() + ": '" + found.get(1).name()
                + "' runs " + JobGrpc.SERVICE_NAME + ", and so does '" + found.get(0).name()
                + "' at " + found.get(0).where() + ". A simulation starts in one place. Two"
                + " designs are two files, compared with losim compare.");
    }

    /**
     * What the simulation declared, shrunk to the size this run is measuring.
     *
     * <p>The file's {@code count:} is the workload at <i>full</i> scale. What this
     * run does is that times {@code units / fullUnits}: 1 for a direct run, and the
     * rung the engine picked otherwise.
     *
     * <p>Nothing in the message says which rung it is. A Job that could tell a
     * probe run from the full one could behave differently at the two sizes, and
     * then the ladder would be a ladder of different designs.
     */
    private static Input input(Simulation s) {
        var spec = s.input();
        long count = Math.round(spec.count() * (s.units() / (double) s.fullUnits()));
        if (count < 1) throw new IllegalArgumentException(spec.where() + ": count is "
                + spec.count() + " at full scale, and this run is measuring "
                + s.units() + " of " + s.fullUnits() + " — which rounds to no "
                + spec.unit() + " at all. Raise the count, or lower the scale.");
        var in = Input.newBuilder().setUnit(spec.unit()).setCount(count);
        if (spec.source() != null) in.setSource(spec.source());
        return in.build();
    }

    /** What went wrong, as one line, from whichever kind of wrapper carries it. */
    private static String describe(Throwable t) {
        if (t instanceof CompletionException c && c.getCause() != null) t = c.getCause();
        if (t instanceof io.grpc.StatusRuntimeException g) {
            var st = g.getStatus();
            return st.getDescription() == null ? st.getCode().name()
                    : st.getCode().name() + ": " + st.getDescription();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    // -------------------------------------------------------------- the first call

    /**
     * The call that starts the simulation, made from outside the system.
     *
     * <p>A plain in-process channel with no {@link ClientSide} on it, because
     * there is no caller for it to charge: nobody's bytes, nobody's latency,
     * nobody's failure injection. The entry node's {@code bytesIn} does gain the
     * request's size, and that is correct — it genuinely received it.
     *
     * <p>The channel is shut down in a {@code finally}, which is what this class
     * exists for. A probe grid is about thirty simulations, and thirty leaked
     * in-process transports is not a hypothetical.
     */
    private static final class Outside implements AutoCloseable {
        private final io.grpc.ManagedChannel channel;

        Outside(String node) {
            this.channel = io.grpc.inprocess.InProcessChannelBuilder.forName(node)
                    .directExecutor()
                    .intercept(new io.grpc.ClientInterceptor() {
                        @Override public <Q, S> io.grpc.ClientCall<Q, S> interceptCall(
                                io.grpc.MethodDescriptor<Q, S> md, io.grpc.CallOptions opts,
                                io.grpc.Channel next) {
                            return new io.grpc.ForwardingClientCall
                                    .SimpleForwardingClientCall<>(next.newCall(md, opts)) {
                                @Override public void start(Listener<S> l, io.grpc.Metadata h) {
                                    h.put(Machines.OUTSIDE, "1");
                                    super.start(l, h);
                                }
                            };
                        }
                    })
                    .build();
        }

        /**
         * The stub, with no deadline on it.
         *
         * <p>The watchdog is a real-time limit on a run that has stopped moving,
         * and a gRPC deadline is simulated time — which stops advancing at exactly
         * the moment a stuck run needs to be noticed. So the deadline is
         * {@code future.get(WATCHDOG_SECONDS)} and the cancellation it throws.
         */
        JobGrpc.JobFutureStub futures() { return JobGrpc.newFutureStub(channel); }

        /** For {@code Load}, which is not on the watchdog because it is not the run. */
        JobGrpc.JobBlockingStub blocking() { return JobGrpc.newBlockingStub(channel); }

        @Override public void close() { channel.shutdownNow(); }
    }

    // ------------------------------------------------------------------- weather

    private static void schedule(Simulation s, Machines machines, Map<String, Machine> byName,
                                 Dispatcher d, Telemetry tel) {
        // The stream a drawn failure fires from. One per node and rule rather than
        // one per simulation, so adding a rule to one node does not shift when the
        // other nodes fail — a sweep that moved every other node's afternoon
        // because one line was added would be comparing two different questions.
        for (NodeSpec node : s.nodes()) {
            Machine target = byName.get(node.name());
            int rule = 0;
            for (Failure f : node.failures()) {
                if (f.drawn()) drawn(s, f, target, byName, d, tel, rule++);
                else d.at(f.atRefMs(), () -> fire(f, target, machines, d, tel, f.atRefMs()));
            }
        }
    }

    /**
     * One failure, happening.
     *
     * <p>Shared by the scripted and the drawn paths so that a kill at an instant
     * and a kill at a rate are the same event with the same consequences. They
     * were two code paths once, and a freeze scheduled its thaw in one of them.
     */
    private static void fire(Failure f, Machine target, Machines machines,
                             Dispatcher d, Telemetry tel, double now) {
        switch (f.kind()) {
            case KILL -> {
                target.kill(f.drawn() ? "killed, drawn from a rate" : "killed by the simulation");
                if (f.restartAfterRefMs() > 0)
                    d.at(now + f.restartAfterRefMs(), target::restart);
            }
            case FREEZE -> {
                target.freeze(f.forRefMs());
                // The thaw is scheduled too: a pause ends whether or not a call
                // happened to be waiting at the moment it did.
                d.at(now + f.forRefMs(), target::thaw);
            }
            case DEGRADE -> target.degrade(f.factor());
            case RESTART -> target.restart();
            case SPOT_RECLAIM -> {
                // The notice is the whole lesson: a spot node tells you it is
                // going, and a design that ignores the warning deserves what happens.
                tel.event(target.name, "spot_notice", "inRefMs", f.noticeRefMs());
                d.at(now + f.noticeRefMs(), () -> target.kill("spot reclaimed"));
                if (f.restartAfterRefMs() > 0)
                    d.at(now + f.noticeRefMs() + f.restartAfterRefMs(), target::restart);
            }
            case PARTITION -> {
                machines.net().partition(target.name, f.other());
                tel.event(target.name, "partition", "from", f.other());
            }
            case HEAL -> {
                machines.net().heal(target.name, f.other());
                tel.event(target.name, "heal", "with", f.other());
            }
        }
    }

    /**
     * A standing chance of a bad day, rather than a scripted one.
     *
     * <p>The draws are exponential: the gaps vary the way real bad afternoons do,
     * and a sweep of seeds shows the spread.
     *
     * <p>Each firing draws the next one. Drawing the whole series up front would
     * need a horizon to stop at, and a run that outlived its horizon would have a
     * quiet second half that reads as a system behaving well — the worst kind of
     * wrong, because it is indistinguishable from a finding. A rate that
     * reschedules itself has no end to outlive.
     */
    private static void drawn(Simulation s, Failure f, Machine target,
                              Map<String, Machine> byName, Dispatcher d,
                              Telemetry tel, int rule) {
        var rng = Machines.stream(s.seed(), target.name + '/' + f.kind() + '/' + rule);
        var next = new Runnable[1];
        var at = new double[]{0};
        next[0] = () -> {
            at[0] += -Math.log(1 - rng.nextDouble()) * f.perRefMs();
            final double when = at[0];
            d.at(when, () -> {
                next[0].run();                     // the rate outlives every firing
                if (!target.alive()) return;
                tel.event(target.name, "failure", "kind", f.kind().name().toLowerCase(),
                          "drawn", true, "atRefMs", Machine.round(when));
                fire(f, target, target.machines(), d, tel, when);
            });
        };
        next[0].run();
    }

}
