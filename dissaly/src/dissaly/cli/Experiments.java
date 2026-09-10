package dissaly.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs named simulations from a Java {@code main} method.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public final class RunExperiments {
 *     public static void main(String[] args) throws Exception {
 *         Experiments.here()
 *             .run("main.yaml")
 *             .run("slow.yaml")
 *             .show();
 *     }
 * }
 * }</pre>
 *
 * <p>Each {@code run} builds the system, runs the simulation, and writes a trace.
 * {@code show} opens the viewer for the completed runs.
 *
 * <p>This API provides a reviewable, repeatable alternative to shell commands
 * for projects that run simulations from an editor.
 */
public final class Experiments {

    private final Lab lab;
    private final List<String> ran = new ArrayList<>();
    private int failed;

    private Experiments(Path root) {
        this.lab = new Lab(root);
    }

    /** The project this file is in. */
    public static Experiments here() { return in("."); }

    public static Experiments in(String root) {
        return new Experiments(Path.of(root).toAbsolutePath().normalize());
    }

    /**
     * Builds the project and runs the named simulation.
     *
     * <p>A failed run is reported and does not prevent later runs.
     */
    public Experiments run(String simulation) {
        if (lab.simulation(simulation) == null) {
            System.out.println("there is no simulation called " + simulation + " in this lab");
            System.out.println("  there is: " + lab.simulationNames());
            failed++;
            return this;
        }
        try {
            int code = lab.run(simulation, System.out::print);
            if (code != 0) failed++;
            ran.add(simulation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("could not run " + simulation + ": " + e.getMessage());
            failed++;
        }
        return this;
    }

    /** Every simulation in the lab, one after another. */
    public Experiments runAll() {
        for (String name : lab.simulationNames()) run(name);
        return this;
    }

    /**
     * Opens the viewer for every completed run and keeps it open.
     *
     * <p>The viewer remains active until it is stopped.
     */
    public void show() { show(8000); }

    public void show(int port) {
        System.out.println();
        System.out.println(ran.size() + " run" + (ran.size() == 1 ? "" : "s")
                + (failed > 0 ? ", " + failed + " of which did not finish" : ""));
        try {
            // Serve only the results already produced. Use the configured host so
            // the viewer also works from a container or Codespace.
            Serve.main(lab.root().toString(), null, lab.root().resolve(Lab.RESULTS).toString(),
                       port, Main.host(), true, false);
        } catch (IOException e) {
            System.out.println("the runs are written; the viewer would not start: " + e.getMessage());
        }
    }

    /** Prints the result directory without opening the viewer. */
    public void done() {
        System.out.println(ran.size() + " run" + (ran.size() == 1 ? "" : "s") + " -> "
                + lab.root().resolve(Lab.RESULTS));
    }
}
