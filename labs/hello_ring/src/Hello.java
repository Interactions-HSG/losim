import losim.api.*;

/**
 * Lab 1 — once around the world.
 *
 * One program, running on every VM in the ring. A token goes hop by hop and
 * must come home.
 */
public final class Hello implements Program {

    @Override
    public void main(Ctx ctx) {
        if (ctx.isOrigin()) {                       // only the first VM starts it
            ctx.send(ctx.next(), new Token("hello", 0));
        }
    }

    @OnMessage
    public void pass(Ctx ctx, VmRef sender, Token msg) {
        int hops = msg.hops() + 1;
        ctx.reveal("hops", hops);
        if (hops == ctx.fleetSize()) {
            ctx.done(msg.text() + " came home after " + hops + " hops");
        } else {
            ctx.send(ctx.next(), new Token(msg.text(), hops));
        }
    }
}
