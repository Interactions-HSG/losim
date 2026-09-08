import io.grpc.stub.StreamObserver;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;

/** A Job that does nothing, for simulations whose point is what happens before one runs. */
public final class NoopJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        out.onNext(Result.newBuilder().putAnswer("did", "nothing to do").build());
        out.onCompleted();
    }
}
