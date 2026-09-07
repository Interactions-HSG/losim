package losim.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code losim check} — what is still between this project and a run.
 *
 * <p>The same {@link Shape} {@code losim adopt} printed from, so the account it
 * gave is not something to have kept: a terminal scrolls away and a command does
 * not. Nothing is compiled and nothing is run, which is the point — this answers
 * before the first build, when a project has not yet reached the state where a
 * compiler would have anything useful to say.
 */
public final class Check {
    private Check() {}

    public static int main(String[] args) throws Exception {
        Path root = Path.of(Main.option(args, "--root",
                Main.positionalOr(args, "check", "."))).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("no such directory: " + root);
            return 2;
        }
        Shape shape = Shape.of(root);
        System.out.printf("%s, %s, %s%n%n",
                count(shape.services().size(), "service"),
                count(shape.rpcs().size(), "rpc"),
                count(shape.sources().size(), "source file"));
        report(root, shape);
        // Not a failure exit. A project part-way through a migration is the ordinary
        // state of a project part-way through a migration, and a check that returns 1
        // for it cannot be put in front of anything without becoming a thing people
        // pass --no-verify to.
        return 0;
    }

    /**
     * Everything {@link Shape} found, in the order somebody would act on it.
     *
     * <p>Shared with {@link Adopt} rather than written twice, because the whole
     * claim about {@code check} is that it says what {@code adopt} said.
     *
     * <h2>Two lists, and only one of them is numbered</h2>
     *
     * <p>The numbered ones are edits. The dead ones are not: they are lines to
     * delete, they are usually many, and printing fourteen numbered items for one
     * file's bootstrap buries the two that matter. So they go under the file they
     * are in, one line each, compact — which is also how they read when somebody
     * opens that file to do the work.
     */
    static void report(Path root, Shape shape) {
        int n = 0;
        n = numbered(root, "will not run at all", shape.of(Shape.Kind.REFUSED), n);
        n = numbered(root, "runs, and a number comes out wrong",
                shape.of(Shape.Kind.UNTRUSTWORTHY), n);
        n = numbered(root, "not wrong, but the run will be less than it could be",
                shape.of(Shape.Kind.MISSING), n);

        var dead = shape.of(Shape.Kind.DEAD);
        if (!dead.isEmpty()) {
            System.out.println("  dead rather than wrong, and no run will ever say so");
            for (var e : byFile(root, dead).entrySet()) {
                System.out.println("      " + e.getKey());
                int width = e.getValue().stream()
                        .mapToInt(f -> f.what().length()).max().orElse(20);
                for (Shape.Finding f : e.getValue()) {
                    System.out.printf("       %-6s %-" + width + "s  %s%n",
                            f.where() == null ? "" : ":" + f.where().line(),
                            f.what(), brief(f.why()));
                }
            }
            System.out.println();
            if (dead.stream().anyMatch(f -> f.what().startsWith("main("))) {
                System.out.println("""
                      ! main() is not merely dead. With no scenario named, losim runs the first
                        class it finds containing `static void main(` — so pressing the arrow
                        could launch one of these, and it would try to bind a port inside a lab.
                    """);
            }
        }
        if (n == 0 && dead.isEmpty()) {
            System.out.println("  nothing left: this project is the shape losim runs.");
            return;
        }
        System.out.println("  " + count(n, "edit") + " left"
                + (dead.isEmpty() ? "" : ", plus what is listed as dead")
                + ". AGENTS.md has the same list.");
    }

    private static int numbered(Path root, String heading, List<Shape.Finding> found, int n) {
        if (found.isEmpty()) return n;
        System.out.println("  " + heading);
        for (Shape.Finding f : found) {
            n++;
            System.out.printf("  %2d  %s%n", n, f.what());
            if (f.where() != null) {
                System.out.println("      " + at(root, f.where()));
            }
            System.out.println(Adopt.wrap(f.why(), "      "));
            System.out.println();
        }
        return n;
    }

    /** Grouped by the file they are in, in the order the files were read. */
    private static Map<String, List<Shape.Finding>> byFile(Path root, List<Shape.Finding> found) {
        var out = new LinkedHashMap<String, List<Shape.Finding>>();
        for (Shape.Finding f : found) {
            String where = f.where() == null ? "the build file"
                    : rel(root, f.where().file());
            out.computeIfAbsent(where, k -> new ArrayList<>()).add(f);
        }
        return out;
    }

    private static String at(Path root, Shape.At where) {
        return rel(root, where.file()) + ":" + where.line();
    }

    private static String rel(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString() : p.toString();
    }

    /** The first clause of a reason, for a line that has to fit beside fifteen others. */
    private static String brief(String why) {
        int stop = why.length();
        for (String end : List.of(". ", ", so ", ", and ", " — ")) {
            int at = why.indexOf(end);
            if (at > 0 && at < stop) stop = at;
        }
        String said = why.substring(0, stop);
        return said.length() <= 62 ? said : said.substring(0, 59) + "…";
    }

    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
