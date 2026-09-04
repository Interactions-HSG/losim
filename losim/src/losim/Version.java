package losim;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Which losim this is.
 *
 * <p>This exists because {@code losim update} can replace a lab's {@code lib/}
 * with a newer one: a student who runs it has to be told what they had and
 * what they now have, and a bug report from a fork made in March has to be
 * answerable without asking somebody to describe a jar.
 *
 * <p>The number lives in one file — {@code VERSION} at the root of the
 * simulator's repository — and is copied into the jar as a resource by whichever
 * build ran. Both builds do it: {@code build.sh} writes it into the classes
 * directory before the jar is made, and Gradle reads the same file. That is D10
 * again, in the smallest possible form: two commands, one number, and no way for
 * them to disagree without the file itself changing.
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

    /** The version of the losim this class was loaded from, or {@link #UNKNOWN}. */
    public static String get() { return VALUE; }

    /** Whether this jar knows what it is, which a hand-built one does not. */
    public static boolean known() { return !UNKNOWN.equals(VALUE); }

    private static String read() {
        try (InputStream in = Version.class.getResourceAsStream("/losim/version")) {
            if (in == null) return UNKNOWN;
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? UNKNOWN : s;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
