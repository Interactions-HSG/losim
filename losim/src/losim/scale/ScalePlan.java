package losim.scale;

import java.util.*;
import losim.scenario.Scenario;

/**
 * How the world was shrunk, and how to unshrink an answer.
 *
 * <p>The plan travels in the trace, so {@code projected = f(observed)} is
 * recomputable by anyone reading it later rather than being a number they have to
 * take on trust.
 *
 * @param records     what the run actually processed
 * @param fullRecords what it is a scale model of
 * @param caps        per machine, {memoryMb, diskMb}, <b>solved rather than divided</b>
 * @param notes       what the engine could not do, in words, so nothing is silently absent
 */
public record ScalePlan(long records, long fullRecords, double kTime,
                        Map<String, double[]> caps, Laws laws,
                        int gridRuns, List<String> notes, String infeasible) {

    public boolean feasible() { return infeasible == null; }

    public double scaleFactor() { return fullRecords / (double) Math.max(1, records); }

    /** Everything the engine will say about one resource, at both scales. */
    public record Projection(String resource, double observed, OptionalDouble projected,
                             double errorBar, String refusedBecause) {}

    public Projection projectionOf(String resource, double observed) {
        String why = laws.refused().get(resource);
        if (why != null)
            return new Projection(resource, observed, OptionalDouble.empty(), 0, why);
        var projected = laws.project(resource, fullRecords);
        return new Projection(resource, observed, projected,
                laws.errorBars().getOrDefault(resource, 1.0), null);
    }

    /** A description a person can read, and a trace can carry. */
    public Map<String, Object> asMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("records", records);
        m.put("fullRecords", fullRecords);
        m.put("factor", Math.round(scaleFactor()));
        m.put("kTime", kTime);
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
        // The variable-of-records laws travel too, or a cached plan could not
        // project a resource that is a function of anything but records.
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
        var capMap = new LinkedHashMap<String, Object>();
        caps.forEach((name, c) -> capMap.put(name, List.of(round(c[0]), round(c[1]))));
        m.put("caps", capMap);
        if (!notes.isEmpty()) m.put("notes", notes);
        if (infeasible != null) m.put("infeasible", infeasible);
        return m;
    }

    private static double round(double x) { return Math.round(x * 1000) / 1000.0; }

    /** The scenario the plan says to run: the chosen size, k_time and every solved cap. */
    public Scenario applyTo(Scenario s) {
        return s.withRecords(records).withKTime(kTime).withCaps(caps);
    }
}
