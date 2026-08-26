import losim.api.Cluster;
import losim.api.Job;

/** A job that does nothing, for scenarios whose point is what happens before one runs. */
public final class NoopJob implements Job {
    @Override public void run(Cluster cluster) { cluster.done("nothing to do"); }
}
