import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Job;
import mr.pb.Bucket;
import mr.pb.Empty;
import mr.pb.Gather;
import mr.pb.MapDone;
import mr.pb.MapWorkerGrpc;
import mr.pb.Output;
import mr.pb.Pair;
import mr.pb.Power;
import mr.pb.ReduceWorkerGrpc;
import mr.pb.RosterGrpc;
import mr.pb.ShuffleWorkerGrpc;
import mr.pb.Split;

/**
 * The master: all ten phases of MapReduce, over gRPC, on a fleet that is lying to
 * it about being healthy.
 *
 * <p>The 2025 assignment stops at map and reduce, which is the right place to stop
 * when the lesson is what an RPC is. This one keeps going, because every phase it
 * leaves out is a place a real machine runs out of something:
 *
 * <pre>
 *   (0) census        ask every worker what it is made of
 *   (1) split         cut the input into M pieces, sized to the machines
 *   (2) assign        hand each piece to a map worker
 *   (3) read      \
 *   (4) map        |  one call, on the map worker
 *   (5) combine    |
 *   (6) local write/  R regions, on that worker's own disk
 *   (7) remote read   a shuffler fetches region p from every map worker
 *   (8) sort          group by key, in the shuffler's memory
 *   (9) reduce        fold the values that share a key
 *  (10) write         one output file per partition
 * </pre>
 *
 * <h2>What the master does not know</h2>
 *
 * Everything below follows from one fact: there is no way to ask whether a machine
 * is alive. You call it, and either an answer arrives or your deadline passes, and
 * the two most interesting failures are indistinguishable from the third. A worker
 * that died mid-map, a worker behind a partition, and a worker that is merely
 * eight times slower than it was a second ago all look exactly the same from here.
 *
 * <p>So the census is stale the moment it is taken, every call carries a deadline,
 * and a task that does not come back is redone somewhere else — where "somewhere
 * else" is a decision, because redoing it on the machine that was too small for it
 * the first time is not progress.
 *
 * <h2>The expensive lesson</h2>
 *
 * A map task that finished is not a map task that stays finished. Its output is on
 * the worker's local disk, so when that worker dies the regions go with it, and
 * the shuffler discovers this in phase (7) — long after the map phase was declared
 * over. There is nothing to do but run the map task again. That is not a flaw in
 * the design; it is the price of the master never relaying the data, and it is
 * what makes {@code lost} a field in the shuffler's answer rather than an
 * exception.
 */
public class MapReduce implements Job {

    // Written in reference milliseconds like every duration in losim, so they mean
    // the same thing at any compression: 800 refMs is 800 ms of the simulated
    // world, not 800 ms of your afternoon.
    static final int MAP_DEADLINE_REFMS     = 2500;
    static final int SHUFFLE_DEADLINE_REFMS = 6000;
    static final int REDUCE_DEADLINE_REFMS  = 3000;
    // Generous for a call that does nothing, and deliberately so. The first call
    // through a fresh channel pays for every class on the path being loaded and
    // compiled, which is tens of milliseconds of host time and has nothing to do
    // with the machine being asked. A tight deadline here does not measure the
    // fleet; it measures the JIT, and it drops the first machine asked every time.
    static final int CENSUS_DEADLINE_REFMS  = 4000;

    /** Words per line of the corpus. */
    static final int WORDS_PER_LINE = 8;

    /** How large a vocabulary the corpus is drawn from. */
    static final int VOCAB = 4000;

    /** Zipf's exponent. Above 1 the head dominates, which is what skews the partitions. */
    static final double SKEW = 1.08;

    // ------------------------------------------------------------------- the job

    @Override public void run(Cluster cluster) throws Exception {
        List<String> mapperNames  = cluster.serving("MapWorker");
        List<String> shuffleNames = cluster.serving("ShuffleWorker");
        List<String> reducerNames = cluster.serving("ReduceWorker");
        if (mapperNames.isEmpty() || reducerNames.isEmpty())
            throw new IllegalStateException("this fleet has no pipeline: "
                    + mapperNames.size() + " map workers and " + reducerNames.size()
                    + " reduce workers");
        // A fleet with no dedicated shufflers makes the reducers do their own
        // fetching, which is the classic shape. Both are real architectures and the
        // difference between them is visible in the trace as an extra column.
        if (shuffleNames.isEmpty()) shuffleNames = reducerNames;

        Map<String, Power> power = census(cluster,
                union(mapperNames, shuffleNames, reducerNames));

        int parts = reducerNames.size();
        List<Split> splits = split(cluster, mapperNames, power, parts);

        Map<Integer, MapDone> done = mapPhase(cluster, splits, mapperNames, power);

        Map<Integer, Bucket> buckets = shufflePhase(
                cluster, done, splits, shuffleNames, power, parts, mapperNames);

        Map<String, Integer> answer = reducePhase(cluster, buckets, reducerNames, power);

        try (var phase = cluster.phase("collect")) {
            phase.note("keys", answer.size());
            phase.note("total", answer.values().stream().mapToLong(Integer::longValue).sum());
        }
        cluster.done(new TreeMap<>(answer));
    }

