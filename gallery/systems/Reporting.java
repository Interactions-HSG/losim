import io.grpc.stub.StreamObserver;
import java.util.List;
import losim.api.Losim;
import mr.pb.Empty;
import mr.pb.MonitorGrpc;
import mr.pb.Phase;
import mr.pb.Trouble;

/**
 * How a worker tells the master something went wrong without waiting to be heard.
 *
 * <p>The method returns {@code Empty} and is called on an async stub, so the
 * caller's thread carries on immediately. That is the entire mechanism, and it is
 * worth saying what it is not: there is no queue, no bus and no second messaging
 * path. losim's interceptors are on this call exactly as they are on a blocking
 * one — it costs bytes, it waits out the latency, it appears in the trace, and it
 * fails when the master is unreachable. The only thing that changed is who waits.
 *
 * <p>Best-effort by construction. A machine dying mid-handler sends nothing, a
 * partitioned one sends into the dark, and neither is worth failing a map task
 * over — so every failure here is swallowed. A monitor that can break the system
 * it monitors is worse than no monitor.
 */
final class Reporting {
    private Reporting() {}

    static void tell(Phase phase, String what) {
        try {
            List<String> masters = Losim.current().peersServing("Monitor");
            if (masters.isEmpty()) return;
            MonitorGrpc.newStub(Losim.current().channelTo(masters.get(0)))
                    .report(Trouble.newBuilder()
                                    .setPhase(phase)
                                    .setWorker(Losim.current().machine())
                                    .setMessage(String.valueOf(what))
                                    .addTags(phase.name().toLowerCase())
                                    .build(),
                            new StreamObserver<Empty>() {
                                @Override public void onNext(Empty e) { }
                                @Override public void onError(Throwable t) { }
                                @Override public void onCompleted() { }
                            });
        } catch (RuntimeException ignored) {
            // Nobody is listening, or nobody can be reached. Neither is a reason to
            // fail the work this machine was actually asked to do.
        }
    }
}
