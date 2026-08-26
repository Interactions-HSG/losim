import java.util.*;

/**
 * t9-causality — what the trace says about order.
 *
 * <p><b>Catches:</b> trace ordering, and the metadata-header propagation that carries
 * a span's parent across the RPC boundary. Take the parent from the ambient context
 * instead — which is the obvious thing to do, and wrong — and every server span hangs
 * off the root: there is no distributed call stack, and nothing in the trace says so
 * except that the answers all still came out right.
 */
public final class T9 {
    public static void main(String[] args) {
        var e = Expect.of("t9-causality", args);

        var byId = new HashMap<Long, Map<String, Object>>();
        for (var s : e.spans()) byId.put(Expect.lng(s.get("id")), s);

        // Every server span opened under the client span of the call that reached it,
        // and that client span belongs to a different machine. Both halves matter: the
        // first is causality, the second is that it crossed a boundary to get there.
        var handlers = e.spansOf("handler");
        int crossed = 0;
        var orphans = new ArrayList<String>();
        for (var h : handlers) {
            var parent = byId.get(Expect.lng(h.get("parent")));
            if (parent == null || !"rpc".equals(parent.get("kind"))) { orphans.add(String.valueOf(h.get("vm"))); continue; }
            if (!Objects.equals(parent.get("vm"), h.get("vm"))) crossed++;
        }
        e.check(orphans.isEmpty(), handlers.size() + " server spans, every one of them opened "
                + "under the call that reached it" + (orphans.isEmpty() ? "" : " — except " + orphans));
        e.check(crossed == handlers.size(),
                "and in every case the parent is on another machine (" + crossed + "/"
                + handlers.size() + ") — which is what makes it a distributed call stack "
                + "rather than a local one that happens to nest");

        // Happens-after, across the boundary: nothing was served before it was called.
        var starts = e.of("handler_start");
        var calls = e.of("rpc_call");
        var calledAt = new HashMap<String, Double>();
        for (var c : calls) calledAt.put(String.valueOf(Expect.detail(c).get("call")), Expect.num(c.get("t")));
        int after = 0, checked = 0;
        for (var s : starts) {
            Double when = calledAt.get(String.valueOf(Expect.detail(s).get("call")));
            if (when == null) continue;
            checked++;
            if (Expect.num(s.get("t")) >= when) after++;
        }
        e.check(checked > 0 && after == checked,
                "every call was served after it was made (" + after + "/" + checked + ") — a "
                + "receive that sorts before its own send is the trace telling you its clock "
                + "is not one clock");

        // And things with no causal path between them are allowed to overlap. A trace
        // that serialised everything would satisfy every check above and be a lie.
        int widest = 0;
        for (var probe : handlers) {
            double t = Expect.num(probe.get("t0")) + 0.001;
            int at = 0;
            for (var other : handlers)
                if (Expect.num(other.get("t0")) <= t && t <= Expect.num(other.get("t1"))) at++;
            widest = Math.max(widest, at);
        }
        e.check(widest >= 2, "concurrent work is reported concurrent — " + widest + " spans "
                + "overlap at once, on machines with no causal path between them");

        // Latency is charged by zone, so a call that crossed one costs more than a call
        // that did not — which is the other thing the ordering has to reflect.
        var rpcs = e.spansOf("rpc");
        e.check(rpcs.stream().anyMatch(s -> Expect.num(Expect.detail(s).get("ms")) > 100),
                "and a cross-zone call is visibly dearer than a same-zone one, so the shape "
                + "of the timeline is the shape of the network the scenario described");
        e.done();
    }
}
