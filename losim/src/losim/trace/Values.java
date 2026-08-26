package losim.trace;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.*;

/**
 * What a call actually carried, in a form a person can read.
 *
 * A real system would never put arguments and results in its telemetry. losim
 * does, deliberately: the point is to watch a computation happen, and a video of
 * machines exchanging opaque byte counts teaches nothing. So every call records
 * what went in and what came out — bounded, because a trace that embeds a
 * gigabyte of payload is its own kind of useless.
 */
public final class Values {
    private Values() {}

    /** Beyond this, a collection is summarised rather than reproduced. */
    public static final int MAX_ENTRIES = 12;
    public static final int MAX_STRING  = 120;

    public static Object render(Object o) { return render(o, 0); }

    private static Object render(Object o, int depth) {
        if (o == null) return null;
        if (depth > 4) return "…";
        if (o instanceof Message m) return renderMessage(m, depth);
        if (o instanceof String s) return s.length() <= MAX_STRING ? s
                : s.substring(0, MAX_STRING) + "… (" + s.length() + " chars)";
        if (o instanceof List<?> l) return renderList(l, depth);
        if (o instanceof Map<?, ?> mp) return renderMap(mp, depth);
        return o;
    }

    private static Object renderMessage(Message m, int depth) {
        var out = new LinkedHashMap<String, Object>();
        // getFields() is already ordered by field number, which is the order a
        // reader of the .proto expects — and the order two traces must agree on.
        for (FieldDescriptor f : m.getDescriptorForType().getFields()) {
            if (f.isMapField()) {
                var entries = (List<?>) m.getField(f);
                if (entries.isEmpty()) continue;
                out.put(f.getName(), renderMapField(entries, depth));
            } else if (f.isRepeated()) {
                var l = (List<?>) m.getField(f);
                if (l.isEmpty()) continue;
                out.put(f.getName(), renderList(l, depth));
            } else {
                if (f.hasPresence() && !m.hasField(f)) continue;
                Object v = m.getField(f);
                if (isDefault(v)) continue;
                out.put(f.getName(), render(v, depth + 1));
            }
        }
        return out;
    }

    /** Map fields arrive as repeated key/value messages; sorted, so runs are comparable. */
    private static Object renderMapField(List<?> entries, int depth) {
        var sorted = new TreeMap<String, Object>();
        int shown = 0;
        for (Object e : entries) {
            Message em = (Message) e;
            var fs = em.getDescriptorForType().getFields();
            String k = String.valueOf(em.getField(fs.get(0)));
            if (shown++ < MAX_ENTRIES) sorted.put(k, render(em.getField(fs.get(1)), depth + 1));
        }
        if (entries.size() > MAX_ENTRIES)
            sorted.put("…", "+" + (entries.size() - MAX_ENTRIES) + " more");
        return sorted;
    }

    private static Object renderList(List<?> l, int depth) {
        var out = new ArrayList<>();
        for (int i = 0; i < Math.min(l.size(), MAX_ENTRIES); i++) out.add(render(l.get(i), depth + 1));
        if (l.size() > MAX_ENTRIES) out.add("+" + (l.size() - MAX_ENTRIES) + " more");
        return out;
    }

    private static Object renderMap(Map<?, ?> m, int depth) {
        var out = new TreeMap<String, Object>();
        int i = 0;
        for (var e : m.entrySet()) {
            if (i++ >= MAX_ENTRIES) { out.put("…", "+" + (m.size() - MAX_ENTRIES) + " more"); break; }
            out.put(String.valueOf(e.getKey()), render(e.getValue(), depth + 1));
        }
        return out;
    }

    private static boolean isDefault(Object v) {
        return (v instanceof Number n && n.doubleValue() == 0)
            || (v instanceof Boolean b && !b)
            || (v instanceof String s && s.isEmpty());
    }

    /** A one-line form, for a label on a diagram or a subtitle in a film. */
    public static String summary(Object o) {
        Object r = render(o);
        String s = String.valueOf(r);
        return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING) + "…";
    }
}
