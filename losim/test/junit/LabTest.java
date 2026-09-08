import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import losim.cli.Lab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a lab is, found off the disk rather than declared.
 *
 * <p>{@code Lab} is what every other piece here sits on: {@code Serve}'s
 * endpoints answer with what it finds, {@code Palette} reads what it compiles,
 * {@code Experiments} runs what it names. Nothing here mocks the filesystem or
 * the toolchain — {@link Fixture} builds a real lab and this compiles and runs
 * it for real, with the same protoc and javac a student's would use.
 */
class LabTest {

    static Path root;
    static Lab lab;

    @Test
    @DisplayName("a run with no --cp falls back to the lab's own classes, never to the JVM's")
    void classesAreTheFallback() throws Exception {
        // The reason there is a fallback at all: the classpath the build resolves is
        // the simulator and gRPC, and the lab's own output is deliberately not on it,
        // so the JVM running `losim run` has never heard of the class a simulation
        // names. Nothing caught that for a release, because the console passes --cp
        // itself and every test went through the console.
        var log = new StringBuilder();
        assertNotNull(lab.compile(log::append), log.toString());
        assertFalse(lab.cp().contains(lab.classes().toString()),
                "the resolved classpath does not carry the lab's own output, by design");
        assertEquals(lab.classes().toString(), lab.classesIfBuilt(),
                "so a run with no --cp has to be sent there");

        // And a project nobody has built yet says so about the class, not about a
        // directory: "" leaves the refusal naming what the simulation asked for.
        Path fresh = Files.createTempDirectory(Path.of("build"), "unbuilt-lab-");
        try {
            assertEquals("", new Lab(fresh).classesIfBuilt(),
                    "nothing compiled yet is not a classpath");
        } finally {
            Fixture.delete(fresh);
        }
    }

    @BeforeAll
    static void build() throws Exception {
        root = Fixture.build();
        lab = new Lab(root);
    }

    @AfterAll
    static void clean() throws Exception {
        Fixture.delete(root);
    }

    @Test
    @DisplayName("a lab is a directory whose build resolved losim, and nothing else makes one")
    void isALab() throws Exception {
        assertTrue(lab.isLab());

        Path empty = Files.createTempDirectory(Path.of("build"), "not-a-lab-");
        try {
            // The comment on isLab() is explicit about why this matters: without
            // it, pointing the server at the wrong directory lists that
            // directory's own furniture as things to run.
            assertFalse(new Lab(empty).isLab());
        } finally {
            Fixture.delete(empty);
        }
    }

    @Test
    @DisplayName("the code is everything under the lab, minus its own furniture")
    void findsItsOwnCode() {
        Lab.Code c = lab.code();
        assertTrue(c.started());
        assertEquals(1, c.protos().size(), "exactly lab.proto");
        // Bundle, Counter, WorkerBase, WordCountJob, NoisyJob — and nothing gen/
        // has not been asked to produce yet. Which directories the walk declines to
        // enter is LabWalkTest's question, and it is asked there against a lab that
        // has Java in them; a count here would pass whatever the walk did.
        assertEquals(5, c.sources().size());
    }

    @Test
    @DisplayName("simulations come from simulations/, and from a loose file at the root")
    void findsScenariosInTheFolderAndAtTheRoot() throws Exception {
        Path loose = root.resolve("loose.yaml");
        Files.writeString(loose, "nodes:\n  a: { instance: m5.large, zone: eu-central-1a }\n");
        try {
            var names = lab.simulationNames();
            assertTrue(names.contains("main.yaml"));
            assertTrue(names.contains("loose.yaml"));
        } finally {
            Files.deleteIfExists(loose);
        }
    }

