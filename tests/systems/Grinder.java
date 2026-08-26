import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Takes;

/** A hundred reference milliseconds of work, and nothing else. */
public final class Grinder extends Mapper {
    @Takes(refMs = 100)
    @Override protected Counts map(Chunk c) {
        return Counts.newBuilder().putCounts("ground", c.getLines()).build();
    }
}
