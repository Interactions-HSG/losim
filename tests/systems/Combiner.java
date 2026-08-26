import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Losim;
import losim.api.Takes;

/**
 * A worker that folds every chunk into what it is already holding.
 *
 * <p>Its three resources grow at three different rates, and that is the entire
 * reason this service exists rather than a simpler one:
 *
 * <ul>
 *   <li><b>memory</b> follows <i>distinct keys</i>, and vocabulary saturates — the
 *       corpus is Zipf, so keys grow as a sublinear power of records</li>
 *   <li><b>disk</b> follows the <i>data volume</i>, linearly, because every chunk is
 *       spilled whether or not its words were new</li>
 *   <li><b>wire bytes</b> follow the record count, with a per-call constant that is
 *       proportionally huge when the run is small</li>
 * </ul>
 *
 * <p>An engine that fitted all three against the same variable would be right about
 * at most one of them, and plausible about all three.
 */
public class Combiner extends WorkerBase {

    /** Retained longs per distinct key: the bucket's payload, not its bookkeeping. */
    static final int PAYLOAD_PER_KEY = 256;

    final Map<String, Integer> holding = new ConcurrentHashMap<>();
    final Map<String, long[]> payload = new ConcurrentHashMap<>();

    /**
     * Two reference milliseconds of setup, and 0.36 of one per line.
     *
     * <p>Large enough that the workers, not the coordinator handing out chunks, are
     * what the map phase is waiting for. A handler that costs less than the RPC
     * around it makes a fleet look like it does not scale, and the fleet would not
     * be the reason.
     */
    @Takes(refMs = 2, refNsPerRecord = 360_000)
    @Override protected Counts map(Chunk c) {
        var chunk = new HashMap<String, Integer>();
        for (String word : c.getText().split(" ")) {
            chunk.merge(word, 1, Integer::sum);
            holding.merge(word, 1, Integer::sum);
            keep(word);
        }
        Losim.current().records(c.getLines());
        // Every chunk is spilled, new words or not — so disk follows volume while
        // memory follows vocabulary, and the two part company as the run grows.
        Losim.current().wroteDisk(c.getText().length());
        // How the engine learns what this machine's memory is really a function of.
        Losim.current().reveal("distinctKeys", holding.size());
        return Counts.newBuilder().putAllCounts(chunk).build();
    }

    /** Overridden by a worker that stops keeping them past a point. */
    void keep(String word) {
        payload.computeIfAbsent(word, k -> new long[PAYLOAD_PER_KEY]);
    }

    @Takes(refMs = 5)
    @Override protected Counts reduce(Counts request) {
        request.getCountsMap().forEach((word, n) -> {
            holding.merge(word, n, Integer::sum);
            keep(word);
        });
        Losim.current().reveal("distinctKeys", holding.size());
        return Counts.newBuilder().putAllCounts(holding).build();
    }
}
