package losim.trace;

import io.grpc.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import losim.time.Clock;

/**
 * What a run is observed to have done.
 *
 * A change log — one record per state change — is silent exactly when a system
 * is stuck, which is exactly when you want to look at it. Measured against the
 * questions a debugger answers, one answered one and a half of five. So this
 * records three things, not one:
 *
 * <pre>
 *   events   sparse, rich, one moment each   "the reducer was killed"
 *   spans    intervals carrying a parent     "and this is what was waiting on it"
 *   series   dense numbers on a fixed cadence"and this is what everything held meanwhile"
 * </pre>
 *
 * Together they answer what a debugger would: scrub to a time (series), see the
 * state there (series and open spans), ask how you got here (span parents), and
 * step to what happened next (events).
 *
 * <p>Telemetry replaces the debugger in this design, so it is load-bearing:
 * <i>if it cannot answer a question a debugger would have answered, that is a
 * bug in the telemetry — and if watching changes what it reports, that is a
 * worse one.</i>
 */
public final class Telemetry {

    /** The span a thread is working under, so its calls are attributed to the call that caused them. */
    public static final Context.Key<Span> SPAN = Context.key("losim.span");

    /**
     * How much is recorded.
     *
     * <p>Recording runs on the machine's own threads, so what it costs lands on
     * the counters being read. That makes "how much" a measurable question rather
     * than a matter of taste — and {@link #OFF} is the baseline everything else is
     * measured against.
     */
    public enum Level {
        /** Nothing at all. */
        OFF,
        /** Events, spans and series, but no rendering of arguments or results. */
        NO_PAYLOAD,
        /** Everything, including every call's argument and result. */
        FULL
    }

    public record Event(double t, String vm, String kind, Map<String, Object> detail) {}

    /** An interval, and what it was waiting on. */
    public static final class Span {
        public final long id, parent;
        public final String vm, kind, label;
        public final double t0;
        public volatile double t1 = -1;
        public volatile String status = "OPEN";
        public final Map<String, Object> detail = new ConcurrentHashMap<>();

        /** Wall clock spent inside this span on losim's own bookkeeping, not the program's. */
        public final AtomicLong losimNanos = new AtomicLong();
        /** Records the handler declared it processed, or −1 if it never said. */
        public final AtomicLong records = new AtomicLong(-1);

        Span(long id, long parent, String vm, String kind, String label, double t0) {
            this.id = id; this.parent = parent; this.vm = vm;
            this.kind = kind; this.label = label; this.t0 = t0;
        }

        public boolean openAt(double t) { return t0 <= t && (t1 < 0 || t < t1); }

        /** Open to close, including whatever losim did meanwhile. */
        public double grossMs() { return t1 < 0 ? -1 : t1 - t0; }

        /**
         * What the <i>program</i> took.
         *
         * <p>losim's own work runs inside this window, on this thread, so leaving
         * it in would report a handler as slower for having been watched — and
         * this duration is what the scaler engine fits (D13).
         */
        public double programMs(double kTime) {
            if (t1 < 0) return -1;
            return Math.max(0, (t1 - t0) - losimNanos.get() / 1e6 * kTime);
        }
    }

    /** A dense numeric channel: one machine, one metric, one value per tick. */
    public static final class Series {
        public final String vm, metric;
        private double[] v = new double[1024];
        private int n = 0;
        Series(String vm, String metric) { this.vm = vm; this.metric = metric; }
        void push(double x) {
            if (n == v.length) v = Arrays.copyOf(v, n * 2);
            v[n++] = x;
        }
        public double at(int i)     { return i < n ? v[i] : Double.NaN; }
        public int size()           { return n; }
        public double[] values()    { return Arrays.copyOf(v, n); }
    }

    /** Anything that can describe itself on every tick. */
    public interface Sampled {
        String vmName();
        /** Fills in this machine's numbers for one tick. */
        void sample(Map<String, Double> into);
    }

