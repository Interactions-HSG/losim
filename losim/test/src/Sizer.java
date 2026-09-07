import losim.api.Cluster;
import losim.api.Input;
import losim.api.Scalable;

/**
 * A job that does nothing but say what it was handed.
 *
 * <p>Two parts, one of each kind, because the whole claim about {@link Input} is
 * that a count shrinks with the run and a constant does not — and a job with only
 * one of them could not tell them apart.
 */
public final class Sizer implements Scalable {

    @Override public Input.Shape shape() {
        return Input.Shape.counting("items", "item").with("valueBytes");
    }

    @Override public void run(Cluster cluster, Input at) {
        cluster.log("items=" + at.count("items")
                + " valueBytes=" + at.value("valueBytes")
                + " units=" + at.units());
    }
}