    // ------------------------------------------------------------- (0) census

    /**
     * Asks every worker what it is made of.
     *
     * <p>One round of calls, with a short deadline, and whatever does not answer is
     * simply not in the table. That is not an oversight — a machine that cannot
     * answer a one-millisecond call is a machine you should think twice about
     * sending a map task to, and the census failing is itself the first useful
     * thing the master learns.
     */
    private Map<String, Power> census(Cluster cluster, List<String> workers)
            throws InterruptedException {
        var table = new ConcurrentHashMap<String, Power>();
        try (var phase = cluster.phase("census")) {
            // All at once. A scheduler that polls its fleet one machine at a time
            // takes as long as the sum of its timeouts, and the one machine that is
            // wedged delays every healthy machine behind it — which is a real
            // failure mode and not one worth reproducing in the cheapest call in
            // the system.
            var latch = new CountDownLatch(workers.size());
            for (String name : workers) {
                RosterGrpc.newStub(cluster.channelTo(name))
                        .withDeadlineAfter(CENSUS_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .capacity(Empty.getDefaultInstance(), new StreamObserver<Power>() {
                            @Override public void onNext(Power p) { table.put(name, p); }
                            @Override public void onError(Throwable t) {
                                cluster.log(name + " did not answer the census; it will be "
                                        + "given work as though it were an ordinary machine");
                                latch.countDown();
                            }
                            @Override public void onCompleted() { latch.countDown(); }
                        });
            }
            latch.await(60, TimeUnit.SECONDS);
            phase.note("asked", workers.size());
            phase.note("answered", table.size());
            phase.note("cores", table.values().stream().mapToInt(Power::getVcpu).sum());
            phase.note("zones", table.values().stream().map(Power::getZone).distinct().count());
        }
        return table;
    }

    // ------------------------------------------------- (1) split, sized to fit

    /**
     * Cuts the input into one split per map worker, each sized to the machine that
     * will get it.
     *
     * <p>This is the whole of "send small work to small machines", and it is worth
     * being clear about why it is done here rather than by a queue. A work queue
     * self-balances and needs no census at all — a fast machine simply takes more
     * pieces. It also hides the decision: every machine ends up with the same
     * *number* of pieces in the trace and the difference shows up only as timing.
     * Sizing the split makes the placement a fact about the run that can be read,
     * argued with, and got wrong.
     *
     * <p>Got wrong in a specific way, too: this proportions by cores and ignores
     * memory, so a sixteen-core machine with a tiny volume is handed the biggest
     * split and fills its disk with it. That failure is in {@link #weight}, which
     * is one line and is meant to be beaten.
     */
    private List<Split> split(Cluster cluster, List<String> mappers,
                              Map<String, Power> power, int parts) {
        long lines = Math.max(mappers.size(), cluster.records());
        var corpus = cluster.compute("read the input",
                () -> new Corpus(VOCAB, SKEW, cluster.seed()).lines((int) lines, WORDS_PER_LINE));

        var splits = new ArrayList<Split>();
        try (var phase = cluster.phase("split")) {
            double total = 0;
            for (String m : mappers) total += weight(power.get(m));

            int at = 0;
            for (int i = 0; i < mappers.size(); i++) {
                double share = weight(power.get(mappers.get(i))) / total;
                int take = (i == mappers.size() - 1)
                        ? corpus.size() - at
                        : Math.max(1, (int) Math.round(corpus.size() * share));
                take = Math.min(take, corpus.size() - at);
                if (take <= 0) continue;
                splits.add(Split.newBuilder()
                        .setTask(splits.size())
                        .setText(String.join(" ", corpus.subList(at, at + take)))
                        .setLines(take)
                        .setParts(parts)
                        .build());
                at += take;
            }
            phase.note("lines", corpus.size());
            phase.note("tasks", splits.size());
            phase.note("parts", parts);
            phase.note("biggest", splits.stream().mapToInt(Split::getLines).max().orElse(0));
            phase.note("smallest", splits.stream().mapToInt(Split::getLines).min().orElse(0));
        }
        return splits;
    }

    /**
     * How much of the input one machine should be given.
     *
     * <p>Cores, and nothing else. Deliberately the obvious first answer, so that a
     * scenario where it is the wrong one has something to be wrong about — see
     * {@link MapReduceBlind}, which does not ask at all, and the disk-bound
     * scenarios, where proportioning by cores is exactly how the biggest machine
     * ends up filling the smallest volume.
     */
    protected double weight(Power p) {
        return p == null ? 1.0 : Math.max(0.25, p.getVcpu() / 2.0);
    }

    // ------------------------------------------------------- (2)–(6) the map phase

    private Map<Integer, MapDone> mapPhase(Cluster cluster, List<Split> splits,
                                           List<String> mappers, Map<String, Power> power)
            throws InterruptedException {
        var done = new ConcurrentHashMap<Integer, MapDone>();
        var failed = ConcurrentHashMap.<Integer>newKeySet();

        try (var phase = cluster.phase("map")) {
            phase.note("tasks", splits.size());
            phase.note("workers", mappers.size());

            // Fanned out on an async stub and waited on with a latch, which is how
            // you run M calls at once over gRPC without a thread for each of them.
            var latch = new CountDownLatch(splits.size());
            for (int i = 0; i < splits.size(); i++) {
                Split split = splits.get(i);
                String worker = mappers.get(i % mappers.size());
                MapWorkerGrpc.newStub(cluster.channelTo(worker))
                        .withDeadlineAfter(MAP_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .map(split, collect(done, failed, split.getTask(), latch));
            }
            latch.await(120, TimeUnit.SECONDS);

            // Whatever did not come back is redone, and not where it was. The
            // master was told nothing about why — that is the whole of what it has
            // to work with — but it does know which machines have already answered
            // something, and preferring one of those is the difference between
            // retrying and retrying into the same wall.
            var missing = splits.stream().filter(s -> !done.containsKey(s.getTask())).toList();
            if (!missing.isEmpty()) {
                phase.note("redone", missing.size());
                cluster.log(missing.size() + " map tasks did not come back; redoing them elsewhere");
                for (Split split : missing) redo(cluster, split, mappers, power, done, failed);
            }
            phase.note("finished", done.size());
            phase.note("emitted", done.values().stream().mapToLong(MapDone::getEmitted).sum());
        }
        return done;
    }

    /**
     * Runs one map task somewhere it has not already failed.
     *
     * <p>Blocking, one machine at a time, because this is the slow path and there
     * is nothing to be gained from being clever in it. What matters is the order:
     * machines that have answered before, biggest first, and never the one that
     * just refused.
     */
    private void redo(Cluster cluster, Split split, List<String> mappers,
                      Map<String, Power> power, Map<Integer, MapDone> done,
                      Set<Integer> failed) {
        var order = new ArrayList<>(mappers);
        order.sort(Comparator.comparingDouble((String m) -> -weight(power.get(m))));
        for (String worker : order) {
            try {
                done.put(split.getTask(), MapWorkerGrpc.newBlockingStub(cluster.channelTo(worker))
                        .withDeadlineAfter(MAP_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .map(split));
                failed.remove(split.getTask());
                return;
            } catch (RuntimeException e) {
                // That one is gone, or full, or too slow. Try the next; the answer
                // is exact or the job has not finished, and there is no third
                // outcome worth reporting.
            }
        }
        cluster.log("map task " + split.getTask() + " could not be placed on any machine");
    }

    private static StreamObserver<MapDone> collect(Map<Integer, MapDone> done,
                                                   Set<Integer> failed, int task,
                                                   CountDownLatch latch) {
        return new StreamObserver<>() {
            @Override public void onNext(MapDone d) { done.put(task, d); }
            @Override public void onError(Throwable t) { failed.add(task); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        };
    }

    // ----------------------------------------------------- (7)(8) the shuffle

    private Map<Integer, Bucket> shufflePhase(Cluster cluster, Map<Integer, MapDone> done,
                                              List<Split> splits, List<String> shufflers,
                                              Map<String, Power> power, int parts,
                                              List<String> mappers) throws InterruptedException {
        var buckets = new ConcurrentHashMap<Integer, Bucket>();
        try (var phase = cluster.phase("shuffle")) {
            phase.note("parts", parts);
            phase.note("shufflers", shufflers.size());
            int refetched = 0;

            // Every partition at once. R gathers, each fetching from M workers, is
            // R×M calls in flight — which is what the shuffle actually is, and
            // running them one partition at a time would make the trace show a
            // fleet politely taking turns at the busiest moment of the run.
            // On async stubs and a latch, not on a thread pool of the master's own.
            // Work started on a thread losim did not make belongs to no machine:
            // its memory and its CPU are attributed to nobody and the verifier says
            // so. gRPC's own callback threads are already accounted for, so waiting
            // on a latch costs one blocked thread instead of R unaccounted ones.
            var placed = new LinkedHashMap<Integer, String>();
            var latch = new CountDownLatch(parts);
            for (int part = 0; part < parts; part++) {
                final int p = part;
                Gather gather = gatherFor(done, p);
                String where = pickShuffler(shufflers, power, gather, p);
                placed.put(p, where);
                ShuffleWorkerGrpc.newStub(cluster.channelTo(where))
                        .withDeadlineAfter(SHUFFLE_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                        .sort(gather, new StreamObserver<Bucket>() {
                            @Override public void onNext(Bucket b) { buckets.put(p, b); }
                            @Override public void onError(Throwable t) {
                                cluster.log("shuffler " + where + " failed partition " + p
                                        + ": " + io.grpc.Status.fromThrowable(t).getCode());
                                latch.countDown();
                            }
                            @Override public void onCompleted() { latch.countDown(); }
                        });
            }
            latch.await(120, TimeUnit.SECONDS);

            for (int part = 0; part < parts; part++) {
                Bucket bucket = buckets.get(part);
                String where = placed.getOrDefault(part, shufflers.get(part % shufflers.size()));

                // A fetch failure is news about the map phase, not about the
                // shuffler. Run those map tasks again — somewhere their output will
                // still be there — and ask once more.
                if (bucket != null && bucket.getLostCount() > 0) {
                    refetched += bucket.getLostCount();
                    cluster.log("partition " + part + ": " + bucket.getLostCount()
                            + " map outputs were gone; re-running those tasks");
                    for (int task : bucket.getLostList()) {
                        Split again = splits.stream()
                                .filter(s -> s.getTask() == task).findFirst().orElse(null);
                        if (again != null)
                            redo(cluster, again, mappers, power, done,
                                 ConcurrentHashMap.newKeySet());
                    }
                    bucket = sort(cluster, where, gatherFor(done, part));
                    if (bucket != null) buckets.put(part, bucket);
                }
            }
            phase.note("gathered", buckets.size());
            phase.note("refetched", refetched);
            phase.note("distinct", buckets.values().stream()
                    .mapToInt(Bucket::getDistinct).sum());
        }
        return buckets;
    }

    private Bucket sort(Cluster cluster, String where, Gather gather) {
        try {
            return ShuffleWorkerGrpc.newBlockingStub(cluster.channelTo(where))
                    .withDeadlineAfter(SHUFFLE_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                    .sort(gather);
        } catch (StatusRuntimeException e) {
            cluster.log("shuffler " + where + " failed partition " + gather.getPart()
                    + ": " + e.getStatus().getCode());
            return null;
        }
    }

    /** Which map workers hold a piece of this partition, in map-task order. */
    private static Gather gatherFor(Map<Integer, MapDone> done, int part) {
        var g = Gather.newBuilder().setPart(part);
        done.values().stream().sorted(Comparator.comparingInt(MapDone::getTask)).forEach(d -> {
            g.addTasks(d.getTask());
            g.addHolders(d.getWorker());
        });
        return g.build();
    }

    /**
     * Where to group partition p.
     *
     * <p>Data locality, and this is the one place in the system where it is a
     * decision rather than a consequence. The shuffle is R×M connections carrying
     * every intermediate byte in the run, so a shuffler in the same zone as most of
     * the map workers pays the same-zone latency for most of them and a shuffler in
     * the wrong zone pays the cross-zone rate for nearly all of them. Nothing else
     * about the run changes. The bill does.
     */
    protected String pickShuffler(List<String> shufflers, Map<String, Power> power,
                                  Gather gather, int part) {
        var weightByZone = new LinkedHashMap<String, Integer>();
        for (String holder : gather.getHoldersList()) {
            Power p = power.get(holder);
            if (p != null) weightByZone.merge(p.getZone(), 1, Integer::sum);
        }
        // Ranked, then taken round-robin down the ranking rather than always from
        // the top. Locality is a preference and not an instruction: sending every
        // partition to the one machine in the busiest zone would win every
        // cross-zone byte and lose the entire shuffle to one machine's memory.
        var ranked = new ArrayList<>(shufflers);
        ranked.sort(Comparator
                .comparingInt((String s) -> {
                    Power p = power.get(s);
                    return p == null ? 0 : -weightByZone.getOrDefault(p.getZone(), 0);
                })
                .thenComparingDouble(s -> {
                    Power p = power.get(s);
                    return p == null ? 0 : -p.getMemoryMb();
                }));
        return ranked.get(part % ranked.size());
    }

    // ------------------------------------------------------ (9)(10) the reduce

    private Map<String, Integer> reducePhase(Cluster cluster, Map<Integer, Bucket> buckets,
                                             List<String> reducers, Map<String, Power> power) {
        var answer = new TreeMap<String, Integer>();
        try (var phase = cluster.phase("reduce")) {
            phase.note("buckets", buckets.size());
            phase.note("reducers", reducers.size());
            int local = 0;
            Map<Integer, String> placed = placeReducers(reducers, power, buckets);
            phase.note("busiest", placed.values().stream().distinct().count());

            for (var entry : buckets.entrySet()) {
                int part = entry.getKey();
                Bucket bucket = entry.getValue();
                String where = placed.getOrDefault(part, reducers.get(part % reducers.size()));
                try {
                    Output out = ReduceWorkerGrpc.newBlockingStub(cluster.channelTo(where))
                            .withDeadlineAfter(REDUCE_DEADLINE_REFMS, TimeUnit.MILLISECONDS)
                            .reduce(bucket);
                    out.getCountsMap().forEach((k, v) -> answer.merge(k, v, Integer::sum));

                    // Fire-and-forget: Empty on an async stub, so the master does not
                    // wait to be told the file is final. Not a second messaging path —
                    // an ordinary call with ordinary interceptors, costing ordinary
                    // bytes. The only thing that changed is who waits.
                    ReduceWorkerGrpc.newStub(cluster.channelTo(where))
                            .commit(out, new StreamObserver<Empty>() {
                                @Override public void onNext(Empty e) { }
                                @Override public void onError(Throwable t) { }
                                @Override public void onCompleted() { }
                            });
                } catch (RuntimeException e) {
                    // The reducer did not answer, and nobody said why. Fold the
                    // bucket here instead: slower, on a machine that was not chosen
                    // for it, and correct — which is the trade every coordinator
                    // eventually makes.
                    local++;
                    cluster.log("partition " + part + " failed on " + where + "; folding it here");
                    var folded = cluster.compute("fold partition " + part + " locally", () -> {
                        var out = new TreeMap<String, Integer>();
                        for (Pair p : bucket.getPairsList())
                            out.merge(p.getKey(), p.getValue(), Integer::sum);
                        return out;
                    });
                    folded.forEach((k, v) -> answer.merge(k, v, Integer::sum));
                }
            }
            phase.note("foldedLocally", local);
            phase.note("keys", answer.size());
        }
        return answer;
    }

    /**
     * Which reducer folds partition p.
     *
     * <p>By memory, because a reducer holds its answer until the master collects
     * it and a machine given four partitions holds four of them. Biggest partition
     * to roomiest machine is the obvious rule, and it is obviously not enough: it
     * says nothing about the disk the output is written to, which is the other way
     * this phase fails.
     */
    protected Map<Integer, String> placeReducers(List<String> reducers,
                                                 Map<String, Power> power,
                                                 Map<Integer, Bucket> buckets) {
        // Biggest partition to the roomiest machine, then the next, and so on round
        // the fleet. Both orders matter and for different reasons: without sorting
        // the partitions this is round-robin with extra steps, and without cycling
        // the machines every partition goes to the single roomiest one and the
        // fleet reduces on one machine.
        var byMemory = new ArrayList<>(reducers);
        byMemory.sort(Comparator.comparingDouble(r -> {
            Power p = power.get(r);
            return p == null ? 0 : -p.getMemoryMb();
        }));
        var bySize = new ArrayList<>(buckets.keySet());
        bySize.sort(Comparator.comparingInt(part -> -buckets.get(part).getDistinct()));

        var out = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < bySize.size(); i++)
            out.put(bySize.get(i), byMemory.get(i % byMemory.size()));
        return out;
    }

    // ------------------------------------------------------------------ utility

    @SafeVarargs
    private static List<String> union(List<String>... lists) {
        var out = new LinkedHashSet<String>();
        for (List<String> l : lists) out.addAll(l);
        return new ArrayList<>(out);
    }
}
