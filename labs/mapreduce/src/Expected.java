import java.util.Map;
import java.util.TreeMap;

/** The answer, computed the boring way, so invariants have ground truth. */
final class Expected {
    private Expected() {}
    static Map<String, Integer> counts(String text) {
        Map<String, Integer> m = new TreeMap<>();
        for (String w : text.trim().split("\\s+")) if (!w.isBlank()) m.merge(w, 1, Integer::sum);
        return m;
    }
}
