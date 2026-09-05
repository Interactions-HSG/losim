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

    @Test
    @DisplayName("no lib/ and nothing declared is not a lab")
    void neither(@TempDir Path root) {
        assertFalse(at(root).isLab());
    }

    @Test
    @DisplayName("a declared classpath is a lab, with no lib/ anywhere")
    void declaredIsALab(@TempDir Path root) throws Exception {
        declare(root, "classpath=/x/losim.jar:/x/grpc.jar\n");
        assertTrue(at(root).isLab());
        assertEquals("/x/losim.jar:/x/grpc.jar", at(root).cp());
    }

    @Test
    @DisplayName("a key left out falls back to lib/, so a container keeps its compilers")
    void partial(@TempDir Path root) throws Exception {
        // The case a Gradle lab in a devcontainer is actually in: the build knows
        // the classpath, and the vendored protoc is still the right protoc.
        declare(root, "classpath=/x/losim.jar\n");
        assertEquals("/x/losim.jar", at(root).cp());
        assertTrue(at(root).isLab());
    }

    @Test
    @DisplayName("the file is read again, not remembered")
    void notCached(@TempDir Path root) throws Exception {
        // `losim serve` outlives any number of Gradle invocations. A server holding
        // the classpath from before a dependency was added fails a build in a way
        // nobody can explain from the error.
        Lab lab = at(root);
        declare(root, "classpath=/one.jar\n");
        assertEquals("/one.jar", lab.cp());
        declare(root, "classpath=/two.jar\n");
        assertEquals("/two.jar", lab.cp());
        Files.delete(root.resolve(Lab.TOOLCHAIN));
        assertFalse(lab.isLab());
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
