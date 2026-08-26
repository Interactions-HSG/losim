import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.ShufflerGrpc;
import lab.pb.WorkerGrpc;
import losim.api.Cluster;
import losim.api.Job;

/**
 * Split, map, shuffle, reduce — the pipeline most of the suite's harder cases run on.
 *
 * <p>The map phase fans out on an async stub and waits on a latch, which is how you
 * fan out over gRPC without a thread per call. The reduce phase blocks, because the
 * interesting thing about it is what happens when a call never comes back: the
 * coordinator waits out its own deadline, learns nothing about why, and does the
 * work itself.
 */
public final class WordCount implements Job {

    /** Fixed, so an assertion can compute the right answer independently. */
    public static final String[] CORPUS = {
        "the cat sat on the mat",  "the dog sat on the log",  "a bird and a cat",
        "the cat and the dog",     "a log and a mat",         "the bird and the cat",
        "a cat a dog a bird",      "the mat the log the cat",
    };

    /** What the answer has to be, counted here so the fleet's answer can be checked. */
    public static Map<String, Integer> truth() {
        var out = new TreeMap<String, Integer>();
        for (String line : CORPUS)
            for (String w : line.split(" ")) out.merge(w, 1, Integer::sum);
        return out;
    }

    private static Chunk chunk(int i) {
        return Chunk.newBuilder().setText(CORPUS[i]).setLines(1).build();
    }

    @Override public void run(Cluster cluster) throws Exception {
        List<String> mappers = cluster.serving("Worker");
        List<String> reducers = cluster.serving("Shuffler");
        if (mappers.isEmpty() || reducers.isEmpty())
            throw new IllegalStateException("this fleet has no pipeline to run");

        var mapped = new ConcurrentHashMap<Integer, Counts>();
        try (var phase = cluster.phase("map")) {
            phase.note("chunks", CORPUS.length).note("mappers", mappers.size());
            var done = new CountDownLatch(CORPUS.length);
            for (int i = 0; i < CORPUS.length; i++) {
                final int at = i;
                WorkerGrpc.newStub(cluster.channelTo(mappers.get(i % mappers.size())))
                    .withDeadlineAfter(3000, TimeUnit.MILLISECONDS)
                    .map(chunk(i), new StreamObserver<Counts>() {
                             @Override public void onNext(Counts c) { mapped.put(at, c); }
                             @Override public void onError(Throwable t) { done.countDown(); }
                             @Override public void onCompleted() { done.countDown(); }
                         });
            }
            done.await(60, TimeUnit.SECONDS);
        }

        // Whatever did not come back has to be redone somewhere else. Nobody said
        // which machine died or why — the coordinator knows only that a chunk it
        // sent out has no answer, and that is the whole of what it gets to work with.
        if (mapped.size() < CORPUS.length) {
            try (var phase = cluster.phase("remap")) {
                phase.note("missing", CORPUS.length - mapped.size());
                for (int i = 0; i < CORPUS.length; i++) {
                    if (mapped.containsKey(i)) continue;
                    for (String worker : mappers) {
                        try {
                            mapped.put(i, WorkerGrpc.newBlockingStub(cluster.channelTo(worker))
                                    .withDeadlineAfter(3000, TimeUnit.MILLISECONDS).map(chunk(i)));
                            break;
                        } catch (RuntimeException e) {
                            // That one is gone too. Try the next; the answer is exact
                            // or the job has failed, and there is no third outcome.
                        }
                    }
                }
            }
        }

        // Shuffle: every occurrence of a word goes to exactly one reducer, which is
        // what makes the reducers' totals add up to the whole rather than overlap.
        var buckets = new ArrayList<Map<String, Integer>>();
        for (int r = 0; r < reducers.size(); r++) buckets.add(new TreeMap<>());
        for (Counts c : mapped.values())
            c.getCountsMap().forEach((word, n) ->
                    buckets.get(Math.floorMod(word.hashCode(), reducers.size())).merge(word, n, Integer::sum));

        var answer = new TreeMap<String, Integer>();
        try (var phase = cluster.phase("reduce")) {
            phase.note("buckets", buckets.size());
            for (int r = 0; r < reducers.size(); r++) {
                final Map<String, Integer> bucket = buckets.get(r);
                final String reducer = reducers.get(r);
                if (bucket.isEmpty()) continue;
                try {
                    Counts folded = ShufflerGrpc.newBlockingStub(cluster.channelTo(reducer))
                            .withDeadlineAfter(3000, TimeUnit.MILLISECONDS)
                            .fold(Counts.newBuilder().putAllCounts(bucket).build());
                    answer.putAll(folded.getCountsMap());
                } catch (RuntimeException e) {
                    // The reducer did not answer. Nobody said why, and nobody will:
                    // redo its bucket here, which is the exercise.
                    answer.putAll(cluster.compute("local merge after " + reducer, () -> bucket));
                }
            }
        }
        cluster.done(answer);
    }
}
