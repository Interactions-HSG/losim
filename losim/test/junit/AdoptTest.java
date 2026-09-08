import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import losim.cli.Adopt;
import losim.cli.Scan;
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

    private static Scan scan() throws Exception {
        return Scan.of(fixture());
    }

    /** A lab written against 1.5.0: it reads its size from the cluster. */
    private static Scan scaled() throws Exception {
        for (Path p : List.of(Path.of("losim/test/fixtures/scaled"),
                              Path.of("../losim/test/fixtures/scaled"))) {
            if (Files.isDirectory(p)) return Scan.of(p);
        }
        throw new IllegalStateException("no scaled fixture beside this test");
    }

    private static boolean saw(Scan s, Scan.Kind kind, String contains) {
        return s.of(kind).stream().anyMatch(f -> f.what().contains(contains));
    }

    @Test
    @DisplayName("the schema is read without protoc, and the service with it")
    void schema() throws Exception {
        Scan s = scan();
        assertEquals(1, s.rpcs().size());
        Scan.Rpc rpc = s.rpcs().get(0);
        // The name the schema announces, package and all: that is what gRPC puts
        // on the wire and what a `runs:` entry files the file under.
        assertEquals("helloworld.Greeter", rpc.service());
        assertEquals("SayHello", rpc.name());
        assertFalse(rpc.streaming());
        assertFalse(rpc.idempotent(), "the quickstart declares no idempotency_level");
    }

    @Test
    @DisplayName("the handler is found where it actually is: nested inside the server")
    void nested() throws Exception {
        Scan s = scan();
        assertEquals(1, s.services().size());
        Scan.Service greeter = s.services().get(0);
        assertEquals("GreeterImpl", greeter.name());
        assertTrue(greeter.nested(), "it is a static nested class, which is the whole problem");
        assertEquals("io.grpc.examples.helloworld.GreeterImpl", greeter.qualified(),
                "the loader derives this from the path, so the package has to come with it");
    }

    @Test
    @DisplayName("a nested handler will not run at all; a shutdown hook is merely dead")
    void classification() throws Exception {
        Scan s = scan();
        // The distinction the entire report is built on. A nested class has no path
        // that reaches it — `runs:` names a file, and a file loads the class it is
        // named after — so there is no line anybody could write that would place it.
        assertTrue(saw(s, Scan.Kind.REFUSED, "nested"));
        assertTrue(saw(s, Scan.Kind.UNTRUSTWORTHY, "channel"),
                "the client builds its own, which no interceptor is attached to");
        // These cost nothing and mean nothing, and no run will ever mention them —
        // which is why they are listed at all.
        assertTrue(saw(s, Scan.Kind.DEAD, "addShutdownHook"));
        assertTrue(saw(s, Scan.Kind.DEAD, "main("));
        assertTrue(saw(s, Scan.Kind.DEAD, "newFixedThreadPool"));
        assertTrue(saw(s, Scan.Kind.DEAD, "grpc-netty-shaded"));
        assertTrue(saw(s, Scan.Kind.MISSING, "idempotency_level"));
    }

    @Test
    @DisplayName("a lab from before 3.0 is refused in Java and in YAML, and told what to write")
    void theBreak() throws Exception {
        Scan s = scaled();
        // Both halves, because a project converted in only one of them is the
        // ordinary half-done state and the report has to name what is left.
        assertTrue(saw(s, Scan.Kind.REFUSED, "implements losim.api.Job"),
                "a type that is gone is a project that will not compile, not a hint");
        assertTrue(saw(s, Scan.Kind.REFUSED, "losim.api.Cluster"),
                "and what the job was handed is gone with it");
        assertTrue(saw(s, Scan.Kind.REFUSED, "job: is not read any more"),
                "the key that named it is gone too, and the file says so before javac does");

        // Everything above is a refusal. A project part-way through this is not
        // running at all, so nothing here may come back as merely worth doing —
        // that heading is for a run that happens and is less than it could be.
        assertTrue(s.of(Scan.Kind.MISSING).stream()
                        .noneMatch(f -> String.valueOf(f.what()).contains("losim.api")),
                "a deleted type is refused, never suggested");
    }

    @Test
    @DisplayName("versions come off the build file even when it names them by variable")
    void versions() throws Exception {
        Scan s = scan();
        assertEquals("1.83.1", s.grpcVersion());
        assertEquals("3.25.9", s.protobufVersion(),
                "the quickstart pins an old protobuf; adopting must not silently"
                + " re-pin it to losim's own");
    }

    @Test
    @DisplayName("a project with no schema and no service is not one to adopt")
    void nothingToAdopt(@TempDir Path empty) throws Exception {
        Files.writeString(empty.resolve("build.gradle.kts"), "plugins { java }\n");
        Scan s = Scan.of(empty);
        assertTrue(s.rpcs().isEmpty());
        assertTrue(s.services().isEmpty());
    }

    @Test
    @DisplayName("a lab with a committed lib/ keeps every byte of it, out of the index")
    void vendored(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("proto"));
        Files.createDirectories(root.resolve("simulations"));
        Files.writeString(root.resolve("lib/losim.jar"), "stands in for the simulator");
        Files.writeString(root.resolve("proto/lab.proto"), """
                syntax = "proto3";
                package lab;
                message Chunk { string text = 1; }
                service Worker { rpc Map (Chunk) returns (Chunk); }
                """);
        Files.writeString(root.resolve("simulations/mine.yaml"), "job: Mine\n");
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
        // A lab has simulations of its own. Writing a first one into it would be a
        // file nobody asked for, beside the ones they wrote.
        assertFalse(Files.exists(root.resolve("simulations/1-one-call.yaml")));
        assertEquals("job: Mine\n", Files.readString(root.resolve("simulations/mine.yaml")));
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
