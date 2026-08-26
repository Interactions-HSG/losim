import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that keeps its state where every other worker can reach it.
 *
 * <p>Eight of these on eight machines hold one map. Whatever is in it was allocated by
 * whichever machine touched it first and is retained by all of them, so no per-machine
 * memory figure is that machine's — and a partition, which cuts the network, does not
 * cut this.
 */
public final class Hoarder extends WorkerBase {

    static final Map<String, Integer> EVERYONES = new ConcurrentHashMap<>();

    /** A table, not state: nothing is called to build it, and nothing can add to it. */
    static final String[] IGNORED = {"the", "a", "and"};

    @Cost(refMs = 2)
    @Override protected Counts map(Chunk c) {
        for (String word : c.getText().split(" ")) EVERYONES.merge(word, 1, Integer::sum);
        return Counts.newBuilder().putAllCounts(EVERYONES).build();
    }
}
