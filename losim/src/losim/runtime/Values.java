package losim.runtime;

import losim.api.*;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every value knows how to be shown.
 *
 * The default covers records, collections and primitives so nothing needs an
 * annotation; a type may override by implementing {@link Drawable}.
 */
public final class Values {
    private Values() {}

    public static Object render(Object v) { return render(v, 0); }

    private static Object render(Object v, int depth) {
        if (v == null) return null;
        if (v instanceof Drawable d) return render(d.visual(), depth + 1);
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof Enum<?> e) return e.name();
        if (depth > 4) return String.valueOf(v);
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            int n = Math.min(list.size(), 8);
            for (int i = 0; i < n; i++) out.add(render(list.get(i), depth + 1));
            if (list.size() > n) out.add("+" + (list.size() - n));
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i++ >= 8) { out.put("+", map.size() - 8); break; }
                out.put(String.valueOf(e.getKey()), render(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                try { var acc = rc.getAccessor(); acc.setAccessible(true); out.put(rc.getName(), render(acc.invoke(v), depth + 1)); }
                catch (ReflectiveOperationException e) { out.put(rc.getName(), "?"); }
            }
            return out;
        }
        return String.valueOf(v);
    }
}
