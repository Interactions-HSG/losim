import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Job;
import mr.pb.Collect;
import mr.pb.Empty;
import mr.pb.Power;
import mr.pb.RankMapperGrpc;
import mr.pb.RankReducerGrpc;
import mr.pb.Ranked;
import mr.pb.RosterGrpc;
import mr.pb.Slice;
import mr.pb.MapRound;
import mr.pb.Vertex;

/**
 * PageRank on MapReduce: the same job, run until the numbers stop moving.
 *
 * <p>Everything here is a loop around the pipeline the word count runs once, and
 * the loop is the entire lesson. MapReduce keeps no state between rounds, so each
 * iteration:
 *
 * <ul>
 *   <li>ships the whole graph out to the map workers,</li>
 *   <li>has them write the whole graph to local disk again, partitioned,</li>
 *   <li>has the reducers pull the whole graph back across the network,</li>
 *   <li>and collects the whole graph into the master so it can do it again.</li>
 * </ul>
 *
 * <p>Ten iterations is ten times that. Not ten times the arithmetic — the
 * arithmetic is one division and one addition per link — ten times the disk and
 * ten times the network, for a graph that could have been loaded once and kept.
 * That gap is why Spark exists, and it is measurable here rather than assertable:
 * run {@code pr-two} and {@code pr-ten} and divide the disk lines.
 *
 * <h2>Where it stops</h2>
 *
 * On convergence or on the iteration budget, whichever comes first — and it
 * reports which. A run that stopped because it ran out of iterations has not
 * converged, and saying it had would be the one lie a simulator cannot afford.
 */
public class PageRank implements Job {

    static final int SPREAD_DEADLINE_REFMS = 2500;
    static final int FOLD_DEADLINE_REFMS   = 6000;
    static final int CENSUS_DEADLINE_REFMS = 4000;

    /**
     * How many rounds at most. Written here rather than in the scenario because it
     * is a property of the algorithm, not of the weather — and because a scenario
     * that could set it would be a scenario that could make convergence look easy.
     */
    protected int iterations() { return 4; }

    /** Out-links per page, drawn up to this. */
    protected int linksPerPage() { return 6; }

    /** Stop when the ranks move less than this in a whole round. */
    protected double converged() { return 1e-4; }

    @Override public void run(Cluster cluster) throws Exception {
        List<String> mappers = cluster.serving("RankMapper");
        List<String> reducers = cluster.serving("RankReducer");
        if (mappers.isEmpty() || reducers.isEmpty())
            throw new IllegalStateException("this fleet has no rank pipeline: "
                    + mappers.size() + " mappers and " + reducers.size() + " reducers");

        Map<String, Power> power = census(cluster, mappers, reducers);
        int parts = reducers.size();

        List<Vertex> graph = build(cluster);
        double delta = Double.MAX_VALUE;
        int round = 0;

        while (round < iterations() && delta > converged()) {
            round++;
            List<Slice> slices = slice(cluster, graph, mappers, power, parts, round);
            Map<Integer, MapRound> spread = spreadPhase(cluster, slices, mappers, round);
            var folded = foldPhase(cluster, spread, reducers, power, parts, round, graph.size());

            if (folded.isEmpty()) {
                cluster.log("iteration " + round + " produced nothing; stopping here");
                break;
            }
            delta = folded.values().stream().mapToDouble(Ranked::getDelta).sum();
            graph = folded.values().stream().flatMap(r -> r.getVerticesList().stream())
                    .sorted(Comparator.comparingInt(Vertex::getId)).toList();
        }

        var top = new TreeMap<String, Double>();
        graph.stream().sorted(Comparator.comparingDouble(Vertex::getRank).reversed())
                .limit(10)
                .forEach(v -> top.put("page" + v.getId(), Math.round(v.getRank() * 1e6) / 1e6));

        try (var phase = cluster.phase("collect")) {
            phase.note("iterations", round);
            phase.note("delta", Math.round(delta * 1e6) / 1e6);
            phase.note("converged", delta <= converged());
            phase.note("pages", graph.size());
        }
        cluster.done(top);
    }

