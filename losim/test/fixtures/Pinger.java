package fixtures;

import losim.api.*;

public final class Pinger implements Program {
    private int seen;

    @Override public void main(Ctx ctx) {
        if (ctx.isOrigin()) ctx.send(ctx.next(), new Ping(0));
    }

    @OnMessage
    public void onPing(Ctx ctx, VmRef from, Ping p) {
        seen++;
        ctx.reveal("seen", seen);
        if (p.hop() >= 8) { ctx.done("hops=" + p.hop()); return; }
        ctx.compute(2);
        ctx.send(ctx.next(), new Ping(p.hop() + 1));
    }
}
