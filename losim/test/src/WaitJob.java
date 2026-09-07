import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Job;
import losim.t.Ping;
import losim.t.VolleyGrpc;

/**
 * A load generator: it touches every machine, over and over, for a stretch of
 * simulated time it decides itself.
 *
 * <p>Its shape is the point. A job that does a fixed amount of work finishes before
 * the scenario's weather arrives, and then nothing under test has anything to
 * happen to it. How long it keeps going is <b>the job's</b> business and nobody
 * else's: a scenario cannot say how long a run will take, and a job that asked
 * would be asking for a guess.
 */
public final class WaitJob implements Job {

    /** Long enough for any weather these fixtures schedule to have happened. */
    private static final double RUNS_FOR_REFMS = 20_000;

    @Override public void run(Cluster cluster) {
        var peers = cluster.serving("Volley");
        int rounds = 0;
        double until = RUNS_FOR_REFMS;
        while (cluster.clockMs() < until) {
            rounds++;
            // Asked afresh each round: a machine that has gone is no longer serving,
            // and one that has come back is serving again.
            for (String peer : cluster.serving("Volley")) {
                try {
                    VolleyGrpc.newBlockingStub(cluster.channelTo(peer))
                            .withDeadlineAfter(100, TimeUnit.MILLISECONDS)
                            .hit(Ping.newBuilder().setSeq(rounds).setFrom("job").build());
                } catch (RuntimeException ignored) {
                    // Whether a peer answered is not this job's business.
                }
            }
            if (peers.isEmpty()) break;
        }
        cluster.done(rounds + " rounds over " + peers.size() + " peers");
    }
}
