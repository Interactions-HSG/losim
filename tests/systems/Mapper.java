import java.util.HashMap;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.Report;
import dissaly.api.Dissaly;

/**
 * The service most of the suite is written against: count the words in a chunk.
 *
 * <p>An ordinary gRPC service from an ordinary .proto, with one dissaly annotation
 * and one dissaly call — neither of which appears in a signature, which is what lets
 * the same handler be constructed and called from a plain test with nothing
 * simulating anything.
 */
public class Mapper extends WorkerBase {

    @Override protected Counts map(Chunk c) {
        var out = new HashMap<String, Integer>();
        for (String word : c.getText().split("\\s+")) if (!word.isEmpty()) out.merge(word, 1, Integer::sum);
        Dissaly.current().units(c.getLines());
        Dissaly.current().reveal("emitted", out.size());
        return Counts.newBuilder().putAllCounts(out).build();
    }

    /** Echoes a message with an enum and a oneof in it, so rendering has work to do. */
    @Override protected Report note(Report r) {
        return r.toBuilder().addTags("seen").build();
    }
}
