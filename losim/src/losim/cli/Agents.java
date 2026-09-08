package losim.cli;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code AGENTS.md}, for the project being adopted.
 *
 * <p>The other half of the migration. {@link Adopt} refuses to touch a
 * {@code .java} because guessing what a program means produces a system its author
 * did not write; this is how the Java half gets done anyway — by the coding agent
 * of the person who wrote it, from the same three classes of finding the command
 * just printed.
 *
 * <p>The body is a resource, so it is written and reviewed as prose rather than as
 * a string constant. What is appended is what {@link Scan} found <b>in this
 * project</b>, because an agent reading a generic file has to rediscover
 * specifics the command already knows — and because a terminal scrolls away and a
 * file in the repository does not.
 */
final class Agents {
    private Agents() {}

    static String forProject(Scan scan, Path root) {
        return body() + "\n" + here(scan, root);
    }

    private static String body() {
        try (InputStream in = Agents.class.getResourceAsStream("/losim/AGENTS.md")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // A jar without the resource is a jar built by hand. The findings below
            // are the half that could not be written any other way, so they still go.
        }
        return "# losim, under a gRPC system that already works\n\n"
                + "(This jar carries no copy of the general instructions. The manual has"
                + " them: /start/adopt.)\n";
    }

    /** What was found here, in the three classes, with a line number each. */
    private static String here(Scan scan, Path root) {
        var sb = new StringBuilder("## This project\n\n");
        sb.append("What `losim adopt` found in this repository, at the moment it ran."
                + " `./losim check` re-runs exactly this.\n\n");

        if (!scan.services().isEmpty()) {
            // The pair a `runs:` entry is written from: the file a node is given,
            // and the service it thereby serves. Named together, because an agent
            // that has to derive one from the other derives it wrong.
            sb.append("**Services.** ");
            var said = new java.util.ArrayList<String>();
            for (Scan.Service s : scan.services()) {
                said.add("`" + scan.serviceOf(s) + "` in `" + rel(root, s.file())
                        + ":" + s.line() + "`" + (s.nested() ? " (nested — see edit 1)" : ""));
            }
            sb.append(String.join(", ", said)).append("\n\n");
        }
        if (!scan.rpcs().isEmpty()) {
            sb.append("**Rpcs.**\n\n");
            sb.append("| rpc | shape | retryable |\n|---|---|---|\n");
            for (Scan.Rpc r : scan.rpcs()) {
                sb.append("| `").append(r.service()).append('.').append(r.name()).append("` | ")
                  .append(r.streaming() ? "**streaming — refused**" : "unary").append(" | ")
                  .append(r.idempotent() ? "yes" : "no `idempotency_level`").append(" |\n");
            }
            sb.append('\n');
        }

        boolean any = false;
        any |= list(sb, root, "Will not run", scan.of(Scan.Kind.REFUSED));
        any |= list(sb, root, "Runs, and a number comes out wrong",
                scan.of(Scan.Kind.UNTRUSTWORTHY));
        any |= list(sb, root, "Runs, does nothing, and nothing else will say so",
                scan.of(Scan.Kind.DEAD));
        any |= list(sb, root, "Not wrong, but the run will be less than it could be",
                scan.of(Scan.Kind.MISSING));
        if (!any) sb.append("Nothing. This project is already the shape losim runs.\n");
        return sb.toString();
    }

    private static boolean list(StringBuilder sb, Path root, String heading,
                                List<Scan.Finding> found) {
        if (found.isEmpty()) return false;
        sb.append("### ").append(heading).append("\n\n");
        for (Scan.Finding f : found) {
            sb.append("- **").append(f.what()).append("**");
            if (f.where() != null) {
                sb.append(" — `").append(rel(root, f.where().file()))
                  .append(':').append(f.where().line()).append('`');
            }
            sb.append("  \n  ").append(f.why()).append('\n');
        }
        sb.append('\n');
        return true;
    }

    private static String rel(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString() : p.toString();
    }
}
