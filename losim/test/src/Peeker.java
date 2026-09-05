import java.util.HashMap;
import losim.api.Takes;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that times itself, with the wrong clock.
 *
 * <p>It looks careful and it is wrong. Every duration losim knows is reference-machine
 * time divided by {@code k_time}; {@code System.nanoTime} is the host's afternoon, and
 * at a compression of forty the two differ by forty. Nothing breaks, no exception is
 * thrown, and the number this machine reports about itself is simply not a number about
 * the simulated world.
 */
public final class Peeker extends WorkerBase {

    @Takes(refMs = 5)
    @Override protected Counts map(Chunk c) {
        long began = System.nanoTime();
        var out = new HashMap<String, Integer>();
        for (String word : c.getText().split(" ")) out.merge(word, 1, Integer::sum);
        out.put("__tookNanos", (int) (System.nanoTime() - began));
        return Counts.newBuilder().putAllCounts(out).build();
    }
}
