package losim.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import losim.Version;

/**
 * Replaces this lab's {@code lib/} with a newer one.
 *
 * <p>An assignment is a GitHub template. A student presses "Use this template"
 * in week one and works in their own repository for a term — which is the whole
 * point, and which also means that every fix made to the simulator after that
 * moment reaches nobody. The course could push a branch and ask a hundred people
 * to merge it, and roughly the number of people who merge a branch when asked
 * would merge it. This is the other way: the simulator can replace itself.
 *
 * <p><b>What it does not do is fetch anything at build time.</b> That distinction
 * is the whole design. {@code lib/} stays committed, so a fork made in March
 * still compiles in June on a machine with no network — the guarantee
 * {@code publish.sh} exists to make. The network is touched here and only here,
 * when somebody asks, and what comes back is written to disk and committed like
 * anything else. A student who never runs this is never behind in a way that
 * breaks; they are behind in a way that is stable, which is the correct default
 * for an assignment being marked.
 *
 * <h2>Where it fetches from</h2>
 *
 * <p>GitHub's {@code /releases/latest/download/<asset>} path, which redirects to
 * whatever the newest release published. Deliberately not the REST API: the API
 * allows sixty unauthenticated calls an hour <i>per address</i>, and a lecture
 * room behind one NAT is one address. The download path has no such limit and
 * needs no token — but it does need the release to be readable without one, so
 * the repository it points at has to be public. If the simulator's own
 * repository is not, point this at one that is; see {@link #DIST}.
 *
 * <h2>Why a zip and not a tar</h2>
 *
 * <p>{@code java.util.zip} is in the JDK and {@code tar} is not, and this must
 * work with nothing installed. The cost is that zip does not carry an executable
 * bit, so {@code lib/bin/} — the protobuf compiler, which {@code Lab} refuses to
 * run without {@link Files#isExecutable} — is marked executable here, explicitly,
 * after unpacking.
 */
public final class Update {

    /**
     * The repository releases are read from.
     *
     * <p>Overridable, because the answer to "may students read the simulator's
     * source?" is a course's to make and not this file's. If the simulator's
     * repository is public, this is it and there is nothing to decide. If it is
     * private, publish the releases to a small public repository that holds
     * nothing else — {@code Interactions-HSG/losim-dist}, say — and set
     * {@code LOSIM_DIST} to it. Either way a student types the same command.
     */
    public static final String DIST = "https://github.com/Interactions-HSG/losim";

    /** The published artifact. Made by {@code publish.sh}, released by CI. */
    private static final String ASSET = "losim-lib.zip";

    /** A one-line asset holding the version, so a check costs a few bytes. */
    private static final String STAMP = "VERSION";

    private Update() {}

