import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import losim.api.Losim;
import losim.api.Takes;
import mr.pb.Contribution;
import mr.pb.Fetch;
import mr.pb.RankMapperGrpc;
import mr.pb.Region;
import mr.pb.Portion;
import mr.pb.Slice;
import mr.pb.MapRound;
import mr.pb.Vertex;

/**
 * The map half of one PageRank iteration.
 *
 * <p>A page with rank {@code r} and {@code k} out-links sends {@code r/k} to each
 * of them. That is the entire computation, and it is trivial — which is exactly
 * why this machine is worth watching. What it spends its time and its disk on is
 * not the arithmetic.
 *
 * <h2>The graph rides along</h2>
 *
 * The contribution says "page 41 gets 0.003". It does not say where page 41
 * points, and the machine that computes page 41's next rank needs to know,
 * because the iteration after that one has to send it onwards. So the out-links
 * are shipped with the contributions, every round, to a machine that will ship
 * them back next round.
 *
 * <p>Nobody wants that. It happens because MapReduce has no state between
 * iterations: the reducer that computed page 41 last time is not the reducer that
 * will compute it this time and would not remember it if it were. The graph is
 * therefore re-read, re-written and re-shuffled once per iteration, and ten
 * iterations cost ten times the disk of one.
 *
 * <p>That is the answer to "is this programming model suitable for iterative
 * algorithms", and it is a measurement rather than an opinion: run pr-ten against
 * pr-two and divide.
 */
public final class RankMapMachine extends RankMapperGrpc.RankMapperImplBase {

    /** What a contribution costs on disk: a page id, a double, and the framing. */
    static final int BYTES_PER_CONTRIBUTION = 40;

    /** What carrying one vertex's structure costs, per out-link. */
    static final int BYTES_PER_LINK = 24;

    /** iteration and part, to what was written for it. */
    private final Map<Long, Portion> disk = new ConcurrentHashMap<>();

    private static long slot(int iteration, int task, int part) {
        return ((long) iteration << 40) | ((long) task << 20) | part;
    }

    @Takes(refMs = 3, refNsPerRecord = 40_000)
    @Override public void spread(Slice slice, StreamObserver<MapRound> out) {
        try {
            out.onNext(run(slice));
            out.onCompleted();
        } catch (RuntimeException e) {
            Reporting.tell(mr.pb.Phase.MAP, e.getMessage());
            out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(Losim.current().machine() + ": " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private MapRound run(Slice slice) {
        int parts = Math.max(1, slice.getParts());
        var contributions = new ArrayList<List<Contribution>>(parts);
        var carried = new ArrayList<List<Vertex>>(parts);
        for (int p = 0; p < parts; p++) { contributions.add(new ArrayList<>()); carried.add(new ArrayList<>()); }

        long sent = 0;
        for (Vertex v : slice.getVerticesList()) {
            // The vertex itself goes to whoever owns it, so that its next rank is
            // computed somewhere that knows where it points.
            carried.get(Web.partition(v.getId(), parts)).add(v);

            int k = v.getOutCount();
            if (k == 0) continue;                       // a dangling page keeps its rank to itself
            double each = v.getRank() / k;
            for (int to : v.getOutList()) {
                contributions.get(Web.partition(to, parts))
                        .add(Contribution.newBuilder().setTo(to).setAdd(each).build());
                sent++;
            }
        }
        Losim.current().records(slice.getVerticesCount());

        var regions = new ArrayList<Region>(parts);
        for (int p = 0; p < parts; p++) {
            long bytes = (long) contributions.get(p).size() * BYTES_PER_CONTRIBUTION;
            for (Vertex v : carried.get(p)) bytes += (long) (1 + v.getOutCount()) * BYTES_PER_LINK;

            // Once per iteration, for the whole graph. This is the line that makes
            // ten iterations cost ten times the disk.
            Losim.current().wroteDisk(bytes);

            disk.put(slot(slice.getIteration(), slice.getTask(), p),
                     Portion.newBuilder().addAllContributions(contributions.get(p))
                          .addAllCarried(carried.get(p)).build());
            regions.add(Region.newBuilder().setTask(slice.getTask()).setPart(p)
                    .setPairs(contributions.get(p).size()).setBytes(bytes).build());
        }

        Losim.current().reveal("iteration", slice.getIteration());
        Losim.current().reveal("contributions", sent);
        Losim.current().reveal("rounds", disk.size());
        return MapRound.newBuilder().setTask(slice.getTask())
                .setWorker(Losim.current().machine())
                .setIteration(slice.getIteration())
                .addAllRegions(regions).setContributions(sent).build();
    }

    @Takes(refMs = 1, refNsPerRecord = 3_000)
    @Override public void share(Fetch request, StreamObserver<Portion> out) {
        Portion region = disk.get(slot(request.getIteration(), request.getTask(), request.getPart()));
        if (region == null) {
            out.onError(Status.NOT_FOUND
                    .withDescription(Losim.current().machine() + " has nothing for iteration "
                            + request.getIteration() + " task " + request.getTask()
                            + " part " + request.getPart())
                    .asRuntimeException());
            return;
        }
        Losim.current().records(region.getContributionsCount());
        Losim.current().reveal("shared", region.getContributionsCount());
        out.onNext(region);
        out.onCompleted();
    }
}
