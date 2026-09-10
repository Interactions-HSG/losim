import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.WorkerGrpc;
import dissaly.api.Dissaly;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;

/**
 * Eight calls at once into a node with two vCPUs.
 *
 * <p>All eight are dispatched on an async stub, so nothing here serialises them:
 * whatever queueing shows up is the node's own. Async rather than eight threads on
 * purpose — threads the node did not create would be work attributed to nobody,
 * which is the opposite of what this case is measuring.
 */
public final class Storm extends JobGrpc.JobImplBase {

    public static final int CALLS = 8;

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var stub = WorkerGrpc.newStub(Dissaly.current().channelTo("srv"));
        var request = Chunk.newBuilder().setText("grind").setLines(1).build();

        try {
            var warm = new CountDownLatch(1);
            stub.map(request, counting(warm));
            warm.await(20, TimeUnit.SECONDS);

            var done = new CountDownLatch(CALLS);
            for (int i = 0; i < CALLS; i++) stub.map(request, counting(done));
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        out.onNext(Result.newBuilder()
                .putAnswer("calls", String.valueOf(CALLS))
                .putAnswer("state", "all answered")
                .build());
        out.onCompleted();
    }

    private static StreamObserver<Counts> counting(CountDownLatch done) {
        return new StreamObserver<>() {
            @Override public void onNext(Counts c) { }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onCompleted() { done.countDown(); }
        };
    }
}
