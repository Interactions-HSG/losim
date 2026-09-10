import java.util.List;
import java.util.Map;

/**
 * t12-refusal — the two ways a ladder does not straightforwardly extrapolate, and
 * what the engine says about each.
 *
 * <p><b>Catches:</b> extrapolating past a discontinuity, and anyone later
 * "simplifying" the check back to R². This case exists as much for the second as
 * for the first: the split-ladder test looks fussy beside a familiar goodness-of-fit
 * number, and the whole point is that the familiar number does not work here.
 *
 * <p>Two workloads, and neither is refused any more. One spills to disk above a key
 * count, so its memory climbs and then flattens — the ladder bends, and the engine
 * fits the upper regime and says which half it dropped. The other writes a fixed
 * index that dwarfs everything the probe scale varies, so its projection barely
 * moves, and the engine says how far it travels rather than declining to travel.
 *
 * <p>Both used to be absences. An absence tells a reader nothing and gives them
 * nothing to disagree with; a number with its condition attached tells them what was
 * measured, what was supposed, and where to push back.
 */
public final class T12 {

    public static void main(String[] args) {
        var e = Expect.of("t12-refusal", args);
        var scale = T10.sub(e.meta(), "scale");
        @SuppressWarnings("unchecked")
        var assumed = (Map<String, Object>) scale.getOrDefault("assumed", Map.of());

        String why = String.valueOf(assumed.get("memoryMb"));
        e.check(assumed.containsKey("memoryMb") && why.contains("bends"),
                "the engine sees the bend and says what it did about it, rather than either "
                + "extrapolating across the spill in silence or declining to answer at all");
        e.note(why);

        // The claim this case exists to make. Read the two exponents out of the
        // engine's own words rather than recomputing them, because what is being
        // checked is that the engine says this — not that it is sayable.
        double lower = number(why, "lower half it grows as units^");
        double upper = number(why, "upper half as units^");
        e.check(Math.abs(lower - upper) > 0.25, String.format(
                "and it catches it by splitting the ladder: the lower half fits units^%.2f "
                + "and the upper half units^%.2f, which is unambiguous and interpretable — "
                + "something in this program behaves differently large than small", lower, upper));

        double r2 = number(why, "R2 over the whole ladder is still ");
        e.check(r2 > 0.80, String.format(
                "while R2 over the whole ladder is still %.3f — a score a merely noisy straight "
                + "line reaches just as easily. No threshold on R2 separates bent from noisy, "
                + "which is why the check is not allowed to be simplified back to one", r2));

        // The number has to carry its condition.
        //
        // This was the inverse assertion — that no projection was emitted at all —
        // and it was the right check for an engine that refused. It is the wrong one
        // for an engine that answers, and the failure it now has to catch is the
        // opposite: a bent ladder fitted from its upper half and reported as though
        // nothing had been assumed. A number without its condition is a different
        // claim from the one the engine made.
        var memoryProjection = T10.projections(e).stream()
                .filter(p -> "memoryMb".equals(p.get("resource")))
                .findFirst().orElse(null);
        e.check(memoryProjection != null
                        && memoryProjection.containsKey("projected")
                        && memoryProjection.containsKey("errorBar")
                        && memoryProjection.containsKey("assumed"),
                "and the projection it does emit carries the assumption with it — a value, the "
                + "band around it, and the condition under which it was produced, so that a "
                + "reader who disagrees with the condition can see they are entitled to");

        // And the assumption names the half it dropped. Fitting the upper regime is
        // the right move when the projection climbs away from the small end, but a
        // reader whose interest is the small end has to be able to see that theirs
        // is the half that was thrown away.
        e.check(why.contains("lower half") && why.contains("upper regime"),
                "and it says which half of the ladder it kept and which it discarded, because "
                + "a projection that climbs away from the small end is entitled to drop the "
                + "small end only if it admits to having done so");

        // Other resources on the same run are unaffected. Refusing a law is not
        // refusing the run: what could be said is still said.
        long stillProjected = T10.projections(e).stream().filter(p -> p.containsKey("projected")).count();
        e.check(stillProjected >= 2, stillProjected + " other resources are still projected from "
                + "the same run — one resource the engine cannot fit does not make the ones it "
                + "can fit unsayable");

        // The other half of the pair: a design whose cost is mostly a constant.
        //
        // This used to assert that the engine stopped — no feasible size, no run, no
        // trace. It no longer stops, and it should not have: `fixed + c*n^beta` is a
        // perfectly good law with a large constant in it, and refusing the whole run
        // over one resource threw away every other resource that fitted fine. What
        // the case is named for is overhead, and overhead is what it still tests —
        // only now the engine answers instead of declining, which is a better
        // demonstration of the same phenomenon.
        String said = Expect.text(args[1]);
        e.check(!said.contains("no feasible size"),
                "a constant that dwarfs what the ladder varied is not a reason to abandon the "
                + "run: sixty-four megabytes of index per worker is the headline about this "
                + "design, and every other resource in it is still measurable");

        String moves = said.lines().filter(l -> l.contains("barely moves it"))
                .findFirst().orElse("");
        e.check(!moves.isEmpty(),
                "and the engine says so in the terms that survive however the fit chose to "
                + "decompose itself — how far the answer travels between the two sizes, not "
                + "the split between a fixed term and a coefficient, which on a flat "
                + "measurement is an artefact and not a fact");
        e.note(moves.trim());

        // The claim worth pinning down, and the one a refusal could never have made:
        // six times the workload is not six times the disk when most of the disk is
        // an index that gets written once.
        double growth = number(moves, "a change of ");
        e.check(Math.abs(growth) < 5.0, String.format(
                "six times the work moves it by %.2f%% — because the workload is not what it "
                + "is a cost of. A projection that multiplied the observed figure by the scale "
                + "factor would have been out by a factor of six, and would have looked "
                + "perfectly reasonable doing it", growth));

        e.done();
    }

    /** The number the engine printed after a phrase, so the assertion reads its words. */
    static double number(String text, String after) {
        int at = text.indexOf(after);
        if (at < 0) return Double.NaN;
        var digits = new StringBuilder();
        for (int i = at + after.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || c == '.' || (c == '-' && digits.isEmpty())) digits.append(c);
            else break;
        }
        try { return Double.parseDouble(digits.toString()); }
        catch (NumberFormatException x) { return Double.NaN; }
    }
}
