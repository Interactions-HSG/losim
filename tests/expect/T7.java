import java.util.*;

/**
 * t7-abuse — a machine killed mid-run, a standing rate of failure, and two retry
 * policies of which one must be refused.
 *
 * <p><b>Catches:</b> fault scheduling, the idempotency gate, and a coordinator that
 * only works when nothing goes wrong. The answer has to come out exact anyway —
 * "no word lost" is observed, not projected, and a bad afternoon is no excuse.
 */
public final class T7 {
    public static void main(String[] args) {
        var e = Expect.of("t7-abuse", args);

        var truth = WordCount.truth();
        var done = e.of("done");
        String answer = done.isEmpty() ? "" : String.valueOf(Expect.detail(done.get(0)).get("value"));
        boolean whole = truth.entrySet().stream()
                .allMatch(w -> answer.contains(w.getKey() + "=" + w.getValue()));
        e.check(whole && Boolean.TRUE.equals(e.meta().get("completed")),
                "the job finished with the exact answer, having lost a machine on the way — "
                + "which is the whole exercise, and is checked exactly rather than projected");

        var kills = e.of("kill");
        e.check(!kills.isEmpty(), "the scripted kill fired — "
                + kills.stream().map(k -> k.get("vm") + " at " + Math.round(Expect.num(k.get("t")))
                        + " refMs").toList());
        double at = kills.isEmpty() ? -1 : Expect.num(kills.get(0).get("t"));
        e.check(at > 240 && at < 400,
                String.format("and it fired at about the 300 refMs the scenario wrote, not "
                        + "wherever the host got round to it (%.0f refMs)", at));

        var chaos = e.of("chaos");
        e.check(!chaos.isEmpty(), "and the standing rate of failure drew too — " + chaos.size()
                + " events, whose spacing comes from the seed, so a sweep shows a distribution "
                + "rather than one lucky afternoon");

        e.check(e.of("restart").size() + e.of("boot").size() > 6,
                "the killed machine came back, which is a different exercise from one that "
                + "never does");

        // What was declared safe was retried, and the trace says how many times.
        var retried = e.of("retry");
        e.note(retried.isEmpty() ? "no retry was needed this seed"
                                 : retried.size() + " retries, backing off as declared");

        // And what was not declared safe never got as far as running.
        String refused = Expect.text(args.length > 1 ? args[1] : "");
        e.check(refused.contains("t7-unsafe.yaml:15:") && refused.contains("lab.Volley.Hit"),
                "the policy on a method the schema does not call safe was refused with the "
                + "line it was written on, before a single call was made — a duplicate write "
                + "discovered in a trace is a much worse way to find this out");
        e.check(refused.contains("idempotency_level") && refused.contains("unsafe: true"),
                "and the refusal says both ways out: declare it in the .proto if it is safe, "
                + "or say so here in as many words if you mean it anyway");
        e.done();
    }
}
