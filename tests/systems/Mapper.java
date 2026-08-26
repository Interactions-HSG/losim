import java.util.HashMap;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.Report;
import losim.api.Losim;
import losim.api.Takes;

/**
 * The service most of the suite is written against: count the words in a chunk.
 *
 * <p>An ordinary gRPC service from an ordinary .proto, with one losim annotation
 * and one losim call — neither of which appears in a signature, which is what lets
 * the same handler be constructed and called from a plain test with nothing
 * simulating anything.
 */
public class Mapper extends WorkerBase {

    @Takes(refMs = 20)
    @Override protected Counts map(Chunk c) {
        var out = new HashMap<String, Integer>();
        for (String word : c.getText().split("\\s+")) if (!word.isEmpty()) out.merge(word, 1, Integer::sum);
        Losim.current().records(c.getLines());
        Losim.current().reveal("emitted", out.size());
        return Counts.newBuilder().putAllCounts(out).build();
    }

    /** Echoes a message with an enum and a oneof in it, so rendering has work to do. */
    @Takes(refMs = 1)
    @Override protected Report note(Report r) {
        return r.toBuilder().addTags("seen").build();
    }
}
