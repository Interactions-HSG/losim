import lab.pb.Chunk;
import lab.pb.Counts;
import dissaly.api.Dissaly;

/**
 * A combiner that writes a fixed-size index before it is any use.
 *
 * <p>Perfectly ordinary — plenty of real services build a constant working set at
 * startup and then grow slowly on top of it. What makes it interesting is that the
 * constant does not shrink when the workload does, so at probe scale it is almost
 * the whole of the disk figure and the part that actually varies is a rounding
 * error on top of it.
 *
 * <p>A law fitted there is a law about the index, and says a cluster processing
 * forty-eight thousand units needs about as much disk as one processing eight
 * thousand. That is exactly right: the index is what the disk is, and it is written
 * once. The engine reports the number and says how little it moved — 0.08% for six
 * times the work — rather than refusing on the grounds that most of the figure is
 * a constant. A constant that dominates is a fact about a design, not a reason to
 * abandon the measurement of everything around it.
 */
public final class Prefill extends Combiner {

    /** Written once, whatever the size of the run. Bytes are accounted, not stored. */
    static final long INDEX_BYTES = 64L * 1024 * 1024;

    private boolean built;

    @Override protected Counts map(Chunk c) {
        if (!built) {
            built = true;
            Dissaly.current().wroteDisk(INDEX_BYTES);
            Dissaly.current().log("built the index: 64 MB, the same at every scale");
        }
        return super.map(c);
    }
}
