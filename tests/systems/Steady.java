import java.util.HashMap;
import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Losim;
import losim.api.Takes;

/**
 * Cheap to start and dear per record: 2 refMs, and 0.02 refMs for each one.
 *
 * <p>The shape a fixed deadline is actually got wrong against. Two refMs is under
 * any deadline anybody would write, so nothing about this method looks expensive
 * until the count arrives — and the count is the handler's to declare, which is
 * why the caller cannot check it before making the call.
 */
public final class Steady extends Mapper {
    @Takes(refMs = 2, refNsPerRecord = 20_000)
    @Override protected Counts map(Chunk c) {
        var out = new HashMap<String, Integer>();
        for (String word : c.getText().split("\\s+")) if (!word.isEmpty()) out.merge(word, 1, Integer::sum);
        Losim.current().records(c.getLines());
        return Counts.newBuilder().putAllCounts(out).build();
    }
}
