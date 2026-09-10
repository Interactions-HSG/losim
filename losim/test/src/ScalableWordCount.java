import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.TimeUnit;
import dissaly.api.Dissaly;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;
import dissaly.t.*;

/**
 * A word count whose size is whatever it is asked for.
 *
 * <p>This is what makes a Job scalable: how many lines there are arrives in the
 * {@link Workload}, so the simulation decides how much work there is. A Job that
 * held its own size could not be shrunk, and the engine would have nothing to
 * turn.
 *
 * <p>The corpus is generated a chunk at a time in {@code Run} and never held
 * whole — not built in {@code Load}, which is the tempting mistake. At full scale
 * the input lives on disk and no master holds it, so a master that held
 * it here would put a linear term in the one node whose memory is supposed to be
 * flat, and the fitted memory law would follow it. What {@code Load} hands over is
 * how many lines to make, which is a number.
 */
public final class ScalableWordCount extends JobGrpc.JobImplBase {

    /** How many lines go in one call. The Job's own batching, not the workload's size. */
    static final int LINES_PER_CHUNK = 200;

    /** The shape of the data, which is the Job's business and nowhere in the file. */
    static final int WORDS_PER_LINE = 8;
    static final int VOCABULARY = 200_000;

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).setType(in.getUnit()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Dissaly.current();
        var workers = here.peersServing("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        long lines = work.getCount();
        // Seeded from the simulation, so a sweep varies the data and not only the weather.
        var corpus = new Corpus(VOCABULARY, 1.1, here.seed());
        var stubs = new ArrayList<WorkerGrpc.WorkerBlockingStub>();
        for (String w : workers) stubs.add(WorkerGrpc.newBlockingStub(here.channelTo(w)));

        int chunks = 0;
        for (long done = 0; done < lines; done += LINES_PER_CHUNK) {
            int batch = (int) Math.min(LINES_PER_CHUNK, lines - done);
            var text = new StringBuilder();
            for (var line : corpus.lines(batch, WORDS_PER_LINE)) {
                if (text.length() > 0) text.append(' ');
                text.append(line);
            }
            var stub = stubs.get(chunks % stubs.size());
            try {
                stub.withDeadlineAfter(4000, TimeUnit.MILLISECONDS)
                    .map(Chunk.newBuilder().setText(text.toString()).setLines(batch).build());
            } catch (StatusRuntimeException e) {
                here.log("chunk " + chunks + " lost: " + e.getStatus().getCode());
            }
            chunks++;
        }

        var merged = new TreeMap<String, Integer>();
        for (int i = 0; i < stubs.size(); i++) {
            try {
                stubs.get(i).withDeadlineAfter(4000, TimeUnit.MILLISECONDS)
                    .reduce(Counts.getDefaultInstance()).getCountsMap()
                    .forEach((k, v) -> merged.merge(k, v, Integer::sum));
            } catch (StatusRuntimeException e) {
                here.log(workers.get(i) + " did not answer: " + e.getStatus().getCode());
            }
        }
        out.onNext(Result.newBuilder()
                .putAnswer("lines", String.valueOf(lines))
                .putAnswer("chunks", String.valueOf(chunks))
                .putAnswer("distinct", String.valueOf(merged.size()))
                .build());
        out.onCompleted();
    }
}
