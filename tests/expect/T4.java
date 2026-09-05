import java.util.Set;
import java.util.stream.Collectors;

/**
 * t4-pingpong — fire-and-forget over gRPC, both directions.
 *
 * <p><b>Catches:</b> the claim that there is no second messaging path. If
 * fire-and-forget needed one, this case would need a mechanism that costs, faults
 * and byte counts did not apply to — and the trace would show it.
 */
public final class T4 {
    public static void main(String[] args) {
        var e = Expect.of("t4-pingpong", args);

        var calls = e.of("rpc_call");
        e.check(calls.size() == Rally.RALLIES * 2 + 2,
                (Rally.RALLIES * 2 + 2) + " calls in the trace — the rallies and two to warm "
                + "the path — each an ordinary unary call, because that is what it is ("
                + calls.size() + ")");

        Set<String> served = e.of("handler_end").stream()
                .map(x -> String.valueOf(x.get("vm"))).collect(Collectors.toSet());
        e.check(served.equals(Set.of("left", "right")),
                "both directions were served — " + served + " — so the topology has two "
                + "machines and two arrows, not one");

        // Each hit declares 200 refMs, which at k_time 10 is 20 ms of host time. Ten
        // of them awaited would be at least a hundred; dispatched, it is about one.
        var done = e.of("done");
        String how = done.isEmpty() ? "" : String.valueOf(Expect.detail(done.get(0)).get("value"));
        e.note(how);
        double returned = how.isEmpty() ? 999
                : Double.parseDouble(how.replaceAll(".*in ([0-9.]+) ms.*", "$1"));
        e.check(returned < 30.0,
                "and the caller was not blocked: ten calls declaring 200 refMs each were "
                + "dispatched in " + returned + " ms of host time, where awaiting even one "
                + "of them would have cost twenty");

        // Spans first, then the property. `noneMatch` over nothing is true, so a
        // run that recorded no spans would have proved that none of them dangled.
        e.check(!e.spans().isEmpty()
                        && e.spans().stream().noneMatch(s -> "OPEN".equals(s.get("status"))),
                "no span dangles — a dangling span is a telemetry bug rather than a finding, "
                + "and async calls are where one would first appear");

        var rallies = e.of("state").stream()
                .filter(x -> "rally".equals(Expect.detail(x).get("key"))).toList();
        e.check(rallies.size() == Rally.RALLIES * 2 + 2,
                "every rally reported itself from inside the handler, so the ambient context "
                + "reached a call nobody is waiting on");
        e.done();
    }
}
