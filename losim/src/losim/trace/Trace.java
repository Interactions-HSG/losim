package losim.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The one artefact a run produces. Everything downstream reads this.
 *
 * <p><b>The trace is the interchange format, so its shapes are a contract.</b>
 * Two runs weeks apart must stay comparable, and every view, the bill and the
 * invariant checks all read it. {@code spans} and {@code series} are therefore
 * <i>new top-level channels</i> rather than changes to {@code events}: the event
 * kinds and their keys are extended only additively, and the acceptance test is
 * diffing a trace's event kinds and keys before and after and finding it empty.
 */
public final class Trace {

    /** Bumped only when a shape changes incompatibly, which is meant never to happen. */
    public static final int SCHEMA_VERSION = 2;

    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final List<Telemetry.Event> events = new ArrayList<>();
    private final List<Telemetry.Span> spans = new ArrayList<>();
    private final Map<String, Telemetry.Series> series = new TreeMap<>();
    private final List<Map<String, Object>> machines = new ArrayList<>();
    private double[] sampleTimes = new double[0];
    private double sampleDtMs;

    public Trace meta(String k, Object v) { meta.put(k, v); return this; }

    /**
     * What each machine consumed, in total, by the time the run ended.
     *
     * <p>A fourth top-level channel rather than a fifth kind of event, for the same
     * reason spans and series are: these are not moments. They are the run's closing
     * balance, and everything that reasons about quantities rather than about what
     * happened — the bill, a ground-truth comparison, a machine's row in the
     * viewer — needs them without having to reconstruct a peak from a sampled series
     * that was quantised for scrubbing and may have missed the last walk entirely.
     */
    public Trace machine(Map<String, Object> totals) { machines.add(totals); return this; }

    /** Takes everything a recorder holds. Ordered by time, so a reader can scrub. */
    public static Trace of(Telemetry tel) {
        var t = new Trace();
        t.events.addAll(tel.events());
        t.events.sort(Comparator.comparingDouble(Telemetry.Event::t));
        t.spans.addAll(tel.spans());
        t.spans.sort(Comparator.comparingDouble(s -> s.t0));
        t.series.putAll(tel.series());
        t.sampleTimes = tel.sampleTimes();
        t.sampleDtMs = tel.sampleDtMs();
        t.meta.put("schemaVersion", SCHEMA_VERSION);
        t.meta.put("kTime", tel.kTime());
        t.meta.put("telemetry", tel.level().name());
        return t;
    }

    public List<Telemetry.Event> events() { return List.copyOf(events); }
    public List<Telemetry.Span>  spans()  { return List.copyOf(spans); }
    public Map<String, Telemetry.Series> series() { return Map.copyOf(series); }

    /** Every event kind present, with the keys each carried. The D9 acceptance test reads this. */
    public Map<String, Set<String>> shape() {
        var out = new TreeMap<String, Set<String>>();
        for (var e : events)
            out.computeIfAbsent(e.kind(), k -> new TreeSet<>()).addAll(e.detail().keySet());
        return out;
    }

    public String toJson() {
        var root = new LinkedHashMap<String, Object>();
        root.put("schema", SCHEMA_VERSION);
        root.put("meta", meta);

        var evs = new ArrayList<Object>(events.size());
        for (var e : events) {
            var m = new LinkedHashMap<String, Object>();
            m.put("t", round(e.t()));
            m.put("kind", e.kind());
            m.put("vm", e.vm());
            m.put("detail", e.detail());
            evs.add(m);
        }
        root.put("events", evs);

        var sps = new ArrayList<Object>(spans.size());
        for (var s : spans) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", s.id);
            m.put("parent", s.parent);
            m.put("vm", s.vm);
            m.put("kind", s.kind);
            m.put("label", s.label);
            m.put("t0", round(s.t0));
            m.put("t1", round(s.t1));
            m.put("status", s.status);
            if (s.records.get() >= 0) m.put("records", s.records.get());
            m.put("detail", new TreeMap<>(s.detail));
            sps.add(m);
        }
        root.put("spans", sps);

        var ser = new LinkedHashMap<String, Object>();
        ser.put("dtMs", round(sampleDtMs));
        var times = new ArrayList<Object>(sampleTimes.length);
        for (double t : sampleTimes) times.add(round(t));
        ser.put("t", times);
        var channels = new LinkedHashMap<String, Object>();
        for (var e : series.entrySet()) {
            var enc = Telemetry.encode(e.getValue());
            var m = new LinkedHashMap<String, Object>();
            m.put("vm", e.getValue().vm);
            m.put("metric", e.getValue().metric);
            m.put("form", enc.form());
            m.put("ticks", enc.ticks());
            switch (enc.form()) {
                case "constant" -> m.put("value", enc.constant());
                case "runs" -> {
                    var rs = new ArrayList<Object>();
                    for (double[] r : enc.runs()) rs.add(List.of(r[0], (long) r[1]));
                    m.put("runs", rs);
                }
                default -> {
                    var raw = new ArrayList<Object>();
                    for (double v : enc.raw()) raw.add(v);
                    m.put("raw", raw);
                }
            }
            channels.put(e.getKey(), m);
        }
        ser.put("channels", channels);
        root.put("series", ser);
        root.put("machines", machines);

        return Json.write(root);
    }

    public void writeTo(Path p) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, toJson());
    }

    private static double round(double x) {
        return Double.isNaN(x) ? 0 : Math.round(x * 1000) / 1000.0;
    }
}
