import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * t10-groundtruth — project a big job from a small one, then run the big one and look.
 *
 * <p><b>Catches:</b> the scaler engine silently degrading. This is the core
 * contribution's only real test, and the only one that cannot be passed by an
 * engine that has quietly stopped working: every other case checks that a
 * projection is <i>shaped</i> right, and a projection can be perfectly well shaped
 * and completely wrong.
 *
 * <p>Two runs. One in scaled mode, which climbs a ladder of four small runs and
 * projects to forty-eight thousand records. One in direct mode at forty-eight
 * thousand records, where nothing is scaled and nothing inferred and every number is
 * what happened. Then they are put beside each other.
 *
 * <p>The comparison is against <b>the obvious alternative</b> as well as against the
 * truth, because "within 25%" on its own proves nothing: multiplying the small run
 * by the size ratio is what anyone would reach for without an engine, and if that
 * does just as well then the engine is an elaborate way of doing nothing.
 */
public final class T10 {

    public static void main(String[] args) {
        var e = Expect.of("t10-groundtruth", args);
        var truth = Expect.of("", new String[]{args[1]});

        var scale = sub(e.meta(), "scale");
        double factor = Expect.num(scale.get("factor"));
        var projected = new LinkedHashMap<String, Map<String, Object>>();
        for (var p : projections(e)) projected.put(String.valueOf(p.get("resource")), p);

        e.check(factor >= 4 && !projected.isEmpty(), String.format(
                "the engine ran %s records to say what %s would do — a factor of %.0f, from a"
                + " ladder it actually climbed rather than one it assumed",
                scale.get("records"), scale.get("fullRecords"), factor));

        // What actually happened, computed off the other trace the way the engine
        // computes it: memory and disk are the worst machine's, because a fleet does
        // not run out on average; wire and allocation are the fleet's total.
        var actual = new LinkedHashMap<String, Double>();
        actual.put("memoryMb", truth.peak("memoryMb"));
        actual.put("diskMb",   truth.peak("diskMb"));
        actual.put("wireMb",   truth.sum("wireMb"));
        actual.put("allocMb",  truth.sum("allocMb"));

        System.out.printf("    %-10s %10s %10s %8s   %10s %8s %8s%n",
                "", "actual", "engine", "err", "uniform", "err", "bar");
        int compared = 0, tooFar = 0, worseThanUniform = 0;
        var error = new LinkedHashMap<String, Double>();
        for (var entry : actual.entrySet()) {
            var p = projected.get(entry.getKey());
            if (p == null || !p.containsKey("projected")) continue;
            double was = entry.getValue();
            double engine = Expect.num(p.get("projected"));
            double uniform = Expect.num(p.get("observed")) * factor;
            double bar = Expect.num(p.get("errorBar"));
            double eErr = Math.abs(engine - was) / was * 100;
            double uErr = Math.abs(uniform - was) / was * 100;
            error.put(entry.getKey(), eErr);
            System.out.printf("    %-10s %10.3f %10.3f %7.1f%%   %10.3f %7.1f%%  x%.2f%n",
                    entry.getKey(), was, engine, eErr, uniform, uErr, bar);
            compared++;
            if (eErr > 25) tooFar++;
            // At least as close everywhere. A resource that really is linear in
            // records is one the uniform factor gets right too, and the engine is not
            // required to beat a thing that happens to be correct — only never to be
            // worse than it.
            if (eErr > uErr + 1) worseThanUniform++;
        }

        e.check(compared >= 3 && tooFar == 0, compared + " resources were projected from the"
                + " small run, and every one of them lands within 25% of what the big run"
                + " actually did");
        e.check(compared > 0 && worseThanUniform == 0,
                "and on none of them is it worse than multiplying the small run by the size"
                + " ratio, which is what anyone would do without an engine");

        // The claim that separates the two. Disk really is linear in records, so the
        // uniform factor gets it right and deserves to; memory is not, and that is
        // where an engine either earns its place or does not.
        Double memErr = error.get("memoryMb");
        double memUniform = uniformError(projected.get("memoryMb"), factor, actual.get("memoryMb"));
        e.check(memErr != null && memErr < memUniform / 2, String.format(
                "and on memory — the resource that is not linear in records — it is %.1f%% out"
                + " where the uniform factor is %.0f%% out. Vocabulary saturates: a reducer at"
                + " a sixth of the scale sees far more than a sixth of the distinct keys, and"
                + " an engine that cannot see that under-shrinks every machine it sizes",
                memErr == null ? 0 : memErr, memUniform));

        var memoryLaw = sub(sub(scale, "laws"), "memoryMb");
        e.check("revealed.distinctKeys".equals(memoryLaw.get("variable")),
                "which it can only do because memory was attributed to distinct keys rather"
                + " than to records — the program said what its cost depends on, in the one"
                + " place that knows, and the engine fitted against that");

        // D7: a projection carries its confidence or it is absent. Never a plausible
        // number in a column that could not be filled.
        var time = projected.get("makespanRefMs");
        e.check(time != null && !time.containsKey("projected") && time.containsKey("refused"),
                "the timeline carries no projection at all — wall clock is the noisiest thing"
                + " measured, and a duration that could not be reproduced is absent with a"
                + " reason rather than printed beside a byte count as though the two were"
                + " equally trustworthy");
        if (time != null && time.containsKey("refused")) e.note("" + time.get("refused"));

        // The plan travels, so projected = f(observed) is something a reader can
        // redo rather than take on trust.
        double keys = at(sub(sub(scale, "variables"), "revealed.distinctKeys"),
                         Expect.num(scale.get("fullRecords")));
        double redone = at(memoryLaw, keys);
        double reported = Expect.num(projected.get("memoryMb").get("projected"));
        e.check(Math.abs(redone - reported) < 0.01 * Math.max(1, reported), String.format(
                "and the plan travels in the trace: recomputing the memory projection from the"
                + " two laws it carries — keys from records, then memory from keys — gives"
                + " %.3f against the %.3f it reported", redone, reported));
        e.done();
    }

    private static double uniformError(Map<String, Object> p, double factor, double was) {
        if (p == null) return 0;
        return Math.abs(Expect.num(p.get("observed")) * factor - was) / was * 100;
    }

    /** {@code fixed + coefficient * n^beta}, exactly as the engine states it. */
    private static double at(Map<String, Object> law, double n) {
        return Expect.num(law.get("fixed"))
                + Expect.num(law.get("coefficient")) * Math.pow(n, Expect.num(law.get("beta")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.getOrDefault(key, Map.of());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> projections(Expect e) {
        return (List<Map<String, Object>>) e.meta().getOrDefault("projections", List.of());
    }
}
