import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import losim.t.Empty;
import losim.t.Ping;
import losim.t.VolleyGrpc;

/** The same adapter shape for methods whose answer is nothing. */
public abstract class VolleyBase extends VolleyGrpc.VolleyImplBase {

    protected abstract void hit(Ping ping);

    protected void poll(Ping ping) { hit(ping); }

    @Override public final void hit(Ping ping, StreamObserver<Empty> out) { answer(out, () -> hit(ping)); }

    @Override public final void poll(Ping ping, StreamObserver<Empty> out) { answer(out, () -> poll(ping)); }

    private static void answer(StreamObserver<Empty> out, Runnable body) {
        try {
            body.run();
            out.onNext(Empty.getDefaultInstance());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.UNAVAILABLE.withDescription(String.valueOf(e.getMessage()))
                    .asRuntimeException());
        }
    }
}