    @Test
    @DisplayName("numbered simulations list in their numbers' order, not their digits'")
    void ordersScenariosTheWayAPersonNumbersThem() throws Exception {
        Path dir = root.resolve("simulations");
        String[] made = {"2-two.yaml", "10-ten.yaml", "1-one.yaml", "9-nine.yaml", "01-one.yaml",
                         "2-two-slow.yaml"};
        for (String n : made) Files.writeString(dir.resolve(n), Fixture.SIMULATION);
        try {
            var names = new java.util.ArrayList<>(lab.simulationNames());
            names.retainAll(java.util.List.of(made));
            assertEquals(
                    java.util.List.of("1-one.yaml", "01-one.yaml", "2-two.yaml", "2-two-slow.yaml",
                                      "9-nine.yaml", "10-ten.yaml"),
                    names);
            // The same list read backwards is not the same order — without which
            // this would still pass on a plain string sort of a list that happens
            // to be short enough to look right.
            assertNotEquals(names, names.reversed());
            assertNotEquals(new java.util.TreeSet<>(names).stream().toList(), names,
                    "a string sort would put 10- second");
        } finally {
            for (String n : made) Files.deleteIfExists(dir.resolve(n));
        }
    }

    @Test
    @DisplayName("runs list in the same order their simulations do")
    void ordersRunsTheSameWay() {
        // `Serve`'s run index sorts by this same comparator. A run is named after
        // the simulation it came from, so a tour numbered to be read in order has
        // to come out in that order on the Runs page too — a plain string sort
        // would instead list a sixteen-stop tour as 1, 10, 11, … 2, 3.
        var files = new java.util.ArrayList<>(java.util.List.of(
                Path.of("10-two-halves.json"), Path.of("2-three-machines.json"),
                Path.of("1-two-machines.json"), Path.of("16-thirty-runs.json"),
                Path.of("6-chain-of-calls-slow.json"), Path.of("6-chain-of-calls.json")));
        files.sort(Lab::byName);
        assertEquals(
                java.util.List.of("1-two-machines.json", "2-three-machines.json",
                                  "6-chain-of-calls.json", "6-chain-of-calls-slow.json",
                                  "10-two-halves.json", "16-thirty-runs.json"),
                files.stream().map(Path::toString).toList());
    }

    @Test
    @DisplayName("a simulation by name, and the first one when none is named")
    void scenarioLookup() {
        assertNotNull(lab.simulation("main.yaml"));
        assertEquals("main.yaml", lab.simulation(null).getFileName().toString());
        assertEquals("main.yaml", lab.simulation("").getFileName().toString());
        assertNull(lab.simulation("nope.yaml"));
    }

    @Test
    @DisplayName("a trace is named after its simulation, not the other way round")
    void traceNaming() {
        assertEquals("main.json", lab.trace("main.yaml").getFileName().toString());
        assertNull(lab.trace("nope.yaml"));
    }

    @Test
    @DisplayName("compiled() answers from mtimes, without asking the toolchain")
    void compiledIsStaleness() throws Exception {
        StringBuilder log = new StringBuilder();
        assertNotNull(lab.compile(log::append), log.toString());
        assertTrue(lab.compiled());

        // A source touched after the last build makes the answer false — this is
        // the whole reason a console can ask "does this compile?" without paying
        // for a rebuild on every page load.
        Files.setLastModifiedTime(root.resolve("src/Counter.java"),
                FileTime.from(Instant.now().plusSeconds(10)));
        assertFalse(lab.compiled());
    }

    @Test
    @DisplayName("a real run: generate, compile, simulate, bill")
    void runEndToEnd() throws Exception {
        StringBuilder log = new StringBuilder();
        int code = lab.run("main.yaml", log::append);
        assertEquals(0, code, log::toString);

        Path trace = lab.trace("main.yaml");
        assertTrue(Files.exists(trace), log::toString);

        // `bill()` keeps stdout and stderr apart, so "no price list, using the
        // defaults" — printed to stderr — can never land inside the JSON. This
        // checks the JSON actually parses, not just that the file exists.
        Path bill = trace.resolveSibling("main.bill.json");
        assertTrue(Files.exists(bill));
        String text = Files.readString(bill).strip();
        assertTrue(text.startsWith("{") && text.endsWith("}"), () -> "not JSON:\n" + text);
        assertTrue(text.contains("\"observed\""));
    }

    @Test
    @DisplayName("a simulation nobody wrote is refused, not silently skipped")
    void refusesAnUnknownScenario() throws Exception {
        StringBuilder log = new StringBuilder();
        int code = lab.run("ghost.yaml", log::append);
        assertEquals(2, code);
        assertTrue(log.toString().contains("no simulation called ghost.yaml"), log::toString);
    }
}
