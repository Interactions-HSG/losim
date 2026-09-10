package dissaly.cli;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code AGENTS.md}, for the project being adopted.
 *
 * <p>{@link Adopt} does not edit Java files because it cannot infer their intent.
 * This class records the findings in {@code AGENTS.md} so the project's coding agent
 * can make those edits.
 *
 * <p>The body is a resource and the appended section contains the findings from
 * {@link Scan} for this project. The file keeps those details available after the
 * command exits.
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
            // Keep project-specific findings available even when the jar has no resource.
        }
        return "# losim, under a gRPC system that already works\n\n"
                + "(This jar carries no copy of the general instructions. The manual has"
                + " them: /start/adopt.)\n";
    }

    /** Formats the findings with their source lines. */
    private static String here(Scan scan, Path root) {
        var sb = new StringBuilder("## This project\n\n");
        sb.append("What `losim adopt` found in this repository, at the moment it ran."
                + " `./losim check` re-runs exactly this.\n\n");

        if (!scan.services().isEmpty()) {
            // Keep the source file and the service it provides together.
            sb.append("**Services.** ");
            var said = new java.util.ArrayList<String>();
            for (Scan.Service s : scan.services()) {
                said.add("`" + scan.serviceOf(s) + "` in `" + rel(root, s.file())
                        + ":" + s.line() + "`" + (s.nested() ? " (nested)" : ""));
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
