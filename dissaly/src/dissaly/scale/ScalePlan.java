package dissaly.scale;

import java.util.*;
import dissaly.sim.Simulation;

/**
 * How the world was shrunk, and how to unshrink an answer.
 *
 * <p>The plan travels in the trace, so {@code projected = f(observed)} is
 * recomputable by anyone reading it later rather than being a number they have to
 * take on trust.
 *
 * @param units     what the run actually processed
 * @param fullUnits what it is a scale model of
 * @param caps        per machine, {memoryMb, diskMb}, <b>solved rather than divided</b>
 * @param fullCaps    per machine, {memoryMb, diskMb}, at full size — what the instance
 *                    type actually offers, or what the file declared for it. The pair
 *                    travels because a percentage is the only reading of a cap anybody
 *                    wants, and taking one against {@link #caps} answers a question about
 *                    the probe. Same node, same order, same units as {@code caps}
 * @param notes       what the engine could not do, in words, so nothing is silently absent
 */
public record ScalePlan(long units, long fullUnits,
                        Map<String, double[]> caps, Map<String, double[]> fullCaps, Laws laws,
                        int gridRuns, List<String> notes, String infeasible) {

    /**
     * What shape a cached plan is in.
     *
     * <p>Part of the cache key, so a plan fitted by an older engine is a miss rather
     * than a plan missing half its answers. The class fingerprint in the key covers
     * the *simulation's* code and not dissaly's own, so without this an engine that
     * learned to fit something new would keep being handed plans that had never
     * heard of it — and would report their absence as a refusal, which is the one
     * kind of wrong answer this whole package is built to avoid.
     *
     * <p><b>Bump it whenever a plan's contents change, not only its keys.</b> What
     * is cached is the engine's answers, so a change in how a resource is fitted —
     * one that used to be refused and now is not — is as invalidating as a renamed
     * field, and it is the harder one to notice: nothing fails, the old plan simply
     * keeps being handed back and the fix appears not to work. That happened once
     * already, to the change that made 3 out of 2.
     *
     * <ul>
     *   <li><b>9</b> — the data ladder is warmed up before it is measured and
     *       climbed seed-major, so JVM warm-up no longer correlates with rung
     *       size and lands in a law's exponent.</li>
     *   <li><b>8</b> — the notes describe the figure the table reports, so a
     *       resource assembled from its machines is not explained by the cluster
     *       law it was assembled instead of.</li>
     *   <li><b>7</b> — a resource the workload barely moves says so as growth
     *       between the two sizes, not as the law's fixed/coefficient split.</li>
     *   <li><b>6</b> — a dominant fixed term is an assumption on that resource
     *       rather than the end of the run, and the host-heap estimate is summed
     *       from the per-machine laws.</li>
     *   <li><b>5</b> — what used to be refused is fitted under a stated assumption,
     *       carried in {@code assumed}.</li>
     *   <li><b>4</b> — cluster peaks and totals are assembled from the per-machine
     *       projections instead of being fitted to the pre-aggregated series.</li>
     *   <li><b>3</b> — a resource measured at zero on every rung is projected as
     *       zero rather than refused.</li>
     *   <li><b>2</b> — per-machine laws under {@code <machine>/<resource>}, and
     *       {@code fullCaps} beside the solved ones.</li>
     *   <li><b>1</b> — cluster laws, solved caps.</li>
     * </ul>
     */
    public static final int FORMAT = 9;

    public boolean feasible() { return infeasible == null; }

    public double scaleFactor() { return fullUnits / (double) Math.max(1, units); }

    /** Everything the engine will say about one resource, at both scales. */
    public record Projection(String resource, double observed, OptionalDouble projected,
                             double errorBar, String refusedBecause) {}

    public Projection projectionOf(String resource, double observed) {
        String why = laws.refused().get(resource);
        if (why != null)
            return new Projection(resource, observed, OptionalDouble.empty(), 0, why);
        var projected = laws.project(resource, fullUnits);
        return new Projection(resource, observed, projected,
                laws.errorBars().getOrDefault(resource, 1.0), null);
    }

    /** A description a person can read, and a trace can carry. */
    public Map<String, Object> asMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("units", units);
        m.put("fullUnits", fullUnits);
        m.put("factor", Math.round(scaleFactor()));
        m.put("gridRuns", gridRuns);
        var laws = new LinkedHashMap<String, Object>();
        this.laws.byResource().forEach((resource, law) -> {
            var l = new LinkedHashMap<String, Object>();
            l.put("variable", law.variable());
            l.put("fixed", law.fixed());
            l.put("coefficient", law.coefficient());
            l.put("beta", law.beta());
            l.put("r2", law.r2());
            l.put("wobble", law.wobble());
            l.put("errorBar", this.laws.errorBars().get(resource));
            double amp = this.laws.amplification().getOrDefault(resource, 1.0);
            if (amp > 1.001) l.put("faultAmplification", amp);
            laws.put(resource, l);
        });
        m.put("laws", laws);
        // The variable-of-units laws travel too, or a cached plan could not
        // project a resource that is a function of anything but units.
        var vars = new LinkedHashMap<String, Object>();
        this.laws.byVariable().forEach((name, law) -> {
            var l = new LinkedHashMap<String, Object>();
            l.put("fixed", law.fixed());
            l.put("coefficient", law.coefficient());
            l.put("beta", law.beta());
            l.put("r2", law.r2());
            l.put("wobble", law.wobble());
            vars.put(name, l);
        });
        if (!vars.isEmpty()) m.put("variables", vars);
        if (!this.laws.refused().isEmpty()) m.put("refused", new LinkedHashMap<>(this.laws.refused()));
        // What had to be assumed to produce a number. Travels with the law rather
        // than beside it, because a projection read without its condition is a
        // different claim from the one the engine made.
        if (!this.laws.assumed().isEmpty()) m.put("assumed", new LinkedHashMap<>(this.laws.assumed()));
        var capMap = new LinkedHashMap<String, Object>();
        caps.forEach((name, c) -> capMap.put(name, List.of(round(c[0]), round(c[1]))));
        m.put("caps", capMap);
        // What the machine would really have. Without it a reader has a demand
        // projected to full size and a capacity solved for the probe, and dividing
        // one by the other gives a percentage of nothing.
        var fullCapMap = new LinkedHashMap<String, Object>();
        fullCaps.forEach((name, c) -> fullCapMap.put(name, List.of(round(c[0]), round(c[1]))));
        m.put("fullCaps", fullCapMap);
        if (!notes.isEmpty()) m.put("notes", notes);
        if (infeasible != null) m.put("infeasible", infeasible);
        return m;
    }

    private static double round(double x) { return Math.round(x * 1000) / 1000.0; }

    /** The simulation the plan says to run: the chosen size and every solved cap. */
    public Simulation applyTo(Simulation s) {
        return s.withUnits(units).withCaps(caps);
    }
}
