import losim.api.*;

/**
 * The same job, written the way you would if the data fitted.
 *
 * It does not fit. This is the version that dies, and it dies for the right
 * reason: the shard is bigger than the machine you provisioned.
 */
public final class Naive implements Program, CrunchService {

    @Override
    public Summary process(Ctx ctx, Shard request) {
        Data shard = request.data().resolve();
        ctx.hold(shard);                     // the whole shard, at once
        ctx.process(shard, 12);
        Data emitted = shard.derive(shard.name() + "-out", 0.08, 48);
        ctx.spill(emitted);
        return new Summary(emitted.records(), emitted.gigabytes(), "held whole");
    }
}
