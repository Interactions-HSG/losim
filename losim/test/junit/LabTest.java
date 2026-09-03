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

    @BeforeAll
    static void build() throws Exception {
        root = Fixture.build();
        lab = new Lab(root, root.resolve("lib"));
    }

    @AfterAll
    static void clean() throws Exception {
        Fixture.delete(root);
    }

    @Test
    @DisplayName("a lab is a directory with losim.jar in it, and nothing else makes one")
    void isALab() throws Exception {
        assertTrue(lab.isLab());

        Path empty = Files.createTempDirectory(Path.of("build"), "not-a-lab-");
        try {
            // The comment on isLab() is explicit about why this matters: without
            // it, pointing the server at the wrong directory lists that
            // directory's own furniture as things to run.
            assertFalse(new Lab(empty, empty.resolve("lib")).isLab());
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
        // Counter, WorkerBase, WordCountJob, NoisyJob — never lib/, scenarios/,
        // or anything gen/ has not been asked to produce yet.
        assertEquals(4, c.sources().size());
        assertTrue(c.sources().stream().noneMatch(p -> p.toString().contains("lib" + java.io.File.separator)));
    }

    @Test
    @DisplayName("scenarios come from scenarios/, and from a loose file at the root")
    void findsScenariosInTheFolderAndAtTheRoot() throws Exception {
        Path loose = root.resolve("loose.yaml");
        Files.writeString(loose, "job: WordCountJob\nmachines:\n  a: { instance: m5.large, zone: eu-central-1a }\n");
        try {
            var names = lab.scenarioNames();
            assertTrue(names.contains("main.yaml"));
            assertTrue(names.contains("loose.yaml"));
        } finally {
            Files.deleteIfExists(loose);
        }
    }

    @Test
    @DisplayName("a scenario by name, and the first one when none is named")
    void scenarioLookup() {
        assertNotNull(lab.scenario("main.yaml"));
        assertEquals("main.yaml", lab.scenario(null).getFileName().toString());
        assertEquals("main.yaml", lab.scenario("").getFileName().toString());
        assertNull(lab.scenario("nope.yaml"));
    }

    @Test
    @DisplayName("a trace is named after its scenario, not the other way round")
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

        // The bug this repeats: `bill()` used to merge stdout and stderr, so "no
        // price list, using the defaults" landed inside the JSON and nothing
        // could read it. This is the JSON parsing, not just existing.
        Path bill = trace.resolveSibling("main.bill.json");
        assertTrue(Files.exists(bill));
        String text = Files.readString(bill).strip();
        assertTrue(text.startsWith("{") && text.endsWith("}"), () -> "not JSON:\n" + text);
        assertTrue(text.contains("\"observed\""));
    }

    @Test
    @DisplayName("a scenario nobody wrote is refused, not silently skipped")
    void refusesAnUnknownScenario() throws Exception {
        StringBuilder log = new StringBuilder();
        int code = lab.run("ghost.yaml", log::append);
        assertEquals(2, code);
        assertTrue(log.toString().contains("no scenario called ghost.yaml"), log::toString);
    }
}
