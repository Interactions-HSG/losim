import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import losim.api.Takes;
import losim.api.Losim;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A combiner: it folds every chunk it is given into what it is already holding.
 *
 * <p>Its three resources grow at three different rates, which is the entire reason
 * this workload exists rather than a simpler one.
 *
 * <ul>
 *   <li><b>memory</b> follows <i>distinct keys</i>, and vocabulary saturates — the
 *       corpus is Zipf, so keys grow as a sublinear power of records (Heaps' law)</li>
 *   <li><b>disk</b> follows the <i>data volume</i>, linearly, because every chunk is
 *       spilled whether or not its words were new</li>
 *   <li><b>wire bytes</b> follow the record count, but with a per-call constant that
 *       is proportionally huge when the run is small</li>
 * </ul>
 *
 * <p>An engine that fitted all three against the same variable would be right about
 * at most one, and plausible about all three.
 */
public final class Accumulator extends WorkerBase {

    /** Retained bytes per distinct key: the bucket's payload, not its bookkeeping. */
    static final int PAYLOAD_PER_KEY = 256;      // longs

    private final Map<String, Integer> holding = new ConcurrentHashMap<>();
    private final Map<String, long[]> payload = new ConcurrentHashMap<>();

    @Takes(refMs = 2, refNsPerRecord = 20_000)
    @Override protected Counts map(Chunk c) {
        var chunk = new HashMap<String, Integer>();
        for (String word : c.getText().split(" ")) {
            chunk.merge(word, 1, Integer::sum);
            holding.merge(word, 1, Integer::sum);
            payload.computeIfAbsent(word, k -> new long[PAYLOAD_PER_KEY]);
        }
        Losim.current().records(c.getLines());
        // Every chunk is spilled, new words or not — so disk follows volume while
        // memory follows vocabulary, and the two part company as the run grows.
        Losim.current().wroteDisk(c.getText().length());
        // How the engine learns what this machine's memory is really a function of.
        Losim.current().reveal("distinctKeys", holding.size());
        return Counts.newBuilder().putAllCounts(chunk).build();
    }

    @Takes(refMs = 5)
    @Override protected Counts reduce(Counts request) {
        request.getCountsMap().forEach((k, v) -> {
            holding.merge(k, v, Integer::sum);
            payload.computeIfAbsent(k, x -> new long[PAYLOAD_PER_KEY]);
        });
        Losim.current().reveal("distinctKeys", holding.size());
        return Counts.newBuilder().putAllCounts(holding).build();
    }
}
