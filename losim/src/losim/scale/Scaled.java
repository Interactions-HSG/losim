package losim.scale;

import java.nio.file.Path;
import java.util.*;
import losim.runtime.Run;
import losim.scenario.Scenario;
import losim.trace.Telemetry;
import losim.verify.Trust;

/**
 * Scaled mode: shrink the world, run it, and project the answer back.
 *
 * <p>There is exactly one scaled mode and it always uses the engine. A scenario
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
    public record Result(Run.Result run, ScalePlan plan, boolean planWasCached,
                         List<ScalePlan.Projection> projections, Trust trust) {

        public boolean feasible() { return plan.feasible(); }
    }

    public static Result of(Scenario s, ClassLoader loader, Telemetry.Level level,
                            List<Path> code) throws Exception {
        if (s.workload() == null)
            throw new IllegalArgumentException("scaled mode needs a workload: to scale down from");

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
            double reach = s.records() / (double) Math.max(1, grid.dataLadder()
                    .get(grid.dataLadder().size() - 1).get(0).records());
            var laws = Laws.fit(grid, reach);
            plan = Solve.of(s, grid, laws);
            Plans.save(key, plan);
        }

        if (!plan.feasible()) return new Result(null, plan, fromCache, List.of(), trust);

        // The run is the plan, applied. The same telemetry configuration as the
        // probes, deliberately: a fit that described a differently-watched system
        // would be the same mistake as fitting on clean runs and predicting a
        // faulty one.
        var result = Run.of(plan.applyTo(s), loader, level, trust);

        var probe = Probe.of(plan.applyTo(s), result);
        var projections = new ArrayList<ScalePlan.Projection>();
        var asJson = new ArrayList<Object>();
        for (var e : probe.resources().entrySet()) {
            var p = plan.projectionOf(e.getKey(), e.getValue());
            projections.add(p);
            var m = new LinkedHashMap<String, Object>();
            m.put("resource", p.resource());
            m.put("observed", round(p.observed()));
            if (p.projected().isPresent()) {
                m.put("projected", round(p.projected().getAsDouble()));
                m.put("errorBar", round(p.errorBar()));
                var law = plan.laws().law(p.resource());
                if (law != null) m.put("of", law.variable());
            } else {
                m.put("refused", p.refusedBecause());
            }
            asJson.add(m);
        }
        result.trace()
              .meta("mode", "scaled")
              .meta("scale", plan.asMap())
              .meta("projections", asJson)
              .meta("planCached", fromCache);
        return new Result(result, plan, fromCache, projections, trust);
    }

    private static double round(double x) { return Math.round(x * 1000) / 1000.0; }
}
