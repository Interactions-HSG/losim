import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import losim.api.Losim;
import losim.api.Takes;
import mr.pb.Bucket;
import mr.pb.Empty;
import mr.pb.Output;
import mr.pb.Pair;
import mr.pb.ReduceWorkerGrpc;

/**
 * Phases (9) reduce and (10) write — the end of the pipeline.
 *
 * <p>Deliberately the dullest machine in the fleet, and that is a design claim
 * rather than an accident. Everything hard has already happened: the grouping is
 * done, the partition is exactly this machine's, and what is left is a fold over
 * values that share a key. A reduce that has to do more than that is a reduce
 * that was given the wrong bucket.
 *
 * <p>It still runs out of disk, because (10) writes an output file per partition
 * and a partition's size is not something anybody chose. And it still holds its
 * answer, because somebody has to until the master collects it — so a machine
 * given four partitions holds four answers, which is the argument for giving it
 * fewer rather than for buying it more memory.
 */
public final class ReduceMachine extends ReduceWorkerGrpc.ReduceWorkerImplBase {

    /** What one key costs in the output file. */
    static final int BYTES_PER_KEY = 64;

    /** Held per key, so what this machine keeps is real and the heap walk finds it. */
    static final int PER_KEY = 90_000;

    private final Map<Integer, Map<String, Integer>> outputs = new ConcurrentHashMap<>();
    private final Map<String, long[]> held = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> committed = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------- (9)

    @Takes(refMs = 5, refNsPerRecord = 45_000)
    @Override public void reduce(Bucket bucket, StreamObserver<Output> out) {
        try {
            out.onNext(run(bucket));
            out.onCompleted();
        } catch (RuntimeException e) {
            Reporting.tell(mr.pb.Phase.REDUCE, e.getMessage());
            out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(Losim.current().machine() + ": " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private Output run(Bucket bucket) {
        var counts = new TreeMap<String, Integer>();
        for (Pair p : bucket.getPairsList()) counts.merge(p.getKey(), p.getValue(), Integer::sum);
        Losim.current().records(bucket.getPairsCount());

        outputs.put(bucket.getPart(), counts);
        for (String key : counts.keySet()) held.computeIfAbsent(key, k -> new long[PER_KEY / 8]);

        // (10) write. Throws when the volume is full, and the partition is then
        // simply not written — which is a job that did not finish, not a job that
        // finished wrongly. Those are different outcomes and a simulator that
        // rounded one into the other would be no use for deciding anything.
        long bytes = (long) counts.size() * BYTES_PER_KEY;
        Losim.current().wroteDisk(bytes);

        Losim.current().reveal("part", bucket.getPart());
        Losim.current().reveal("keys", counts.size());
        Losim.current().reveal("holding", outputs.size());

        return Output.newBuilder().setPart(bucket.getPart()).setKeys(counts.size())
                .setBytes(bytes).putAllCounts(counts).build();
    }

    // ------------------------------------------------------------------- (10b)

    /**
     * Makes a partition's output final.
     *
     * <p>Not declared idempotent in the {@code .proto}, on purpose. Committing an
     * output twice is a real decision with a real consequence, and a scenario that
     * wants to retry it has to write {@code unsafe: true} where a reader will see
     * it — which is the entire point of putting idempotency in the schema rather
     * than in a comment.
     */
    @Takes(refMs = 2)
    @Override public void commit(Output request, StreamObserver<Empty> out) {
        boolean again = committed.put(request.getPart(), true) != null;
        Losim.current().reveal("committed", committed.size());
        if (again) Losim.current().log("partition " + request.getPart()
                + " was committed twice; this method never said that was safe");
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }
}
