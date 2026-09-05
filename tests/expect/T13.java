import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * t13-transparent — does how closely it is watched change what it says?
 *
 * <p><b>Catches:</b> the observer effect creeping back in. It regresses silently and
 * invisibly: every number stays plausible and only the projection is wrong. losim
 * runs on the machine's own threads, so opening a span, rendering an argument and
 * accounting a cost all allocate and all take wall clock on exactly the threads
 * whose allocation and duration are the measurement — and the thread counter cannot
 * tell losim's bytes from the program's.
 *
 * <p>What is asserted is the <b>fitted laws</b>, not the numbers. Recording costs
 * what it costs; the law is what ships, and the law has to be the same.
 *
 * <p>Four runs of one ladder: with telemetry off, with it on but recording no
 * payloads, with every argument and result rendered, and with a thousand
 * {@code reveal} calls in every handler. <b>The last is mandatory rather than
 * thorough</b> — at one reveal per handler a leak that halves an exponent is
 * undetectable.
 */
public final class T13 {

    public static void main(String[] args) {
        var e = Expect.of("t13-transparent", args);
        var runs = new LinkedHashMap<String, Expect>();
        String[] names = {"off", "no payloads", "full", "full, 1000 reveals"};
        for (int i = 0; i < names.length; i++)
            runs.put(names[i], i == 0 ? e : Expect.of("", new String[]{args[i]}));

        // Allocation is the resource the observer inflates, and it is fitted against
        // records at every level — so this is the one comparison that can be made
        // across all four runs at once.
        var alloc = new LinkedHashMap<String, Double>();
        runs.forEach((name, r) -> alloc.put(name, beta(r, "allocMb")));
        e.note("allocation exponent: " + alloc.entrySet().stream()
                .map(x -> String.format("%s %.4f", x.getKey(), x.getValue())).toList());
        // Each law's own seed-to-seed wobble, printed beside the spread it is being
        // compared against. Not asserted, and deliberately so — see the bound below,
        // which is what the wobble was once proposed to replace.
        //
        // The bound is 0.10, and it was measured rather than chosen. `tests/t13-null.sh`
        // runs groups of four at **one fixed telemetry level**, so a spread inside a
        // group cannot be telemetry; the distribution of those spreads is what this
        // statistic does when nothing is moving it. On a CI runner, 283 groups:
        //
        //     min 0.0030   median 0.0286   p90 0.0503   p99 0.0718   max 0.0768
        //
        // So **11% of groups already exceed 0.05 with telemetry held constant**. The
        // old bound was not measuring the observer effect on that hardware, it was
        // measuring the noise floor, and it failed accordingly — which is exactly
        // what it looked like from the outside and could not be told apart from a
        // real signal without this.
        //
        // 0.10 sits about 30% above the highest noise-only spread ever observed here.
        // It is not a weaker test than it looks: the regression this exists to catch
        // is a leak that halves a fitted exponent, which on an exponent near 0.84 is
        // a move of about 0.42 — four times the bound.
        //
        // The repair everyone reaches for first does not work, and it is worth
        // recording why. Comparing the spread against the exponent's own seed wobble
        // is self-calibrating and needs no constant — but the spread is a range over
        // four independent runs and the wobble is one run's own, so locally the
        // spread exceeds a single wobble one run in four. And CI wobbles measure
        // 0.032 to 0.036, the same as local, so the wobble does not grow on slow
        // hardware and cannot account for the difference at all.
        e.note("their own seed wobble: " + runs.entrySet().stream()
                .map(x -> String.format("%s %.4f", x.getKey(),
                        Expect.num(law(x.getValue(), "allocMb").get("wobble"))))
                .toList());
        double lo = alloc.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double hi = alloc.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        e.check(hi - lo < 0.10, String.format(
                "the allocation exponent moves by %.4f between telemetry off, telemetry on, "
                + "every payload rendered, and a thousand reveal calls per handler — the law is "
                + "the same law, and the law is what gets extrapolated. The bound is 0.10 because "
                + "283 groups of four runs at one fixed telemetry level put the noise floor at "
                + "0.077, so anything tighter was measuring the machine", hi - lo));

        double bare = beta(runs.get("no payloads"), "memoryMb");
        double watched = beta(runs.get("full"), "memoryMb");
        e.check(Math.abs(bare - watched) < 0.02, String.format(
                "and the memory exponent is %.4f without payloads against %.4f with them — "
                + "rendering an argument and a result is the most expensive thing losim does, "
                + "about three times everything else, and it lands on exactly the threads being "
                + "measured", bare, watched));

        // Proof that the exclusion is doing work rather than there being nothing to
        // exclude. If the ledger were empty the assertions above would be vacuous.
        double ledgerOff = runs.get("off").sum("losimMb");
        double ledgerFull = runs.get("full").sum("losimMb");
        double ledgerChatty = runs.get("full, 1000 reveals").sum("losimMb");
        long regionsFull = (long) runs.get("full").sum("losimStops");
        long regionsChatty = (long) runs.get("full, 1000 reveals").sum("losimStops");
        e.note(String.format("losim charged itself %.2f MB off, %.2f MB watched, %.2f MB chatty; "
                + "%,d metered regions against %,d", ledgerOff, ledgerFull, ledgerChatty,
                regionsFull, regionsChatty));
        e.check(ledgerFull > ledgerOff * 1.5 && regionsChatty > regionsFull * 5,
                "and the exclusion had real work to do: watching costs materially more than not "
                + "watching, and the chatty run meters many times more regions than the quiet "
                + "one — so the laws agreeing above is subtraction working, not nothing having "
                + "gone wrong");

        // Metered, not modelled. Nothing assumes a call count or a per-call constant,
        // which is why a program that leans on losim heavily is simply excluded more.
        double reportedFull = runs.get("full").sum("allocMb");
        double reportedChatty = runs.get("full, 1000 reveals").sum("allocMb");
        double rawFull = reportedFull + ledgerFull;
        double rawChatty = reportedChatty + ledgerChatty;
        e.note(String.format("the fleet reports %.1f MB quiet and %.1f MB chatty; before "
                + "subtraction it was %.1f and %.1f", reportedFull, reportedChatty, rawFull, rawChatty));
        e.check(rawChatty - rawFull > ledgerFull,
                "the raw figure moves by more than the whole of the quiet run's overhead when "
                + "the instrumentation goes up a thousandfold — so there is plainly something "
                + "there to subtract, and the trace carries the ledger rather than asking to "
                + "be believed");

        // What turning telemetry off actually costs, which is not only trace size.
        e.check("records".equals(variable(runs.get("off"), "memoryMb"))
                && "revealed.distinctKeys".equals(variable(runs.get("full"), "memoryMb")),
                "and with telemetry off the engine can only fit memory against records, because "
                + "nothing revealed anything — turning it off does not merely make the trace "
                + "smaller, it removes the evidence resources are attributed with");
        e.done();
    }

    static Map<String, Object> law(Expect r, String resource) {
        return T10.sub(T10.sub(T10.sub(r.meta(), "scale"), "laws"), resource);
    }

    static double beta(Expect r, String resource) { return Expect.num(law(r, resource).get("beta")); }

    static String variable(Expect r, String resource) {
        return String.valueOf(law(r, resource).get("variable"));
    }
}
