package losim.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A YAML value that remembers where it came from, so errors can say the line. */
public final class Node {
    public final Object value;          // Map<String,Node> | List<Node> | String | null
    public final int line;
    public final String file;

    public Node(Object value, int line, String file) {
        this.value = value; this.line = line; this.file = file;
    }

    public String where() { return file + ":" + line; }

    public ConfigError error(String message) { return new ConfigError(where() + ": " + message); }

    @SuppressWarnings("unchecked")
    public Map<String, Node> map() {
        if (!(value instanceof Map)) throw error("expected a mapping");
        return (Map<String, Node>) value;
    }

    @SuppressWarnings("unchecked")
    public List<Node> list() {
        if (value instanceof List) return (List<Node>) value;
        List<Node> one = new ArrayList<>();
        one.add(this);
        return one;                     // a scalar is a one-element list; convenient for az:
    }

    public boolean isMap() { return value instanceof Map; }
    public boolean isList() { return value instanceof List; }
    public boolean isScalar() { return !(value instanceof Map) && !(value instanceof List); }

    public String str() {
        if (!isScalar()) throw error("expected a scalar");
        return value == null ? null : String.valueOf(value);
    }

    public boolean bool() {
        String s = str();
        if ("true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) return false;
        throw error("expected true or false, got '" + s + "'");
    }

    public long integer() {
        try { return Long.parseLong(str().trim()); }
        catch (NumberFormatException e) { throw error("expected an integer, got '" + str() + "'"); }
    }

    public double number() {
        try { return Double.parseDouble(str().trim()); }
        catch (NumberFormatException e) { throw error("expected a number, got '" + str() + "'"); }
    }

    /** Durations read the way people write them: 30s, 800ms, 2m. */
    public long millis() {
        String s = str().trim();
        try {
            if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2).trim());
            if (s.endsWith("s")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 1000);
            if (s.endsWith("m")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 60_000);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw error("expected a duration like 800ms, 30s or 2m, got '" + s + "'");
        }
    }

    public Node get(String key) {
        Node n = map().get(key);
        if (n == null) throw error("missing required key '" + key + "'");
        return n;
    }

    public Node opt(String key) { return isMap() ? map().get(key) : null; }

    public Node optOr(String key, String fallback) {
        Node n = opt(key);
        return n != null ? n : new Node(fallback, line, file);
    }

    public static Node of(Map<String, Node> m, int line, String file) { return new Node(m, line, file); }
    public static Node empty(String file) { return new Node(new LinkedHashMap<String, Node>(), 1, file); }

    public static final class ConfigError extends RuntimeException {
        public ConfigError(String m) { super(m); }
    }
}
