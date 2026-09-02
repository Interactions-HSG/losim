package losim.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Running systems from a `main`, so that nobody needs a command line.
 *
 * <p>A student on this course has an editor with a run button in it. This is
 * what that button presses:
 *
 * <pre>{@code
 * public final class RunExperiments {
 *     public static void main(String[] args) throws Exception {
 *         Experiments.here()
 *             .run("0-tour/1-two-machines")
 *             .run("1-tour-grpc", "slow.yaml")
 *             .show();
 *     }
 * }
 * }</pre>
 *
 * <p>Each {@code run} builds the system, runs the scenario, and writes a trace;
 * {@code show} opens the viewer on all of them at once and stays open. Two
 * scenarios run this way are two runs side by side in the picker, which is the
 * only way to compare a design against itself.
 *
 * <p><b>Why this exists at all.</b> Everything here can also be done from a
 * command line, and for a course this is the wrong way round: the shell is the
 * thing that has to be learned, not the thing that teaches. A file with a
 * {@code main} in it is something a first-year already knows how to run, and it
 * is diffable, commentable and reviewable in a way a shell history is not.
 */
public final class Experiments {

    private final Lab lab;
    private final List<String> ran = new ArrayList<>();
    private int failed;

    private Experiments(Path root) {
        this.lab = new Lab(root, root.resolve("lib"));
    }

    /** The project this file is in. */
    public static Experiments here() { return in("."); }

    public static Experiments in(String root) {
        return new Experiments(Path.of(root).toAbsolutePath().normalize());
    }

    /** Build and run a system, in its ordinary world. */
    public Experiments run(String task) { return run(task, null); }

    /**
     * Build and run a system, in the world you name.
     *
     * <p>A failure is reported and does not stop the rest: when three scenarios
     * are being compared, the one that fell over is a result about the design and
     * the other two are still worth looking at.
     */
    public Experiments run(String task, String scenario) {
        Lab.Task t = lab.task(task);
        if (t == null) {
            System.out.println("there is no system called " + task + " in this project");
            System.out.println("  there is: " + lab.tasks().stream().map(Lab.Task::id).toList());
            failed++;
            return this;
        }
        try {
            int code = lab.run(t, scenario, System.out::print);
            if (code != 0) failed++;
            ran.add(task + (scenario == null ? "" : " " + scenario));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("could not run " + task + ": " + e.getMessage());
            failed++;
        }
        return this;
    }

    /** Every system in the project, each in its ordinary world. */
    public Experiments runAll() {
        for (Lab.Task t : lab.tasks()) if (t.started() && t.distributed()) run(t.id());
        return this;
    }

    /**
     * Open the viewer on everything that has been run, and keep it open.
     *
     * <p>Does not return. That is deliberate: a viewer that closed itself the
     * moment it opened would be a screenshot, and stopping it is what the stop
     * button in the editor is for.
     */
    public void show() { show(8000); }

    public void show(int port) {
        System.out.println();
        System.out.println(ran.size() + " run" + (ran.size() == 1 ? "" : "s")
                + (failed > 0 ? ", " + failed + " of which did not finish" : ""));
        try {
            // `false`: show what was just run, and do not go and build everything
            // else in the project on the way — the list above is the experiment.
            //
            // `Main.host()` rather than a written-down loopback: this is what the
            // editor's run button presses, and the editor is a devcontainer or a
            // Codespace as often as it is a laptop. A viewer bound to 127.0.0.1
            // in a container is a forwarded port with nothing behind it, so the
            // run succeeded and the browser said connection refused.
            Serve.main(lab.root().toString(), null, lab.root().resolve(Lab.RUNS).toString(),
                       port, Main.host(), true, false);
        } catch (IOException e) {
            System.out.println("the runs are written; the viewer would not start: " + e.getMessage());
        }
    }

    /** Everything is written; do not open anything. For a run you only want the traces from. */
    public void done() {
        System.out.println(ran.size() + " run" + (ran.size() == 1 ? "" : "s") + " -> "
                + lab.root().resolve(Lab.RUNS));
    }
}
