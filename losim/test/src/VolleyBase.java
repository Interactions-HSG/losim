import io.grpc.stub.StreamObserver;
import losim.t.Empty;
import losim.t.Ping;
import losim.t.VolleyGrpc;

/** The same adapter shape for a method whose answer is nothing. */
public abstract class VolleyBase extends VolleyGrpc.VolleyImplBase {

    protected abstract void hit(Ping ping);

    @Override public final void hit(Ping ping, StreamObserver<Empty> out) {
        hit(ping);
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }
}
