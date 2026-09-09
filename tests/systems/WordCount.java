import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.ShufflerGrpc;
import lab.pb.WorkerGrpc;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;

/**
 * Split, map, shuffle, reduce — the pipeline most of the suite's harder cases run on.
 *
 * <p>The map phase fans out on an async stub and waits on a latch, which is how you
 * fan out over gRPC without a thread per call. The reduce phase blocks, because the
 * interesting thing about it is what happens when a call never comes back: the
 * master waits out its own deadline, learns nothing about why, and does the
 * work itself.
 */
public final class WordCount extends JobGrpc.JobImplBase {

    /** Fixed, so an assertion can compute the right answer independently. */
    public static final String[] CORPUS = {
        "the cat sat on the mat",  "the dog sat on the log",  "a bird and a cat",
        "the cat and the dog",     "a log and a mat",         "the bird and the cat",
        "a cat a dog a bird",      "the mat the log the cat",
    };

    /** What the answer has to be, counted here so the cluster's answer can be checked. */
    public static Map<String, Integer> truth() {
        var out = new TreeMap<String, Integer>();
        for (String line : CORPUS)
            for (String w : line.split(" ")) out.merge(w, 1, Integer::sum);
        return out;
    }

    private static Chunk chunk(int i) {
        return Chunk.newBuilder().setText(CORPUS[i]).setLines(1).build();
    }

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Losim.current();
        List<String> mappers = here.peersServing("Worker");
        List<String> reducers = here.peersServing("Shuffler");
        if (mappers.isEmpty() || reducers.isEmpty())
            throw new IllegalStateException("this system has no pipeline to run");

        var mapped = new ConcurrentHashMap<Integer, Counts>();
        var done = new CountDownLatch(CORPUS.length);
        for (int i = 0; i < CORPUS.length; i++) {
            final int at = i;
            WorkerGrpc.newStub(here.channelTo(mappers.get(i % mappers.size())))
                .withDeadlineAfter(3000, TimeUnit.MILLISECONDS)
                .map(chunk(i), new StreamObserver<Counts>() {
                         @Override public void onNext(Counts c) { mapped.put(at, c); }
                         @Override public void onError(Throwable t) { done.countDown(); }
                         @Override public void onCompleted() { done.countDown(); }
                     });
        }
        await(done, 60);

        // Whatever did not come back has to be redone somewhere else. Nobody said
        // which node died or why — the master knows only that a chunk it sent
        // out has no answer, and that is the whole of what it gets to work with.
        for (int i = 0; i < CORPUS.length; i++) {
            if (mapped.containsKey(i)) continue;
            for (String worker : mappers) {
                try {
                    mapped.put(i, WorkerGrpc.newBlockingStub(here.channelTo(worker))
                            .withDeadlineAfter(3000, TimeUnit.MILLISECONDS).map(chunk(i)));
                    break;
                } catch (RuntimeException e) {
                    // That one is gone too. Try the next; the answer is exact or the
                    // simulation has failed, and there is no third outcome.
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
        for (int r = 0; r < reducers.size(); r++) {
            final Map<String, Integer> bucket = buckets.get(r);
            final String reducer = reducers.get(r);
            if (bucket.isEmpty()) continue;
            try {
                Counts folded = ShufflerGrpc.newBlockingStub(here.channelTo(reducer))
                        .withDeadlineAfter(3000, TimeUnit.MILLISECONDS)
                        .fold(Counts.newBuilder().putAllCounts(bucket).build());
                answer.putAll(folded.getCountsMap());
            } catch (RuntimeException e) {
                // The reducer did not answer. Nobody said why, and nobody will: redo
                // its bucket here, on the thread serving losim.Job/Run — which is
                // what makes this node busy for exactly as long as it takes.
                answer.putAll(bucket);
            }
        }
        var built = Result.newBuilder();
        answer.forEach((word, n) -> built.putAnswer(word, String.valueOf(n)));
        out.onNext(built.build());
        out.onCompleted();
    }

    /** Waiting, without making every caller declare it can be interrupted. */
    private static void await(CountDownLatch latch, int seconds) {
        try { latch.await(seconds, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
