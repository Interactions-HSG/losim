package losim.scale;

import java.util.*;
import losim.res.InstanceCatalog;
import losim.scenario.Scenario;
import losim.time.Clock;

/**
 * Choosing the size of the world.
 *
 * <p>What the scale model preserves is <b>ratio</b>: demand over capacity, per
 * machine, per resource. So the caps are solved, not divided — and
 * each by its own factor, because memory follows distinct keys and disk follows
 * data volume and those grow at different rates. A uniform factor would give both
 * the same shrink and be wrong about at least one.
 *
 * <p>Fixed overhead is real at every scale. A JVM's baseline heap does not shrink
 * because the workload did, so it is added back at full size rather than scaled
 * down with everything else.
 */
public final class Solve {
    private Solve() {}

    /** The variable part must be at least this many times the fixed part to be worth fitting. */
    static final double VARIABLE_MUST_DOMINATE = 10.0;

    /** How much of the host's heap a scaled fleet may ask for. */
    static final double HOST_HEAP_SHARE = 0.6;

    public static ScalePlan of(Scenario s, Grid grid, Laws laws) {
        long full = s.records();
        var notes = new ArrayList<>(grid.notes());
        var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();

        // The run size is the largest rung that was actually measured and is
        // feasible. Choosing one beyond the ladder would mean extrapolating the very
        // thing the ladder exists to establish.
        Long chosen = null;
        String infeasible = null;
        for (int i = rungs.size() - 1; i >= 0; i--) {
            long n = rungs.get(i).variables().getOrDefault("records", 0.0).longValue();
            String why = whyNot(n, s, laws, grid);
            if (why == null) { chosen = n; break; }
            infeasible = why;
        }
        if (chosen == null)
            return new ScalePlan(0, full, s.kTime(), Map.of(), laws, grid.runs(), notes,
                    infeasible != null ? infeasible
                            : "no size on the probe ladder satisfies every resource at once");

        long n = chosen;
        var caps = solveCaps(s, laws, n, full);
        String tooFine = kTimeTooFine(s, laws, n);
        if (tooFine != null)
            return new ScalePlan(0, full, s.kTime(), Map.of(), laws, grid.runs(), notes, tooFine);

        if (laws.byResource().isEmpty())
            notes.add("no resource could be fitted at all, so this plan shrinks the world"
                    + " without being able to project anything back from it");
        for (var e : laws.refused().entrySet())
            notes.add(e.getKey() + " is not projected: " + e.getValue());

        return new ScalePlan(n, full, s.kTime(), caps, laws, grid.runs(), notes, null);
    }

    /**
     * Feasibility, checked in both directions.
     *
     * <p>Large enough that the variable part dominates the fixed overhead, small
     * enough that every scaled machine fits in the host's heap. If no size on the
     * ladder satisfies both, the engine names the resource and stops rather than
     * producing a projection nobody should use.
     */
    private static String whyNot(long n, Scenario s, Laws laws, Grid grid) {
        // Only the resources the solve actually caps. A large fixed term elsewhere —
        // allocation carries the JVM's own warm-up, which is real at every scale —
        // says the law for that resource is weak, not that the run size is wrong, and
        // refusing a whole run over it would leave nothing runnable at all.
        for (String resource : List.of(Probe.MEMORY, Probe.DISK)) {
            var law = laws.law(resource);
            if (law == null) continue;
            double variable = laws.variablePart(resource, n);
            if (law.fixed() > 0 && variable < law.fixed() * VARIABLE_MUST_DOMINATE)
                return String.format("%s at %d records is only %.1fx its own fixed overhead"
                        + " (%.3f against %.3f); below %.0fx the fit is describing the overhead"
                        + " rather than the workload", resource, n, variable / law.fixed(),
                        variable, law.fixed(), VARIABLE_MUST_DOMINATE);
        }
        double heapMb = Runtime.getRuntime().maxMemory() / 1048576.0 * HOST_HEAP_SHARE;
        double demand = laws.project(Probe.MEMORY, n).orElse(0)
                * Math.max(1, s.machines().size() - 1);
        if (demand > heapMb)
            return String.format("at %d records the fleet would hold %.0f MB, and this host"
                    + " offers %.0f MB to work in — the run does not fit the laptop it is"
                    + " meant to fit on", n, demand, heapMb);
        return null;
    }

    /**
     * Caps, solved per machine and per resource.
     *
     * <p>{@code cap = fixed + declared * (variable at run scale / variable at full
     * scale)}. The ratio is what preserves demand-over-capacity; the fixed term is
     * what stops a machine being given less baseline than a JVM needs to exist.
     */
    private static Map<String, double[]> solveCaps(Scenario s, Laws laws, long n, long full) {
        var caps = new LinkedHashMap<String, double[]>();
        double memRatio = ratio(laws, Probe.MEMORY, n, full);
        double diskRatio = ratio(laws, Probe.DISK, n, full);
        double memFixed = laws.has(Probe.MEMORY) ? laws.law(Probe.MEMORY).fixed() : 0;
        double diskFixed = laws.has(Probe.DISK) ? laws.law(Probe.DISK).fixed() : 0;

        for (var m : s.machines()) {
            var spec = InstanceCatalog.get(m.instance());
            double declaredMem = m.memoryCapMb() != null ? m.memoryCapMb() : spec.memoryMb();
            double declaredDisk = m.diskCapMb() != null ? m.diskCapMb() : spec.storageGb() * 1024.0;
            caps.put(m.name(), new double[]{
                    Math.max(1, memFixed + declaredMem * memRatio),
                    Math.max(1, diskFixed + declaredDisk * diskRatio)});
        }
        return caps;
    }

    private static double ratio(Laws laws, String resource, long n, long full) {
        if (!laws.has(resource)) return 1.0;
        double atRun = laws.variablePart(resource, n);
        double atFull = laws.variablePart(resource, full);
        if (atFull <= 0) return 1.0;
        return Math.min(1.0, atRun / atFull);
    }

    /**
     * Whether the scenario's compression can still express its own costs.
     *
     * <p>k_time is <b>declared, not chosen</b>. It has to be global — there is one
     * wall clock, and two machines sleeping under different factors would disagree
     * about when now is — and it has to be the same for the probe grid as for the run
     * it fits, or the fit describes a differently-compressed system. So the engine
     * checks it rather than picking one after the grid has already been climbed.
     *
     * <p>What it checks: a cost below the timer floor is owed rather than lost, and
     * that ledger is good for about fifty times below the floor. Past that the
     * residual stops being absorbed and the run's own timings drift.
     */
    private static String kTimeTooFine(Scenario s, Laws laws, long n) {
        double finest = Double.MAX_VALUE;
        String site = null;
        for (var e : laws.byCostSite().entrySet()) {
            double at = e.getValue().at(laws.variableAt(e.getValue().variable(), n));
            if (at > 0 && at < finest) { finest = at; site = e.getKey(); }
        }
        if (site == null) return null;
        double usable = finest / (Clock.FLOOR_MS / 50);
        if (s.kTime() <= usable) return null;
        return String.format("k_time %.0f is finer than this workload can express: %s costs"
                + " %.4f refMs, which at that compression is %.0fx below what the sleep debt can"
                + " still settle. Lower k_time to %.0f or less, or give that cost site more to do.",
                s.kTime(), site, finest, s.kTime() / usable, Math.max(1, Math.floor(usable)));
    }
}
