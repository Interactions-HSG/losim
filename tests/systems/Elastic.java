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
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;

/**
 * A word count whose size is whatever it is asked for.
 *
 * <p>This is what makes a Job scalable: how many lines there are arrives in the
 * {@link Workload}, so the simulation decides how much work there is. A Job that
 * held its own size could not be shrunk, and the engine would have nothing to turn.
 *
 * <p>Two phases, deliberately of different shapes. The map phase fans out over every
 * worker at once, so it is the part that gets faster when the cluster grows. The
 * collect phase asks each worker for its bucket and merges them here, so it is the
 * part that does not. A projection that cannot tell those apart will say a design
 * scales when it does not.
 *
 * <p>The corpus is generated a chunk at a time in {@code Run} and never held whole
 * — not built in {@code Load}, which is the tempting mistake now that there is a
 * place to build it. At full scale the input lives on disk and no coordinator holds
 * it, so a coordinator that held it here would put a linear term in the one node
 * whose memory is meant to be flat, and the fitted memory law would faithfully
 * follow it. What {@code Load} hands over is how many lines to make.
 */
public final class Elastic extends JobGrpc.JobImplBase {

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

    /** Chunks allowed in flight at once, so fanning out does not mean holding the corpus. */
    static final int IN_FLIGHT = 8;

    /** The shape of the data, which is the Job's business and nowhere in the file. */
    static final int WORDS_PER_LINE = 8;
    static final int VOCABULARY = 200_000;

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).setType(in.getUnit()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) throws RuntimeException {
        var here = Losim.current();
        var workers = here.peersServing("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        var stubs = new ArrayList<WorkerGrpc.WorkerStub>();
        var blocking = new ArrayList<WorkerGrpc.WorkerBlockingStub>();
        for (String w : workers) {
            var channel = here.channelTo(w);
            stubs.add(WorkerGrpc.newStub(channel));
            blocking.add(WorkerGrpc.newBlockingStub(channel));
        }

        long units = work.getCount();
        var corpus = new Zipf(VOCABULARY, 1.1, here.seed());
        int chunks = (int) ((units + LINES_PER_CHUNK - 1) / LINES_PER_CHUNK);

        // Fanned out across every worker at once — which is what makes this the phase
        // a bigger cluster finishes sooner.
        var lost = new ConcurrentLinkedQueue<Chunk>();
        var room = new Semaphore(IN_FLIGHT);
        var done = new CountDownLatch(chunks);
        try {
            for (int i = 0; i < chunks; i++) {
                int lines = (int) Math.min(LINES_PER_CHUNK, units - (long) i * LINES_PER_CHUNK);
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
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Whatever did not come back has to be done again somewhere else, and that is
        // not bookkeeping: it is why a cluster that loses a machine needs more memory
        // than one that does not. Some survivor absorbs the dead machine's bucket, and
        // a model fitted only on clean runs under-predicts by exactly that much —
        // optimistically, which is the worst direction to be wrong in.
        for (Chunk chunk = lost.poll(); chunk != null; chunk = lost.poll()) {
            for (String worker : here.peersServing("Worker")) {
                try {
                    WorkerGrpc.newBlockingStub(here.channelTo(worker))
                            .withDeadlineAfter(8000, TimeUnit.MILLISECONDS).map(chunk);
                    break;
                } catch (StatusRuntimeException e) {
                    // That one is gone too. Try the next; there is no third outcome.
                }
            }
        }

        // And this one is one call per worker and a merge here, so a bigger system
        // makes it no shorter. Nothing distinguishes the two stretches but their
        // shape — and the merge runs on the thread serving losim.Job/Run, so the
        // node is busy for it without anything having to say so.
        var merged = new TreeMap<String, Integer>();
        for (int i = 0; i < blocking.size(); i++) {
            try {
                blocking.get(i).withDeadlineAfter(8000, TimeUnit.MILLISECONDS)
                        .reduce(Counts.getDefaultInstance()).getCountsMap()
                        .forEach((word, n) -> merged.merge(word, n, Integer::sum));
            } catch (StatusRuntimeException e) {
                here.log(workers.get(i) + " did not answer: " + e.getStatus().getCode());
            }
        }
        out.onNext(Result.newBuilder()
                .putAnswer("units", String.valueOf(units))
                .putAnswer("chunks", String.valueOf(chunks))
                .putAnswer("distinct", String.valueOf(merged.size()))
                .build());
        out.onCompleted();
    }
}
