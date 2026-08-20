package losim.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/** One thing that happened, at a virtual instant. Plain data; the viewer draws it. */
public record TraceEvent(long t, String kind, String vm, Map<String, Object> detail) {

    public static TraceEvent of(long t, String kind, String vm, Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) d.put(String.valueOf(kv[i]), kv[i + 1]);
        return new TraceEvent(t, kind, vm, d);
    }
}
