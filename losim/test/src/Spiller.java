import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import dissaly.api.Dissaly;
import dissaly.t.Chunk;
import dissaly.t.Counts;

/**
 * The same accumulator, except that it spills to disk above a key count.
 *
 * <p>Below the threshold its memory climbs with vocabulary; above it, memory
 * flattens and disk takes over. That is a perfectly reasonable thing for a real
 * reducer to do, and it is exactly what an extrapolation must not be allowed to
 * cross: a law fitted below the threshold predicts a machine that fills up, and a
 * law fitted across it predicts something that never happened at either end.
 */
public final class Spiller extends WorkerBase {

    /**
     * Keys this machine keeps in memory. Past this, the rest go to disk.
     *
     * <p>Counted per machine, because that is where the threshold really lives: a
     * reducer spills when <i>its</i> bucket outgrows <i>its</i> heap, not when the
     * cluster's total does.
     */
    public static volatile int keepInMemory = 2200;

    static final int PAYLOAD_PER_KEY = 256;

    private final Map<String, Integer> holding = new ConcurrentHashMap<>();
    private final Map<String, long[]> payload = new ConcurrentHashMap<>();
    private long spilled;

    @Override protected Counts map(Chunk c) {
        var chunk = new HashMap<String, Integer>();
        for (String word : c.getText().split(" ")) {
            chunk.merge(word, 1, Integer::sum);
            holding.merge(word, 1, Integer::sum);
            if (payload.size() < keepInMemory) payload.computeIfAbsent(word, k -> new long[PAYLOAD_PER_KEY]);
            else spilled += PAYLOAD_PER_KEY * 8L;
        }
        Dissaly.current().units(c.getLines());
        Dissaly.current().wroteDisk(c.getText().length());
        Dissaly.current().reveal("distinctKeys", holding.size());
        Dissaly.current().reveal("spilledBytes", spilled);
        return Counts.newBuilder().putAllCounts(chunk).build();
    }

    @Override protected Counts reduce(Counts request) {
        return Counts.newBuilder().putAllCounts(holding).build();
    }
}
