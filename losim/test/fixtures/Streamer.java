package fixtures;

import losim.api.*;

/** The same 500 GB, worked through in batches sized to the machine. */
public final class Streamer implements Program {
    @Override public void main(Ctx ctx) {
        Data huge = Data.gigabytes("huge", 500, 100);
        long batch = Math.max(1, ctx.memoryFree() / (2 * huge.bytesPerRecord()));
        long done = 0;
        int batches = 0;
        while (done < huge.records()) {
            long n = Math.min(batch, huge.records() - done);
            Data b = Data.of("b" + batches, n, huge.bytesPerRecord());
            ctx.hold(b);
            ctx.process(b, 1);
            ctx.release(b);
            done += n; batches++;
        }
        ctx.done("streamed in " + batches + " batches");
    }
}
