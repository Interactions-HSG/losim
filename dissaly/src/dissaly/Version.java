package dissaly;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Which dissaly this is.
 *
 * <p>This exists so that a bug report from a fork made in March is answerable
 * without asking somebody to describe a jar. {@code dissaly version} prints it,
 * and with {@code --check} compares it against the newest release.
 *
 * <p>The number lives in one file — {@code VERSION} at the root of the
 * simulator's repository — and the build copies it into the jar as a resource.
 * One file, one build, and no way for a jar to disagree with the tag it was cut
 * from without that file changing.
 *
 * <p>A jar built some other way has no such resource, and says so. It does not
 * guess and it does not fail — a version is a label, and a run whose label is
 * missing is still a run.
 */
public final class Version {

    /** What a jar with no stamped version calls itself. */
    public static final String UNKNOWN = "unknown";

    private static final String VALUE = read();

    private Version() {}

    /** The version of the dissaly this class was loaded from, or {@link #UNKNOWN}. */
    public static String get() { return VALUE; }

    /** Whether this jar knows what it is, which a hand-built one does not. */
    public static boolean known() { return !UNKNOWN.equals(VALUE); }

    private static String read() {
        try (InputStream in = Version.class.getResourceAsStream("/dissaly/version")) {
            if (in == null) return UNKNOWN;
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? UNKNOWN : s;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
