/**
 * t3-deadline — 500 refMs of work, 200 refMs of patience.
 *
 * <p><b>Catches:</b> a declared duration that is never applied, or applied after
 * the response; and a deadline that is not divided by k_time, which would make
 * every timing lesson depend on how fast the laptop is.
 */
public final class T3 {
    public static void main(String[] args) {
        var e = Expect.of("t3-deadline", args);

        var done = e.of("done");
        String answer = done.isEmpty() ? "" : String.valueOf(Expect.detail(done.get(0)).get("value"));
        e.check(answer.contains("DEADLINE_EXCEEDED"),
                "the call failed with DEADLINE_EXCEEDED rather than answering late — " + answer);

        var timeouts = e.of("rpc_timeout");
        e.check(!timeouts.isEmpty(),
                "and the trace says so as an rpc_timeout, so the caller's own story — it "
                + "waited and nothing came — is readable without knowing what the callee did");

        // Both sides divided by the same k_time. If only one were, this case would
        // pass or fail according to the host, which is the one thing it must not do.
        var timedOut = e.spansOf("rpc").stream()
                .filter(s -> "DEADLINE_EXCEEDED".equals(s.get("status"))).toList();
        var last = timedOut.isEmpty() ? null : timedOut.get(timedOut.size() - 1);
        double waited = last == null ? -1 : Expect.num(last.get("t1")) - Expect.num(last.get("t0"));
        e.note(String.format("waited %.0f refMs of simulated time for a 200 refMs deadline", waited));
        e.check(waited > 100 && waited < 600,
                "it waited about the 200 refMs it declared, in simulated time — so the "
                + "deadline was rescaled by k_time before gRPC saw it, exactly as the "
                + "handler's declared duration was. Were only one of the two rescaled, "
                + "this lesson would hold or break according to the host");

        var handler = e.spansOf("handler");
        e.check(!handler.isEmpty() && handler.stream().noneMatch(s -> "OK".equals(s.get("status"))),
                "and the server never finished: it was cut off mid-work, which is what a "
                + "deadline shorter than a declared duration actually means — "
                + handler.stream().map(s -> String.valueOf(s.get("status"))).toList());

        e.check(e.spans().stream().noneMatch(s -> "OPEN".equals(s.get("status"))),
                "no span is left open, although the run ended with a handler still in "
                + "flight — a dangling span has to mean the recorder lost track, or it "
                + "means nothing");

        e.check(Boolean.TRUE.equals(e.meta().get("tightMargin")),
                "and the trace carries the scenario's own warning that this case turns on a "
                + "margin — a run that flips between seeds should look declared, not broken");
        e.done();
    }
}
