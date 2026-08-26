package losim.scale;

import java.util.*;
import losim.trace.Telemetry;

/**
 * The full-scale timeline, re-derived rather than multiplied.
 *
 * <p>Projecting each observed duration by its own exponent and laying the results
 * back on the observed timeline gives a timeline whose parts no longer sum to its
 * whole: overlapping calls slide past each other and the critical path lands in
 * the wrong place. Multiplying the whole makespan by a factor is worse still,
 * because it multiplies the fixed overhead and the idle stretches along with the
 * work.
 *
 * <p>So the schedule is replayed. Each cost site is projected independently, and
 * then the observed call graph is run again with the projected durations and each
 * machine's real concurrency limit. <b>Observed order is preserved; observed
 * spacing is not</b> — spacing is precisely what changes with scale, and a
 * reconstruction that kept it would be answering a question nobody asked.
 *
 * <p>What this catches that multiplication cannot: a fleet with spare cores. Four
 * calls into eight cores take one wave; sixteen calls take two. Multiplying the
 * first run by four says eight waves.
 */
public final class Schedule {
    private Schedule() {}

    /**
     * One unit of work in the replay.
     *
     * @param after ids that must finish first — siblings the caller demonstrably
     *              waited for, as opposed to ones it issued alongside this
     */
    public record Task(long id, long parent, String machine, double projectedMs,
                       double observedStart, List<Long> after) {}

    public record Replay(double makespanRefMs, int tasks, double criticalPathMs, String note) {}

    /**
     * Builds the replay from an observed run.
     *
     * <p>Whether two calls were parallel is <i>observed</i>, not assumed: siblings
     * that overlapped in the run were issued together, and siblings that did not
     * were issued one after the other. That is the one thing a trace knows and a
     * model cannot guess.
     */
    public static List<Task> tasksOf(Telemetry tel, Map<String, Double> projectedByLabel) {
        var handlers = tel.spans().stream()
                .filter(s -> s.kind.equals("handler") && s.t1 >= 0)
                .sorted(Comparator.comparingDouble(s -> s.t0))
                .toList();
        var byParent = new LinkedHashMap<Long, List<Telemetry.Span>>();
        // A handler's parent is the client span; group by the client span's own
        // parent so that siblings are calls made by the same caller.
        var spanById = new HashMap<Long, Telemetry.Span>();
        for (var s : tel.spans()) spanById.put(s.id, s);

        var tasks = new ArrayList<Task>();
        for (var h : handlers) {
            var rpc = spanById.get(h.parent);
            long caller = rpc == null ? 0 : rpc.parent;
            byParent.computeIfAbsent(caller, k -> new ArrayList<>()).add(h);
        }
        for (var group : byParent.entrySet()) {
            var siblings = group.getValue();
            for (int i = 0; i < siblings.size(); i++) {
                var self = siblings.get(i);
                var after = new ArrayList<Long>();
                for (int j = 0; j < i; j++) {
                    var earlier = siblings.get(j);
                    // Non-overlapping and earlier: the caller waited for it.
                    if (earlier.t1 <= self.t0 + 1e-9) after.add(earlier.id);
                }
                double projected = projectedByLabel.getOrDefault(self.label,
                        self.programMs(tel.kTime()));
                tasks.add(new Task(self.id, group.getKey(), self.vm, projected, self.t0, after));
            }
        }
        return tasks;
    }

    /**
     * Runs the graph again, with the projected durations and the fleet's real cores.
     *
     * <p>A list-scheduling replay: a task becomes ready when everything the caller
     * waited for has finished, and starts when its machine has a core free. The
     * makespan is read off the result rather than summed from the parts.
     */
    public static Replay replay(List<Task> tasks, Map<String, Integer> vcpus) {
        if (tasks.isEmpty()) return new Replay(0, 0, 0, "nothing to replay");
        var byId = new LinkedHashMap<Long, Task>();
        for (Task t : tasks) byId.put(t.id(), t);

        var finish = new HashMap<Long, Double>();
        var freeCores = new HashMap<String, PriorityQueue<Double>>();
        for (Task t : tasks)
            freeCores.computeIfAbsent(t.machine(), m -> {
                var q = new PriorityQueue<Double>();
                for (int i = 0; i < Math.max(1, vcpus.getOrDefault(m, 1)); i++) q.add(0.0);
                return q;
            });

        // Observed order is the tie-break, which is what keeps the replay a replay.
        var order = new ArrayList<>(tasks);
        order.sort(Comparator.comparingDouble(Task::observedStart).thenComparingLong(Task::id));

        double makespan = 0, longestChain = 0;
        var chain = new HashMap<Long, Double>();
        for (Task t : order) {
            double ready = 0;
            for (long dep : t.after()) ready = Math.max(ready, finish.getOrDefault(dep, 0.0));
            var cores = freeCores.get(t.machine());
            double core = cores.poll();
            double start = Math.max(ready, core);
            double end = start + t.projectedMs();
            cores.add(end);
            finish.put(t.id(), end);

            double depth = t.projectedMs();
            for (long dep : t.after()) depth = Math.max(depth, chain.getOrDefault(dep, 0.0) + t.projectedMs());
            chain.put(t.id(), depth);
            longestChain = Math.max(longestChain, depth);
            makespan = Math.max(makespan, end);
        }
        return new Replay(makespan, tasks.size(), longestChain,
                makespan > longestChain * 1.05
                        ? "the fleet's cores are the limit, not the call graph"
                        : "the call graph is the limit, not the fleet's cores");
    }

    /** What multiplying the observed makespan would have said, for comparison. */
    public static double multiplied(double observedMakespanRefMs, double factor) {
        return observedMakespanRefMs * factor;
    }
}
