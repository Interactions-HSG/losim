package dissaly.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code .java} file, read as text, for the one question a simulation asks of it:
 * what is the class in here called?
 *
 * <p>A simulation names code by path — {@code src/Shrinker.java} — because a path is
 * a thing that either exists or does not, and a fully qualified class name is a
 * thing that looks right until the run starts. So the loader can say "no such
 * file" on the line it is written on, and the class name is derived rather than
 * typed twice.
 *
 * <p><b>Text, never bytecode.</b> The same discipline {@code dissaly check} is under
 * and for the same reason: this runs before anything is compiled, so there is no
 * class to reflect on and no classloader to ask. Both read a {@code package} line
 * with this, so the loader and the checker cannot come to different conclusions
 * about what a file declares.
 */
public final class JavaSource {

    private JavaSource() {}

    /**
     * The {@code package} declaration, ignoring one inside a comment or a string.
     *
     * <p>Anchored to the start of a line and to the keyword, which is what keeps
     * {@code // package lab;} and {@code "package "} out of it. A file whose real
     * declaration is indented is still found; one that is commented out is not.
     */
    private static final Pattern PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    /** Whether the file declares a top-level type of this name. */
    private static final String TYPE = "(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+"
            + "|non-sealed\\s+|strictfp\\s+)*(?:class|record|enum|interface)\\s+%s\\b";

    /** The name a file's class would be loaded under: its package, and its own name. */
    public static String className(Path file) throws IOException {
        String text = Files.readString(file);
        String simple = simpleName(file);
        Matcher m = PACKAGE.matcher(text);
        return m.find() ? m.group(1) + "." + simple : simple;
    }

    /** {@code src/lab/Shrinker.java} -> {@code Shrinker}. */
    public static String simpleName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    /**
     * Whether the file declares a type named after itself.
     *
     * <p>Java requires this of a public type, so a file that fails it is one whose
     * class is package-private and named something else — which compiles, and then
     * cannot be found under the name a simulation would derive. Better said here than
     * discovered as "no class called src.Foo" after a build.
     */
    public static boolean declaresItsOwnName(Path file) throws IOException {
        return Pattern.compile(String.format(TYPE, Pattern.quote(simpleName(file))))
                .matcher(Files.readString(file)).find();
    }
}