    public static int run(Path root, String from, String to, boolean check) throws IOException {
        Path lib = root.resolve("lib");
        if (!Files.isRegularFile(lib.resolve("losim.jar"))) {
            System.err.println("""
                    This is not a lab: %s has no lib/losim.jar.

                    `losim update` replaces a lab's lib/ directory, so it has to be run
                    from a lab — the folder holding your systems, your scenarios and the
                    lib/ the assignment came with. Use --root to point at one.""".formatted(root));
            return 2;
        }

        String base = base(from);
        String have = installed(lib);
        // Releases are tagged `v1.2.3` and versions are written `1.2.3`, and a
        // person may reasonably type either. Stripping the tag's `v` here rather
        // than at the URL means `--to v1.0.1` on a lab already at 1.0.1 is
        // recognised as "nothing to do" instead of downloading 22 MB to stand
        // still.
        String there = to != null ? bare(to) : latest(base);

        boolean known = !Version.UNKNOWN.equals(have);
        System.out.println("  this lab has  " + (known ? have : have + " (an older lib/, from before these were numbered)"));
        System.out.println("  released      " + there);
        System.out.println("  from          " + base);

        if (there.equals(have)) {
            System.out.println("\nUp to date. Nothing to do.");
            return 0;
        }
        if (check) {
            System.out.println("\nA newer lib/ is available. `losim update` writes it.");
            return 0;
        }

        // Everything transient lives under build/, which is gitignored and which
        // Lab already knows is not a student's code. Nothing half-written ever
        // appears beside the lab's own folders.
        Path work = root.resolve("build/update");
        rmrf(work);
        Files.createDirectories(work);
        Path zip = work.resolve(ASSET);
        Path staged = work.resolve("lib");

        System.out.println("\nfetching " + ASSET + "…");
        download(url(base, to, ASSET), zip);
        unzip(zip, staged);

        if (!Files.isRegularFile(staged.resolve("losim.jar"))) {
            System.err.println("that archive has no losim.jar in it; lib/ is untouched");
            return 3;
        }
        executable(staged.resolve("bin"));

        // The swap, in the one order that leaves lib/ intact if any step fails:
        // the old one is moved aside before the new one takes its name, and moved
        // back if taking the name does not work.
        Path previous = work.resolve("lib-previous");
        Files.move(lib, previous, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staged, lib, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(previous, lib, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        rmrf(previous);
        Files.deleteIfExists(zip);

        System.out.println("""

                lib/ is now %s.

                Two things follow from that. The simulator you are running is still the
                old one — a JVM does not reload a jar underneath itself — so restart the
                lab (stop `losim serve` and start it again, or reopen the container).

                And lib/ is part of your repository, so this is a change like any other:
                `git add lib && git commit`. Committing it is what makes your next
                Codespace, and whoever marks this, run the same simulator you just did.""".formatted(there));
        return 0;
    }

    /**
     * Which losim is in this lab's {@code lib/} — which is not necessarily the
     * one running.
     *
     * <p>{@link Version#get()} answers for the jar this class was loaded from,
     * and that is the right answer to a different question. With {@code --root}
     * the two come apart: a maintainer checking a student's lab from their own
     * checkout would otherwise be told the student has whatever the maintainer
     * has. So this reads the lab, not itself — the {@code version} file
     * {@code publish.sh} writes, and failing that the stamp inside the lab's own
     * jar, and failing that it says it does not know, which for a lib/ published
     * before any of this existed is exactly true.
     */
    private static String installed(Path lib) {
        Path stamp = lib.resolve("version");
        if (Files.isRegularFile(stamp)) {
            try {
                String v = Files.readString(stamp).trim();
                if (!v.isEmpty()) return v;
            } catch (IOException ignored) {
                // Fall through to the jar; an unreadable file is not an answer.
            }
        }
        try (var jar = new java.util.zip.ZipFile(lib.resolve("losim.jar").toFile())) {
            ZipEntry e = jar.getEntry("losim/version");
            if (e != null) {
                try (var in = jar.getInputStream(e)) {
                    String v = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!v.isEmpty()) return v;
                }
            }
        } catch (IOException ignored) {
            // A jar that cannot be opened is a lab with a bigger problem than
            // being out of date, and `losim update` is a reasonable thing to try.
        }
        return Version.UNKNOWN;
    }

    // ------------------------------------------------------------------ the network

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
     * Where an asset of a given release is.
     *
     * <p>{@code latest} is a path GitHub resolves, not a version this has to know
     * — so a lab from two terms ago finds the current release without carrying a
     * list of what has been released since.
     */
    private static URI url(String base, String version, String asset) {
        // The two shapes GitHub actually serves, which are not the same shape:
        // the newest release is `releases/latest/download/<asset>`, a named one is
        // `releases/download/<tag>/<asset>`. Writing the first as though it were
        // the second gives a 404 that reads exactly like "nothing is released".
        String path = version == null ? "latest/download" : "download/" + tag(version);
        return URI.create(base + "/releases/" + path + "/" + asset);
    }

    /** Releases are tagged {@code v1.2.3}; a person types {@code 1.2.3} or either. */
    private static String tag(String version) {
        return version.startsWith("v") ? version : "v" + version;
    }

    /** The same version without its tag's {@code v}, which is how one is written. */
    private static String bare(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }

    private static String latest(String base) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(url(base, null, STAMP))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/plain")
                .GET().build();
        try {
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                throw new IOException("""
                        no release found at %s.

                        Either nothing has been released yet, or that repository is private —
                        a release in a private repository cannot be downloaded without a token,
                        and this deliberately does not ask you for one. Set LOSIM_DIST to a
                        public repository, or ask the course to publish to one.""".formatted(base));
            }
            if (res.statusCode() != 200) {
                throw new IOException("the release server answered " + res.statusCode() + " for " + STAMP);
            }
            String v = res.body().trim();
            if (v.isEmpty() || v.lines().count() != 1) {
                throw new IOException("the released " + STAMP + " is not a version: " + v);
            }
            return v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking what the latest release is", e);
        } catch (IOException e) {
            throw unreachable(base, e);
        }
    }

    /**
     * A network failure, said in words.
     *
     * <p>{@code ConnectException} arrives here with a null message, and the CLI's
     * last-resort handler prints the class name and the message — so the whole of
     * what a student saw for "there is no network in this container" was
     * {@code ConnectException: null}. This is the one command that can fail for
     * reasons outside the lab entirely, so it says which reasons.
     */
    private static IOException unreachable(String base, IOException cause) {
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) return cause;
        return new IOException("""
                could not reach %s.

                This is the only losim command that needs the network, and nothing else
                here is affected — your lab still builds and runs on the lib/ it has.
                Worth checking: whether this machine is online at all, and whether a
                proxy stands between it and github.com.""".formatted(base), cause);
    }

    private static void download(URI uri, Path into) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        try {
            HttpResponse<Path> res = client().send(req, HttpResponse.BodyHandlers.ofFile(into));
            if (res.statusCode() != 200) {
                Files.deleteIfExists(into);
                throw new IOException(uri + " answered " + res.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + uri, e);
        } catch (IOException e) {
            Files.deleteIfExists(into);
            throw unreachable(uri.toString(), e);
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                // GitHub answers /releases/latest/download/ with a redirect to
                // wherever the asset actually lives. Not following it is a 302
                // body and a very confusing error.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    // ------------------------------------------------------------------- unpacking

    /**
     * Unpacks the archive, whose entries are all under a single {@code lib/}.
     *
     * <p>Every entry's destination is checked to be inside the target. An archive
     * naming {@code ../../.ssh/authorized_keys} is a real and old trick, and the
     * fact that this one comes from a repository the course controls is not a
     * reason to be the program that would have written it.
     */
    private static void unzip(Path zip, Path into) throws IOException {
        Path target = into.toAbsolutePath().normalize();
        Files.createDirectories(target);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                String name = e.getName();
                // The archive holds `lib/…`; this writes the contents of that
                // directory, because the caller already chose where it goes.
                if (name.equals("lib/") || name.equals("lib")) continue;
                if (name.startsWith("lib/")) name = name.substring(4);
                if (name.isEmpty()) continue;

                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("that archive tried to write outside lib/: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Marks the protobuf compiler runnable.
     *
     * <p>Zip carries no executable bit, and {@code Lab.protocProblem} tests for
     * one — so without this the first run after an update reports that there is
     * no protobuf compiler for this platform while looking straight at it.
     */
    private static void executable(Path bin) throws IOException {
        if (!Files.isDirectory(bin)) return;
        try (var files = Files.list(bin)) {
            for (Path p : files.toList()) {
                try {
                    Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(p));
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                    perms.add(PosixFilePermission.GROUP_EXECUTE);
                    perms.add(PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(p, perms);
                } catch (UnsupportedOperationException ignored) {
                    // Not a POSIX filesystem. Nothing to set, and nothing to say:
                    // a platform with no executable bit does not need one.
                }
            }
        }
    }

    private static void rmrf(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
