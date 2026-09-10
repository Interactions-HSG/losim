import java.nio.file.Path;
import dissaly.runtime.Simulate;
import dissaly.sim.Loader;
import dissaly.trace.Telemetry;

/**
 * The reference run, loaded from the simulation the way anyone else would load it.
 *
 * <p>Everything the telemetry is held to is asked of this: a cluster of seven, one
 * machine reclaimed after it has mapped but before it is asked to reduce, and one
 * far too small for the bucket it is handed. A recorder only ever tested on a run
 * where nothing goes wrong is a recorder that has not been tested.
 */
public final class Wordcount {

    public static final Path SIMULATION = Path.of("losim/test/simulations/wordcount.yaml");

    public static Simulate.Result result() throws Exception {
        return Simulate.of(Loader.load(SIMULATION), Wordcount.class.getClassLoader());
    }

    public static Telemetry run() throws Exception { return result().telemetry(); }

    public static void main(String[] args) throws Exception {
        var r = result();
        var tel = r.telemetry();
        System.out.printf("%s in %.0f refMs — events %d   spans %d   series %d   dangling %d%n",
                r.completed() ? "completed" : "failed", r.durationRefMs(),
                tel.events().size(), tel.spans().size(), tel.series().size(),
                tel.dangling().size());
    }
}
