import losim.api.*;

/**
 * Lab 4 — a terabyte on a laptop.
 *
 * The shard handed to this worker is far larger than the machine's memory, so
 * holding it is not an option. Work through it in batches sized to what the
 * machine actually has, then spill what comes out.
 */
public final class Cruncher implements Program, CrunchService {

    @Override
    public Summary process(Ctx ctx, Shard request) {
        Data shard = request.data().resolve();
        long perRecord = Math.max(1, shard.bytesPerRecord());

        // Size the working set to the machine, not to the problem.
        long batchRecords = Math.max(1, ctx.memoryFree() / (2 * perRecord));
        long done = 0;
        int batches = 0;
        while (done < shard.records()) {
            long n = Math.min(batchRecords, shard.records() - done);
            Data batch = Data.of(shard.name() + ":b" + batches, n, perRecord);
            ctx.hold(batch);                 // OutOfMemory if this does not fit
            ctx.process(batch, 12);          // 12ns per record, scaled by the machine
            ctx.release(batch);
            done += n;
            batches++;
        }

        Data emitted = shard.derive(shard.name() + "-out", 0.08, 48);
        ctx.spill(emitted);                  // NoSpace if the local disk is too small
        ctx.reveal("batches", batches);
        return new Summary(emitted.records(), emitted.gigabytes(), batches + " batches");
    }
}
