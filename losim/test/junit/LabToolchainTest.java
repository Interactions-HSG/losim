import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import losim.cli.Lab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lab whose build declares the toolchain, rather than one losim goes looking for.
 *
 * <p>A lab that resolves losim with Gradle has no {@code lib/} and cannot sensibly
 * be given one: its jars sit in a package cache, under names the build chose. Until
 * {@link Lab#TOOLCHAIN} existed such a lab had to keep an otherwise empty
 * {@code lib/} beside it purely so that {@code isLab} and {@code cp} had something
 * to look at — a directory that existed to be found, holding a second copy of what
 * the build had already resolved and free to disagree with it.
 *
 * <p>What is asserted here is the seam itself, because the failure it prevents is
 * silent: a lab losim declines to recognise shows a student an empty console with
 * no error in it.
 */
class LabToolchainTest {

    private static Lab at(Path root) {
        return new Lab(root, root.resolve("lib"), root.resolve("runs"));
    }

    private static void declare(Path root, String body) throws Exception {
        Path file = root.resolve(Lab.TOOLCHAIN);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    /**
     * A path that exists, so that a declaration is one this machine could use.
     *
     * <p>Every classpath here would otherwise name nothing real, which losim now
     * reads — correctly — as a declaration copied in from somewhere else.
     */
    private static String jar(Path root, String name) throws Exception {
        Path p = root.resolve(name);
        Files.writeString(p, "stands in for a jar");
        return p.toString();
    }

    @Test
    @DisplayName("no lib/ and nothing declared is not a lab")
    void neither(@TempDir Path root) {
        assertFalse(at(root).isLab());
    }

    @Test
    @DisplayName("a declared classpath is a lab, with no lib/ anywhere")
    void declaredIsALab(@TempDir Path root) throws Exception {
        String cp = jar(root, "losim.jar") + java.io.File.pathSeparator + jar(root, "grpc.jar");
        declare(root, "classpath=" + cp + "\n");
        assertTrue(at(root).isLab());
        assertEquals(cp, at(root).cp());
    }

    @Test
    @DisplayName("a key left out falls back to lib/, so a container keeps its compilers")
    void partial(@TempDir Path root) throws Exception {
        // The case a Gradle lab in a devcontainer is actually in: the build knows
        // the classpath, and the vendored protoc is still the right protoc.
        String cp = jar(root, "losim.jar");
        declare(root, "classpath=" + cp + "\n");
        assertEquals(cp, at(root).cp());
        assertTrue(at(root).isLab());
    }

    @Test
    @DisplayName("the file is read again, not remembered")
    void notCached(@TempDir Path root) throws Exception {
        // `losim serve` outlives any number of Gradle invocations. A server holding
        // the classpath from before a dependency was added fails a build in a way
        // nobody can explain from the error.
        Lab lab = at(root);
        String one = jar(root, "one.jar"), two = jar(root, "two.jar");
        declare(root, "classpath=" + one + "\n");
        assertEquals(one, lab.cp());
        declare(root, "classpath=" + two + "\n");
        assertEquals(two, lab.cp());
        Files.delete(root.resolve(Lab.TOOLCHAIN));
        assertFalse(lab.isLab());
    }

    @Test
    @DisplayName("a classpath from somebody else's machine is not this machine's")
    void foreignClasspath(@TempDir Path root) throws Exception {
        // Marking a submission means copying a working directory that has run
        // Gradle, and the file travels with it holding absolute paths from a
        // laptop. Honouring those in a container reported the candidate's code as
        // broken while the right jars sat in lib/.
        declare(root, "classpath=/Users/someone/build/losim.jar:/Users/someone/.gradle/grpc.jar\n");
        Lab lab = at(root);
        assertFalse(lab.isLab(), "nothing on that classpath exists here");

        // lib/ is what such a directory should fall back to, and does.
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/losim.jar"), "present");
        assertTrue(lab.isLab());
        assertTrue(lab.cp().contains("lib"), () -> "expected the fallback, got " + lab.cp());
    }

    @Test
    @DisplayName("one surviving entry is enough — a half-built classpath is still the build's")
    void partiallyPresent(@TempDir Path root) throws Exception {
        Path real = root.resolve("real.jar");
        Files.writeString(real, "a jar");
        declare(root, "classpath=" + real + ":/nowhere/absent.jar\n");
        Lab lab = at(root);
        assertTrue(lab.isLab());
        assertTrue(lab.cp().contains("absent.jar"), "the declaration is used whole, not filtered");
    }

    @Test
    @DisplayName("the fallback says what it disregarded, rather than working silently")
    void saysWhatItIgnored(@TempDir Path root) throws Exception {
        // Two machines disagreeing about one lab, where the difference is a file one
        // of them carries, is the case silence costs: the working machine and the
        // machine that would have failed otherwise print the same thing.
        declare(root, "classpath=/Users/someone/losim.jar\nprotoc=/Users/someone/protoc-osx-aarch_64\n");
        String note = at(root).toolchainNote();
        assertTrue(note.contains("classpath"), () -> "the classpath is not named: " + note);
        assertTrue(note.contains("protoc"), () -> "the compiler is not named: " + note);
        assertTrue(note.contains("lib/"), () -> "what it used instead is not named: " + note);
    }

    @Test
    @DisplayName("and says nothing when there is nothing to say")
    void quietWhenHonoured(@TempDir Path root) throws Exception {
        // A declaration this machine can use is used, and a run that ignored nothing
        // must not print a line about it — a note that fires every time is noise.
        declare(root, "classpath=" + jar(root, "losim.jar") + "\n");
        assertEquals("", at(root).toolchainNote());
        Files.delete(root.resolve(Lab.TOOLCHAIN));
        assertEquals("", at(root).toolchainNote(), "no file, nothing to report");
    }

    @Test
    @DisplayName("an unreadable toolchain is not an answer, and lib/ may still be one")
    void unreadable(@TempDir Path root) throws Exception {
        // A directory where the file should be: load() fails, and the fallback has
        // to be the vendored lib/ rather than an exception out of isLab().
        Files.createDirectories(root.resolve(Lab.TOOLCHAIN));
        Lab lab = at(root);
        assertFalse(lab.isLab());
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/losim.jar"), "not really a jar, but present");
        assertTrue(lab.isLab());
    }
}
