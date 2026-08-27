import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import losim.api.Losim;
import losim.api.Takes;
import mr.pb.Empty;
import mr.pb.MonitorGrpc;
import mr.pb.Trouble;

/**
 * Where a worker says what went wrong, and nobody waits to hear it.
 *
 * <p>{@code Report} returns {@code Empty} and the workers call it on an async
 * stub, so it does not block them — which is the whole of what fire-and-forget is
 * in this system. There is no second messaging path: this is an ordinary gRPC
 * method with ordinary interceptors on it, costing ordinary bytes, and the trace
 * records it like any other call.
 *
 * <p>It is also honest about what a monitor is worth. A machine that dies mid-map
 * reports nothing at all — the interesting failures are exactly the ones that
 * cannot file a report — so this is a convenience for reading the trace
 * afterwards and never the thing the coordinator makes decisions on.
 */
public final class Watchtower extends MonitorGrpc.MonitorImplBase {

    private final Map<String, AtomicInteger> byWorker = new ConcurrentHashMap<>();

    @Takes(refMs = 1)
    @Override public void report(Trouble t, StreamObserver<Empty> out) {
        int n = byWorker.computeIfAbsent(t.getWorker(), k -> new AtomicInteger()).incrementAndGet();
        Losim.current().reveal("troubled", byWorker.size());
        Losim.current().log(t.getWorker() + " in " + t.getPhase() + ": "
                + (t.hasMessage() ? t.getMessage() : "code " + t.getCode()) + " (" + n + ")");
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }
}
