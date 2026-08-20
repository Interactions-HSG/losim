package fixtures;

import losim.api.*;

/** Spills more than the local disk holds. */
public final class Filler implements Program {
    @Override public void main(Ctx ctx) {
        ctx.spill(Data.gigabytes("spill", 5000, 100));     // 5 TB onto a small disk
        ctx.done("spilled");
    }
}
