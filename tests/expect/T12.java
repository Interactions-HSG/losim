import java.util.List;
import java.util.Map;

/**
 * t12-refusal — the two ways an extrapolation is not available, and saying so.
 *
 * <p><b>Catches:</b> extrapolating past a discontinuity, and anyone later
 * "simplifying" the check back to R². This case exists as much for the second as
 * for the first: the split-ladder test looks fussy beside a familiar goodness-of-fit
 * number, and the whole point is that the familiar number does not work here.
 *
 * <p>Two workloads. One spills to disk above a key count, so its memory climbs and
 * then flattens — the ladder bends. The other writes a fixed index that dwarfs
 * everything the probe scale varies, so there is no feasible size at all and the run
 * does not happen.
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

        // The other refusal: no feasible size, so nothing ran and nothing was written.
        String said = Expect.text(args[1]);
        e.check(said.contains("no feasible size") && said.contains("diskMb"),
                "and where a probe would be too small to have measured the workload at all, the "
                + "engine names the resource and stops: nothing is run, so there is no trace "
                + "to mistake for a result");
        for (String line : said.lines().toList())
            if (line.contains("fixed overhead")) e.note(line.trim());
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
