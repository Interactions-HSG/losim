import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lab.pb.Empty;
import lab.pb.Ping;
import lab.pb.VolleyGrpc;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;

/** Five rallies, each way, on async stubs — so the caller never waits for one. */
public final class Rally extends JobGrpc.JobImplBase {

    public static final int RALLIES = 5;

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Losim.current();
        var left = VolleyGrpc.newStub(here.channelTo("left"));
        var right = VolleyGrpc.newStub(here.channelTo("right"));
        // One rally each way first, awaited: the first call through a channel loads
        // and compiles everything on the path, and that is not what is being timed.
        double returnedMs;
        try {
            var warm = new CountDownLatch(2);
            left.hit(Ping.newBuilder().setSeq(-1).setFrom("right").build(), counting(warm));
            right.hit(Ping.newBuilder().setSeq(-1).setFrom("left").build(), counting(warm));
            warm.await(20, TimeUnit.SECONDS);

            var landed = new CountDownLatch(RALLIES * 2);
            long began = System.nanoTime();
            for (int i = 0; i < RALLIES; i++) {
                left.hit(Ping.newBuilder().setSeq(i).setFrom("right").build(), counting(landed));
                right.hit(Ping.newBuilder().setSeq(i).setFrom("left").build(), counting(landed));
            }
            returnedMs = (System.nanoTime() - began) / 1e6;
            landed.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            returnedMs = -1;
        }
        out.onNext(Result.newBuilder()
                .putAnswer("returnedMs", String.valueOf(Math.round(returnedMs * 100) / 100.0))
                .build());
        out.onCompleted();
    }

    private static StreamObserver<Empty> counting(CountDownLatch landed) {
        return new StreamObserver<>() {
            @Override public void onNext(Empty e) { }
            @Override public void onError(Throwable t) { landed.countDown(); }
            @Override public void onCompleted() { landed.countDown(); }
        };
    }
}
