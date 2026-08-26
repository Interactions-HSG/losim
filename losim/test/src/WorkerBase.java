import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import losim.t.Chunk;
import losim.t.Counts;
import losim.t.WorkerGrpc;

/**
 * The shape a student writes — and the reason it exists.
 *
 * grpc-java generates {@code void map(Chunk, StreamObserver<Counts>)}; the
 * value-returning form is client-side only. Twelve lines of adapter turn it into
 * {@code Counts map(Chunk)}, which is the difference between a method you can
 * construct and call from a plain test and one you cannot.
 *
 * <p>Note what is <b>not</b> here: no losim type in any signature. That is what
 * lets the same handler be debugged alone, with nothing simulating anything.
 */
public abstract class WorkerBase extends WorkerGrpc.WorkerImplBase {

    protected abstract Counts map(Chunk request);

    protected Counts reduce(Counts request) { return request; }

    @Override public final void map(Chunk request, StreamObserver<Counts> out) {
        answer(out, () -> map(request));
    }

    @Override public final void reduce(Counts request, StreamObserver<Counts> out) {
        answer(out, () -> reduce(request));
    }

    private static void answer(StreamObserver<Counts> out, Supplier<Counts> body) {
        try {
            out.onNext(body.get());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(String.valueOf(e.getMessage()))
                    .asRuntimeException());
        }
    }
}
