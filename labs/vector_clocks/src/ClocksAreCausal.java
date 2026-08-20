import losim.api.Invariant;
import losim.api.RunResult;

import java.util.*;

/**
 * Grades a vector clock against ground truth.
 *
 * The trace records what actually caused what, so the check does not need to
 * trust the student's own bookkeeping: after receiving a message, the
 * receiver's clock must dominate the sender's clock at the moment of sending.
 * Forget the merge and this fails with both events named.
 */
public final class ClocksAreCausal implements Invariant {

    @Override @SuppressWarnings("unchecked")
    public void check(RunResult run) {
        List<Map<String, Object>> events = run.events();
        Map<String, List<Integer>> latest = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Map<String, Object> e : events) {
            String kind = String.valueOf(e.get("kind"));
            String vm = String.valueOf(e.get("vm"));

            if (kind.equals("state") && "clock".equals(e.get("key"))) {
                latest.put(vm, toInts(e.get("value")));
                continue;
            }
            if (!kind.equals("handler_start")) continue;

            Object arg = e.get("arg");
            if (!(arg instanceof Map)) continue;
            List<Integer> senderClock = toInts(((Map<String, Object>) arg).get("clock"));
            if (senderClock.isEmpty()) continue;

            // find the receiver's clock immediately after this delivery
            List<Integer> after = clockAfter(events, events.indexOf(e), vm);
            if (after.isEmpty()) continue;
            checked++;
            for (int i = 0; i < senderClock.size() && i < after.size(); i++) {
                if (after.get(i) < senderClock.get(i)) {
                    problems.add(vm + " received " + e.get("arg") + " but its clock became " + after
                            + " — entry " + i + " went backwards, so the merge was missed");
                    break;
                }
            }
        }
        if (checked == 0) throw new Violation("no message was ever stamped with a clock");
        if (!problems.isEmpty()) throw new Violation(String.join("; ", problems));
    }

    private static List<Integer> clockAfter(List<Map<String, Object>> events, int from, String vm) {
        for (int i = from + 1; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            if (vm.equals(e.get("vm")) && "state".equals(e.get("kind")) && "clock".equals(e.get("key")))
                return toInts(e.get("value"));
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> toInts(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Integer> out = new ArrayList<>();
        for (Object x : l) if (x instanceof Number n) out.add(n.intValue());
        return out;
    }
}
