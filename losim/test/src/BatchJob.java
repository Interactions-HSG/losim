import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Job;
import losim.t.*;

/**
 * Issues every call at once and waits for all of them.
 *
 * <p>A workload whose makespan is decided by how many cores the fleet has, not by
 * how much work there is — which is exactly the case a uniform factor gets wrong.
 * Four calls into eight cores take one wave; sixteen take two. Multiplying the
 * first run by four says eight.
 */
public final class BatchJob implements Job {

    @Override public void run(Cluster cluster) throws Exception {
        var workers = cluster.serving("Volley");
        int calls = (int) cluster.records();
        var done = new CountDownLatch(calls);
        try (var phase = cluster.phase("batch")) {
            for (int i = 0; i < calls; i++) {
                String peer = workers.get(i % workers.size());
                VolleyGrpc.newStub(cluster.channelTo(peer))
                        .withDeadlineAfter(60_000, TimeUnit.MILLISECONDS)
                        .poll(Ping.newBuilder().setSeq(i).setFrom("job").build(),
                              new StreamObserver<Empty>() {
                                  @Override public void onNext(Empty e) { }
                                  @Override public void onError(Throwable t) { done.countDown(); }
                                  @Override public void onCompleted() { done.countDown(); }
                              });
            }
            done.await(120, TimeUnit.SECONDS);
            phase.note("calls", calls);
        }
        cluster.done(calls + " calls over " + workers.size() + " machines");
    }
}
