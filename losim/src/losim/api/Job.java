package losim.api;

/**
 * What the fleet is asked to do.
 *
 * <p>A scenario declares machines and weather; this is the one thing in it that is
 * code, and it is named rather than embedded so the file stays pure data. The job
 * runs on a machine's own threads, so the calls it makes are attributed to that
 * machine like any other.
 *
 * <p>It is an ordinary class with a no-argument constructor. Nothing is injected
 * and nothing is inherited — whatever it needs, it asks {@link Cluster} for.
 */
@FunctionalInterface
public interface Job {

    /**
     * Runs the job. Returning normally ends the run; throwing ends it too, and the
     * trace says which and why.
     */
    void run(Cluster cluster) throws Exception;
}
