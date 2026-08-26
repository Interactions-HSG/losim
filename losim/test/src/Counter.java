import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import losim.api.Takes;
import losim.api.Losim;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that maps cheaply and reduces by accumulating — which is what kills it.
 *
 * <p>Nothing here declares a size. What the reduce costs is whatever the heap walk
 * finds the machine holding, so a machine too small for its bucket runs out of
 * memory for the same reason a real one would, in this code.
 *
 * <p>A no-argument constructor, because losim builds a fresh instance when a
 * machine restarts. Whatever it needs to know, it asks {@code Losim.current()}.
 */
public final class Counter extends WorkerBase {

    private final Map<String, Integer> holding = new ConcurrentHashMap<>();
    private final Map<String, long[]> ballast = new ConcurrentHashMap<>();

    @Takes(refMs = 15)
    @Override protected Counts map(Chunk c) {
        var out = new HashMap<String, Integer>();
        for (String word : c.getText().split("\\s+")) out.merge(word, 1, Integer::sum);
        Losim.current().records(c.getLines());
        Losim.current().reveal("keys", out.size());
        return Counts.newBuilder().putAllCounts(out).build();
    }

    @Takes(refMs = 100)
    @Override protected Counts reduce(Counts c) {
        c.getCountsMap().forEach((k, v) -> {
            holding.merge(k, v, Integer::sum);
            ballast.computeIfAbsent(k, x -> new long[180_000]);      // the bucket's payload
        });
        Losim.current().wroteDisk(holding.size() * 350_000L);
        Losim.current().reveal("distinctKeys", holding.size());
        return Counts.newBuilder().putAllCounts(holding).build();
    }
}
