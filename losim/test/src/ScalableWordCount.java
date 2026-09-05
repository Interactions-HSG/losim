import io.grpc.StatusRuntimeException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Job;
import losim.t.*;

/**
 * A word count whose size is whatever it is asked for.
 *
 * <p>This is what makes a job scalable: it reads {@link Cluster#records()} rather
 * than deciding for itself how much work there is. A job that hardcodes its own
 * size cannot be shrunk, and the engine has nothing to turn.
 *
 * <p>The corpus is generated a chunk at a time and never held whole. At full scale
 * the input lives on disk and no coordinator holds it, so a coordinator that held
 * it here would put a linear term in the one machine whose memory is supposed to be
 * flat, and the fitted memory law would follow it.
 */
public final class ScalableWordCount implements Job {

    static final int LINES_PER_CHUNK = 200;
    static final int WORDS_PER_LINE = 8;

    @Override public void run(Cluster cluster) {
        var workers = cluster.serving("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        long records = cluster.records();
        // Seeded from the scenario, so a sweep varies the data and not only the weather.
        var corpus = new Corpus(200_000, 1.1, cluster.seed());
        var stubs = new ArrayList<WorkerGrpc.WorkerBlockingStub>();
        for (String w : workers) stubs.add(WorkerGrpc.newBlockingStub(cluster.channelTo(w)));

        int chunks = 0;
        try (var phase = cluster.phase("map")) {
            for (long done = 0; done < records; done += LINES_PER_CHUNK) {
                int lines = (int) Math.min(LINES_PER_CHUNK, records - done);
                var text = new StringBuilder();
                for (var line : corpus.lines(lines, WORDS_PER_LINE)) {
                    if (text.length() > 0) text.append(' ');
                    text.append(line);
                }
                var stub = stubs.get(chunks % stubs.size());
                try {
                    stub.withDeadlineAfter(4000, TimeUnit.MILLISECONDS)
                        .map(Chunk.newBuilder().setText(text.toString()).setLines(lines).build());
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
        cluster.done(Map.of("records", records, "chunks", chunks, "distinct", merged.size()));
    }
}
