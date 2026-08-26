import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lab.pb.Counts;
import lab.pb.ShufflerGrpc;

/** The same twelve lines, for the service that folds a bucket together. */
public abstract class ShufflerBase extends ShufflerGrpc.ShufflerImplBase {

    protected abstract Counts fold(Counts request);

    @Override public final void fold(Counts request, StreamObserver<Counts> out) {
        try {
            out.onNext(fold(request));
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(String.valueOf(e.getMessage()))
                    .asRuntimeException());
        }
    }
}
