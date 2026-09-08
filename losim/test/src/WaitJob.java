import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;
import losim.t.Ping;
import losim.t.VolleyGrpc;

/**
 * A load generator: it touches every node, over and over, for a stretch of
 * simulated time it decides itself.
 *
 * <p>Its shape is the point. A Job that does a fixed amount of work finishes
 * before the simulation's weather arrives, and then nothing under test has
 * anything to happen to it. How long it keeps going is <b>the Job's</b> business
 * and nobody else's: a simulation cannot say how long a run will take, and one
 * that asked would be asking for a guess.
 */
public final class WaitJob extends JobGrpc.JobImplBase {

    /** Long enough for any weather these fixtures schedule to have happened. */
    private static final double RUNS_FOR_REFMS = 20_000;

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Losim.current();
        var peers = here.peersServing("Volley");
        int rounds = 0;
        while (here.clockMs() < RUNS_FOR_REFMS) {
            rounds++;
            // Asked afresh each round: a node that has gone is no longer serving,
            // and one that has come back is serving again.
            for (String peer : here.peersServing("Volley")) {
                try {
                    VolleyGrpc.newBlockingStub(here.channelTo(peer))
                            .withDeadlineAfter(100, TimeUnit.MILLISECONDS)
                            .hit(Ping.newBuilder().setSeq(rounds).setFrom("job").build());
                } catch (RuntimeException ignored) {
                    // Whether a peer answered is not this Job's business.
                }
            }
            if (peers.isEmpty()) break;
        }
        out.onNext(Result.newBuilder()
                .putAnswer("rounds", String.valueOf(rounds))
                .putAnswer("peers", String.valueOf(peers.size()))
                .build());
        out.onCompleted();
    }
}
