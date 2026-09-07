import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
