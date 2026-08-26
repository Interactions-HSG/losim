import java.util.concurrent.CompletableFuture;
import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that hands its work to somebody else's thread.
 *
 * <p>A machine <i>is</i> its pool: every thread in it belongs to exactly one machine,
 * which is how allocation is attributed and how the vCPU model means anything. Work on
 * the common pool is charged to nobody and contends for nobody's cores, so this machine
 * looks cheaper and less busy than it is.
 */
public final class Forker extends WorkerBase {

    @Cost(refMs = 2)
    @Override protected Counts map(Chunk c) {
        return CompletableFuture.supplyAsync(() ->
                Counts.newBuilder().putCounts(c.getText().trim(), 1).build()).join();
    }
}
