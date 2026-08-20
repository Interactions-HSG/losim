import losim.api.*;

/**
 * Lab 1 — once around the world.
 *
 * One program, running on every VM in the ring. A token goes hop by hop and
 * must come home.
 */
public final class Hello implements Program, Drawable {

    private int seen = 0;                           // what this machine remembers

    /**
     * How this machine looks while it runs.
     *
     * The framework asks again whenever the machine has just done something, so
     * the card in the video and in the browser is rewritten rather than piled
     * up — the number visibly moves. Delete this method and you get the default
     * appearance; nothing else changes.
     */
    @Override
    public Object visual() {
        return seen == 0 ? "waiting for the token" : "token passed " + seen + "×";
    }

    @Override
    public void main(Ctx ctx) {
        if (ctx.isOrigin()) {                       // only the first VM starts it
            ctx.send(ctx.next(), new Token("hello", 0));
        }
    }

    @OnMessage
    public void pass(Ctx ctx, VmRef sender, Token msg) {
        int hops = msg.hops() + 1;
        seen++;
        ctx.reveal("hops", hops);
        if (hops == ctx.fleetSize()) {
            ctx.done(msg.text() + " came home after " + hops + " hops");
        } else {
            ctx.send(ctx.next(), new Token(msg.text(), hops));
        }
    }
}
