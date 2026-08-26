import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that sleeps instead of declaring what it costs.
 *
 * <p>The same lie as {@link Peeker}, from the other end. A hand-rolled pause is the
 * one duration in a run that does not move when the compression does: at {@code k_time}
 * forty, {@code @Cost(refMs = 40)} sleeps a real millisecond and this sleeps forty.
 */
public final class Napper extends WorkerBase {

    @Cost(refMs = 1)
    @Override protected Counts map(Chunk c) {
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Counts.newBuilder().putCounts(c.getText().trim(), 1).build();
    }
}
