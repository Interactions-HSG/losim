import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import losim.cli.Experiments;
import losim.cli.Lab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The front door: a student's {@code main}, not a command line.
 *
 * <p>{@code Experiments} is a thin fluent wrapper over {@link Lab} — build, run,
 * report — and its own contract is worth its own test: a failure is reported and
 * does not stop the rest, which is the one behaviour a shell script's {@code &&}
 * would get backwards.
 *
 * <p>{@code show()} is never called here. Its own javadoc says why: it does not
 * return, and a test that called it would not either.
 */
class ExperimentsTest {

    static Path root;

    @BeforeAll
    static void build() throws Exception {
        root = Fixture.build();
    }

    @AfterAll
    static void clean() throws Exception {
        Fixture.delete(root);
    }

    /** What `done()` printed, captured rather than parsed off a return value the API does not have. */
    private static String captured(Runnable body) {
        PrintStream real = System.out;
        var buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(real);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("run(scenario).done() writes a trace and says so")
    void runsAScenarioAndWritesATrace() {
        String out = captured(() -> Experiments.in(root.toString()).run("main.yaml").done());
        assertTrue(Files.exists(root.resolve(Lab.RUNS).resolve("main.json")), out);
        assertTrue(out.contains("1 run"), out);
    }

    @Test
    @DisplayName("two scenarios chained are two runs, not one overwriting the other")
    void chainsMultipleRuns() throws Exception {
        Files.writeString(root.resolve("scenarios/second.yaml"), """
                job: WordCountJob
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                  b: { instance: c5.large, zone: eu-central-1a, count: 2, prefix: b, runs: [Counter] }
                """);
        try {
            String out = captured(() ->
                    Experiments.in(root.toString()).run("main.yaml").run("second.yaml").done());
            assertTrue(Files.exists(root.resolve(Lab.RUNS).resolve("main.json")), out);
            assertTrue(Files.exists(root.resolve(Lab.RUNS).resolve("second.json")), out);
            assertTrue(out.contains("2 runs"), out);
        } finally {
            Files.deleteIfExists(root.resolve("scenarios/second.yaml"));
        }
    }

    @Test
    @DisplayName("a scenario that does not exist is reported, and the run beside it still happens")
    void aMissingScenarioDoesNotStopTheRest() {
        String out = captured(() ->
                Experiments.in(root.toString()).run("ghost.yaml").run("main.yaml").done());
        assertTrue(out.contains("there is no scenario called ghost.yaml"), out);
        assertTrue(Files.exists(root.resolve(Lab.RUNS).resolve("main.json")), out);
        // Only the scenario that actually ran counts toward the total — the
        // refusal above never reaches Lab.run, so there is nothing to have failed.
        assertTrue(out.contains("1 run"), out);
    }

    @Test
    @DisplayName("runAll() runs every scenario the lab has")
    void runsEverything() throws Exception {
        Files.writeString(root.resolve("scenarios/third.yaml"), """
                job: WordCountJob
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                  b: { instance: c5.large, zone: eu-central-1a, count: 2, prefix: b, runs: [Counter] }
                """);
        try {
            captured(() -> Experiments.in(root.toString()).runAll().done());
            var written = Files.list(root.resolve(Lab.RUNS)).map(p -> p.getFileName().toString()).toList();
            assertTrue(written.contains("main.json"), written::toString);
            assertTrue(written.contains("third.json"), written::toString);
        } finally {
            Files.deleteIfExists(root.resolve("scenarios/third.yaml"));
        }
    }
}
