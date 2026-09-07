package losim.cli;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where a directory that travels inside the jar actually is.
 *
 * <p>The viewer and the manual are static files, and a lab used to carry both as
 * committed directories put there by a maintainer's script. They ride in the jar
 * instead, so that depending on losim is the whole of getting losim: one
 * coordinate, and the console and the manual come with it.
 *
 * <p><b>A {@link Path}, not a stream.</b> {@code Serve} and {@code Manual} are
 * written entirely against {@code Files.walk}, {@code Files.readAllBytes},
 * {@code resolve} and {@code startsWith}, and every one of those works unchanged
 * on a path inside a zip filesystem. So nothing about serving changes — the only
 * question either of them asks is where its root is.
 *
 * <p>Nothing is unpacked to a temporary directory. That would add a lifetime to
 * manage, two processes to race over it — {@code losim serve} and {@code losim
 * serve docs} are deliberately separate — and a pause on the first request, in
 * exchange for nothing: the zip filesystem reads the same bytes from the same
 * file.
 */
final class Bundled {

    private Bundled() {}

    /**
     * The bundled {@code name} directory, or null if this jar was built without it.
     *
     * @param marker a file that must be in it, which is also how the directory is
     *               found: a classloader can open a resource but cannot list one,
     *               so something inside has to be named to locate the rest.
     */
    static Path dir(String name, String marker) {
        URL found = Bundled.class.getResource("/losim/" + name + "/" + marker);
        if (found == null) return null;
        try {
            URI uri = found.toURI();
            // Running off build/classes rather than a jar, which is how losim's own
            // repository runs it. The resource is an ordinary file and its parent is
            // an ordinary directory.
            if ("file".equals(uri.getScheme())) return Path.of(uri).getParent();

            // jar:file:/…/losim.jar!/losim/docs/docs.json — the filesystem is the
            // jar, and the path inside it is what gets handed back.
            String whole = uri.toString();
            int bang = whole.indexOf("!/");
            if (bang < 0) return null;
            FileSystem fs = open(URI.create(whole.substring(0, bang + 2)));
            return fs.getPath("/losim/" + name);
        } catch (Exception e) {
            // A jar that cannot be opened as a filesystem is a jar with no manual
            // in it, as far as anything downstream is concerned — and both callers
            // already have something to say about that.
            return null;
        }
    }

    /**
     * The jar, as a filesystem, opened once and never closed.
     *
     * <p>Both lookups resolve to the same jar, so the second one finds it already
     * open — which is not an error and is the ordinary case. Closing it would
     * invalidate every path handed out, and the only moment it could safely happen
     * is the one where the process is ending anyway.
     */
    private static synchronized FileSystem open(URI jar) throws IOException {
        try {
            return FileSystems.newFileSystem(jar, Map.<String, Object>of());
        } catch (FileSystemAlreadyExistsException already) {
            return FileSystems.getFileSystem(jar);
        }
    }
}
