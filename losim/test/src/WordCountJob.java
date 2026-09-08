import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;
import losim.t.*;

/**
 * Map across the system, then reduce, and cope with a node that is not there.
 *
 * <p>The map phase uses an async stub and a latch, which is how you would fan out
 * over gRPC without a thread per call. The reduce phase is blocking, because the
 * interesting thing about it is what happens when one of the calls never comes
 * back: the coordinator waits out its own deadline, learns nothing about why, and
 * redoes the work itself.
 */
public final class WordCountJob extends JobGrpc.JobImplBase {

    static final String[] CORPUS = {
        "the cat sat on the mat", "the dog sat on the log", "a bird and a cat",
        "the cat and the dog",    "a log and a mat",        "the bird and the cat"
    };

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(CORPUS.length).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Losim.current();
        List<String> workers = here.peersServing("Worker");
        if (workers.isEmpty()) throw new IllegalStateException("nobody serves Worker");

        var mapped = new ConcurrentHashMap<String, Counts>();
        var done = new CountDownLatch(Math.min(CORPUS.length, workers.size()));
        for (int i = 0; i < CORPUS.length && i < workers.size(); i++) {
            String worker = workers.get(i);
            Channel ch = here.channelTo(worker);
            WorkerGrpc.newStub(ch).withDeadlineAfter(600, TimeUnit.MILLISECONDS)
                .map(Chunk.newBuilder().setText(CORPUS[i]).setLines(1).build(),
                     new StreamObserver<Counts>() {
                         @Override public void onNext(Counts c) { mapped.put(worker, c); }
                         @Override public void onError(Throwable t) {
                             here.log("map on " + worker + " failed: " + t.getMessage());
                             done.countDown();
                         }
                         @Override public void onCompleted() { done.countDown(); }
                     });
        }
        try { done.await(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        var merged = new TreeMap<String, Integer>();
        // In a settled order, so which node reduces last is a property of the
        // simulation rather than of whichever thread happened to finish first.
        for (var entry : new TreeMap<>(mapped).entrySet()) {
            String worker = entry.getKey();
            Counts counts = entry.getValue();
            try {
                WorkerGrpc.newBlockingStub(here.channelTo(worker))
                        .withDeadlineAfter(1200, TimeUnit.MILLISECONDS)
                        .reduce(counts).getCountsMap()
                        .forEach((k, v) -> merged.merge(k, v, Integer::sum));
            } catch (StatusRuntimeException e) {
                // Not "is it alive?": there is no such question. It did not answer
                // in the time this coordinator was willing to wait, so the work is
                // its own again. Done here, on the thread serving losim.Job/Run,
                // which is what makes the node busy for as long as it takes.
                here.log("reducer " + worker + " did not answer ("
                       + e.getStatus().getCode() + ") — merging locally");
                counts.getCountsMap().forEach((k, v) -> merged.merge(k, v, Integer::sum));
            }
        }

        var top = merged.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(3).map(Map.Entry::getKey).toList();
        here.log("commonest: " + top);

        var answer = Result.newBuilder();
        merged.forEach((word, n) -> answer.putAnswer(word, String.valueOf(n)));
        out.onNext(answer.build());
        out.onCompleted();
    }
}
