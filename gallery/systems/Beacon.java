import io.grpc.stub.StreamObserver;
import losim.api.Losim;
import losim.api.Spec;
import losim.api.Takes;
import mr.pb.Empty;
import mr.pb.Power;
import mr.pb.RosterGrpc;

/**
 * What this machine is, when asked.
 *
 * <p>Every working machine offers this alongside whatever else it serves, and
 * the master calls it once before it places anything. That round trip is not
 * ceremony: it is the only way the orchestrator can know that {@code w3} is a
 * two-core burstable with a four-megabyte disk. There is no registry, no
 * configuration file the master also read, and no shared memory — the fleet's
 * shape is discovered the same way everything else is, by calling somebody and
 * seeing whether they answer.
 *
 * <p>Which means the table is already stale. A machine that answers here can be
 * gone by the time work is sent to it, and a machine that never answers might be
 * perfectly healthy behind a partition. Placing work well is a decision made on
 * old information, and pretending otherwise is the mistake this call exists to
 * prevent.
 */
public final class Beacon extends RosterGrpc.RosterImplBase {

    /**
     * Cheap on purpose. A capacity call that cost real time would be a capacity
     * call an orchestrator learns not to make, and then it stops asking and starts
     * assuming.
     */
    @Takes(refMs = 1)
    @Override public void capacity(Empty request, StreamObserver<Power> out) {
        Spec me = Losim.current().here();
        Losim.current().reveal("advertised", me.vcpu());
        out.onNext(Power.newBuilder()
                .setMachine(me.machine())
                .setInstance(me.instance())
                .setZone(me.zone())
                .setVcpu(me.vcpu())
                .setMemoryMb(me.memoryCapMb())
                .setDiskMb(me.diskCapMb())
                .build());
        out.onCompleted();
    }
}
