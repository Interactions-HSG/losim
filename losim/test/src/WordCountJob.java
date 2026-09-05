import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import losim.api.Cluster;
import losim.api.Job;
import losim.t.*;

/**
 * Map across the fleet, then reduce, and cope with a machine that is not there.
 *
 * <p>The map phase uses an async stub and a latch, which is how you would fan out
 * over gRPC without a thread per call. The reduce phase is blocking, because the
 * interesting thing about it is what happens when one of the calls never comes
 * back: the coordinator waits out its own deadline, learns nothing about why, and
 * redoes the work itself.
 */
public final class WordCountJob implements Job {

    static final String[] CORPUS = {
        "the cat sat on the mat", "the dog sat on the log", "a bird and a cat",
        "the cat and the dog",    "a log and a mat",        "the bird and the cat"
    };

    @Override public void run(Cluster cluster) throws Exception {
        List<String> workers = cluster.serving("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        var mapped = new ConcurrentHashMap<String, Counts>();
        try (var phase = cluster.phase("map")) {
            var done = new CountDownLatch(Math.min(CORPUS.length, workers.size()));
            for (int i = 0; i < CORPUS.length && i < workers.size(); i++) {
                String worker = workers.get(i);
                Channel ch = cluster.channelTo(worker);
                WorkerGrpc.newStub(ch).withDeadlineAfter(600, TimeUnit.MILLISECONDS)
                    .map(Chunk.newBuilder().setText(CORPUS[i]).setLines(1).build(),
                         new StreamObserver<Counts>() {
                             @Override public void onNext(Counts c) { mapped.put(worker, c); }
                             @Override public void onError(Throwable t) {
                                 cluster.log("map on " + worker + " failed: " + t.getMessage());
                                 done.countDown();
                             }
                             @Override public void onCompleted() { done.countDown(); }
                         });
            }
            done.await(30, TimeUnit.SECONDS);
            phase.note("mapped", mapped.size());
        }

        var merged = new TreeMap<String, Integer>();
        try (var phase = cluster.phase("reduce")) {
        // In a settled order, so which machine reduces last is a property of the
        // scenario rather than of whichever thread happened to finish first.
        for (var entry : new TreeMap<>(mapped).entrySet()) {
            String worker = entry.getKey();
            Counts counts = entry.getValue();
            try {
                WorkerGrpc.newBlockingStub(cluster.channelTo(worker))
                        .withDeadlineAfter(1200, TimeUnit.MILLISECONDS)
                        .reduce(counts).getCountsMap()
                        .forEach((k, v) -> merged.merge(k, v, Integer::sum));
            } catch (StatusRuntimeException e) {
                // Not "is it alive?": there is no such question. It did not answer
                // in the time this coordinator was willing to wait, so the work is
                // its own again.
                cluster.log("reducer " + worker + " did not answer ("
                          + e.getStatus().getCode() + ") — merging locally");
                cluster.compute("local merge for " + worker, () -> {
                    counts.getCountsMap().forEach((k, v) -> merged.merge(k, v, Integer::sum));
                    return new TreeMap<>(merged);
                });
            }
        }
            phase.note("keys", merged.size());
        }
        cluster.done(merged);
    }
}
