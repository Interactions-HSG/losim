package losim.runtime;

import io.grpc.BindableService;
import io.grpc.Channel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import losim.api.Cluster;
import losim.api.Input;
import losim.api.Job;
import losim.api.Scalable;
import losim.res.InstanceCatalog;
import losim.scenario.InputSize;
import losim.scenario.Scenario;
import losim.scenario.Scenario.*;
import losim.time.Clock;
import losim.time.Dispatcher;
import losim.trace.Telemetry;
import losim.trace.Trace;
import losim.trace.Values;
import losim.verify.Trust;

/**
 * A scenario, actually run.
 *
 * <p>Everything the file declared is assembled here in one order that matters:
 * the machines and their services first, because the retry gate has to be checked
 * against what the cluster really serves; then the failures, which need every node
 * to exist before one can be aimed at; then the sampler; and only then the job.
 *
 * <p>The run ends when the job returns, throws, or outstays its welcome. All three
 * are recorded — a run that failed is a result, not an absence of one.
 */
public final class Run {

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

    private Run() {}

    public static Result of(Scenario s) throws Exception {
        return of(s, Thread.currentThread().getContextClassLoader());
    }

    public static Result of(Scenario s, ClassLoader loader) throws Exception {
        return of(s, loader, Telemetry.Level.FULL);
    }

    public static Result of(Scenario s, ClassLoader loader, Telemetry.Level level) throws Exception {
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

    public static Result of(Scenario s, ClassLoader loader, Telemetry.Level level, Trust trust)
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
                                   svc.path(), svc.failures(), svc.where());
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


            machines.begin();
            tel.event("-", "scenario", "file", s.file(), "seed", s.seed(), "scale", s.scale(),
                      "machines", s.nodes().size(), "job", s.job(),
                      "tightMargin", s.tightMargin() ? true : null);

            // On the machines, at the start, before anything they do is measured: a
            // figure that is a lower bound should say so beside itself, not in a log.
            trust.recordInto(tel);

            machines.startSampling();

            // Scheduled only now, against a clock that starts at zero, so a fault
            // written at 120 refMs lands at 120 refMs in the trace.
            var dispatcher = new Dispatcher(clock);
            schedule(s, machines, byName, dispatcher, tel);
            dispatcher.start();

