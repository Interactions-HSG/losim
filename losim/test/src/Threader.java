import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker whose work happens where the JVM will not say what it cost.
 *
 * <p>Worse than merely unattributed: {@code getThreadAllocatedBytes} returns −1 for a
 * virtual thread, so this is not memory charged to the wrong machine but memory that
 * cannot be read at all. It is the reason a machine's pool is platform threads —
 * a requirement rather than a preference.
 */
public final class Threader extends WorkerBase {

    @Cost(refMs = 2)
    @Override protected Counts map(Chunk c) {
        var counted = new int[1];
        Thread worker = Thread.startVirtualThread(() -> counted[0] = c.getText().split(" ").length);
        try { worker.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Counts.newBuilder().putCounts("words", counted[0]).build();
    }
}
