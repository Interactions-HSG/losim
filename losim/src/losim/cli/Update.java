package losim.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import losim.Version;

/**
 * Replaces this lab's copy of losim with a newer one.
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
 * <h2>The fetch source</h2>
 *
 * <p>GitHub's {@code /releases/latest/download/<asset>} path, which redirects to
 * whatever the newest release published. Deliberately not the REST API: the API
 * allows sixty unauthenticated calls an hour <i>per address</i>, and a lecture
 * room behind one NAT is one address. The download path has no such limit and
 * needs no token — but it does need the release to be readable without one, so
 * the repository it points at has to be public. If the simulator's own
 * repository is not, point this at one that is; see {@link #DIST}.
 *
 * <h2>Three archives, not one</h2>
 *
 * <p>A lab carries three things from losim and they go stale together: the
 * simulator in {@code lib/}, the trace viewer in {@code viewer/}, and the manual
 * in {@code docs/}. A viewer whose scrubber does not stop on a {@code heal} is a
 * fix nobody sees if {@code lib/} is the only thing that can be replaced — and
 * for a term that is exactly what happened.
 *
 * <p>They are fetched separately because only the first is certainly the lab's
 * own. {@code publish.sh --lib-only} writes {@code lib/} alone, so that several
 * labs in one assignment can share a single viewer and manual by symlink; a lab
 * arranged that way must be able to take a new simulator without silently
 * rewriting a directory its neighbours are also reading. See {@link #refresh}.
 *
 * <h2>A zip, not a tar</h2>
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

    /**
     * One of the directories a release carries: what it is called in a lab, and
     * what it is called as a release asset. Made by {@code publish.sh} by way of
     * {@code dist.sh}, released by CI.
     */
    private record Part(String name, String asset) {}

    private static final Part LIB = new Part("lib", "losim-lib.zip");
    private static final Part VIEWER = new Part("viewer", "losim-viewer.zip");
    private static final Part DOCS = new Part("docs", "losim-docs.zip");

    /** The published artifact holding the simulator itself. */
    private static final String ASSET = LIB.asset();

    /** A one-line asset holding the version, so a check costs a few bytes. */
    private static final String STAMP = "VERSION";

    private Update() {}

    public static int run(Path root, String from, String to, boolean check) throws IOException {
        Path lib = root.resolve("lib");
        boolean isLab = Files.isRegularFile(lib.resolve("losim.jar"));

        // A directory that is not a lab can still be losim's to update. Several labs
        // sharing one viewer put that viewer in a plain folder holding the labs —
        // which is a sensible layout and is not itself a lab, having no lib/ and no
        // scenarios. Refusing it made the redirect this command prints when it meets
        // a symlink ("update it once where it lives") point at a directory this
        // command would then decline, which is a dead end of its own making.
        boolean shared = !isLab && (holds(root, VIEWER) || holds(root, DOCS));

        if (!isLab && !shared) {
            System.err.println("""
                    Nothing here for `losim update` to replace: %s has no lib/losim.jar,
                    and no viewer/ or docs/ that losim published.

                    Point --root at a lab — the folder holding your systems, your scenarios
                    and the lib/ the assignment came with — or, if several labs share one
                    viewer, at the folder that viewer actually lives in.""".formatted(root));
            return 2;
        }

        String base = base(from);
        String have = installed(root, LIB);
        // Releases are tagged `v1.2.3` and versions are written `1.2.3`, and a
        // person may reasonably type either. Stripping the tag's `v` here rather
        // than at the URL means `--to v1.0.1` on a lab already at 1.0.1 is
        // recognised as "nothing to do" instead of downloading 22 MB to stand
        // still.
        String there = to != null ? bare(to) : latest(base);

        boolean known = !Version.UNKNOWN.equals(have);
        if (isLab) {
            System.out.println("  this lab has  " + (known ? have : have + " (an older lib/, from before these were numbered)"));
        } else {
            // Saying "this lab has an older lib/" about a folder with no lib/ in it
            // is how a person starts looking for the thing that is not there.
            System.out.println("  a shared viewer or manual here, not a lab");
        }
        System.out.println("  released      " + there);
        System.out.println("  from          " + base);

        // Which of the three are behind, asked separately. They do not have to move
        // together: a lab that took a new lib/ from a losim too old to know viewer/
        // was updatable has a current jar beside a stale viewer, and asking only the
        // jar would answer "up to date" forever — which is this command's own upgrade
        // path, so it is not a hypothetical.
        var behind = new ArrayList<Part>();
        for (Part part : List.of(LIB, VIEWER, DOCS)) {
            if (!Files.exists(root.resolve(part.name()), LinkOption.NOFOLLOW_LINKS)) continue;
            // Outside a lab, only what losim plainly published. A folder holding a
            // shared viewer may hold anything else too, and a directory called
            // docs/ that losim never wrote is not this command's to replace.
            if (shared && !holds(root, part)) continue;
            if (!there.equals(installed(root, part))) behind.add(part);
        }

        // A lab can be perfectly current and still not run here. lib/ carries Linux
        // binaries only, on purpose — see dist.sh — so on a Mac outside a container
        // there is no protobuf compiler at all, and "up to date" would be a true
        // answer to a question nobody asked.
        // A shared lib/ is not this lab's to write into, and that includes adding a
        // compiler to its bin/. Following the link to do so would put a binary in a
        // directory every other lab reads, from inside one that never said it was
        // touching anything shared.
        boolean tools = isLab && !Files.isSymbolicLink(lib) && toolsMissing(lib);

        if (behind.isEmpty() && !tools) {
            System.out.println("\nUp to date. Nothing to do.");
            return 0;
        }
        if (check) {
            if (!behind.isEmpty()) {
                System.out.println("\nBehind: " + listing(behind.stream().map(Part::name).toList())
                        + ". `losim update` writes " + (behind.size() == 1 ? "it." : "them."));
            }
            if (tools) {
                System.out.println((behind.isEmpty() ? "\n" : "")
                        + "No protobuf compiler for " + Lab.platform() + " in lib/bin. "
                        + "`losim update` fetches one.");
            }
            return 0;
        }

        // Everything transient lives under build/, which is gitignored and which
        // Lab already knows is not a student's code. Nothing half-written ever
        // appears beside the lab's own folders.
        Path work = root.resolve("build/update");
        rmrf(work);
        Files.createDirectories(work);
        var changed = new ArrayList<String>();

        if (behind.contains(LIB) && !sharedElsewhere(lib, LIB.name())) {
            Path zip = work.resolve(ASSET);
            Path staged = work.resolve("lib");

            System.out.println("\nfetching " + ASSET + "…");
            download(url(base, to, ASSET), zip);
            unzip(zip, staged, LIB.name());

            if (!Files.isRegularFile(staged.resolve("losim.jar"))) {
                System.err.println("that archive has no losim.jar in it; lib/ is untouched");
                return 3;
            }
            executable(staged.resolve("bin"));
            swap(lib, staged, work.resolve("lib-previous"));
            Files.deleteIfExists(zip);
            changed.add(LIB.name());
        }

        // The viewer and the manual, which go stale exactly as the jar does. After
        // lib/ and never instead of it: if the network dies halfway, the thing a
        // lab cannot run without is already in place.
        for (Part part : List.of(VIEWER, DOCS)) {
            if (behind.contains(part) && refresh(root, work, base, to, part)) changed.add(part.name());
        }

        // Asked again rather than reused: replacing lib/ has just put a fresh
        // Linux-only bin/ there, so a Mac needs its compiler after every update
        // and not only the first.
        //
        // Kept out of `changed` deliberately. The rest of an update is a change to
        // commit; this one is a change not to, and saying "git add" here would
        // recommend putting in a fork exactly the 36 MB the template leaves out.
        boolean gotTools = isLab && !Files.isSymbolicLink(lib)
                && toolsMissing(lib) && hostTools(lib, work, base, to);
        if (gotTools) {
            System.out.println("""

                    lib/bin now has a protobuf compiler for %s.

                    That one is for this machine and does not belong in the repository. The
                    template ships Linux only so that a fork does not carry 36 MB nothing in
                    a container can execute, so leave it out of your commit — `losim update`
                    puts it back on any machine that needs it.""".formatted(Lab.platform()));
        }

        if (changed.isEmpty()) {
            if (!gotTools) {
                // Everything behind was somebody else's to update — a shared viewer,
                // a shared manual. `refresh` has already said where.
                System.out.println("\nNothing here was this lab's to replace.");
            }
            return 0;
        }

        // The restart only matters if the jar moved. Saying it when only the manual
        // was replaced is advice that costs a person a minute to follow and teaches
        // them the rest of the message is boilerplate too.
        String restart = changed.contains(LIB.name()) ? """

                The simulator you are running is still the old one — a JVM does not reload
                a jar underneath itself — so restart the lab (stop `losim serve` and start
                it again, or reopen the container).
                """ : "";

        System.out.println("""

                %s %s now %s.
                %s
                This is part of your repository, so it is a change like any other:
                `git add %s && git commit`. Committing it is what makes your next
                Codespace, and whoever marks this, run the same simulator you just did."""
                .formatted(listing(changed), changed.size() == 1 ? "is" : "are", there,
                           restart, String.join(" ", changed)));
        return 0;
    }

    /** {@code lib/}, or {@code lib/ and viewer/}, or {@code lib/, viewer/ and docs/}. */
    private static String listing(List<String> names) {
        var slashed = names.stream().map(n -> n + "/").toList();
        if (slashed.size() == 1) return slashed.get(0);
        return String.join(", ", slashed.subList(0, slashed.size() - 1))
                + " and " + slashed.get(slashed.size() - 1);
    }

    /**
     * Replaces one of the directories a release carries, if this lab owns it.
     *
     * <p>The caller has already established that this directory exists and is out
     * of date. Absent is not an error and is not handled here: that is what a lab
     * published with {@code --lib-only} looks like, and it reaches its viewer and
     * its manual somewhere else.
     *
     * <p>Two cases remain, and only one is a download. The directory may be a
     * symlink into a directory several labs share, and a shared copy
     * is deliberately one copy: replacing it from inside one lab would rewrite
     * what the others are reading without anybody asking. So that case is refused
     * and pointed at the place the update belongs, which is a one-line answer
     * rather than a surprise found later. Otherwise it is the lab's own and is
     * replaced.
     *
     * <p>A failure here is reported and swallowed. {@code lib/} has already been
     * written by the time this runs, and turning "the manual did not download"
     * into a failed update would be a worse answer than a stale manual.
     */
    private static boolean refresh(Path root, Path work, String base, String to, Part part) {
        Path target = root.resolve(part.name());
        if (sharedElsewhere(target, part.name())) return false;
        try {
            Path zip = work.resolve(part.asset());
            Path staged = work.resolve(part.name() + "-new");
            System.out.println("fetching " + part.asset() + "…");
            download(url(base, to, part.asset()), zip);
            unzip(zip, staged, part.name());
            if (empty(staged)) {
                System.err.println("  that archive was empty; " + part.name() + "/ is untouched");
                return false;
            }
            swap(target, staged, work.resolve(part.name() + "-previous"));
            Files.deleteIfExists(zip);
            return true;
        } catch (IOException e) {
            System.err.println("  " + part.name() + "/ was not refreshed: " + e.getMessage());
            System.err.println("  lib/ was, and that is the one a lab cannot run without.");
            return false;
        }
    }

    /**
     * Whether this directory belongs to somebody else, and says where if so.
     *
     * <p>A lab may reach any of the three by symlink — several labs sharing one
     * viewer, or one {@code lib/} built once and pointed at from each lab. A shared
     * directory is deliberately one copy: replacing it from inside one lab would
     * change what its neighbours read without saying so, and replacing the
     * <i>link</i> with a real directory would quietly end the sharing altogether,
     * which is worse for being invisible until the next update.
     */
    private static boolean sharedElsewhere(Path target, String name) {
        if (!Files.isSymbolicLink(target)) return false;
        Path shared;
        try {
            shared = target.resolveSibling(Files.readSymbolicLink(target)).normalize();
        } catch (IOException e) {
            System.out.println("  " + name + "/ is a link; left alone");
            return true;
        }
        Path where = shared.getParent();
        System.out.println(("  %s/ is a link to %s, which another lab may be reading too.%n"
                + "  Left alone. Update it once where it lives:  losim update --root %s")
                .formatted(name, shared, where == null ? shared : where));
        return true;
    }

    /**
     * Puts {@code staged} where {@code target} is, in the one order that leaves
     * {@code target} intact if any step fails: the old one is moved aside before
     * the new one takes its name, and moved back if taking the name does not work.
     */
    private static void swap(Path target, Path staged, Path previous) throws IOException {
        Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        rmrf(previous);
    }

    /**
     * Whether this directory is one losim published, rather than one that merely
     * has the right name.
     *
     * <p>Asked only of a root that is not a lab, where the answer decides whether
     * this command may overwrite it. A lab's own {@code viewer/} needs no such test
     * — it got there from {@code publish.sh} — but a plain folder holding a shared
     * viewer is identified by nothing except what is in it, and "there is a
     * directory called viewer here" is not enough to start deleting one.
     *
     * <p>The stamp settles it from 1.1.0 on. Before that there was none, so the
     * export's own shape stands in: a built Next application has {@code _next/},
     * and the manual has an {@code index.mdx} at its root.
     */
    private static boolean holds(Path root, Part part) {
        Path dir = root.resolve(part.name());
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) return false;
        if (Files.isRegularFile(dir.resolve("version"))) return true;
        return VIEWER.equals(part) ? Files.isDirectory(dir.resolve("_next"))
                                   : Files.isRegularFile(dir.resolve("index.mdx"));
    }

    /** Whether this host has no protobuf compiler it can actually execute. */
    private static boolean toolsMissing(Path lib) {
        Path bin = lib.resolve("bin");
        String plat = Lab.platform();
        return !Files.isExecutable(bin.resolve("protoc-" + plat))
                || !Files.isExecutable(bin.resolve("protoc-gen-grpc-java-" + plat));
    }

    /**
     * Fetches the protobuf compiler for the host, if one is published for it.
     *
     * <p>Unpacked into {@code lib/bin} rather than swapped over it: this adds two
     * files to a directory that already holds four, and replacing the directory
     * would take the container's compilers away from a repository that is also
     * opened in a container.
     *
     * <p>Not being published for a platform is an ordinary outcome, not a failure.
     * A lab on a machine losim has no binaries for still runs everything that does
     * not need protoc, and saying so plainly is better than a stack trace.
     */
    private static boolean hostTools(Path lib, Path work, String base, String to) {
        String plat = Lab.platform();
        String asset = "losim-tools-" + plat + ".zip";
        System.out.println("\nno protobuf compiler for " + plat + " here; fetching " + asset + "…");
        try {
            Path zip = work.resolve(asset);
            download(url(base, to, asset), zip);
            unzip(zip, lib, LIB.name());
            executable(lib.resolve("bin"));
            Files.deleteIfExists(zip);
            if (toolsMissing(lib)) {
                System.err.println("  that archive did not carry a compiler for " + plat);
                return false;
            }
            return true;
        } catch (IOException e) {
            System.err.println("  none published for " + plat + ": " + e.getMessage());
            System.err.println("  everything that does not compile a .proto still works.");
            return false;
        }
    }

    private static boolean empty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return true;
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
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
     * {@code publish.sh} writes into each of the three, and for {@code lib/} the
     * stamp inside the lab's own jar, and failing that it says it does not know —
     * which for a directory published before any of this existed is exactly true,
     * and counts as behind so that it is replaced once and then stamped.
     */
    private static String installed(Path root, Part part) {
        Path dir = root.resolve(part.name());
        Path stamp = dir.resolve("version");
        if (Files.isRegularFile(stamp)) {
            try {
                String v = Files.readString(stamp).trim();
                if (!v.isEmpty()) return v;
            } catch (IOException ignored) {
                // Fall through to the jar; an unreadable file is not an answer.
            }
        }
        if (!LIB.equals(part)) return Version.UNKNOWN;
        try (var jar = new java.util.zip.ZipFile(dir.resolve("losim.jar").toFile())) {
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
     * Unpacks the archive, whose entries are all under a single directory named
     * for the part — {@code lib/}, {@code viewer/} or {@code docs/}.
     *
     * <p>Every entry's destination is checked to be inside the target. An archive
     * naming {@code ../../.ssh/authorized_keys} is a real and old trick, and the
     * fact that this one comes from a repository the course controls is not a
     * reason to be the program that would have written it.
     */
    private static void unzip(Path zip, Path into, String prefix) throws IOException {
        Path target = into.toAbsolutePath().normalize();
        String dir = prefix + "/";
        Files.createDirectories(target);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                String name = e.getName();
                // The archive holds `<prefix>/…`; this writes the contents of that
                // directory, because the caller already chose where it goes.
                if (name.equals(dir) || name.equals(prefix)) continue;
                if (name.startsWith(dir)) name = name.substring(dir.length());
                if (name.isEmpty()) continue;

                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("that archive tried to write outside " + dir + ": " + e.getName());
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
