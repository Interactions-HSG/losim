import losim.api.Losim;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * The same wait as {@link Napper}, written in the unit everything else is written in.
 *
 * <p>Which is the point of the fixture: it must not be flagged. A backoff that grows
 * with the attempt cannot be an annotation, so `@Takes` is not the answer here. The
 * duration is still reference time, and losim still divides it by k_time before
 * spending it. The problem is `Thread.sleep`'s unit, not waiting itself.
 */
public final class Waiter extends WorkerBase {

    @Override protected Counts map(Chunk c) {
        double backoff = 25;
        for (int attempt = 0; attempt < 3; attempt++) {
            Losim.current().sleep(backoff);
            backoff *= 2;
        }
        return Counts.newBuilder().putCounts(c.getText().trim(), 1).build();
    }
}
