package losim.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One piece of a scenario, and the line it was written on.
 *
 * <p>Carrying the line everywhere is the whole reason this exists rather than a
 * plain {@code Map<String, Object>}. A scenario is the instructor's surface, and
 * an error in it should read like a compiler error — {@code wordcount.yaml:14:
 * unknown instance type 'm5.mega'} — not like a stack trace from inside a parser.
 */
public final class Node {

    /** Nothing at all: an absent key, so callers can ask before they insist. */
    static final Object MISSING = new Object();

    private final String file;
    private final int line;
    private final Object value;               // Map<String,Node> | List<Node> | String | MISSING

    Node(String file, int line, Object value) {
        this.file = file; this.line = line; this.value = value;
    }

    public String where() { return file + ":" + line; }
    public int line()     { return line; }
    public boolean present() { return value != MISSING; }
    public boolean isMap()   { return value instanceof Map; }
    public boolean isList()  { return value instanceof List; }

    public IllegalArgumentException fail(String message) {
        return new IllegalArgumentException(where() + ": " + message);
    }

    // ------------------------------------------------------------------ reading

    @SuppressWarnings("unchecked")
    public Map<String, Node> map() {
        if (!(value instanceof Map)) throw fail("expected a block of keys here");
        return (Map<String, Node>) value;
    }

    @SuppressWarnings("unchecked")
    public List<Node> list() {
        if (value instanceof List) return (List<Node>) value;
        if (value == MISSING) return List.of();
        return List.of(this);                 // one item is a list of one, which reads better
    }

    /** A child, or an absent node that still knows where it should have been. */
    public Node opt(String key) {
        if (value == MISSING) return this;
        Node n = map().get(key);
        return n == null ? new Node(file, line, MISSING) : n;
    }

    public Node at(String key) {
        Node n = opt(key);
        if (!n.present()) throw fail("'" + key + "' is required here");
        return n;
    }

    public String str() {
        if (!(value instanceof String s)) throw fail("expected a single value here");
        return s;
    }

    public String str(String fallback) { return present() ? str() : fallback; }

    public double num() {
        try { return Double.parseDouble(str().trim()); }
        catch (NumberFormatException e) { throw fail("expected a number, got '" + str() + "'"); }
    }

    public double num(double fallback) { return present() ? num() : fallback; }

    public int integer() {
        double d = num();
        if (d != Math.rint(d)) throw fail("expected a whole number, got " + str());
        return (int) d;
    }

    public int integer(int fallback) { return present() ? integer() : fallback; }

    public boolean bool(boolean fallback) {
        if (!present()) return fallback;
        String s = str().trim();
        if (s.equals("true") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("no")) return false;
        throw fail("expected true or false, got '" + s + "'");
    }

    /**
     * A duration, in reference-machine time.
     *
     * <p><b>A bare number is refused.</b> "2s" is ambiguous between two seconds of
     * the simulated world and two seconds of your afternoon, and those differ by
     * {@code k_time}, which the person writing the scenario never sees. So the unit
     * has to be said: {@code 900 refMs}, {@code 2 refSeconds}.
     */
    public double refMs() {
        String s = str().trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([0-9]*\\.?[0-9]+)\\s*(refMs|refms|refSeconds|refSec|refS|refs)$")
                .matcher(s);
        if (!m.matches())
            throw fail("'" + s + "' does not say what kind of time it is. Durations are"
                     + " reference-machine time: write '" + s + " refMs' or '" + s + " refSeconds'."
                     + " A bare number would be ambiguous between the simulated world and your"
                     + " afternoon, and those differ by k_time.");
        double v = Double.parseDouble(m.group(1));
        return m.group(2).toLowerCase().startsWith("refs") && !m.group(2).equalsIgnoreCase("refMs")
                ? v * 1000 : v;
    }

    public double refMs(double fallback) { return present() ? refMs() : fallback; }

    /** Every key here that is not in {@code known} — a typo caught at load, not at run. */
    public void onlyAllows(String... known) {
        if (!isMap()) return;
        var allowed = List.of(known);
        for (var e : map().entrySet())
            if (!allowed.contains(e.getKey()))
                throw e.getValue().fail("unknown key '" + e.getKey() + "'; expected one of "
                        + String.join(", ", allowed));
    }

    /** Every value in this node, as strings — for a list that may have been written as one item. */
    public List<String> strings() {
        var out = new ArrayList<String>();
        for (Node n : list()) out.add(n.str());
        return out;
    }

    static Node map(String file, int line) {
        return new Node(file, line, new LinkedHashMap<String, Node>());
    }

    static Node list(String file, int line) {
        return new Node(file, line, new ArrayList<Node>());
    }

    static Node scalar(String file, int line, String text) { return new Node(file, line, text); }

    @SuppressWarnings("unchecked")
    void put(String key, Node child) {
        if (map().containsKey(key)) throw child.fail("'" + key + "' is set twice");
        ((Map<String, Node>) value).put(key, child);
    }

    @SuppressWarnings("unchecked")
    void add(Node child) { ((List<Node>) value).add(child); }
}
