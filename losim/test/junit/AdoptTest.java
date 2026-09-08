import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import losim.cli.Adopt;
import losim.cli.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code losim adopt} sees in a project shaped like the grpc-java quickstart.
 *
 * <p>Against a checked-in fixture rather than against a string built here, because
 * the thing being tested is a reading of somebody else's file — and a fixture
 * written to agree with the detector would agree with it however wrong both were.
 *
 * <p>What is asserted is the <b>classification</b>, not the wording. A nested
 * handler must come back as something that makes a number wrong; a shutdown hook
 * must come back as dead. Those two being the same class is the failure this
 * exists to catch, because the whole report is built on their being different.
 *
 * <p>The last case is the other shape entirely: a lab from before 1.5.0, carrying
 * the simulator as committed jars. What it asserts is that the jars are still on
 * disk afterwards — a conversion that deleted them would be one nobody could
 * safely run to find out what it does.
 */
class AdoptTest {

    /** The fixture, found from this test's own working directory. */
    private static Path fixture() {
        for (Path p : List.of(Path.of("losim/test/fixtures/quickstart"),
                              Path.of("../losim/test/fixtures/quickstart"))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("no quickstart fixture beside this test");
    }

    private static Shape shape() throws Exception {
        return Shape.of(fixture());
    }

    /** A lab written against 1.5.0: it reads its size from the cluster. */
    private static Shape scaled() throws Exception {
        for (Path p : List.of(Path.of("losim/test/fixtures/scaled"),
                              Path.of("../losim/test/fixtures/scaled"))) {
            if (Files.isDirectory(p)) return Shape.of(p);
        }
        throw new IllegalStateException("no scaled fixture beside this test");
    }

    private static boolean saw(Shape s, Shape.Kind kind, String contains) {
        return s.of(kind).stream().anyMatch(f -> f.what().contains(contains));
    }

    @Test
    @DisplayName("the schema is read without protoc, and the service with it")
    void schema() throws Exception {
        Shape s = shape();
        assertEquals(1, s.rpcs().size());
        Shape.Rpc rpc = s.rpcs().get(0);
        assertEquals("Greeter", rpc.service());
        assertEquals("SayHello", rpc.name());
        assertFalse(rpc.streaming());
        assertFalse(rpc.idempotent(), "the quickstart declares no idempotency_level");
    }

    @Test
    @DisplayName("the handler is found where it actually is: nested inside the server")
    void nested() throws Exception {
        Shape s = shape();
        assertEquals(1, s.services().size());
        Shape.Service greeter = s.services().get(0);
        assertEquals("GreeterImpl", greeter.name());
        assertTrue(greeter.nested(), "it is a static nested class, which is the whole problem");
        assertEquals("io.grpc.examples.helloworld.GreeterImpl", greeter.qualified(),
                "runs: names a class on a classpath, so the package has to come with it");
    }

    @Test
    @DisplayName("a nested handler is a wrong number; a shutdown hook is merely dead")
    void classification() throws Exception {
        Shape s = shape();
        // The distinction the entire report is built on. A nested class is walked
        // together with the class enclosing it, so the bootstrap's own server and
        // statics are read as the service's and every figure it reports is marked.
        assertTrue(saw(s, Shape.Kind.UNTRUSTWORTHY, "nested"));
        assertTrue(saw(s, Shape.Kind.UNTRUSTWORTHY, "channel"),
                "the client builds its own, which no interceptor is attached to");
        // These cost nothing and mean nothing, and no run will ever mention them —
        // which is why they are listed at all.
        assertTrue(saw(s, Shape.Kind.DEAD, "addShutdownHook"));
        assertTrue(saw(s, Shape.Kind.DEAD, "main("));
        assertTrue(saw(s, Shape.Kind.DEAD, "newFixedThreadPool"));
        assertTrue(saw(s, Shape.Kind.DEAD, "grpc-netty-shaded"));
        assertTrue(saw(s, Shape.Kind.MISSING, "idempotency_level"));
        assertTrue(s.of(Shape.Kind.REFUSED).isEmpty(),
                "the quickstart is unary and protoc-generated, so nothing here refuses it");
    }

    @Test
    @DisplayName("a call to cluster.records() will not run, and a scaled plain Job merely will not scale")
    void theBreak() throws Exception {
        Shape s = scaled();
        assertTrue(saw(s, Shape.Kind.REFUSED, "cluster.records() no longer exists"),
                "a method that is gone is a project that will not compile, not a hint");
        // And the second one is deliberately *not* refused. The run does happen; it
        // is the model that cannot be built, which is a different sentence and a
        // different heading in the report.
        assertTrue(saw(s, Shape.Kind.MISSING, "Filler cannot be run at another size"),
                "a scenario asking for a model of forty times the run, driven by a plain Job");
        assertFalse(saw(s, Shape.Kind.REFUSED, "cannot be run at another size"),
                "a plain Job under a scaled scenario runs — it just cannot be modelled");
    }

    @Test
    @DisplayName("versions come off the build file even when it names them by variable")
    void versions() throws Exception {
        Shape s = shape();
        assertEquals("1.83.1", s.grpcVersion());
        assertEquals("3.25.9", s.protobufVersion(),
                "the quickstart pins an old protobuf; adopting must not silently"
                + " re-pin it to losim's own");
    }

    @Test
    @DisplayName("a project with no schema and no service is not one to adopt")
    void nothingToAdopt(@TempDir Path empty) throws Exception {
        Files.writeString(empty.resolve("build.gradle.kts"), "plugins { java }\n");
        Shape s = Shape.of(empty);
        assertTrue(s.rpcs().isEmpty());
        assertTrue(s.services().isEmpty());
    }

    @Test
    @DisplayName("a lab with a committed lib/ keeps every byte of it, out of the index")
    void vendored(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("proto"));
        Files.createDirectories(root.resolve("scenarios"));
        Files.writeString(root.resolve("lib/losim.jar"), "stands in for the simulator");
        Files.writeString(root.resolve("proto/lab.proto"), """
                syntax = "proto3";
                package lab;
                message Chunk { string text = 1; }
                service Worker { rpc Map (Chunk) returns (Chunk); }
                """);
        Files.writeString(root.resolve("scenarios/mine.yaml"), "job: Mine\n");
        git(root, "init", "-q");
        git(root, "add", "-A");
        git(root, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "a lab as it was");

        assertEquals(0, Adopt.main(new String[]{"adopt", root.toString()}));

        assertTrue(Files.isRegularFile(root.resolve("lib/losim.jar")),
                "the jars are untracked, never deleted");
        assertFalse(git(root, "ls-files", "lib").contains("losim.jar"),
                "and they are out of the index, or the next commit carries them again");
        assertTrue(Files.isRegularFile(root.resolve("build.gradle.kts")));
        assertTrue(Files.readString(root.resolve(".gitignore")).contains("lib/"));
        // A lab has scenarios of its own. Writing a first one into it would be a
        // file nobody asked for, beside the ones they wrote.
        assertFalse(Files.exists(root.resolve("scenarios/1-one-call.yaml")));
        assertEquals("job: Mine\n", Files.readString(root.resolve("scenarios/mine.yaml")));
    }

    private static String git(Path root, String... argv) throws Exception {
        var command = new java.util.ArrayList<String>(List.of("git"));
        command.addAll(List.of(argv));
        Process p = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
