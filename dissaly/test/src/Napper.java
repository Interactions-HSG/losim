import dissaly.t.Chunk;
import dissaly.t.Counts;

/**
 * A worker that sleeps instead of declaring what it costs.
 *
 * <p>The same lie as {@link Peeker}, from the other end. A hand-rolled pause is the
 * one duration in a run that does not move when the compression does: at {@code k_time}
 * forty, a declared 40 refMs sleeps a real millisecond and this sleeps forty.
 */
public final class Napper extends WorkerBase {

    @Override protected Counts map(Chunk c) {
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Counts.newBuilder().putCounts(c.getText().trim(), 1).build();
    }
}
