import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.WorkerGrpc;
import losim.api.Cluster;
import losim.api.Job;

/**
 * A word count whose size is whatever it is asked for.
 *
 * <p>This is what makes a job scalable: it reads {@link Cluster#records()} rather
 * than deciding for itself how much work there is. A job that hardcodes its own size
 * cannot be shrunk, and the engine has nothing to turn.
 *
 * <p>Two phases, deliberately of different shapes. The map phase fans out over every
 * worker at once, so it is the part that gets faster when the fleet grows. The
 * collect phase asks each worker for its bucket and merges them here, so it is the
 * part that does not. A projection that cannot tell those apart will say a design
 * scales when it does not.
 *
 * <p>The corpus is generated a chunk at a time and never held whole. At full scale
 * the input lives on disk and no coordinator holds it, so a coordinator that held it
 * here would put a linear term in the one machine whose memory is meant to be flat —
 * and the fitted memory law would faithfully follow it.
 */
public final class Elastic implements Job {

    /**
     * Small enough that the smallest rung of a probe ladder is still many chunks.
     *
     * <p>Chunks are whole, and the worst machine's disk is the peak of an integer
     * count of them. At a few chunks per worker that quantisation is a real
     * discontinuity in the measurement — the ladder bends because the pieces are
     * few, not because the program does anything different — and the engine refuses
     * a disk law over it, correctly and uselessly.
     */
    static final int LINES_PER_CHUNK = 50;
    static final int WORDS_PER_LINE = 8;

    /** Chunks allowed in flight at once, so fanning out does not mean holding the corpus. */
    static final int IN_FLIGHT = 8;

    @Override public void run(Cluster cluster) throws Exception {
        var workers = cluster.serving("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        var stubs = new ArrayList<WorkerGrpc.WorkerStub>();
        var blocking = new ArrayList<WorkerGrpc.WorkerBlockingStub>();
        for (String w : workers) {
            var channel = cluster.channelTo(w);
            stubs.add(WorkerGrpc.newStub(channel));
            blocking.add(WorkerGrpc.newBlockingStub(channel));
        }

        long records = cluster.records();
        var corpus = new Zipf(200_000, 1.1, cluster.seed());
        int chunks = (int) ((records + LINES_PER_CHUNK - 1) / LINES_PER_CHUNK);

        // Fanned out across every worker at once — which is what makes this the phase
        // a bigger fleet finishes sooner.
        var lost = new ConcurrentLinkedQueue<Chunk>();
        try (var phase = cluster.phase("map")) {
            var room = new Semaphore(IN_FLIGHT);
            var done = new CountDownLatch(chunks);
            for (int i = 0; i < chunks; i++) {
                int lines = (int) Math.min(LINES_PER_CHUNK, records - (long) i * LINES_PER_CHUNK);
                var text = new StringBuilder();
                for (String line : corpus.lines(lines, WORDS_PER_LINE)) {
                    if (text.length() > 0) text.append(' ');
                    text.append(line);
                }
                final Chunk chunk = Chunk.newBuilder().setText(text.toString())
                        .setLines(lines).build();
                room.acquire();
                stubs.get(i % stubs.size()).withDeadlineAfter(8000, TimeUnit.MILLISECONDS)
                     .map(chunk, new StreamObserver<Counts>() {
                              @Override public void onNext(Counts c) { }
                              @Override public void onError(Throwable t) { lost.add(chunk); free(); }
                              @Override public void onCompleted() { free(); }
                              private void free() { room.release(); done.countDown(); }
                          });
            }
            done.await(120, TimeUnit.SECONDS);
            phase.note("chunks", chunks);
        }

        // Whatever did not come back has to be done again somewhere else, and that is
        // not bookkeeping: it is why a fleet that loses a machine needs more memory
        // than one that does not. Some survivor absorbs the dead machine's bucket, and
        // a model fitted only on clean runs under-predicts by exactly that much —
        // optimistically, which is the worst direction to be wrong in.
        if (!lost.isEmpty()) {
            try (var phase = cluster.phase("remap")) {
                int redone = 0;
                for (Chunk chunk = lost.poll(); chunk != null; chunk = lost.poll()) {
                    for (String worker : cluster.serving("Worker")) {
                        try {
                            WorkerGrpc.newBlockingStub(cluster.channelTo(worker))
                                    .withDeadlineAfter(8000, TimeUnit.MILLISECONDS).map(chunk);
                            redone++;
                            break;
                        } catch (StatusRuntimeException e) {
                            // That one is gone too. Try the next; there is no third outcome.
                        }
                    }
                }
                phase.note("redone", redone);
            }
        }

        // And this one is one call per worker and a merge here, so a bigger fleet
        // makes it no shorter. Nothing distinguishes the two phases but their shape.
        var merged = new TreeMap<String, Integer>();
        try (var phase = cluster.phase("collect")) {
            for (int i = 0; i < blocking.size(); i++) {
                try {
                    var bucket = blocking.get(i).withDeadlineAfter(8000, TimeUnit.MILLISECONDS)
                            .reduce(Counts.getDefaultInstance()).getCountsMap();
                    final Map<String, Integer> into = merged;
                    cluster.compute("merge " + workers.get(i), () -> {
                        bucket.forEach((word, n) -> into.merge(word, n, Integer::sum));
                        return into;
                    });
                } catch (StatusRuntimeException e) {
                    cluster.log(workers.get(i) + " did not answer: " + e.getStatus().getCode());
                }
            }
            phase.note("keys", merged.size());
        }
        cluster.done(Map.of("records", records, "chunks", chunks, "distinct", merged.size()));
    }
}