    private final Clock clock;
    private final Level level;
    private final AtomicLong nextSpan = new AtomicLong(1);
    private final Queue<Event> events = new ConcurrentLinkedQueue<>();
    private final Queue<Span> spans = new ConcurrentLinkedQueue<>();
    private final Map<String, Series> series = new ConcurrentHashMap<>();
    private final List<Sampled> sampled = new CopyOnWriteArrayList<>();
    private final List<Double> sampleTimes = Collections.synchronizedList(new ArrayList<>());
    private volatile double sampleDtMs;
    private volatile Thread sampler;

    public Telemetry(Clock clock)               { this(clock, Level.FULL); }
    public Telemetry(Clock clock, Level level)  { this.clock = clock; this.level = level; }

    public Level level()      { return level; }
    /** Whether events, spans and series are kept at all. */
    public boolean records()  { return level != Level.OFF; }
    /** Whether arguments and results are rendered — by far the most expensive part. */
    public boolean payloads() { return level == Level.FULL; }

    public Clock clock()      { return clock; }
    public double kTime()     { return clock.kTime(); }
    /** Simulated milliseconds — what the scenario was written in, not wall clock. */
    public double now()       { return clock.nowMs(); }

    // ------------------------------------------------------------------- record

    public void event(String vm, String kind, Object... kv) {
        if (level == Level.OFF) return;
        events.add(new Event(now(), vm, kind, map(kv)));
    }

    /** Opens a span beneath whatever is ambient on this thread. */
    public Span open(String vm, String kind, String label, Object... kv) {
        Span parent = SPAN.get();
        return openUnder(parent == null ? 0 : parent.id, vm, kind, label, kv);
    }

    /**
     * Opens a span under a parent named explicitly rather than found ambiently.
     *
     * <p>The server side needs this and it is not a detail: its parent arrived in
     * a metadata header, from a thread on another machine that this one has no
     * context from. Take the parent from the ambient context instead and every
     * span hangs off the root, which is to say there is no distributed call stack
     * at all.
     */
    public Span openUnder(long parentId, String vm, String kind, String label, Object... kv) {
        Span s = new Span(nextSpan.getAndIncrement(), parentId, vm, kind, label, now());
        if (level == Level.OFF) return s;    // live, so callers need no null checks; simply not kept
        s.detail.putAll(map(kv));
        spans.add(s);
        return s;
    }

    /** Runs work with a span ambient, so anything it starts is attributed beneath it. */
    public void under(Span s, Runnable r) { Context.current().withValue(SPAN, s).run(r); }

    /** Closes a span. Every span opened must reach exactly one close — see {@link #dangling()}. */
    public void close(Span s, String status, Object... kv) {
        if (s == null || level == Level.OFF) return;
        s.detail.putAll(map(kv));
        s.status = status;
        s.t1 = now();
    }

    /**
     * Key-value pairs, with absent values dropped rather than recorded as null.
     *
     * <p>An absent key is what a reader can act on; a key whose value is null is a
     * question. And the detail maps are concurrent ones, which reject a null value
     * outright — so a caller that omits a value conditionally, as every one of them
     * does at {@link Level#NO_PAYLOAD}, would otherwise take down the very work it
     * was recording.
     */
    private static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------- sample

    public void register(Sampled s) { sampled.add(s); }

