package fixtures;

import losim.api.*;

/** Holds a described dataset whole. Dies when it does not fit the machine. */
public final class Hog implements Program {
    @Override public void main(Ctx ctx) {
        Data huge = Data.gigabytes("huge", 500, 100);      // 500 GB
        ctx.hold(huge);                                    // must not fit an m5.large
        ctx.done("held it");
    }
}
