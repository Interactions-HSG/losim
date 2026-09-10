import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import dissaly.cli.Lab;
import dissaly.cli.Palette;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the assignment's code offers, read off its compiled bytecode.
 *
 * <p>{@code Palette} answers the question a {@code runs:} entry asks: which file
 * can a node be given, and what does it thereby serve. Everything it lists is a
 * pair somebody could write down — so a class that is bindable and has no path to
 * it is counted rather than named, and the one that answers to {@code dissaly.Job}
 * is in the same list as everything else with a flag on it. {@link Fixture} ships
 * one of each, plus {@code Volley} — a second gRPC service in the schema that
 * nothing implements — so the "generated but unimplemented" case is exercised
 * too, not just the happy path.
 */
class PaletteTest {

    static Path root;
    static Lab lab;
    static Palette.Offer offer;

    @BeforeAll
    static void build() throws Exception {
        root = Fixture.build();
        lab = new Lab(root);
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
    @DisplayName("every offer is a file a runs: entry could name, in the order they sort")
    void findsTheFiles() {
        assertEquals(List.of("src/Counter.java", "src/NoisyJob.java", "src/WordCountJob.java"),
                offer.services().stream().map(Palette.Service::file).toList());
    }

    @Test
    @DisplayName("a file is offered under the service its class implements, with its rpcs")
    void findsTheService() {
        Palette.Service worker = offer.services().stream()
                .filter(sv -> sv.file().equals("src/Counter.java")).findFirst().orElseThrow();
        // The name gRPC puts on the wire, which is what peersServing finds it by —
        // and not the name of the class, which a simulation never says at all.
        assertEquals("dissaly.t.Worker", worker.service());
        assertEquals("Worker", worker.bare());
        assertFalse(worker.entry(), "Worker is not where the simulation starts");

        var byName = worker.rpcs().stream()
                .collect(java.util.stream.Collectors.toMap(Palette.Rpc::name, Palette.Rpc::idempotent));
        assertEquals(java.util.Set.of("Map", "Reduce"), byName.keySet());
        // lab.proto declares both NO_SIDE_EFFECTS — the whole point of carrying
        // this flag is that a retry policy on an rpc that did not declare itself
        // safe is refused at run time, not silently allowed.
        assertTrue(byName.get("Map"));
        assertTrue(byName.get("Reduce"));
    }

    @Test
    @DisplayName("a class answering to dissaly.Job is marked in the one list, not held in a second")
    void marksTheEntries() {
        assertEquals(List.of("src/NoisyJob.java", "src/WordCountJob.java"),
                offer.services().stream().filter(Palette.Service::entry)
                        .map(Palette.Service::file).toList());
        assertTrue(offer.services().stream().filter(Palette.Service::entry)
                        .allMatch(sv -> sv.service().equals("dissaly.Job")),
                "the entry is the service called dissaly.Job, and nothing else makes it one");
    }

    @Test
    @DisplayName("a service extended from an abstract base is offered once, as the file that is concrete")
    void abstractBasesAreNotOffered() {
        assertTrue(offer.services().stream().noneMatch(s -> s.file().equals("src/WorkerBase.java")),
                "WorkerBase is abstract: it cannot be constructed, so no node can run it");
    }

    @Test
    @DisplayName("a service nested inside another class is counted, because no path reaches it")
    void aNestedServiceIsNotOffered() {
        // Bundle.Inner does implement Volley. It is still not on offer: a `runs:`
        // value is a path, and src/Bundle.java loads Bundle, which serves nothing.
        // Offering it would be offering a line that starts and then binds nothing.
        assertTrue(offer.services().stream().noneMatch(s -> s.bare().equals("Volley")),
                "src/Bundle.java would place Bundle, not Bundle.Inner");
        assertTrue(offer.services().stream().noneMatch(s -> s.file().equals("src/Bundle.java")),
                "a file whose class serves nothing is not something a node can run");
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
    @DisplayName("classes with the schema in it, one entry and one service, is not zero of anything")
    void otherIsCounted() {
        // Generated message and stub classes (Chunk, Counts, WorkerGrpc's stubs,
        // Volley's own ImplBase…) are neither an entry nor a placeable service, and
        // Palette still has to say something about them: the difference between
        // "nothing here is a service" and "nothing here compiled" is this number.
        assertTrue(offer.other() > 0, "expected generated protobuf/grpc classes to be counted");
    }
}
