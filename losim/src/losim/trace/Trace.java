package losim.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one artefact the simulator produces. Everything downstream reads this. */
public final class Trace {
    public static final int SCHEMA_VERSION = 1;

    private final List<TraceEvent> events = new ArrayList<>();
    private final Map<String, Object> meta = new LinkedHashMap<>();

    public void meta(String k, Object v) { meta.put(k, v); }
    public void add(TraceEvent e) { events.add(e); }
    public List<TraceEvent> events() { return List.copyOf(events); }
    public int size() { return events.size(); }

    /** Stable, line-oriented digest used by determinism tests. */
    public String digest() {
        StringBuilder sb = new StringBuilder();
        for (TraceEvent e : events) {
            sb.append(e.t()).append(' ').append(e.kind()).append(' ').append(e.vm());
            for (Map.Entry<String, Object> en : e.detail().entrySet())
                sb.append(' ').append(en.getKey()).append('=').append(render(en.getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String render(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(e.getKey()).append(':').append(render(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(render(l.get(i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA_VERSION);
        root.put("meta", meta);
        List<Object> evs = new ArrayList<>(events.size());
        for (TraceEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", e.t());
            m.put("kind", e.kind());
            m.put("vm", e.vm());
            m.put("detail", e.detail());
            evs.add(m);
        }
        root.put("events", evs);
        return Json.write(root);
    }

    public void writeTo(Path p) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, toJson());
    }
}
