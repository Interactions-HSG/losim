import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import losim.trace.JsonReader;

/**
 * What a case asserts against: the trace, as anything downstream would read it.
 *
 * <p>Deliberately not losim's own objects. The trace is the interchange format
 * (D9), so the reference suite reads it the way the viewer, the bill and an
 * invariant check read it — parsed back out of JSON, off disk, written by the
 * command line a student types. A suite that reached into {@code Run.Result}
 * instead would pass on a build whose trace was unreadable.
 */
public final class Expect {

    private final String name;
    private final Map<String, Object> trace;
    private int pass, fail;

    private Expect(String name, Map<String, Object> trace) {
        this.name = name;
        this.trace = trace;
    }

    /** For a case that writes a trace. */
    public static Expect of(String name, String[] args) {
        if (args.length == 0) throw new IllegalArgumentException(name + " needs a trace path");
        try {
            var t = JsonReader.readObject(Files.readString(Path.of(args[0])));
            if (!name.isEmpty()) System.out.println("== " + name + " ==");
            return new Expect(name, t);
        } catch (Exception e) {
            System.out.println("== " + name + " ==");
            System.out.println("  [FAIL] no readable trace at " + args[0] + ": " + e);
            var x = new Expect(name, Map.of());
            x.fail++;
            return x;
        }
    }

    /** Whatever the command line printed, for the cases that are about that. */
    public static String text(String path) {
        try { return Files.readString(Path.of(path)); }
        catch (Exception e) { return ""; }
    }

    /** For a case with no simulation, and so no trace. */
    public static Expect bare(String name) {
        System.out.println("== " + name + " ==");
        return new Expect(name, Map.of());
    }

    // ------------------------------------------------------------------ reading

    @SuppressWarnings("unchecked")
    public Map<String, Object> meta() {
        return (Map<String, Object>) trace.getOrDefault("meta", Map.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events() {
        return (List<Map<String, Object>>) trace.getOrDefault("events", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> spans() {
        return (List<Map<String, Object>>) trace.getOrDefault("spans", List.of());
    }

    /** The closing balance, per machine: what each one consumed by the time it ended. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> machines() {
        return (List<Map<String, Object>>) trace.getOrDefault("machines", List.of());
    }

    /** The fleet's total of a per-machine quantity — which is what wire and allocation are. */
    public double sum(String metric) {
        return machines().stream().mapToDouble(m -> num(m.get(metric))).sum();
    }

    /**
     * The worst machine's, which is what memory and disk are.
     *
     * <p>A fleet does not run out of memory on average. One machine does, and the
     * fact that its neighbours had room is no comfort to the job that was on it.
     */
    public double peak(String metric) {
        return machines().stream().mapToDouble(m -> num(m.get(metric))).max().orElse(0);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> series() {
        return (Map<String, Object>) ((Map<String, Object>)
                trace.getOrDefault("series", Map.of())).getOrDefault("channels", Map.of());
    }

    public List<Map<String, Object>> of(String kind) {
        return events().stream().filter(e -> kind.equals(e.get("kind"))).toList();
    }

    public List<Map<String, Object>> spansOf(String kind) {
        return spans().stream().filter(s -> kind.equals(s.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> detail(Map<String, Object> eventOrSpan) {
        return (Map<String, Object>) eventOrSpan.getOrDefault("detail", Map.of());
    }

    public static double num(Object o) { return o instanceof Number n ? n.doubleValue() : Double.NaN; }
    public static long lng(Object o)   { return o instanceof Number n ? n.longValue() : Long.MIN_VALUE; }

    // ---------------------------------------------------------------- asserting

    public Expect check(boolean ok, String what) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) pass++; else fail++;
        return this;
    }

    public void note(String line) { System.out.println("    " + line); }

    /** Prints the tally and leaves with a code a script can read. */
    public void done() {
        System.out.printf("  %s: %d passed, %d failed%n%n", name, pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