            Machine entry = byName.values().iterator().next();
            var cluster = new Live(machines, entry, tel, s.seed());
            Object job = job(s.job(), loader);
            // Resolved before the span opens, because an input the scenario did not
            // describe is a line to fix rather than a run that failed: inside the
            // span it would come out as a job that threw, which is what a design
            // falling over looks like.
            Input input = sized(s, job);
            started = tel.now();
            var span = tel.open(entry.name, "job", s.job());
            try {
                entry.submit(() -> {
                    try {
                        if (job instanceof Scalable scalable) scalable.run(cluster, input);
                        else ((Job) job).run(cluster);
                    }
                    catch (Exception e) { throw new CompletionException(e); }
                }).get(WATCHDOG_SECONDS, TimeUnit.SECONDS);
                completed = true;
                tel.close(span, "OK");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() instanceof CompletionException c ? c.getCause() : e.getCause();
                failure = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                tel.close(span, "FAILED", "error", failure);
                tel.event(entry.name, "job_failed", "error", failure);
            } catch (TimeoutException e) {
                failure = "the job was still running after " + WATCHDOG_SECONDS
                        + " seconds of real time, which is not a slow run — it is a stuck one";
                tel.close(span, "TIMEOUT", "error", failure);
                tel.event(entry.name, "job_failed", "error", failure);
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
                    .meta("scenario", s.file())
                    .meta("seed", s.seed())
                    .meta("job", s.job())
                    .meta("scale", s.scale())
                    .meta("completed", completed)
                    .meta("durationRefMs", Math.round(ended - started));
            if (failure != null) trace.meta("failure", failure);
            if (s.tightMargin()) trace.meta("tightMargin", true);
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
                trace.machine(m);
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

    /** The job, which is a {@link Job} or a {@link Scalable} and nothing else. */
    private static Object job(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!Job.class.isAssignableFrom(type) && !Scalable.class.isAssignableFrom(type))
                throw new IllegalArgumentException("'" + className + "' is named as the job, so it"
                        + " has to implement losim.api.Job, or losim.api.Scalable if its input has"
                        + " a size");
            var c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no class called '" + className + "' is on the"
                    + " classpath to run as the job. Compile the project first; --cp says which"
                    + " directory the classes are in.");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build the job '" + className + "'", e);
        }
    }

    /** A scale as somebody wrote it: 6 rather than 6.0. */
    private static String trim(double scale) {
        return scale == Math.rint(scale) ? String.valueOf((long) scale) : String.valueOf(scale);
    }

    /**
     * The input this run is to process, or {@code null} for a job that takes none.
     *
     * <p>Where the scenario and the job are held against each other. The loader
     * never loads a class, so it cannot know whether {@code items:} is a part of
     * anything; by here the job exists and can be asked, and every answer is refused
     * with the line somebody wrote — the same discipline {@link Machines#costing} uses
     * for a {@code takes:} key naming an rpc that is not served.
     *
     * <p>The scenario's sizes are the input at <i>full</i> scale. What this run does
     * is that times {@code units / fullUnits}: 1 for a direct run, and the rung the
     * engine picked otherwise. Every count moves by the same factor, so a rung is
     * the same design at a smaller size rather than a different one.
     */
    private static Input sized(Scenario s, Object job) {
        if (!(job instanceof Scalable scalable)) {
            // Above scale 1 the scenario is asking for a model, and a model is the
            // same job asked to do more. A plain Job has no way of being asked: its
            // size is a constant in its own Java, where no scenario can reach it.
            if (s.scale() > 1) throw new IllegalArgumentException(s.jobWhere() + ": '" + s.job()
                    + "' is the job of a scenario at scale " + trim(s.scale()) + ", so it has to"
                    + " implement losim.api.Scalable. It declares what its input is made of and"
                    + " the input: block says how big each part is; there is nothing here for the"
                    + " engine to vary.");
            if (!s.input().isEmpty()) throw new IllegalArgumentException(s.input().get(0).where()
                    + ": this scenario sizes an input and '" + s.job() + "' is a plain"
                    + " losim.api.Job, which is never given one. Implement losim.api.Scalable, or"
                    + " delete the input: block.");
            return null;
        }
        Input.Shape shape = scalable.shape();
        var declared = new LinkedHashMap<String, Long>();
        for (InputSize size : s.input()) {
            if (shape.part(size.name()) == null) throw new IllegalArgumentException(size.where()
                    + ": '" + s.job() + "' consumes no part called '" + size.name() + "'. Its"
                    + " shape() declares " + shape + ".");
            declared.put(size.name(), size.n());
        }
        for (String part : shape.names())
            if (!declared.containsKey(part)) throw new IllegalArgumentException(s.jobWhere()
                    + ": '" + s.job() + "' consumes '" + part + "' and this scenario does not say"
                    + " how much. Every part of an input is sized under input:, because a size"
                    + " that is not in the file is a size no sweep can vary.");
        try {
            return Input.of(shape, declared, s.units() / (double) s.fullUnits());
        } catch (IllegalArgumentException e) {
            // Input.of guards itself for anyone building one by hand; reached from a
            // file, it has already been checked against the shape, so what is left is
            // the run size against the sizes — and that is the scenario's line too.
            throw new IllegalArgumentException(s.jobWhere() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- weather

    private static void schedule(Scenario s, Machines machines, Map<String, Machine> byName,
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
    private static void drawn(Scenario s, Failure f, Machine target,
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

    // ------------------------------------------------------------------- cluster

    /** The cluster as the job sees it, and the channels it opened along the way. */
    private static final class Live implements Cluster {
        private final Machines machines;
        private final Machine here;
        private final Telemetry tel;

        private final long seed;

        Live(Machines machines, Machine here, Telemetry tel, long seed) {
            this.machines = machines; this.here = here; this.tel = tel;
            this.seed = seed;
        }

        @Override public List<String> machines() { return machines.names(); }
        @Override public List<String> serving(String service) { return machines.serving(service); }

        // The machine's own, not the job's: a handler on this machine and the job
        // driving it should not be holding two channels to the same peer, and only
        // one of the two would then be closed by anybody.
        @Override public Channel channelTo(String machine) { return here.dial(machine); }

        @Override public double clockMs() { return tel.now(); }
        @Override public long seed() { return seed; }
        @Override public void log(String message) { tel.event(here.name, "log", "message", message); }
        @Override public <T> T compute(String label, Supplier<T> body) {
            return here.compute(label, body);
        }
        @Override public Phase phase(String label) {
            Telemetry.Span span = tel.open(here.name, "phase", label);
            var restore = io.grpc.Context.current()
                    .withValue(Telemetry.SPAN, span).attach();
            return new Phase() {
                @Override public Phase note(String key, Object value) {
                    span.detail.put(key, Values.render(value));
                    return this;
                }
                @Override public void close() {
                    io.grpc.Context.current().detach(restore);
                    tel.close(span, "OK");
                }
            };
        }

        @Override public void done(Object answer) {
            tel.event(here.name, "done", "value", Values.render(answer));
        }

    }
}
