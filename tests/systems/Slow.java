import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Takes;

/** A handler that takes longer than the caller is willing to wait for. */
public final class Slow extends Mapper {
    @Takes(refMs = 500)
    @Override protected Counts map(Chunk c) {
        return Counts.newBuilder().putCounts("eventually", c.getLines()).build();
    }
}
