package losim.trace;

import java.util.List;
import java.util.Map;

/** Minimal, deterministic JSON writer. No dependency, stable key order. */
public final class Json {
    private Json() {}

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        emit(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void emit(Object o, StringBuilder sb) {
        switch (o) {
            case null -> sb.append("null");
            case String s -> quote(s, sb);
            case Boolean b -> sb.append(b);
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case Double d -> sb.append(d.isNaN() || d.isInfinite() ? "null" : trim(d));
            case Float f -> sb.append(trim(f.doubleValue()));
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    emit(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    emit(l.get(i), sb);
                }
                sb.append(']');
            }
            case Object[] a -> emit(List.of(a), sb);
            default -> quote(String.valueOf(o), sb);
        }
    }

    static String trim(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