    // -------------------------------------------------------------------- setup

    private Map<String, Power> census(Cluster cluster, List<String> mappers,
                                      List<String> reducers) throws InterruptedException {
        var table = new ConcurrentHashMap<String, Power>();
        var all = new ArrayList<>(mappers);
        for (String r : reducers) if (!all.contains(r)) all.add(r);
        try (var phase = cluster.phase("census")) {
            var latch = new CountDownLatch(all.size());
            for (String name : all)
                RosterGrpc.newStub(cluster.channelTo(name))
                        .withDeadlineAfter(CENSUS_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .capacity(Empty.getDefaultInstance(), new StreamObserver<Power>() {
                            @Override public void onNext(Power p) { table.put(name, p); }
                            @Override public void onError(Throwable t) { latch.countDown(); }
                            @Override public void onCompleted() { latch.countDown(); }
                        });
            latch.await(60, TimeUnit.SECONDS);
            phase.note("answered", table.size());
            phase.note("cores", table.values().stream().mapToInt(Power::getVcpu).sum());
        }
        return table;
    }

    private List<Vertex> build(Cluster cluster) {
        try (var phase = cluster.phase("build")) {
            int pages = (int) Math.max(16, cluster.records());
            var web = cluster.compute("build the link graph",
                    () -> new Web(pages, linksPerPage(), cluster.seed()));
            var start = new ArrayList<Vertex>(pages);
            double even = 1.0 / pages;
            for (int i = 0; i < pages; i++) {
                var v = Vertex.newBuilder().setId(i).setRank(even);
                for (int to : web.linksFrom(i)) v.addOut(to);
                start.add(v.build());
            }
            var degrees = web.degrees();
            phase.note("pages", pages);
            phase.note("links", start.stream().mapToInt(Vertex::getOutCount).sum());
            // How lopsided the graph is, which is what decides whether one reducer
            // is about to be given several times its share.
            phase.note("hottest", degrees.isEmpty() ? 0 : degrees.get(0));
            phase.note("median", degrees.isEmpty() ? 0 : degrees.get(degrees.size() / 2));
            return start;
        }
    }

    // ---------------------------------------------------------------- one round

    private List<Slice> slice(Cluster cluster, List<Vertex> graph, List<String> mappers,
                              Map<String, Power> power, int parts, int round) {
        var slices = new ArrayList<Slice>();
        double total = 0;
        for (String m : mappers) total += weight(power.get(m));
        int at = 0;
        for (int i = 0; i < mappers.size(); i++) {
            double share = weight(power.get(mappers.get(i))) / total;
            int take = (i == mappers.size() - 1) ? graph.size() - at
                    : Math.max(1, (int) Math.round(graph.size() * share));
            take = Math.min(take, graph.size() - at);
            if (take <= 0) continue;
            slices.add(Slice.newBuilder().setTask(slices.size()).setIteration(round)
                    .setParts(parts).setPages(graph.size())
                    .addAllVertices(graph.subList(at, at + take)).build());
            at += take;
        }
        return slices;
    }

    /** Cores, as in the word count, and as beatable. */
    protected double weight(Power p) {
        return p == null ? 1.0 : Math.max(0.25, p.getVcpu() / 2.0);
    }

    private Map<Integer, MapRound> spreadPhase(Cluster cluster, List<Slice> slices,
                                             List<String> mappers, int round)
            throws InterruptedException {
        var done = new ConcurrentHashMap<Integer, MapRound>();
        try (var phase = cluster.phase("spread " + round)) {
            phase.note("iteration", round);
            phase.note("tasks", slices.size());
            var latch = new CountDownLatch(slices.size());
            for (int i = 0; i < slices.size(); i++) {
                Slice slice = slices.get(i);
                String worker = mappers.get(i % mappers.size());
                RankMapperGrpc.newStub(cluster.channelTo(worker))
                        .withDeadlineAfter(SPREAD_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .spread(slice, new StreamObserver<MapRound>() {
                            @Override public void onNext(MapRound s) { done.put(slice.getTask(), s); }
                            @Override public void onError(Throwable t) { latch.countDown(); }
                            @Override public void onCompleted() { latch.countDown(); }
                        });
            }
            latch.await(120, TimeUnit.SECONDS);

            // A slice that did not come back is redone somewhere else, exactly as in
            // the word count — and for the same reason, which is that nobody told
            // the master why.
            for (Slice slice : slices) {
                if (done.containsKey(slice.getTask())) continue;
                for (String worker : mappers) {
                    try {
                        done.put(slice.getTask(),
                                RankMapperGrpc.newBlockingStub(cluster.channelTo(worker))
                                        .withDeadlineAfter(SPREAD_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                                        .spread(slice));
                        break;
                    } catch (RuntimeException e) {
                        // Gone, or full. Next.
                    }
                }
            }
            phase.note("finished", done.size());
            phase.note("contributions",
                    done.values().stream().mapToLong(MapRound::getContributions).sum());
        }
        return done;
    }

    private Map<Integer, Ranked> foldPhase(Cluster cluster, Map<Integer, MapRound> spread,
                                           List<String> reducers, Map<String, Power> power,
                                           int parts, int round, int pages)
            throws InterruptedException {
        var out = new ConcurrentHashMap<Integer, Ranked>();
        try (var phase = cluster.phase("fold " + round)) {
            phase.note("iteration", round);
            phase.note("parts", parts);

            var holders = new ArrayList<String>();
            var tasks = new ArrayList<Integer>();
            spread.values().stream().sorted(Comparator.comparingInt(MapRound::getTask))
                    .forEach(s -> { tasks.add(s.getTask()); holders.add(s.getWorker()); });

            var placed = placeReducers(reducers, power, parts);
            var latch = new CountDownLatch(parts);
            for (int part = 0; part < parts; part++) {
                final int p = part;
                String where = placed.getOrDefault(p, reducers.get(p % reducers.size()));
                RankReducerGrpc.newStub(cluster.channelTo(where))
                        .withDeadlineAfter(FOLD_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .fold(Collect.newBuilder().setPart(p).setIteration(round)
                                        .setPages(pages).addAllHolders(holders)
                                        .addAllTasks(tasks).build(),
                              new StreamObserver<Ranked>() {
                                  @Override public void onNext(Ranked r) { out.put(p, r); }
                                  @Override public void onError(Throwable t) {
                                      cluster.log("iteration " + round + " partition " + p
                                              + " failed on " + where + ": "
                                              + io.grpc.Status.fromThrowable(t).getCode());
                                      latch.countDown();
                                  }
                                  @Override public void onCompleted() { latch.countDown(); }
                              });
            }
            latch.await(120, TimeUnit.SECONDS);
            phase.note("folded", out.size());
            phase.note("delta", Math.round(
                    out.values().stream().mapToDouble(Ranked::getDelta).sum() * 1e6) / 1e6);
            phase.note("lost", out.values().stream().mapToInt(Ranked::getLostCount).sum());
        }
        return out;
    }

    /** Partitions round the fleet, roomiest machines first. */
    protected Map<Integer, String> placeReducers(List<String> reducers,
                                                 Map<String, Power> power, int parts) {
        var byMemory = new ArrayList<>(reducers);
        byMemory.sort(Comparator.comparingDouble(r -> {
            Power p = power.get(r);
            return p == null ? 0 : -p.getMemoryMb();
        }));
        var placed = new LinkedHashMap<Integer, String>();
        for (int p = 0; p < parts; p++) placed.put(p, byMemory.get(p % byMemory.size()));
        return placed;
    }
}