    /**
     * Starts the sampler.
     *
     * <p>The cadence is derived from how long the run is expected to take, so a
     * run of any length yields a bounded number of ticks. The trace's size then
     * follows the run's <i>duration</i> rather than its busyness, which is the
     * whole point: a stuck system produces as much evidence as a busy one.
     *
     * <p>This thread is not one of the machines' pool threads, deliberately: what
     * it costs must not land on the counters it is reading (D13).
     */
    public void startSampling(double expectedRunMs, int maxTicks) {
        if (level == Level.OFF) return;
        sampleDtMs = Math.max(1.0, expectedRunMs / maxTicks);
        sampler = new Thread(() -> {
            var scratch = new LinkedHashMap<String, Double>();
            long tickNs = (long) (sampleDtMs * 1e6 / clock.kTime());
            long next = System.nanoTime();
            while (!Thread.currentThread().isInterrupted()) {
                next += tickNs;
                sampleTimes.add(now());
                for (Sampled s : sampled) {
                    scratch.clear();
                    try { s.sample(scratch); } catch (RuntimeException ignored) { }
                    for (var e : scratch.entrySet())
                        series.computeIfAbsent(s.vmName() + "." + e.getKey(),
                                        k -> new Series(s.vmName(), e.getKey()))
                              .push(quantise(e.getKey(), e.getValue()));
                }
                long sleep = next - System.nanoTime();
                if (sleep > 0) clock.parkRealNanos(sleep);
                else next = System.nanoTime();          // fell behind; resynchronise
            }
        }, "losim-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    public void stopSampling() {
        Thread s = sampler;
        sampler = null;
        if (s != null) {
            s.interrupt();
            try { s.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    // --------------------------------------------------------------------- read

    public List<Event> events()          { return new ArrayList<>(events); }
    public List<Span>  spans()           { return new ArrayList<>(spans); }
    public Map<String, Series> series()  { return new TreeMap<>(series); }
    public double sampleDtMs()           { return sampleDtMs; }

    public double[] sampleTimes() {
        synchronized (sampleTimes) {
            double[] a = new double[sampleTimes.size()];
            for (int i = 0; i < a.length; i++) a[i] = sampleTimes.get(i);
            return a;
        }
    }

    /** Spans that never closed. A dangling span is a telemetry bug, not a finding. */
    public List<Span> dangling() { return spans.stream().filter(s -> s.t1 < 0).toList(); }

    /** The index of the tick covering a simulated time, or −1 before the first. */
    public int tickAt(double t) {
        double[] ts = sampleTimes();
        int best = -1;
        for (int i = 0; i < ts.length; i++) { if (ts[i] <= t) best = i; else break; }
        return best;
    }

    // ------------------------------------------------------------------- encode

    /**
     * A series, encoded for the wire.
     *
     * <p>Most channels barely move — a machine is alive for the whole run, idle
     * for most of it, and its cap never changes at all — so one number per tick
     * stores mostly repetition. Choosing the smallest of three forms is worth
     * about two orders of magnitude, which is what lets the richer trace come out
     * <i>smaller</i> than the change log it replaces.
     */
    public record Encoded(String form, double constant, double[] raw, double[][] runs, int ticks) {
        /** Roughly what this costs as JSON, in bytes. */
        public int weight() {
            return switch (form) {
                case "constant" -> 8;
                case "runs"     -> runs.length * 14;
                default         -> raw.length * 7;
            };
        }
    }

    public static Encoded encode(Series s) {
        double[] v = s.values();
        if (v.length == 0) return new Encoded("constant", 0, null, null, 0);
        boolean flat = true;
        for (double x : v) if (x != v[0]) { flat = false; break; }
        if (flat) return new Encoded("constant", v[0], null, null, v.length);

        var runs = new ArrayList<double[]>();
        double cur = v[0];
        int n = 1;
        for (int i = 1; i < v.length; i++) {
            if (v[i] == cur) n++;
            else { runs.add(new double[]{cur, n}); cur = v[i]; n = 1; }
        }
        runs.add(new double[]{cur, n});
        var asRuns = new Encoded("runs", 0, null, runs.toArray(new double[0][]), v.length);
        var asRaw  = new Encoded("raw", 0, v, null, v.length);
        return asRuns.weight() < asRaw.weight() ? asRuns : asRaw;
    }

    /**
     * Rounds a sample to the precision anyone will actually read.
     *
     * <p>Without this a flat stretch is not flat — it jitters in the sixth
     * decimal — and no run ever forms, so the encoding above saves nothing.
     */
    public static double quantise(String metric, double x) {
        if (metric.endsWith("Pct")) return Math.round(x * 10) / 10.0;
        if (metric.endsWith("Mb"))  return Math.round(x * 100) / 100.0;
        return x;
    }
}
