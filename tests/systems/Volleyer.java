import io.grpc.stub.StreamObserver;
import lab.pb.Empty;
import lab.pb.Ping;
import lab.pb.VolleyGrpc;
import losim.api.Losim;
import losim.api.Takes;

/**
 * Fire-and-forget, which is not a second messaging path.
 *
 * <p>It is an {@code Empty}-returning method called on an async stub: the caller
 * does not block, and costs, faults, telemetry and byte counts apply to it exactly
 * as to a unary call, because it <i>is</i> one. losim supports the shape and ships
 * no lesson that uses it; a course can.
 */
public final class Volleyer extends VolleyGrpc.VolleyImplBase {

    @Takes(refMs = 200)
    @Override public void hit(Ping p, StreamObserver<Empty> out) {
        Losim.current().reveal("rally", p.getSeq());
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }
}
