import losim.api.Invariant;
import losim.api.RunResult;

import java.util.Map;
import java.util.TreeMap;

/**
 * Nothing may be counted twice.
 *
 * Reassigning a split after a timeout creates duplicate execution, so solving
 * failure detection immediately raises idempotency. This is the check that
 * notices when only the first half was done.
 */
public final class NoDoubleCounting implements Invariant {
    @Override @SuppressWarnings("unchecked")
    public void check(RunResult run) {
        if (run.output() == null) throw new Violation("the job never finished");
        Map<String, Integer> expected = Expected.counts(String.valueOf(run.input()));
        Map<String, Integer> actual = (Map<String, Integer>) run.output();
        Map<String, String> over = new TreeMap<>();
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            int want = expected.getOrDefault(e.getKey(), 0);
            if (e.getValue() > want) over.put(e.getKey(), e.getValue() + " > " + want);
        }
        if (!over.isEmpty()) throw new Violation("counted more than once: " + over);
    }
}
