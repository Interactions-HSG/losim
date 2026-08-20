import losim.api.Invariant;
import losim.api.RunResult;

import java.util.Map;
import java.util.TreeMap;

/** Every word that went in must come out. */
public final class NoLostWords implements Invariant {
    @Override @SuppressWarnings("unchecked")
    public void check(RunResult run) {
        if (run.output() == null) throw new Violation("the job never finished");
        Map<String, Integer> expected = Expected.counts(String.valueOf(run.input()));
        Map<String, Integer> actual = (Map<String, Integer>) run.output();
        Map<String, String> missing = new TreeMap<>();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            int got = actual.getOrDefault(e.getKey(), 0);
            if (got < e.getValue()) missing.put(e.getKey(), got + " < " + e.getValue());
        }
        if (!missing.isEmpty()) throw new Violation("words lost: " + missing);
    }
}
