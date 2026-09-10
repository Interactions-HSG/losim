package dissaly.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import dissaly.Version;

/**
 * {@code losim version} — which losim this is, and whether it is the current one.
 *
 * <p>What replaced {@code losim update}. A lab used to carry the simulator as
 * committed jars, so the simulator had to be able to fetch and replace itself; a
 * lab now resolves it from Maven, so updating is a version string in a build file
 * and the network is not involved. All that is left is the question that string
 * cannot answer on its own — <i>is there a newer one?</i> — and the answer is one
 * line for somebody to edit.
 *
 * <h2>One HEAD, and no token</h2>
 *
 * <p>{@code /releases/latest} redirects to the newest release's own page, whose
 * URL ends in the tag. So the version is in a {@code Location} header and the
 * body is never read.
 *
 * <p>Deliberately not the REST API, which allows sixty unauthenticated calls an
 * hour <i>per address</i> — and a lecture room behind one NAT is one address.
 *
 * <p>Deliberately not {@code maven-metadata.xml} either, which is the answer that
 * looks right: {@code release.yml} copies the Maven tree into the pages branch
 * additively, per run, so each run's metadata lists that run's version and no
 * other. Reading it would report whatever was released most recently as the only
 * release there has ever been, which happens to be the right answer to this
 * question and is right by accident.
 */
public final class Released {

    /**
     * The repository releases are read from.
     *
     * <p>Overridable through {@code LOSIM_DIST}, because the answer to "may
     * students read the simulator's source?" is a course's to make and not this
     * file's. A release in a private repository cannot be read without a token,
     * and this deliberately does not ask for one.
     */
    public static final String DIST = "https://github.com/Interactions-HSG/losim";

    private Released() {}

    public static int main(String[] args) {
        System.out.println(Version.known() ? Version.get()
                : Version.UNKNOWN + " — this jar was built by hand, not cut from a tag");
        if (!Main.flag(args, "--check")) return 0;

        String base = base(Main.option(args, "--from", null));
        Optional<String> newest;
        try {
            newest = latest(base);
        } catch (IOException e) {
            // The only losim command that needs the network, so it is the only one
            // that can fail for a reason outside the project entirely. Said as such,
            // and not as a failure of the thing the person was actually doing.
            System.err.println(e.getMessage());
            return 1;
        }
        if (newest.isEmpty()) {
            System.out.println("nothing is released at " + base + " yet");
            return 0;
        }
        String there = newest.get();
        if (there.equals(Version.get())) {
            System.out.println("the newest release, so there is nothing to do");
            return 0;
        }
        System.out.print("""
                %s is released.

                  Change losimVersion in build.gradle.kts to %s and run ./losim.
                  Nothing else moves: the viewer and the manual are inside the jar,
                  and your simulations, schema and Java are untouched by a version.
                """.formatted(there, there));
        return 0;
    }

    /** The newest release's version, or empty when nothing has been released. */
    private static Optional<String> latest(String base) throws IOException {
        URI uri = URI.create(base + "/releases/latest");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        // Redirects are NOT followed, which is the whole trick: the answer is the
        // Location header. Following it would fetch a page of HTML to learn what
        // the header already said.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<Void> res;
        try {
            res = client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking what the latest release is", e);
        } catch (IOException e) {
            throw unreachable(base, e);
        }
        if (res.statusCode() == 404) {
            throw new IOException("""
                    no releases at %s.

                    Either nothing has been released yet, or that repository is private —
                    a release in a private repository cannot be read without a token, and
                    this deliberately does not ask you for one. Set LOSIM_DIST to a public
                    repository, or ask the course to publish to one.""".formatted(base));
        }
        String where = res.headers().firstValue("location").orElse("");
        if (where.isEmpty()) {
            // A repository with no releases at all answers 200 with the releases
            // page rather than redirecting, which is not an error and not a version.
            return Optional.empty();
        }
        String tag = where.substring(where.lastIndexOf('/') + 1);
        return tag.isBlank() ? Optional.empty()
                : Optional.of(tag.startsWith("v") ? tag.substring(1) : tag);
    }

    private static String base(String from) {
        if (from != null) return trim(from);
        String env = System.getenv("LOSIM_DIST");
        return trim(env != null && !env.isBlank() ? env : DIST);
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /**
     * A network failure, said in words.
     *
     * <p>{@code ConnectException} arrives with a null message, and the CLI's
     * last-resort handler prints the class name and the message — so the whole of
     * what somebody saw for "there is no network in this container" was
     * {@code ConnectException: null}.
     */
    private static IOException unreachable(String base, IOException cause) {
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) return cause;
        return new IOException("""
                could not reach %s.

                This is the only losim command that needs the network, and nothing else
                here is affected — your lab builds and runs against the losim your build
                already resolved. Worth checking: whether this machine is online at all,
                and whether a proxy stands between it and github.com.""".formatted(base), cause);
    }
}
