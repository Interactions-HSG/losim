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
 * a string constant. What is appended is what {@link Shape} found <b>in this
 * project</b>, because an agent reading a generic file has to rediscover
 * specifics the command already knows — and because a terminal scrolls away and a
 * file in the repository does not.
 */
final class Agents {
    private Agents() {}

    static String forProject(Shape shape, Path root) {
        return body() + "\n" + here(shape, root);
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
    private static String here(Shape shape, Path root) {
        var sb = new StringBuilder("## This project\n\n");
        sb.append("What `losim adopt` found in this repository, at the moment it ran."
                + " `./losim check` re-runs exactly this.\n\n");

        if (!shape.services().isEmpty()) {
            sb.append("**Services.** ");
            var said = new java.util.ArrayList<String>();
            for (Shape.Service s : shape.services()) {
                said.add("`" + s.name() + "`" + (s.nested() ? " (nested — see edit 1)" : "")
                        + " at `" + rel(root, s.file()) + ":" + s.line() + "`");
            }
            sb.append(String.join(", ", said)).append("\n\n");
        }
        if (!shape.rpcs().isEmpty()) {
            sb.append("**Rpcs.**\n\n");
            sb.append("| rpc | shape | retryable |\n|---|---|---|\n");
            for (Shape.Rpc r : shape.rpcs()) {
                sb.append("| `").append(r.service()).append('.').append(r.name()).append("` | ")
                  .append(r.streaming() ? "**streaming — refused**" : "unary").append(" | ")
                  .append(r.idempotent() ? "yes" : "no `idempotency_level`").append(" |\n");
            }
            sb.append('\n');
        }

        boolean any = false;
        any |= list(sb, root, "Will not run", shape.of(Shape.Kind.REFUSED));
        any |= list(sb, root, "Runs, and a number comes out wrong",
                shape.of(Shape.Kind.UNTRUSTWORTHY));
        any |= list(sb, root, "Runs, does nothing, and nothing else will say so",
                shape.of(Shape.Kind.DEAD));
        any |= list(sb, root, "Not wrong, but the run will be less than it could be",
                shape.of(Shape.Kind.MISSING));
        if (!any) sb.append("Nothing. This project is already the shape losim runs.\n");
        return sb.toString();
    }

    private static boolean list(StringBuilder sb, Path root, String heading,
                                List<Shape.Finding> found) {
        if (found.isEmpty()) return false;
        sb.append("### ").append(heading).append("\n\n");
        for (Shape.Finding f : found) {
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
