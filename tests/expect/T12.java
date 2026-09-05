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
        var refused = (Map<String, Object>) scale.getOrDefault("refused", Map.of());

        String why = String.valueOf(refused.get("memoryMb"));
        e.check(refused.containsKey("memoryMb") && why.contains("bends"),
                "the engine refuses the memory law rather than extrapolating across the spill");
        e.note(why);

        // The claim this case exists to make. Read the two exponents out of the
        // engine's own words rather than recomputing them, because what is being
        // checked is that the engine says this — not that it is sayable.
        double lower = number(why, "lower half it grows as records^");
        double upper = number(why, "upper half as records^");
        e.check(Math.abs(lower - upper) > 0.25, String.format(
                "and it catches it by splitting the ladder: the lower half fits records^%.2f "
                + "and the upper half records^%.2f, which is unambiguous and interpretable — "
                + "something in this program behaves differently large than small", lower, upper));

        double r2 = number(why, "R2 over the whole ladder is still ");
        e.check(r2 > 0.80, String.format(
                "while R2 over the whole ladder is still %.3f — a score a merely noisy straight "
                + "line reaches just as easily. No threshold on R2 separates bent from noisy, "
                + "which is why the check is not allowed to be simplified back to one", r2));

        // The refusal has to be *present*, not merely un-contradicted.
        //
        // This was `allMatch` over the memoryMb projections, and allMatch on an
        // empty stream is true — so an engine that emitted no memoryMb entry at
        // all would have passed a check whose own sentence demands a field
        // "absent with a reason". Absent without one is the failure it is meant
        // to catch, and it was the one shape that could not fail it.
        var memory = T10.sub(T10.sub(scale, "laws"), "memoryMb");
        var memoryProjections = T10.projections(e).stream()
                .filter(p -> "memoryMb".equals(p.get("resource")))
                .toList();
        boolean absent = memory.isEmpty()
                && !memoryProjections.isEmpty()
                && memoryProjections.stream()
                     .allMatch(p -> !p.containsKey("projected") && p.containsKey("refused"));
        e.check(absent,
                "and no projection is emitted for it at all — a field absent with a reason "
                + "the trace states, never one filled in with a plausible number and never "
                + "one simply missing");

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
