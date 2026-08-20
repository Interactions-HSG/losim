package fixtures;

import losim.api.*;

/** The same shape, done correctly. */
public final class Good implements Program {
    private int counter;

    @Override public void main(Ctx ctx) {
        counter++;
        ctx.log("t=" + ctx.clock() + " r=" + ctx.random().nextInt(100) + " n=" + counter);
    }
}
