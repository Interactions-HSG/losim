import io.grpc.StatusRuntimeException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Input;
import losim.api.Scalable;
import losim.t.*;

/**
 * A word count whose size is whatever it is asked for.
 *
 * <p>This is what makes a job scalable: every number describing the corpus arrives
 * in the {@link Input}, so the scenario decides how much work there is. A job that
 * held its own size could not be shrunk, and the engine would have nothing to turn.
 *
 * <p>The corpus is generated a chunk at a time and never held whole. At full scale
 * the input lives on disk and no coordinator holds it, so a coordinator that held
 * it here would put a linear term in the one machine whose memory is supposed to be
 * flat, and the fitted memory law would follow it.
 */
public final class ScalableWordCount implements Scalable {

    /** How many lines go in one call. The job's own batching, not the input's size. */
    static final int LINES_PER_CHUNK = 200;

    @Override public Input.Shape shape() {
        return Input.Shape.counting("lines", "line").with("wordsPerLine").with("vocabulary");
    }

    @Override public void run(Cluster cluster, Input at) {
        var workers = cluster.serving("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        long lines = at.count("lines");
        int wordsPerLine = (int) at.value("wordsPerLine");
        // Seeded from the scenario, so a sweep varies the data and not only the weather.
        var corpus = new Corpus((int) at.value("vocabulary"), 1.1, cluster.seed());
        var stubs = new ArrayList<WorkerGrpc.WorkerBlockingStub>();
        for (String w : workers) stubs.add(WorkerGrpc.newBlockingStub(cluster.channelTo(w)));

        int chunks = 0;
        try (var phase = cluster.phase("map")) {
            for (long done = 0; done < lines; done += LINES_PER_CHUNK) {
                int batch = (int) Math.min(LINES_PER_CHUNK, lines - done);
                var text = new StringBuilder();
                for (var line : corpus.lines(batch, wordsPerLine)) {
                    if (text.length() > 0) text.append(' ');
                    text.append(line);
                }
                var stub = stubs.get(chunks % stubs.size());
                try {
                    stub.withDeadlineAfter(4000, TimeUnit.MILLISECONDS)
                        .map(Chunk.newBuilder().setText(text.toString()).setLines(batch).build());
                } catch (StatusRuntimeException e) {
                    cluster.log("chunk " + chunks + " lost: " + e.getStatus().getCode());
                }
                chunks++;
            }
            phase.note("chunks", chunks);
        }

        var merged = new TreeMap<String, Integer>();
        try (var phase = cluster.phase("collect")) {
            for (int i = 0; i < stubs.size(); i++) {
                try {
                    stubs.get(i).withDeadlineAfter(4000, TimeUnit.MILLISECONDS)
                        .reduce(Counts.getDefaultInstance()).getCountsMap()
                        .forEach((k, v) -> merged.merge(k, v, Integer::sum));
                } catch (StatusRuntimeException e) {
                    cluster.log(workers.get(i) + " did not answer: " + e.getStatus().getCode());
                }
            }
            phase.note("keys", merged.size());
        }
        cluster.done(Map.of("lines", lines, "chunks", chunks, "distinct", merged.size()));
    }
}
