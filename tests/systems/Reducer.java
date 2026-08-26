import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lab.pb.Counts;
import losim.api.Losim;
import losim.api.Takes;

/**
 * A reducer that accumulates, which is the whole reason it can run out of memory.
 *
 * <p>Nothing here declares a size. What it holds is whatever the heap walk finds it
 * holding, so a machine too small for its bucket fails for the same reason a real
 * one would — in this code, not in an accounting fiction.
 */
public final class Reducer extends ShufflerBase {

    private final Map<String, Integer> holding = new ConcurrentHashMap<>();
    private final Map<String, long[]> payload = new ConcurrentHashMap<>();

    /** Bytes a reducer really keeps per distinct key. Small, so the machines can be too. */
    static final int PER_KEY = 500_000;

    @Takes(refMs = 30)
    @Override protected Counts fold(Counts bucket) {
        bucket.getCountsMap().forEach((word, n) -> {
            holding.merge(word, n, Integer::sum);
            payload.computeIfAbsent(word, k -> new long[PER_KEY / 8]);
        });
        Losim.current().records(bucket.getCountsCount());
        Losim.current().reveal("distinctKeys", holding.size());
        return Counts.newBuilder().putAllCounts(holding).build();
    }
}
