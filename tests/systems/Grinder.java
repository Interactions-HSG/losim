import lab.pb.Chunk;
import lab.pb.Counts;

/** A hundred reference milliseconds of work, and nothing else. */
public final class Grinder extends Mapper {
    @Override protected Counts map(Chunk c) {
        return Counts.newBuilder().putCounts("ground", c.getLines()).build();
    }
}
