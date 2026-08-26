import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Losim;

/**
 * A combiner that writes a fixed-size index before it is any use.
 *
 * <p>Perfectly ordinary — plenty of real services build a constant working set at
 * startup and then grow slowly on top of it. What makes it interesting is that the
 * constant does not shrink when the workload does, so at probe scale it is almost
 * the whole of the disk figure and the part that actually varies is a rounding
 * error on top of it.
 *
 * <p>A law fitted there is a law about the index. Extrapolated, it says a fleet
 * processing forty-eight thousand records needs about as much disk as one
 * processing eight thousand — which is true of the index and false of everything
 * else, and the engine has no way to tell those apart from four points that are all
 * index. So it refuses, and names the resource.
 */
public final class Prefill extends Combiner {

    /** Written once, whatever the size of the run. Bytes are accounted, not stored. */
    static final long INDEX_BYTES = 64L * 1024 * 1024;

    private boolean built;

    @Override protected Counts map(Chunk c) {
        if (!built) {
            built = true;
            Losim.current().wroteDisk(INDEX_BYTES);
            Losim.current().log("built the index: 64 MB, the same at every scale");
        }
        return super.map(c);
    }
}
