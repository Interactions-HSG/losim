import io.grpc.stub.StreamObserver;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;

/**
 * A Job that measures itself with the wrong clock.
 *
 * <p>It runs on a node like any other service, so its allocation and its wall
 * clock land on that node's counters — and so does this.
 */
public final class ClockingJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        long began = System.currentTimeMillis();
        out.onNext(Result.newBuilder()
                .putAnswer("tookMs", String.valueOf(System.currentTimeMillis() - began))
                .build());
        out.onCompleted();
    }
}
