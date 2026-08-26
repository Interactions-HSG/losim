import java.util.Map;

/**
 * t8-oom — a reducer that accumulates, against a machine too small for its bucket.
 *
 * <p><b>Catches:</b> the retained-heap walk regressing, which is the headline
 * feature. Allocation cannot tell an accumulating reducer from a streaming one —
 * two doing identical work churn within about 30% of each other — and only one of
 * them runs out of memory. Retention is what separates them.
 *
 * <p>Two runs: the same code and the same data, differing only in what the machine
 * has. If both completed, or neither did, the machine model is doing nothing.
 */
public final class T8 {
    public static void main(String[] args) {
        var e = Expect.of("t8-oom", args);
        var roomy = args.length > 1 ? Expect.of("", new String[]{args[1]}) : null;

        var ooms = e.of("oom");
        e.check(ooms.size() == 1, "the small machine ran out of memory, once (" + ooms.size() + ")");
        var oom = ooms.isEmpty() ? Map.<String, Object>of() : Expect.detail(ooms.get(0));

        double cap = Expect.num(oom.get("capMb")), demand = Expect.num(oom.get("demandMb"));
        e.note(String.format("r0 held %.2f MB against a %.0f MB cap, in %.0f objects",
                demand, cap, Expect.num(oom.get("objects"))));
        e.check(!ooms.isEmpty() && "r0".equals(ooms.get(0).get("vm"))
                && "memory".equals(oom.get("resource")) && demand > cap,
                "and the event names the machine, the resource, the cap and the demand — so "
                + "what to change is readable without opening anything else");

        e.check(Expect.num(oom.get("objects")) > 0,
                "the demand is measured, not declared: a count of the objects the walk "
                + "actually found. Nothing in this system says how big it is");

        // Observed, never projected. In direct mode there is nothing to project from,
        // and an OutOfMemory either happened or it did not (D7).
        e.check(!e.meta().containsKey("projections"),
                "and it is observed rather than projected — this run carries no projections "
                + "at all, and an out-of-memory is not the kind of thing to extrapolate");

        if (roomy != null) {
            boolean survived = roomy.of("oom").isEmpty()
                    && Boolean.TRUE.equals(roomy.meta().get("completed"));
            e.check(survived,
                    "the same code, the same data and a machine with room for it finishes "
                    + "without incident — so what failed was the size of the machine, not the "
                    + "program, which is the only way the first half of this case means anything");
        }
        e.done();
    }
}
