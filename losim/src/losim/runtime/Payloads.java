package losim.runtime;

import losim.api.Data;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

/**
 * Logical size of a message: the descriptor's real bytes plus the volume any
 * {@link Data} handle stands for. Moving a described terabyte costs what a
 * terabyte costs, without a terabyte existing.
 */
public final class Payloads {
    private Payloads() {}

    public static long logicalBytes(Object v) { return walk(v, 0); }

    private static long walk(Object v, int depth) {
        if (v == null || depth > 6) return 0;
        if (v instanceof Data d) return d.bytes();
        if (v instanceof List<?> l) {
            long sum = 0;
            for (Object o : l) sum += walk(o, depth + 1);
            return sum;
        }
        if (v instanceof Map<?, ?> m) {
            long sum = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) sum += walk(e.getKey(), depth + 1) + walk(e.getValue(), depth + 1);
            return sum;
        }
        if (v.getClass().isRecord()) {
            long sum = 0;
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                try {
                    var acc = rc.getAccessor();
                    acc.setAccessible(true);
                    sum += walk(acc.invoke(v), depth + 1);
                } catch (ReflectiveOperationException ignored) { }
            }
            return sum;
        }
        return 0;
    }
}
