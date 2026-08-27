import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.api.Takes;
import mr.pb.Bucket;
import mr.pb.Gather;
import mr.pb.MapWorkerGrpc;
import mr.pb.Pair;
import mr.pb.Pairs;
import mr.pb.Fetch;
import mr.pb.ShuffleWorkerGrpc;

/**
 * Phases (7) remote read and (8) sort — the machine that runs out of memory.
 *
 * <p>This is the only handler in the system that calls other machines. Everything
 * else answers the master; a shuffler goes and fetches, from every map worker
 * that wrote a piece of its partition, which is why the shuffle is the step that
 * turns a fleet into a network. R partitions × M map tasks is R×M connections,
 * and if the machines are spread across zones then most of those bytes are paying
 * the cross-zone rate. That single fact is what data locality is about, and it is
 * the master's placement — not this code — that decides it.
 *
 * <h2>Why this is where memory runs out</h2>
 *
 * Grouping by key means holding the group. A mapper's memory is bounded by its
 * split; a shuffler's is bounded by how many distinct keys landed in its
 * partition, which nobody chose and nothing bounds. Zipf makes that uneven on
 * purpose: hashing into R buckets does not give R equal buckets, and the shuffler
 * that drew "the" is the one that dies. It dies in this code, holding real
 * objects, measured by the heap walk — not because a counter said it should.
 *
 * <h2>Why a fetch failure is returned rather than thrown</h2>
 *
 * A region that is not there is not this machine's problem and this machine
 * cannot fix it. It is news about the map phase, and only the master can act on
 * it, so it comes back in the answer as {@code lost} rather than as an error.
 */
public final class ShuffleMachine extends ShuffleWorkerGrpc.ShuffleWorkerImplBase {

    /** How long to wait on one region before deciding that worker is not going to answer. */
    private static final int PULL_DEADLINE_REFMS = 1200;

    /**
     * What holding a distinct key really costs this machine, in bytes. Small so
     * that the machines can be small too — the ratio is what the scale model
     * preserves, not the size.
     */
    static final int PER_KEY = 240_000;

    /** Every partition this machine has grouped, and is still holding. */
    private final Map<Integer, Map<String, Integer>> grouped = new ConcurrentHashMap<>();

    /** The weight of it. Real objects, so the heap walk finds them the way it would find yours. */
    private final Map<String, long[]> held = new ConcurrentHashMap<>();

    @Takes(refMs = 6, refNsPerRecord = 30_000)
    @Override public void sort(Gather g, StreamObserver<Bucket> out) {
        try {
            out.onNext(run(g));
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (RuntimeException e) {
            Reporting.tell(mr.pb.Phase.SHUFFLE, e.getMessage());
            out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(Losim.current().machine() + ": " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private Bucket run(Gather g) {
        int part = g.getPart();
        var lost = new ArrayList<Integer>();
        var fetched = new ArrayList<Pair>();

        // (7) remote read. One call per map task, because that is how many places
        // this partition is scattered over — there is no way to ask for "my
        // partition" without asking every machine that might hold a piece of it.
        for (int i = 0; i < g.getTasksCount(); i++) {
            int task = g.getTasks(i);
            String holder = i < g.getHoldersCount() ? g.getHolders(i) : null;
            if (holder == null) { lost.add(task); continue; }
            try {
                Pairs got = MapWorkerGrpc.newBlockingStub(Losim.current().channelTo(holder))
                        .withDeadlineAfter(PULL_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .pull(Fetch.newBuilder().setTask(task).setPart(part).build());
                fetched.addAll(got.getPairsList());
            } catch (StatusRuntimeException e) {
                // Three different things arrive here — the worker is dead, the link
                // is cut, the region is gone — and the caller cannot tell them apart
                // except by the status. All three mean the same thing to the master:
                // that map task has to be run again.
                lost.add(task);
                Reporting.tell(mr.pb.Phase.SHUFFLE,
                        "task " + task + " from " + holder + ": " + e.getStatus().getCode());
            }
        }

        Losim.current().records(fetched.size());

        // (8) sort and group. A TreeMap, not a HashMap: every machine in the fleet
        // has to iterate in the same order, or the same code gives two readers two
        // different pictures of the same run.
        var group = grouped.computeIfAbsent(part, p -> new TreeMap<>());
        synchronized (group) {
            group.clear();                                // a re-gather replaces, never adds
            for (Pair p : fetched) group.merge(p.getKey(), p.getValue(), Integer::sum);
            for (String key : group.keySet())
                held.computeIfAbsent(key, k -> new long[PER_KEY / 8]);
        }

        // The spill. A shuffle that does not fit in memory goes to disk, and this
        // machine writes what it grouped whether or not it had to — which is why a
        // shuffler with a small volume fills it long before it fills its memory.
        Losim.current().wroteDisk((long) group.size() * MapMachine.BYTES_PER_PAIR);

        Losim.current().reveal("part", part);
        Losim.current().reveal("distinctKeys", group.size());
        Losim.current().reveal("lostTasks", lost.size());

        var pairs = new ArrayList<Pair>(group.size());
        group.forEach((k, v) -> pairs.add(Pair.newBuilder().setKey(k).setValue(v).build()));
        return Bucket.newBuilder().setPart(part).addAllPairs(pairs)
                .setDistinct(group.size()).addAllLost(lost).build();
    }

    /** What a shuffler is holding across every partition it has been given. */
    List<Integer> partitions() { return new ArrayList<>(grouped.keySet()); }
}
