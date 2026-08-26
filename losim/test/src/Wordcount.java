import java.nio.file.Path;
import losim.runtime.Run;
import losim.scenario.Loader;
import losim.trace.Telemetry;

/**
 * The reference run, loaded from the scenario the way anyone else would load it.
 *
 * <p>Everything the telemetry is held to is asked of this: a fleet of seven, one
 * machine reclaimed after it has mapped but before it is asked to reduce, and one
 * far too small for the bucket it is handed. A recorder only ever tested on a run
 * where nothing goes wrong is a recorder that has not been tested.
 */
public final class Wordcount {

    public static final Path SCENARIO = Path.of("losim/test/scenarios/wordcount.yaml");

    public static Run.Result result() throws Exception {
        return Run.of(Loader.load(SCENARIO), Wordcount.class.getClassLoader());
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
