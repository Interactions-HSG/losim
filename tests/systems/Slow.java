import lab.pb.Chunk;
import lab.pb.Counts;

/** A handler that takes longer than the caller is willing to wait for. */
public final class Slow extends Mapper {
    @Override protected Counts map(Chunk c) {
        return Counts.newBuilder().putCounts("eventually", c.getLines()).build();
    }
}
