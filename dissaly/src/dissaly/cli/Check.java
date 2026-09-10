package dissaly.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code dissaly check} reports findings that prevent or qualify a run.
 *
 * <p>The command uses the same {@link Scan} as {@code dissaly adopt}. It reads source
 * files without compiling or running the project, so findings are available before
 * the first build.
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
        Scan scan = Scan.of(root);
        System.out.printf("%s, %s, %s%n%n",
                count(scan.services().size(), "service"),
                count(scan.rpcs().size(), "rpc"),
                count(scan.sources().size(), "source file"));
        report(root, scan);
        // Findings are information for the migration and do not make the check fail.
        return 0;
    }

    /**
     * Prints the findings from {@link Scan} in action order.
     *
     * <p>The output is shared with {@link Adopt}. Edits receive numbers; dead code is
     * grouped by file because it usually produces several related findings.
     */
    static void report(Path root, Scan scan) {
        int n = 0;
        n = numbered(root, "will not run at all", scan.of(Scan.Kind.REFUSED), n);
        n = numbered(root, "runs, and a number comes out wrong",
                scan.of(Scan.Kind.UNTRUSTWORTHY), n);
        n = numbered(root, "not wrong, but the run will be less than it could be",
                scan.of(Scan.Kind.MISSING), n);

        var dead = scan.of(Scan.Kind.DEAD);
        if (!dead.isEmpty()) {
            System.out.println("  dead rather than wrong, and no run will ever say so");
            for (var e : byFile(root, dead).entrySet()) {
                System.out.println("      " + e.getKey());
                int width = e.getValue().stream()
                        .mapToInt(f -> f.what().length()).max().orElse(20);
                for (Scan.Finding f : e.getValue()) {
                    System.out.printf("       %-6s %-" + width + "s  %s%n",
                            f.where() == null ? "" : ":" + f.where().line(),
                            f.what(), brief(f.why()));
                }
            }
            System.out.println();
            if (dead.stream().anyMatch(f -> f.what().startsWith("main("))) {
                System.out.println("""
                      ! main() is not merely dead. With no simulation named, dissaly runs the first
                        class it finds containing `static void main(` — so pressing the arrow
                        could launch one of these, and it would try to bind a port inside a lab.
                    """);
            }
        }
        if (n == 0 && dead.isEmpty()) {
            System.out.println("  nothing left: this project is the shape dissaly runs.");
            return;
        }
        System.out.println("  " + count(n, "edit") + " left"
                + (dead.isEmpty() ? "" : ", plus what is listed as dead")
                + ". AGENTS.md has the same list.");
    }

    private static int numbered(Path root, String heading, List<Scan.Finding> found, int n) {
        if (found.isEmpty()) return n;
        System.out.println("  " + heading);
        for (Scan.Finding f : found) {
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

    /** Groups findings by source file in scan order. */
    private static Map<String, List<Scan.Finding>> byFile(Path root, List<Scan.Finding> found) {
        var out = new LinkedHashMap<String, List<Scan.Finding>>();
        for (Scan.Finding f : found) {
            String where = f.where() == null ? "the build file"
                    : rel(root, f.where().file());
            out.computeIfAbsent(where, k -> new ArrayList<>()).add(f);
        }
        return out;
    }

    private static String at(Path root, Scan.At where) {
        return rel(root, where.file()) + ":" + where.line();
    }

    private static String rel(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString() : p.toString();
    }

    /** Returns a short reason suitable for a report row. */
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
