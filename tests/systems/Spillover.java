import losim.api.Losim;

/**
 * The same combiner, except that it spills to disk above a key count.
 *
 * <p>Below the threshold its memory climbs with vocabulary; above it, memory
 * flattens and disk takes over. That is a perfectly reasonable thing for a real
 * reducer to do, and it is exactly what an extrapolation must not be allowed to
 * cross: a law fitted below the threshold predicts a machine that fills up, and a
 * law fitted across it predicts something that happened at neither end.
 *
 * <p>Counted per machine, because that is where the threshold really lives. A
 * reducer spills when <i>its</i> bucket outgrows <i>its</i> heap, not when the
 * fleet's total does.
 */
public final class Spillover extends Combiner {

    static final int KEEP_IN_MEMORY = 2200;

    private long spilled;

    @Override void keep(String word) {
        if (payload.size() < KEEP_IN_MEMORY) {
            payload.computeIfAbsent(word, k -> new long[PAYLOAD_PER_KEY]);
        } else if (!payload.containsKey(word)) {
            spilled += PAYLOAD_PER_KEY * 8L;
            Losim.current().reveal("spilledBytes", spilled);
        }
    }
}
