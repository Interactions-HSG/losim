import losim.api.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Lab 3 — vector clocks.
 *
 * Every VM keeps one counter per VM. A local event bumps its own entry; a
 * received message merges the sender's view before bumping. Get the merge
 * wrong and the clock will claim two events are concurrent when one caused
 * the other — which the grader checks against the real causal graph.
 */
public final class Clocks implements Program, ClocksService {

    private final List<Integer> clock = new ArrayList<>();
    private int index = -1;
    private int sent;

    private void ensureSized(Ctx ctx) {
        if (index >= 0) return;
        List<VmRef> fleet = ctx.fleet();
        for (int i = 0; i < fleet.size(); i++) {
            clock.add(0);
            if (fleet.get(i).name().equals(ctx.name())) index = i;
        }
    }

    /** A local event: only my own entry moves. */
    private void localEvent(Ctx ctx) {
        ensureSized(ctx);
        clock.set(index, clock.get(index) + 1);
        ctx.reveal("clock", List.copyOf(clock));
    }

    @Override
    public void main(Ctx ctx) {
        ensureSized(ctx);
        for (int round = 0; round < 2; round++) {
            localEvent(ctx);
            ClocksPeer next = nextPeer(ctx);
            if (next == null) return;
            sent++;
            next.tell(new Stamped(List.copyOf(clock), ctx.name() + ":m" + sent, sent));
            ctx.sleep(40);
        }
    }

    @Override @Cost(ms = 1)
    public Stamped tell(Ctx ctx, Stamped request) {
        ensureSized(ctx);
        // merge the sender's view, then record that receiving is itself an event
        for (int i = 0; i < clock.size() && i < request.clock().size(); i++)
            clock.set(i, Math.max(clock.get(i), request.clock().get(i)));
        clock.set(index, clock.get(index) + 1);
        ctx.reveal("clock", List.copyOf(clock));
        return new Stamped(List.copyOf(clock), "ack", request.seq());
    }

    private ClocksPeer nextPeer(Ctx ctx) {
        List<ClocksPeer> peers = ctx.peers(ClocksPeer.class);
        for (int i = 0; i < peers.size(); i++)
            if (peers.get(i).name().equals(ctx.name()))
                return peers.get((i + 1) % peers.size());
        return peers.isEmpty() ? null : peers.get(0);
    }
}
