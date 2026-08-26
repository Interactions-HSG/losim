import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.WorkerGrpc;
import losim.api.Cluster;
import losim.api.Job;

/**
 * Eight calls at once into a machine with two vCPUs.
 *
 * <p>All eight are dispatched on an async stub, so nothing here serialises them:
 * whatever queueing shows up is the machine's own. Async rather than eight threads
 * on purpose — threads the machine did not create would be work attributed to
 * nobody, which is the opposite of what this case is measuring.
 */
public final class Storm implements Job {

    public static final int CALLS = 8;

    @Override public void run(Cluster cluster) throws Exception {
        var stub = WorkerGrpc.newStub(cluster.channelTo("srv"));
        var request = Chunk.newBuilder().setText("grind").setLines(1).build();

        var warm = new CountDownLatch(1);
        stub.map(request, counting(warm));
        warm.await(20, TimeUnit.SECONDS);

        var done = new CountDownLatch(CALLS);
        for (int i = 0; i < CALLS; i++) stub.map(request, counting(done));
        done.await(60, TimeUnit.SECONDS);
        cluster.done(CALLS + " calls, all answered");
    }

    private static StreamObserver<Counts> counting(CountDownLatch done) {
        return new StreamObserver<>() {
            @Override public void onNext(Counts c) { }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onCompleted() { done.countDown(); }
        };
    }
}
