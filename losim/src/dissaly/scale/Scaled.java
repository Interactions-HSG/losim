package dissaly.scale;

import java.nio.file.Path;
import java.util.*;
import dissaly.runtime.Simulate;
import dissaly.sim.Simulation;
import dissaly.trace.Telemetry;
import dissaly.verify.Trust;

/**
 * Scaled mode: shrink the world, run it, and project the answer back.
 *
 * <p>There is exactly one scaled mode and it always uses the engine. A simulation
 * cannot hand-declare a shrink factor and bypass this, because a factor somebody
 * guessed is a third mode whose numbers nobody could account for.
 *
 * <p>What comes out carries <b>both scales for every measurement</b>: what happened,
 * and what it is a model of. And where the engine refused to fit a law, the
 * projected column is <i>absent with a reason</i> rather than filled with a
 * plausible number — which is the only honest thing to put there, and the only
 * thing that keeps the numbers beside it worth reading.
 */
public final class Scaled {
    private Scaled() {}

    /**
     * How many independent seeds each rung of the ladder is run at.
     *
     * <p>Six, grouped into three sets of two: the law is fitted on the median of all
     * of them, and its reproducibility is measured by refitting each set the same
     * way. Three sets is the fewest from which a spread means anything.
     */
    public static final int SEEDS = 6;

    /**
     * @param run  the scaled run, or null when the engine found no feasible size and
     *             said so instead of producing one anyway
     */
    public record Result(Simulate.Result run, ScalePlan plan, boolean planWasCached,
                         List<ScalePlan.Projection> projections, Trust trust,
                         List<Invariants.Violation> invariants) {

        public boolean feasible() { return plan.feasible(); }
    }

    public static Result of(Simulation s, ClassLoader loader, Telemetry.Level level,
                            List<Path> code) throws Exception {
        if (s.scale() <= 1)
            throw new IllegalArgumentException("scaled mode needs a scale above 1 to project to");

        // Once, for the whole of scaled mode: the probe grid runs these same classes
        // thirty times over, and the answer does not change between them.
        Trust trust = Trust.of(s, code);

        String key = Plans.key(s, level, code);
        var cached = Plans.load(key);
        ScalePlan plan;
        boolean fromCache = cached.isPresent();
        if (fromCache) {
            plan = cached.get();
        } else {
            var grid = Grid.run(s, loader, level, SEEDS);
            double reach = s.units() / (double) Math.max(1, grid.dataLadder()
                    .get(grid.dataLadder().size() - 1).get(0).units());
            var laws = Laws.fit(grid, reach);
            plan = Solve.of(s, grid, laws);
            Plans.save(key, plan);
        }

        if (!plan.feasible()) return new Result(null, plan, fromCache, List.of(), trust, List.of());

        // The run is the plan, applied. The same telemetry configuration as the
        // probes, deliberately: a fit that described a differently-watched system
        // would be the same mistake as fitting on clean runs and predicting a
        // faulty one.
        var result = Simulate.of(plan.applyTo(s), loader, level, trust);

        var probe = Probe.of(plan.applyTo(s), result);
        var projections = new ArrayList<ScalePlan.Projection>();
        var asJson = new ArrayList<Object>();
        // Per machine, in their own block rather than mixed into the list above.
        // The cluster list is what a person reads at the end of a run and it has to
        // stay one screen; the per-machine ones are what a viewer draws a node at a
        // time, and there are four of them per machine.
        var byNode = new LinkedHashMap<String, List<Object>>();
        var machines = plan.laws().machines();
        for (var e : probe.resources().entrySet()) {
            var p = plan.projectionOf(e.getKey(), e.getValue());
            // A cluster figure is rebuilt from its machines rather than read off a law
            // fitted to the aggregate — `max` and `sum` are taken after projecting,
            // not before. See Probe.isPeak for what fitting the aggregate does to a
            // cluster whose largest machine at probe size is not its largest at scale.
            boolean assembled = false;
            if (!Probe.isPerNode(e.getKey())
                    && (Probe.isPeak(e.getKey()) || Probe.isSum(e.getKey()))
                    && !machines.isEmpty()
                    // A refused resource stays refused. Assembling one from its parts
                    // overwrites the reason with a number, and this shipped: a memory
                    // law refused for bending came back as `projected: 0` with the
                    // refusal cleared, which is a refusal turned into a confident
                    // answer — the one outcome the whole design exists to prevent.
                    && p.projected().isPresent()) {
                var across = plan.laws().across(e.getKey(), plan.fullUnits(), machines);
                // And a peak or a total short of a machine is a lower bound, not a
                // projection. Reporting one as though it were the answer understates
                // it by however much the missing machine would have contributed, and
                // says nothing about by how much.
                if (across.value().isPresent() && across.missing().isEmpty()) {
                    p = new ScalePlan.Projection(p.resource(), p.observed(), across.value(),
                            p.errorBar() > 0 ? p.errorBar() : 1.0, null);
                    assembled = true;
                }
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("resource", Probe.resourceOf(p.resource()));
            m.put("observed", round(p.observed()));
            if (p.projected().isPresent()) {
                m.put("projected", round(p.projected().getAsDouble()));
                m.put("errorBar", round(p.errorBar()));
                var law = plan.laws().law(p.resource());
                if (law != null) m.put("of", law.variable());
                // How this number was arrived at, because the two ways give different
                // answers and a reader recomputing from the laws has to know which
                // arithmetic to do. Without it the trace stopped being self-checking:
                // a cluster peak assembled from per-machine laws does not equal the
                // cluster law evaluated at full size, and the acceptance test that
                // recomputes it said so.
                m.put("from", assembled ? "machines" : "law");
            } else {
                m.put("refused", p.refusedBecause());
            }
            if (Probe.isPerNode(e.getKey())) {
                byNode.computeIfAbsent(Probe.machineOf(e.getKey()), k -> new ArrayList<>()).add(m);
            } else {
                projections.add(p);
                asJson.add(m);
            }
        }
        // Checked after everything is decided and before anything is written, so the
        // trace carries the engine's own doubts about its answers beside the answers.
        var violations = Invariants.of(plan, probe, result, projections);
        var asViolations = new ArrayList<Object>();
        for (var v : violations) asViolations.add(v.asMap());

        result.trace()
              .meta("mode", "scaled")
              .meta("scale", plan.asMap())
              .meta("projections", asJson)
              .meta("nodeProjections", byNode)
              .meta("planCached", fromCache);
        if (!violations.isEmpty()) result.trace().meta("invariants", asViolations);
        return new Result(result, plan, fromCache, projections, trust, violations);
    }

    private static double round(double x) { return Math.round(x * 1000) / 1000.0; }
}
