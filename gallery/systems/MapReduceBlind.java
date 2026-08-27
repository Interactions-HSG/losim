import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mr.pb.Bucket;
import mr.pb.Gather;
import mr.pb.Power;

/**
 * The same ten phases, placing work without looking at what the machines are.
 *
 * <p>It still takes the census — the calls are made, the bytes are paid for, the
 * table is built — and then it ignores every field in it. That is the comparison
 * worth running: not "with a scheduler" against "without one", but the same fleet,
 * the same corpus, the same weather, and one decision changed.
 *
 * <p>Three things follow, and all three are visible in the trace rather than
 * argued for here:
 *
 * <ul>
 *   <li>Equal splits mean the two-core burstable is given as much text as the
 *       sixteen-core machine, so the map phase ends when the slowest machine ends
 *       and every fast machine sits idle waiting for it.</li>
 *   <li>Shufflers chosen by partition number rather than by zone drag the whole
 *       intermediate data set across zones. Nothing fails. The bill grows.</li>
 *   <li>Partitions handed out in order put the biggest one wherever it happens to
 *       land, which is how a fleet with plenty of memory in it still runs out.</li>
 * </ul>
 *
 * <p>Run this against {@code MapReduce} on the same scenario and diff the traces.
 */
public final class MapReduceBlind extends MapReduce {

    /** Every machine is the same size, because nobody looked. */
    @Override protected double weight(Power p) { return 1.0; }

    /** Partition number, modulo the fleet. No zone, no memory, no thought. */
    @Override protected String pickShuffler(List<String> shufflers, Map<String, Power> power,
                                            Gather gather, int part) {
        return shufflers.get(part % shufflers.size());
    }

    /** In order, round the fleet. The biggest partition lands wherever it lands. */
    @Override protected Map<Integer, String> placeReducers(List<String> reducers,
                                                          Map<String, Power> power,
                                                          Map<Integer, Bucket> buckets) {
        var parts = new ArrayList<>(buckets.keySet());
        parts.sort(Comparator.naturalOrder());
        var out = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < parts.size(); i++)
            out.put(parts.get(i), reducers.get(i % reducers.size()));
        return out;
    }
}
