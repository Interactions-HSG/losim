import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.api.Takes;
import mr.pb.Collect;
import mr.pb.Contribution;
import mr.pb.Fetch;
import mr.pb.RankMapperGrpc;
import mr.pb.RankReducerGrpc;
import mr.pb.Ranked;
import mr.pb.Portion;
import mr.pb.Vertex;

/**
 * Fetch, sum, damp, and hand back the next round's ranks.
 *
 * <p>Fetching and folding are on the same machine here, unlike the word count,
 * and that is the honest shape for this problem rather than a shortcut: a rank
 * reducer needs every contribution to a page before it can compute anything about
 * that page, so putting a network hop between the gathering and the summing buys
 * nothing at all.
 *
 * <h2>What it holds, and for how long</h2>
 *
 * Every vertex in its partition, with its out-links, for the whole iteration —
 * and then it hands them back and holds the next iteration's copy instead. The
 * memory does not accumulate across rounds, which is the one thing about this
 * design that is not wasteful. Everything else about it is: the same graph
 * arrives over the network every round, and leaves again.
 */
public final class RankReduceMachine extends RankReducerGrpc.RankReducerImplBase {

    /** Google's damping factor. The probability a reader follows a link rather than leaving. */
    static final double DAMPING = 0.85;

    private static final int FETCH_DEADLINE_REFMS = 1500;

    /** What holding a page really costs this machine. */
    static final int PER_PAGE = 12_000;

    private final Map<Integer, Map<Integer, Vertex>> holding = new ConcurrentHashMap<>();
    private final Map<Integer, long[]> weight = new ConcurrentHashMap<>();

    /**
     * The sum buffer, kept rather than discarded.
     *
     * <p>This is what makes a hub expensive. Pages are partitioned by id, so every
     * reducer holds the same *number* of pages — but the number of contributions
     * arriving for them is the in-degree, and on a graph built by preferential
     * attachment that is wildly uneven. The partition that owns a page everything
     * points at receives a large fraction of every round's traffic and has to hold
     * it while it adds it up.
     */
    private final Map<Integer, double[]> received = new ConcurrentHashMap<>();

    @Takes(refMs = 4, refNsPerRecord = 22_000)
    @Override public void fold(Collect c, StreamObserver<Ranked> out) {
        try {
            out.onNext(run(c));
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (RuntimeException e) {
            Reporting.tell(mr.pb.Phase.REDUCE, e.getMessage());
            out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(Losim.current().machine() + ": " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private Ranked run(Collect c) {
        var lost = new ArrayList<Integer>();
        var incoming = new HashMap<Integer, Double>();
        var structure = new TreeMap<Integer, Vertex>();

        for (int i = 0; i < c.getTasksCount(); i++) {
            int task = c.getTasks(i);
            String holder = i < c.getHoldersCount() ? c.getHolders(i) : null;
            if (holder == null) { lost.add(task); continue; }
            try {
                Portion got = RankMapperGrpc.newBlockingStub(Losim.current().channelTo(holder))
                        .withDeadlineAfter(FETCH_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .share(Fetch.newBuilder().setTask(task).setPart(c.getPart())
                                    .setIteration(c.getIteration()).build());
                for (Contribution x : got.getContributionsList())
                    incoming.merge(x.getTo(), x.getAdd(), Double::sum);
                for (Vertex v : got.getCarriedList()) structure.put(v.getId(), v);
            } catch (StatusRuntimeException e) {
                lost.add(task);
                Reporting.tell(mr.pb.Phase.SHUFFLE, "iteration " + c.getIteration()
                        + " task " + task + " from " + holder + ": " + e.getStatus().getCode());
            }
        }
        Losim.current().records(structure.size());

        // r = (1 - d)/N + d · Σ contributions. The first term is the reader who
        // stops following links and jumps somewhere at random, and it is what keeps
        // a page nobody points at from having a rank of zero.
        double base = (1 - DAMPING) / Math.max(1, c.getPages());
        double moved = 0;
        var next = new ArrayList<Vertex>(structure.size());
        for (Vertex v : structure.values()) {
            double rank = base + DAMPING * incoming.getOrDefault(v.getId(), 0.0);
            moved += Math.abs(rank - v.getRank());
            next.add(v.toBuilder().setRank(rank).build());
        }

        // Sized by how much arrived, not by how many pages this reducer owns.
        received.put(c.getPart(), new double[Math.max(1, incoming.size() * 64)]);

        var mine = holding.computeIfAbsent(c.getPart(), p -> new ConcurrentHashMap<>());
        mine.clear();
        for (Vertex v : next) {
            mine.put(v.getId(), v);
            weight.computeIfAbsent(v.getId(), k -> new long[PER_PAGE / 8]);
        }

        // The output of an iteration is written, like any other MapReduce output.
        // Once per round, for the whole graph, whether or not anybody reads it.
        Losim.current().wroteDisk((long) next.size() * RankMapMachine.BYTES_PER_LINK * 2);

        Losim.current().reveal("iteration", c.getIteration());
        Losim.current().reveal("pages", next.size());
        Losim.current().reveal("received", incoming.size());
        Losim.current().reveal("delta", Math.round(moved * 1e6) / 1e6);

        return Ranked.newBuilder().setPart(c.getPart()).setIteration(c.getIteration())
                .addAllVertices(next).setDelta(moved).addAllLost(lost).build();
    }
}
