import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import losim.cli.Lab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which directories the source walk enters, and which it refuses.
 *
 * <p>{@code NOT_CODE} names the furniture at a lab root — {@code build/},
 * {@code gen/}, {@code scenarios/}, a data directory called {@code input/}. It was
 * once applied at every depth, which made it a list of forbidden <i>package</i>
 * names as well, and {@code input} is an ordinary word that a lab handing students
 * a corpus generator will reasonably use.
 *
 * <p>The failure that caused was silent and misdirected: the package was skipped
 * without a word, 23 files compiled instead of 24, and javac said {@code package
 * input does not exist} on the line that used it — which reads as the student
 * importing something imaginary. Both halves are asserted here, because a walk
 * that entered everything would pass the first half alone.
 */
class LabWalkTest {

    /** A lab, by declaring a toolchain — no lib/ needed to answer this question. */
    private static Lab labAt(Path root) throws Exception {
        Path toolchain = root.resolve(Lab.TOOLCHAIN);
        Files.createDirectories(toolchain.getParent());
        // A real file: a declared classpath none of whose entries exist here is
        // somebody else's, and losim now falls back to lib/ rather than honouring it.
        Path jar = root.resolve("fake-losim.jar");
        Files.writeString(jar, "stands in for the jar");
        Files.writeString(toolchain, "classpath=" + jar + "\n");
        return new Lab(root, root.resolve("lib"), root.resolve("runs"));
    }

    private static void java(Path root, String rel) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "class X {}\n");
    }

    private static List<String> names(Lab lab, Path root) {
        return lab.code().sources().stream().map(p -> root.relativize(p).toString()).sorted().toList();
    }

    @Test
    @DisplayName("a package named for lab furniture is still the student's code")
    void packagesAreNotFurniture(@TempDir Path root) throws Exception {
        Lab lab = labAt(root);
        java(root, "src/input/Corpus.java");
        java(root, "src/Coordinator.java");
        // Every other word on the list, as a package. None of them is losim's to
        // refuse once it is inside the source tree.
        for (String name : List.of("build", "docs", "out", "corpus", "scenarios", "gen")) {
            java(root, "src/" + name + "/Thing.java");
        }
        List<String> found = names(lab, root);
        assertTrue(found.contains("src/input/Corpus.java"), () -> "src/input/Corpus.java missing from " + found);
        assertEquals(8, found.size(), () -> "expected every package to be walked, got " + found);
    }

    @Test
    @DisplayName("and the furniture at the root is still skipped")
    void rootFurnitureSkipped(@TempDir Path root) throws Exception {
        Lab lab = labAt(root);
        java(root, "src/Coordinator.java");
        // The two the comment on NOT_CODE is actually about: output handed to javac
        // twice is how a build starts reporting duplicate classes.
        java(root, "gen/Generated.java");
        java(root, "build/classes/Stale.java");
        java(root, "input/NotCode.java");
        java(root, "scenarios/NotCode.java");
        java(root, ".hidden/Nested.java");
        assertEquals(List.of("src/Coordinator.java"), names(lab, root));
    }

    @Test
    @DisplayName("a reserved root directory holding Java says so, instead of vanishing")
    void reservedSpeaksUp(@TempDir Path root) throws Exception {
        // The narrower trap the root-only rule leaves: a lab keeping its sources at
        // the root rather than under src/ still has `input` reserved there.
        Lab lab = labAt(root);
        java(root, "Coordinator.java");
        java(root, "input/Corpus.java");
        String note = lab.reservedNote();
        assertTrue(note.contains("input"), () -> "expected input/ to be named, got: " + note);
        assertTrue(note.contains("src/"), () -> "expected the fix to be stated, got: " + note);
    }

    @Test
    @DisplayName("and losim's own output directories are not worth a word")
    void ownOutputIsSilent(@TempDir Path root) throws Exception {
        // gen/ is full of Java that losim generated. Reporting it every run would
        // teach people to ignore the line that matters.
        Lab lab = labAt(root);
        java(root, "src/Coordinator.java");
        java(root, "gen/tour/ping/Ping.java");
        java(root, "build/classes/Stale.java");
        assertEquals("", lab.reservedNote());
    }

    @Test
    @DisplayName("a dot-directory is refused at any depth, root or not")
    void dotsAtEveryDepth(@TempDir Path root) throws Exception {
        Lab lab = labAt(root);
        java(root, "src/Coordinator.java");
        java(root, "src/.git/objects/Thing.java");
        assertEquals(List.of("src/Coordinator.java"), names(lab, root));
    }
}
