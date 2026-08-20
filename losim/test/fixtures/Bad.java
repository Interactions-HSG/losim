package fixtures;

import losim.api.*;

/** Deliberately nondeterministic, to prove the verifier rejects it. */
public final class Bad implements Program {
    static int counter;                                  // mutable static

    @Override public void main(Ctx ctx) {
        counter++;
        long t = System.nanoTime();                      // real time
        double r = Math.random();                        // unseeded randomness
        ctx.log("t=" + t + " r=" + r);
    }
}
