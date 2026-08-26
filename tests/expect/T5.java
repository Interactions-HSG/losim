import java.util.Comparator;
import java.util.Map;

/**
 * t5-contention — eight calls at 100 refMs into a two-vCPU machine.
 *
 * <p><b>Catches:</b> {@code directExecutor()} creeping in, or an executor not sized
 * to the machine's vCPUs — which is the whole machine model. If either happened,
 * the eight calls would finish in about the time of one and every lesson about
 * capacity would quietly become a lesson about nothing.
 */
public final class T5 {
    public static void main(String[] args) {
        var e = Expect.of("t5-contention", args);

        // The warm-up call first, then the eight that are the case.
        var handlers = e.spansOf("handler").stream()
                .sorted(Comparator.comparingDouble(s -> Expect.num(s.get("t0")))).toList();
        e.check(handlers.size() == Storm.CALLS + 1,
                Storm.CALLS + " calls were served, plus one to warm the path (" + handlers.size() + ")");

        var storm = handlers.subList(Math.max(0, handlers.size() - Storm.CALLS), handlers.size());
        double first = storm.stream().mapToDouble(s -> Expect.num(s.get("t0"))).min().orElse(0);
        double last  = storm.stream().mapToDouble(s -> Expect.num(s.get("t1"))).max().orElse(0);
        double makespan = last - first;
        e.note(String.format("eight 100 refMs calls into 2 vCPUs took %.0f refMs", makespan));

        // Four waves of two. Not one wave, which is what an unbounded executor gives,
        // and not eight, which is what a single thread gives.
        e.check(makespan > 250,
                String.format("they queued: %.0f refMs, not the ~100 they would take if the "
                        + "machine ran all eight at once — an executor that is not the vCPU "
                        + "model makes every capacity lesson vacuous", makespan));
        e.check(makespan < 700,
                String.format("and they did run two at a time rather than one: %.0f refMs, "
                        + "not the ~800 of a single-threaded machine", makespan));

        // The queue is visible, not merely inferable from the arithmetic.
        var waits = e.of("queue_wait");
        double worst = waits.stream()
                .mapToDouble(w -> Expect.num(Expect.detail(w).get("ms"))).max().orElse(0);
        e.check(!waits.isEmpty(),
                String.format("and the waiting is in the trace as queue_wait — %d of them, the "
                        + "longest %.0f ms — so a stretch of idleness can be told apart from a "
                        + "stretch of queueing", waits.size(), worst));

        var done = e.of("done");
        e.check(!done.isEmpty() && String.valueOf(Expect.detail(done.get(0)).get("value"))
                        .contains("all answered"),
                "every one of them was answered: queued is not dropped");
        e.done();
    }
}
