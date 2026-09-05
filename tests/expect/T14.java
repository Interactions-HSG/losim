import java.util.List;
import java.util.Map;

/**
 * t14-late-deadline — a deadline the caller could not have known was too short.
 *
 * <p><b>Catches:</b> the per-record half of a declared cost going unchecked against
 * the deadline it will blow. The client side can only compare a deadline with the
 * <i>fixed</i> part of a {@code @Takes}, because {@code refNsPerRecord} needs a
 * count and the count is the handler's to declare while it runs. So the mistake
 * people actually make — a constant deadline written once for a small workload and
 * left there — times out with every number looking reasonable.
 *
 * <p>The callee has both by the time it answers, and gRPC hands it the caller's
 * deadline, so it records what it declared against what it was allowed.
 *
 * <p><b>Sound in one direction only, and that is the useful one.</b> A declared cost
 * is slept and never subtracted — {@code @Takes} can make a run longer and never
 * shorter — so it is a lower bound on what the handler took, and a declared cost
 * above the deadline is impossible rather than unlikely. The converse says nothing:
 * a declared cost under the deadline leaves the handler's own work still to come.
 * That asymmetry is why both directions are checked here.
 */
public final class T14 {

    public static void main(String[] args) {
        var e = Expect.of("t14-late-deadline", args);

        var handlers = e.spansOf("handler").stream()
                .filter(s -> Expect.detail(s).get("declaredRefMs") != null).toList();
        e.check(handlers.size() == 2, "both calls recorded what they declared against what "
                + "they were allowed — " + handlers.size() + " of them");

        // 2 refMs fixed plus 40000 at 0.02 refMs. Neither deadline is under the
        // fixed part, so nothing the caller could inspect would have shown this.
        double declared = handlers.isEmpty() ? -1 : Expect.num(Expect.detail(handlers.get(0)).get("declaredRefMs"));
        e.note(String.format("the handler declared %.0f refMs: 2 fixed, and 40,000 records at 0.02", declared));
        e.check(declared > 790 && declared < 815,
                "the declared cost is the fixed part plus the per-record part, counted — "
                + "not the 2 refMs the annotation shows before a call is made");

        var impossible = handlers.stream().filter(s -> Boolean.TRUE.equals(Expect.detail(s).get("unmeetable"))).toList();
        var met = handlers.stream().filter(s -> Expect.detail(s).get("unmeetable") == null).toList();

        e.check(impossible.size() == 1 && met.size() == 1,
                "exactly one of the two is called impossible — a check that fires on both "
                + "would be saying nothing, and one that fires on neither would be absent");

        for (var s : impossible) {
            e.note(String.format("refused: %.0f refMs declared against %.0f allowed",
                    Expect.num(Expect.detail(s).get("declaredRefMs")),
                    Expect.num(Expect.detail(s).get("deadlineRefMs"))));
            e.check(Expect.num(Expect.detail(s).get("declaredRefMs"))
                            > Expect.num(Expect.detail(s).get("deadlineRefMs")),
                    "and it is the one whose declared cost exceeds its deadline");
        }
        for (var s : met) {
            e.note(String.format("allowed: %.0f refMs declared against %.0f allowed",
                    Expect.num(Expect.detail(s).get("declaredRefMs")),
                    Expect.num(Expect.detail(s).get("deadlineRefMs"))));
            e.check(Expect.num(Expect.detail(s).get("declaredRefMs"))
                            <= Expect.num(Expect.detail(s).get("deadlineRefMs")),
                    "while the roomy one is left alone, though it declares exactly as much — "
                    + "what differs is the deadline, which is the claim");
        }

        // The trace carrying it is not enough: this exists to be read by somebody
        // who did not think to look.
        String said = Expect.text(args[1]);
        e.check(said.contains("the deadline was") && said.contains("once its records are counted"),
                "and the run says so on its own summary, where a person who never opens the "
                + "trace will see it");
        e.done();
    }
}
