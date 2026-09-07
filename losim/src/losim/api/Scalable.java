package losim.api;

/**
 * A job whose input has a size, so the same job can be run at another one.
 *
 * <p>The difference from {@link Job} is one parameter, and it is the difference
 * between a run and a model of a run. A plain {@code Job} does whatever it does; how
 * much work that was is a fact about its Java. A {@code Scalable} job is told how
 * much to do, so the scale engine can ask it the same question at four sizes and
 * say what a fifth would cost.
 *
 * <p>Which is what a scaled run needs. A plain {@code Job} keeps its workload size
 * in its own Java, where no scenario can reach it, so there is nothing for the
 * engine to vary and no rung to measure.
 *
 * <pre>{@code
 * public final class FillUp implements Scalable {
 *
 *     public Input.Shape shape() {
 *         return Input.Shape.counting("items", "item").with("bytes");
 *     }
 *
 *     public void run(Cluster cluster, Input at) {
 *         byte[] value = new byte[(int) at.value("bytes")];
 *         for (long i = 0; i < at.count("items"); i++) put(cluster, i, value);
 *     }
 * }
 * }</pre>
 *
 * <p>Not a subtype of {@code Job}: a class would then have two {@code run} methods
 * and the loader would have to guess which one the scenario meant. It is one or the
 * other, and the scenario's {@code input:} block says which was expected.
 */
public interface Scalable {

    /**
     * What this job's input is made of — its parts, named, with no sizes.
     *
     * <p>Called once, before the run, to check the scenario against it. Whatever it
     * returns has to be the same every time: it is a declaration, not a decision.
     */
    Input.Shape shape();

    /**
     * Runs the job over an input of the size losim chose.
     *
     * <p>Everything about the workload's size comes through {@code at}. A number
     * here that was not read from it is a number no scenario can move.
     */
    void run(Cluster cluster, Input at) throws Exception;
}
