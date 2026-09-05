import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import losim.cli.Lab;
import losim.cli.Palette;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the lab's code offers, read off its compiled bytecode.
 *
 * <p>{@code Palette} draws a line the console's whole authoring story depends
 * on: a class is a {@code Job}, or it is a service (bindable, with methods and
 * an idempotency flag apiece), or it is neither and is counted rather than
 * named. {@link Fixture} ships one of each, plus {@code Volley} — a second gRPC
 * service in the schema that nothing implements — so the "generated but
 * unimplemented" case is exercised too, not just the happy path.
 */
class PaletteTest {

    static Path root;
    static Lab lab;
    static Palette.Offer offer;

    @BeforeAll
    static void build() throws Exception {
        root = Fixture.build();
        lab = new Lab(root, root.resolve("lib"));
        StringBuilder log = new StringBuilder();
        Path classes = lab.compile(log::append);
        assertNotNull(classes, log::toString);
        offer = Palette.of(classes, lab, lab.code().sources());
    }

    @AfterAll
    static void clean() throws Exception {
        Fixture.delete(root);
    }

    @Test
    @DisplayName("a class implementing Job is a job, alphabetically")
    void findsTheJobs() {
        assertEquals(java.util.List.of("NoisyJob", "WordCountJob"), offer.jobs());
    }

    @Test
    @DisplayName("a class extending a service's ImplBase is a service, with its methods")
    void findsTheService() {
        assertEquals(1, offer.services().size(),
                () -> "found: " + offer.services().stream().map(Palette.Service::cls).toList());
        Palette.Service worker = offer.services().get(0);
        assertEquals("Counter", worker.cls());
        assertEquals("Worker", worker.service());
        assertEquals("losim.t.Worker", worker.qualified());

        var byName = worker.methods().stream()
                .collect(java.util.stream.Collectors.toMap(Palette.Method::name, Palette.Method::idempotent));
        assertEquals(java.util.Set.of("Map", "Reduce"), byName.keySet());
        // lab.proto declares both NO_SIDE_EFFECTS — the whole point of carrying
        // this flag is that a retry policy on a method that did not declare
        // itself safe is refused at run time, not silently allowed.
        assertTrue(byName.get("Map"));
        assertTrue(byName.get("Reduce"));
    }

    @Test
    @DisplayName("Volley has no implementing class, so it is not offered as a service")
    void unimplementedServiceIsNotOffered() {
        assertTrue(offer.services().stream().noneMatch(s -> s.service().equals("Volley")),
                "the schema declares Volley; nothing in src/ implements it, and nothing should claim it does");
    }

    @Test
    @DisplayName("a service is pointed back at the file it was written in")
    void pointsAtItsOwnSource() {
        Palette.Service worker = offer.services().get(0);
        assertEquals("src/Counter.java", worker.source());
    }

    @Test
    @DisplayName("nothing of the student's is executed to read the palette")
    void nothingIsExecuted() throws Exception {
        // NoisyJob's static initializer writes this file if it ever runs.
        // Class.forName(name, false, loader) loads and links the class without
        // initialising it, which is the whole guarantee this class exists to keep.
        assertTrue(Files.notExists(Fixture.marker(root)),
                "NoisyJob's static initializer ran — Palette executed student code to list it");
    }

    @Test
    @DisplayName("classes with the schema in it, one job and one service, is not zero of anything")
    void otherIsCounted() {
        // Generated message and stub classes (Chunk, Counts, WorkerGrpc's stubs,
        // Volley's own ImplBase…) are neither a Job nor a placeable service, and
        // Palette still has to say something about them: the difference between
        // "nothing here is a service" and "nothing here compiled" is this number.
        assertTrue(offer.other() > 0, "expected generated protobuf/grpc classes to be counted");
    }
}
