import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.Report;
import lab.pb.WorkerGrpc;

/**
 * The twelve lines of adapter, and the reason they exist.
 *
 * <p>grpc-java generates {@code void map(Chunk, StreamObserver<Counts>)}; the
 * value-returning form is client-side only. Turning it into {@code Counts map(Chunk)}
 * is the difference between a handler a plain unit test can call and one it cannot —
 * which is the whole of t1.
 */
public abstract class WorkerBase extends WorkerGrpc.WorkerImplBase {

    protected abstract Counts map(Chunk request);

    protected Counts reduce(Counts request) { return request; }

    protected Report note(Report request) { return request; }

    @Override public final void map(Chunk request, StreamObserver<Counts> out) {
        answer(out, () -> map(request));
    }

    @Override public final void reduce(Counts request, StreamObserver<Counts> out) {
        answer(out, () -> reduce(request));
    }

    @Override public final void note(Report request, StreamObserver<Report> out) {
        answer(out, () -> note(request));
    }

    private static <T> void answer(StreamObserver<T> out, Supplier<T> body) {
        try {
            out.onNext(body.get());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(String.valueOf(e.getMessage()))
                    .asRuntimeException());
        }
    }
}
