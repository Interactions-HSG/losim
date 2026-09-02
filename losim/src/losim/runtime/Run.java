package losim.runtime;

import io.grpc.BindableService;
import io.grpc.Channel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import losim.api.Cluster;
import losim.api.Job;
import losim.res.InstanceCatalog;
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
 * against what the fleet really serves; then the faults, which need every machine
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
    public static Result of(Scenario s, ClassLoader loader, Telemetry.Level level, Trust trust)
            throws Exception {
        // Before anything: the JVM's first gRPC call costs sixty times what the
        // ones after it cost, and whichever handler happens to be first would be
        // billed for it. Paid here, on a fleet of losim's own, in no trace.
        Warm.once();

        var clock = new Clock(s.kTime(), Clock.measureCorrection());
        var tel = new Telemetry(clock, level);
        var net = new Net(s.seed())
                .latency(s.net().sameZoneRefMs(), s.net().crossZoneRefMs())
                .jitter(s.net().jitterRefMs())
                .loss(s.net().loss());

        String failure = null;
        boolean completed = false;
        double started;

        try (var fleet = new Fleet(tel, net)) {
            var byName = new LinkedHashMap<String, Machine>();
            for (MachineSpec m : s.machines()) {
                var spec = InstanceCatalog.get(m.instance());
                var machine = fleet.machine(m.name(), m.instance(), m.zone(),
                        m.memoryCapMb() != null ? m.memoryCapMb() : spec.memoryMb(),
                        m.diskCapMb() != null ? m.diskCapMb() : spec.storageGb() * 1024.0);
                for (String service : m.runs())
                    machine.serves(factory(service, loader, m.where()));
                if (m.runs().isEmpty()) machine.serving();     // listening, offering nothing
                byName.put(m.name(), machine);
            }

            // Checked here, before a single call is made: a retry policy the schema
            // does not support is a line to fix, not a duplicate write to discover.
            fleet.retrying(s.retries());


            fleet.begin();
            tel.event("-", "scenario", "file", s.file(), "seed", s.seed(), "kTime", s.kTime(),
                      "machines", s.machines().size(), "job", s.job(),
                      "tightMargin", s.tightMargin() ? true : null);

            // On the machines, at the start, before anything they do is measured: a
            // figure that is a lower bound should say so beside itself, not in a log.
            trust.recordInto(tel);

            fleet.startSampling(s.expectedRunRefMs());

            // Scheduled only now, against a clock that starts at zero, so a fault
            // written at 120 refMs lands at 120 refMs in the trace.
            var dispatcher = new Dispatcher(clock);
            schedule(s, fleet, byName, dispatcher, tel);
            dispatcher.start();

            Machine entry = byName.values().iterator().next();
            var cluster = new Live(fleet, entry, tel, s.expectedRunRefMs(), s.records(), s.seed());
            Job job = job(s.job(), loader);
            started = tel.now();
            var span = tel.open(entry.name, "job", s.job());
            try {
                entry.submit(() -> {
                    try { job.run(cluster); }
                    catch (Exception e) { throw new CompletionException(e); }
                }).get((long) Math.max(30_000, s.expectedRunRefMs() / s.kTime() * 10),
                       TimeUnit.MILLISECONDS);
                completed = true;
                tel.close(span, "OK");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() instanceof CompletionException c ? c.getCause() : e.getCause();
                failure = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                tel.close(span, "FAILED", "error", failure);
                tel.event(entry.name, "job_failed", "error", failure);
            } catch (TimeoutException e) {
                failure = "the job did not finish within ten times its declared expectedRun";
                tel.close(span, "TIMEOUT", "error", failure);
                tel.event(entry.name, "job_failed", "error", failure);
            }

            dispatcher.close();
            fleet.stopSampling();
            double ended = tel.now();

            // `expectedRun` is a horizon, not a prediction, and this is the line that
            // keeps it honest. Nobody can say in advance how long a run will take —
            // that is what the run is for — but two things have to be sized before it
            // starts: how often to sample, so the trace's size follows duration rather
            // than busyness (D8), and how far ahead to draw the weather.
            //
            // Both are silent when the horizon is short. The sampler simply thins out,
            // and — worse — chaos stops firing at the horizon, so a run that overran it
            // had a quiet second half that looks like a fleet behaving well. Said out
            // loud it is a scenario to fix; unsaid it is a finding.
            double ran = ended - started;
            if (ran > s.expectedRunRefMs() * 1.25) {
                tel.event("-", "over_horizon",
                          "expectedRefMs", Machine.round(s.expectedRunRefMs()),
                          "actualRefMs", Machine.round(ran),
                          "note", s.chaos().isEmpty()
                                ? "sampling thinned out past the horizon"
                                : "chaos was only drawn out to the horizon, so nothing "
                                  + "happened to this fleet after it");
            }

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
            for (Machine m : fleet.all()) {
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
                    .meta("completed", completed)
                    .meta("durationRefMs", Math.round(ended - started));
            if (failure != null) trace.meta("failure", failure);
            if (s.tightMargin()) trace.meta("tightMargin", true);
            if (trust.checked()) trace.meta("trusted", trust.clean());

            // The closing balance, in the same units and under the same names the
            // engine fits its laws on — so an observed figure and a projected one can
            // be put beside each other without a translation table in between.
            for (Machine machine : fleet.all()) {
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

    private static Job job(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!Job.class.isAssignableFrom(type))
                throw new IllegalArgumentException("'" + className + "' is named as the job, so it"
                        + " has to implement losim.api.Job");
            var c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return (Job) c.newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no class called '" + className + "' is on the"
                    + " classpath to run as the job");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build the job '" + className + "'", e);
        }
    }

    // ------------------------------------------------------------------- weather

    private static void schedule(Scenario s, Fleet fleet, Map<String, Machine> byName,
                                 Dispatcher d, Telemetry tel) {
        for (Fault f : s.faults()) {
            Machine target = byName.get(f.target());
            switch (f.kind()) {
                case KILL -> {
                    d.at(f.atRefMs(), () -> target.kill("killed by the scenario"));
                    if (f.restartAfterRefMs() > 0)
                        d.at(f.atRefMs() + f.restartAfterRefMs(), target::restart);
                }
                case FREEZE -> {
                    d.at(f.atRefMs(), () -> target.freeze(f.forRefMs()));
                    // The thaw is scheduled too: a pause ends whether or not a call
                    // happened to be waiting at the moment it did.
                    d.at(f.atRefMs() + f.forRefMs(), target::thaw);
                }
                case DEGRADE  -> d.at(f.atRefMs(), () -> target.degrade(f.factor()));
                case RESTART  -> d.at(f.atRefMs(), target::restart);
                case SPOT_RECLAIM -> {
                    // The notice is the whole lesson: a spot machine tells you it is
                    // going, and a design that ignores the warning deserves what happens.
                    d.at(f.atRefMs(), () -> tel.event(f.target(), "spot_notice",
                            "inRefMs", f.noticeRefMs()));
                    d.at(f.atRefMs() + f.noticeRefMs(), () -> target.kill("spot reclaimed"));
                    if (f.restartAfterRefMs() > 0)
                        d.at(f.atRefMs() + f.noticeRefMs() + f.restartAfterRefMs(), target::restart);
                }
                case PARTITION -> d.at(f.atRefMs(), () -> {
                    fleet.net().partition(f.target(), f.other());
                    tel.event(f.target(), "partition", "from", f.other());
                });
                case HEAL -> d.at(f.atRefMs(), () -> {
                    fleet.net().heal(f.target(), f.other());
                    tel.event(f.target(), "heal", "with", f.other());
                });
            }
        }

        // Chaos is a rate, not a moment, so the draws are exponential: the gaps
        // vary the way real bad afternoons do, and a sweep of seeds shows the spread.
        var rng = new Random(s.seed() * 31 + 7);
        for (Chaos c : s.chaos()) {
            var pool = s.machines().stream()
                    .filter(m -> m.pool().equals(c.among()) || m.name().equals(c.among()))
                    .map(MachineSpec::name).toList();
            double t = 0;
            while (true) {
                t += -Math.log(1 - rng.nextDouble()) * c.everyRefMs();
                if (t > s.expectedRunRefMs()) break;
                final double at = t;
                final long draw = rng.nextLong();
                d.at(at, () -> {
                    var live = pool.stream().map(byName::get)
                            .filter(m -> m != null && m.alive()).toList();
                    if (live.isEmpty()) return;
                    Machine victim = live.get(Math.floorMod(draw, live.size()));
                    tel.event(victim.name, "chaos", "kind", c.kind().name().toLowerCase(),
                              "among", c.among(), "atRefMs", Machine.round(at));
                    switch (c.kind()) {
                        case KILL    -> victim.kill("chaos");
                        case FREEZE  -> {
                            victim.freeze(c.forRefMs());
                            d.at(at + c.forRefMs(), victim::thaw);
                        }
                        case DEGRADE -> victim.degrade(c.factor());
                        default -> { }
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------- cluster

    /** The fleet as the job sees it, and the channels it opened along the way. */
    private static final class Live implements Cluster {
        private final Fleet fleet;
        private final Machine here;
        private final Telemetry tel;

        private final double expectedRunMs;
        private final long records;
        private final long seed;

        Live(Fleet fleet, Machine here, Telemetry tel, double expectedRunMs,
             long records, long seed) {
            this.fleet = fleet; this.here = here; this.tel = tel;
            this.expectedRunMs = expectedRunMs; this.records = records; this.seed = seed;
        }

        @Override public List<String> machines() { return fleet.names(); }
        @Override public List<String> serving(String service) { return fleet.serving(service); }

        // The machine's own, not the job's: a handler on this machine and the job
        // driving it should not be holding two channels to the same peer, and only
        // one of the two would then be closed by anybody.
        @Override public Channel channelTo(String machine) { return here.dial(machine); }

        @Override public double clockMs() { return tel.now(); }
        @Override public double expectedRunMs() { return expectedRunMs; }
        @Override public long records() { return records; }
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
