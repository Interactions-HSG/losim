import io.grpc.stub.StreamObserver;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;

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
