import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import dissaly.cli.Scan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code dissaly check} says about a simulation, before anything is compiled.
 *
 * <p>This is the first of three answers to the same question, and the only one
 * that answers while somebody is still typing. The loader gives the second, and it
 * cannot see everything: it never loads a class, so it does not know what a
 * service serves. {@code Machines} gives the third by asking the bound server, and
 * that one cannot be fooled. All three exist so a typo is caught early <i>and</i>
 * is impossible to run past — and the reason to test this layer separately is that
 * it is the one a text scan can get wrong.
 *
 * <p>Every assertion checks the line as well as the message. A refusal that cannot
 * be opened in an editor is a refusal somebody reads twice and acts on once.
 */
class ScanTest {

    private static final String PROTO = """
            syntax = "proto3";
            package lab;
            service Thumbnailer {
              rpc Thumbnail (Frame) returns (Thumb) { option idempotency_level = IDEMPOTENT; }
              rpc Pull (Frame) returns (Thumb);
            }
            service Sizer {
              rpc Measure (Frame) returns (Thumb);
            }
            message Frame { string id = 1; }
            message Thumb { bytes png = 1; }
            """;

    /** A project with one service, one entry, and whatever simulation is under test. */
    private static Path project(String yaml) throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "scan-");
        Files.createDirectories(root.resolve("proto"));
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("simulations"));
        Files.writeString(root.resolve("proto/lab.proto"), PROTO);
        Files.writeString(root.resolve("src/Shrinker.java"),
                "package lab;\n"
                + "public final class Shrinker extends lab.ThumbnailerGrpc.ThumbnailerImplBase { }\n");
        Files.writeString(root.resolve("src/Measurer.java"),
                "package lab;\n"
                + "public final class Measurer extends lab.SizerGrpc.SizerImplBase { }\n");
        Files.writeString(root.resolve("src/RenderMaster.java"),
                "package lab;\n"
                + "public final class RenderMaster extends dissaly.pb.JobGrpc.JobImplBase { }\n");
        Files.writeString(root.resolve("simulations/one.yaml"), yaml);
        return root;
    }

    private static void delete(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** The one refusal whose {@code what} contains {@code phrase}, or a failure. */
    private static Scan.Finding refused(Scan s, String phrase) {
        List<Scan.Finding> hits = s.of(Scan.Kind.REFUSED).stream()
                .filter(f -> f.what().contains(phrase)).toList();
        assertEquals(1, hits.size(), "expected exactly one refusal saying '" + phrase
                + "', got: " + s.of(Scan.Kind.REFUSED).stream().map(Scan.Finding::what).toList());
        return hits.get(0);
    }

    private static void run(String yaml, java.util.function.Consumer<Scan> then) throws Exception {
        Path root = project(yaml);
        try { then.accept(Scan.of(root)); } finally { delete(root); }
    }

    @Test
    @DisplayName("the one shape that is right says nothing at all")
    void theGoodShape() throws Exception {
        run("""
            seed: 7
            nodes:
              master:
                instance: m5.large
                runs: { dissaly.Job: src/RenderMaster.java }
              w9:
                instance: c5.large
                runs:
                  Thumbnailer:
                    file: src/Shrinker.java
                    failures:
                      Thumbnail:
                        - { status: UNAVAILABLE, per: 20 calls }
                failures:
                  - { kill: true, at: 400 refMs }
            input:
              unit: frame
              count: 30000
            """, s -> assertEquals(List.of(), s.of(Scan.Kind.REFUSED),
                    "the shorthand, the longhand, both levels of failure and an input"));
    }

    @Test
    @DisplayName("an rpc the service does not serve is refused, and the ones it does are named")
    void anRpcThatIsNotThere() throws Exception {
        run("""
            seed: 7
            nodes:
              w9:
                instance: c5.large
                runs:
                  Thumbnailer:
                    file: src/Shrinker.java
                    failures:
                      Thumbnil:
                        - { drop: true, per: 20 calls }
            """, s -> {
            var f = refused(s, "serves no rpc called Thumbnil");
            assertTrue(f.why().contains("Thumbnail") && f.why().contains("Pull"),
                    "a refusal that does not say what it does serve makes the reader"
                    + " go and open the .proto: " + f.why());
            assertEquals(9, f.where().line(),
                    "the line of the rpc name, not of the failures: block it is in");
        });
    }

    @Test
    @DisplayName("runs: written as a list, which is one name where two are needed")
    void runsAsAList() throws Exception {
        run("""
            seed: 7
            nodes:
              w9:
                instance: c5.large
                runs:
                  - lab.Shrinker
            """, s -> {
            var f = refused(s, "runs: is written as a list");
            assertEquals(6, f.where().line());
            // And not also complained about as a bad path: it is one mistake.
            assertTrue(s.of(Scan.Kind.REFUSED).stream()
                            .noneMatch(x -> x.what().contains("not a .java file")),
                    "a list is refused once, not once per entry");
        });
    }

    @Test
    @DisplayName("a class name where a file path goes")
    void runsNamingAClass() throws Exception {
        run("""
            seed: 7
            nodes:
              w9:
                instance: c5.large
                runs: { Thumbnailer: lab.Shrinker }
            """, s -> {
            var f = refused(s, "Thumbnailer is placed as lab.Shrinker");
            assertEquals(5, f.where().line());
        });
    }

    @Test
    @DisplayName("a service filed under its full name is checked like one filed under its last word")
    void eitherFormOfTheName() throws Exception {
        // `lab.Thumbnailer` is what gRPC puts on the wire and `Thumbnailer` is what
        // almost everybody writes. Both reach the same server, so a scan that knew
        // only one of them would fall silent for whoever wrote the other — which
        // reads as a file with nothing wrong in it.
        run("""
            seed: 7
            nodes:
              w9:
                instance: c5.large
                runs:
                  lab.Thumbnailer:
                    file: src/Shrinker.java
                    failures:
                      Thumbnil:
                        - { drop: true, per: 20 calls }
            """, s -> {
            var f = refused(s, "serves no rpc called Thumbnil");
            assertTrue(f.why().contains("Thumbnail") && f.why().contains("Pull"), f.why());
            assertEquals(9, f.where().line());
        });
    }

    @Test
    @DisplayName("every top-level key 3.0 deleted, each naming what to write instead")
    void keysThatAreGone() throws Exception {
        run("""
            job: lab.Render
            mode: direct
            tightMargin: true
            machines:
              only: { instance: m5.large }
            takes:
              lab.Shrinker: { Thumbnail: 3 refMs }
            faults:
              - { kill: only, at: 100 refMs }
            chaos:
              - { degrade: 2, per: 1 refSeconds }
            """, s -> {
            for (String gone : List.of("job", "mode", "tightMargin", "machines",
                                       "takes", "faults", "chaos")) {
                refused(s, gone + ": is not read any more");
            }
            assertTrue(refused(s, "machines: is not read any more").why().contains("nodes:"),
                    "naming the key that is gone is half of it; the other half is the"
                    + " one to write instead");
        });
    }

    @Test
    @DisplayName("an rpc belongs to the service it is written under, not to the node")
    void eachServiceHasItsOwnRpcs() throws Exception {
        // Two services on one node, and one rpc name that is real on the second and
        // not on the first. This is the whole reason failures: is written inside
        // runs: rather than beside it, and a scan that only checked the name against
        // every rpc in the project would pass this file and say nothing.
        run("""
            seed: 7
            nodes:
              w9:
                instance: c5.large
                runs:
                  Thumbnailer:
                    file: src/Shrinker.java
                    failures:
                      Measure:
                        - { drop: true, per: 20 calls }
                  Sizer:
                    file: src/Measurer.java
                    failures:
                      Measure:
                        - { drop: true, per: 20 calls }
            """, s -> {
            var f = refused(s, "Thumbnailer serves no rpc called Measure");
            assertEquals(9, f.where().line(),
                    "the first Measure, under Thumbnailer — the second one is correct");
            assertEquals(1, s.of(Scan.Kind.REFUSED).size(),
                    "the same rpc name under the service that does serve it is fine: "
                    + s.of(Scan.Kind.REFUSED).stream().map(Scan.Finding::what).toList());
        });
    }
}
