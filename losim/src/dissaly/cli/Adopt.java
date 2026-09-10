package dissaly.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code dissaly adopt} adds losim to an existing gRPC project.
 *
 * <p>The command targets projects with a Gradle build, {@code src/main/proto}, and
 * a gRPC server and client. It adapts that layout without changing application
 * Java code.
 *
 * <h2>Files written by the command</h2>
 *
 * <p>The command does not refactor application Java. A handler may be nested inside
 * a server bootstrap, and separating it safely requires a refactoring tool. A client
 * also contains both application logic and transport setup, so the command does not
 * infer which code should become a {@code dissaly.Job}.
 *
 * <p>The command writes losim's files, moves project directories, and reports the
 * Java edits still required. {@code dissaly check} runs the same detector again.
 *
 * <h2>Projects with a committed {@code lib/}</h2>
 *
 * <p>Projects from before 1.5.0 may carry simulator jars in {@code lib/}, with the
 * viewer and manual beside them. losim no longer reads those copies. The command
 * keeps the existing {@code proto/}, {@code src/}, and {@code simulations/}
 * directories, writes the build file, and ignores the old artifacts.
 *
 * <p>{@code git rm --cached} removes those artifacts from the index while leaving
 * their files on disk.
 */
public final class Adopt {
    private Adopt() {}

    public static int main(String[] args) throws Exception {
        Path root = Path.of(Main.option(args, "--root",
                Main.positionalOr(args, "adopt", "."))).toAbsolutePath().normalize();
        boolean force = Main.flag(args, "--force");
        boolean dirty = Main.flag(args, "--dirty");

        if (!Files.isDirectory(root)) {
            System.err.println("no such directory: " + root);
            return 2;
        }
        System.out.println("dissaly adopt — reading " + short_(root, root) + " as a gRPC project");
        System.out.println();

    // Projects from before 1.5.0 may carry losim as committed jars. Keep their
    // source layout and update only the build and ignored artifacts.
        boolean vendored = Files.isRegularFile(root.resolve("lib/losim.jar"));

        Scan scan = Scan.of(root);
        if (scan.protos().isEmpty() && scan.services().isEmpty()) {
            System.err.println("""
                  Nothing here looks like a gRPC project: no .proto, and no class extending a
                  protoc-generated ImplBase. Point this at the project you want to simulate,
                  or write the schema first — the manual starts at /first/schema.""");
            return 2;
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            System.err.println("""
                  This is a Maven project, and losim writes a Gradle build. Convert it first,
                  or add the dependency and the toolchain task by hand — /start/adopt has
                  both, and they are twenty lines.""");
            return 2;
        }
        if (Files.isRegularFile(root.resolve("settings.gradle.kts"))
                || Files.isRegularFile(root.resolve("settings.gradle"))) {
            String settings = Files.readString(Files.isRegularFile(root.resolve("settings.gradle.kts"))
                    ? root.resolve("settings.gradle.kts") : root.resolve("settings.gradle"));
            if (settings.contains("include")) {
                System.err.println("""
                      This is a multi-module build, and a lab is one module: one folder of gRPC
                      code, shared by every simulation in it. Point this at the module that holds
                      the handlers.""");
                return 2;
            }
        }
        if (!dirty && dirtyTree(root)) {
            System.err.println("""
                  There are uncommitted changes here. This moves files with `git mv`, so commit
                  first and the move is a diff you can read — or pass --dirty if you mean it.""");
            return 2;
        }

        found(scan, root, vendored);
        Plan plan = plan(scan, root, vendored);
        plan.print(root);
        plan.apply(root);

        // Re-scan after moving files so findings use the new paths.
        Scan moved = Scan.of(root);
        write(moved, root, force, vendored);
        report(moved, root, vendored);
        return 0;
    }

    // --------------------------------------------------------------------- found

    private static void found(Scan scan, Path root, boolean vendored) {
        var lines = new LinkedHashMap<String, String>();
        if (!scan.buildFile().isEmpty()) {
            var bits = new ArrayList<String>();
            if (!scan.grpcVersion().isEmpty()) bits.add("grpc " + scan.grpcVersion());
            if (!scan.protobufVersion().isEmpty()) bits.add("protobuf " + scan.protobufVersion());
            lines.put(scan.buildFile(), bits.isEmpty() ? "no versions it could name" : String.join(", ", bits));
        }
        lines.put(count(scan.protos().size(), ".proto"),
                  join(scan.protos().stream().map(p -> short_(root, p)).toList()));
    // The runs entry names a file; group each service under that source file.
        for (Scan.Service s : scan.services()) {
            String service = scan.serviceOf(s);
            var rpcs = scan.rpcs().stream()
                    .filter(r -> r.service().equals(service))
                    .map(r -> r.name() + (r.streaming() ? " (streaming)" : ""))
                    .toList();
            lines.put(short_(root, s.file()), service + (rpcs.isEmpty() ? ""
                    : " — " + count(rpcs.size(), "rpc") + ": " + String.join(", ", rpcs)));
        }
        System.out.println("  found");
        int width = lines.keySet().stream().mapToInt(String::length).max().orElse(12);
        for (var e : lines.entrySet()) {
            System.out.printf("    %-" + width + "s   %s%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println(vendored ? """
              This lab carries the simulator as committed jars. All of it — the jars, the
              viewer and the manual — is inside one artifact now, which a build resolves.
              Your code is already where losim looks for it, so nothing here moves."""
            : """
              This is a program that talks to itself over a real socket. losim runs the same
              handlers on many nodes over a simulated network, so the socket, the port and
              the shutdown hook all go — and the handlers do not.""");
        System.out.println();
    }

    // ---------------------------------------------------------------------- plan

    /** What is about to happen, so it can be printed before it does. */
    private record Plan(List<String[]> moves, Map<String, String> writes,
                        List<String> untracked, List<String> kept) {

        void print(Path root) {
            if (!moves.isEmpty()) {
                System.out.println("  moving        git mv, so your history follows");
                for (String[] m : moves) System.out.printf("    %-16s ->  %s%n", m[0], m[1]);
                System.out.println();
            }
            if (!untracked.isEmpty()) {
                System.out.println("  untracking    git rm --cached: out of the index, still on disk");
                for (String d : untracked) {
                    System.out.printf("    %-16s %s%n", d + "/", size(root.resolve(d)));
                }
                System.out.println();
            }
            System.out.println("  writing");
            for (var e : writes.entrySet()) System.out.printf("    %-26s %s%n", e.getKey(), e.getValue());
            if (!kept.isEmpty()) {
                System.out.println();
                System.out.println("  keeping");
                for (String k : kept) System.out.println("    " + k);
            }
            System.out.println();
        }

        void apply(Path root) throws Exception {
            for (String[] m : moves) move(root, m[0], m[1]);
            for (String d : untracked) untrack(root, d);
        }
    }

    private static Plan plan(Scan scan, Path root, boolean vendored) throws IOException {
        var moves = new ArrayList<String[]>();
        if (!vendored) {
            if (Files.isDirectory(root.resolve("src/main/proto"))) moves.add(new String[]{"src/main/proto/", "proto/"});
            if (Files.isDirectory(root.resolve("src/main/java"))) moves.add(new String[]{"src/main/java/", "src/"});
        }

        var writes = new LinkedHashMap<String, String>();
        writes.put("build.gradle.kts", "losim " + Scaffold.version()
                + ", protoc, and the toolchain task");
        writes.put("dissaly", "the launcher: it builds, then runs");
        writes.put("AGENTS.md", "what is left, for your agent");
    // Older projects may already contain simulations. Do not add an extra file
    // unless the project has no simulation to use.
        if (!vendored) writes.put("simulations/1-one-call.yaml", "two nodes and one call");
        writes.put(".gitignore", "+= gen/ build/" + (vendored ? " lib/ viewer/ docs/" : ""));

        var untracked = new ArrayList<String>();
        if (vendored) {
            untracked.add("lib");
    // Remove only files written by losim. A project-owned `docs/` or unrelated
    // `viewer/` directory must remain untouched.
            if (Files.isRegularFile(root.resolve("viewer/index.html"))) untracked.add("viewer");
            if (Files.isRegularFile(root.resolve("docs/docs.json"))) untracked.add("docs");
        }

        var kept = new ArrayList<String>();
        if (Files.isRegularFile(root.resolve("build.gradle"))) {
            kept.add("build.gradle -> build.gradle.bak, because Gradle will not have two");
        }
        if (scan.buildFileText().contains("grpc-netty")) {
            kept.add("grpc-netty-shaded is not carried over: there is no socket transport in");
            kept.add("a simulated network, and nothing on the lab classpath provides one");
        }
        return new Plan(moves, writes, untracked, kept);
    }

    /**
     * The files themselves, after the moves, so their contents name where things
     * are now.
     *
     * <p>Written unless one is there already, which is the difference between
     * adopting a project twice and losing what somebody wrote the first time.
     */
    private static void write(Scan scan, Path root, boolean force, boolean vendored)
            throws IOException {
        put(root, "build.gradle.kts",
                Scaffold.build(scan.grpcVersion(), scan.protobufVersion()), force);
        put(root, "dissaly", Scaffold.launcher(), force);
        put(root, "AGENTS.md", Agents.forProject(scan, root), force);
        if (!vendored) put(root, "simulations/1-one-call.yaml", firstSimulation(scan, root), force);

        // Preserve existing ignore rules and append losim's entries.
        Path ignore = root.resolve(".gitignore");
        String have = Files.isRegularFile(ignore) ? Files.readString(ignore) : "";
        if (!have.contains("gen/")) {
            Files.writeString(ignore, have + (have.endsWith("\n") || have.isEmpty() ? "" : "\n")
                    + "\n" + Scaffold.gitignore());
            have = Files.readString(ignore);
        }
        // Ignore directories removed from the index so git status shows the conversion.
        if (vendored) {
            var add = new StringBuilder();
            for (String d : new String[]{"lib/", "viewer/", "docs/"}) {
                if (!have.contains(d)) add.append(d).append('\n');
            }
            if (!add.isEmpty()) {
                Files.writeString(ignore, have + (have.endsWith("\n") ? "" : "\n")
                        + "\n# What losim used to be copied into a lab as. It is one\n"
                        + "# dependency now, and all of this is inside it.\n" + add);
            }
        }
        // Keep the old build file as a backup so Gradle sees only one active build.
        Path old = root.resolve("build.gradle");
        if (Files.isRegularFile(old)) Files.move(old, root.resolve("build.gradle.bak"));
        Path launcher = root.resolve("dissaly");
        if (Files.isRegularFile(launcher)) launcher.toFile().setExecutable(true);
    }

    private static void put(Path root, String at, String body, boolean force) throws IOException {
        Path file = root.resolve(at);
        if (Files.exists(file) && !force) {
            System.out.println("  (keeping the " + at + " that was already here; --force overwrites)");
            return;
        }
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, body);
    }

    /**
     * Builds the initial simulation from the files found after the split.
     *
     * <p>The generated simulation may name a file that does not exist yet. Loading
     * then reports the missing path and the expected {@code runs:} value. The command
     * cannot choose or create the project's {@code dissaly.Job} implementation.
     */
    private static String firstSimulation(Scan scan, Path root) {
        String entry = "src/YourJob.java";
        var runs = new ArrayList<String[]>();
        var placed = new ArrayList<String>();
        for (Scan.Service s : scan.services()) {
            // A nested service has no standalone path. Check reports its location.
            if (s.nested()) continue;
            String file = short_(root, s.file()).replace('\\', '/');
            if (s.entry()) { entry = file; continue; }
            String service = scan.serviceOf(s);
            // Leave replica placement to the project author.
            if (placed.contains(service)) continue;
            placed.add(service);
            runs.add(new String[]{service, file});
        }
        var costs = new ArrayList<String[]>();
        for (String[] run : runs) {
            for (Scan.Rpc r : scan.rpcs()) {
                if (r.streaming()) continue;
                if (!r.service().equals(run[0])) continue;
                costs.add(new String[]{run[1], r.name()});
            }
        }
        return Scaffold.simulation(entry, runs, costs);
    }

    // -------------------------------------------------------------------- report

    /**
     * Prints remaining findings by class and source line.
     *
     * <p>The renderer is shared with {@link Check}, so both commands report the same
     * findings.
     */
    private static void report(Scan scan, Path root, boolean vendored) {
        System.out.println(vendored ? """
              No .java was touched and no simulation rewritten. What is left is yours —
              AGENTS.md has all of it, in the same classes, so your agent can work from it:"""
            : """
              Nothing inside a .java was touched. What is left is yours — AGENTS.md has all of
              it, in the same classes, so your agent can work from it:""");
        System.out.println();
        Check.report(root, scan);
        System.out.println();
        System.out.println("""
              next
                ./dissaly build          generate, compile, and say what is wrong
                ./dissaly check          re-run these findings without running anything
                ./dissaly serve          the lab, on :8000
                gradle --write-locks losimToolchain
                                       once, then commit gradle.lockfile — your
                                       classpath is resolved now, and this pins it""");
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Moves one directory to another, merging when the target already exists.
     *
     * <p>{@code src/main/java -> src} requires a merge when {@code src} already
     * contains {@code main}. A plain rename cannot perform that merge.
     */
    private static void move(Path root, String from, String to) throws Exception {
        Path source = root.resolve(from.replaceAll("/$", ""));
        Path target = root.resolve(to.replaceAll("/$", ""));
        if (!Files.exists(source)) return;
        if (Files.isDirectory(target)) {
            try (var children = Files.list(source)) {
                for (Path child : children.toList()) {
                    one(root, child, target.resolve(child.getFileName()));
                }
            }
            prune(source, root);
            return;
        }
        Files.createDirectories(target.getParent() == null ? root : target.getParent());
        one(root, source, target);
    }

    private static void one(Path root, Path source, Path target) throws Exception {
        if (Files.exists(target)) {
            System.out.println("    (" + root.relativize(target) + " is already there; left alone)");
            return;
        }
        int code = new ProcessBuilder("git", "mv", root.relativize(source).toString(),
                        root.relativize(target).toString())
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        // Move untracked files with the filesystem when git cannot move them.
        if (code != 0) {
            Files.move(source, target);
            System.out.println("    (moved " + root.relativize(source)
                    + " without git; history will not follow)");
        }
    }

    /**
     * Removes a directory from the index while leaving its files on disk.
     *
     * <p>{@code --cached} preserves the local copy of the simulator artifacts.
     */
    private static void untrack(Path root, String dir) throws Exception {
        int code = new ProcessBuilder("git", "rm", "-r", "--cached", "-q", dir)
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        if (code != 0) {
            System.out.println("    (" + dir + "/ is not tracked here; left alone)");
        }
    }

    /** Returns the approximate size of a directory. */
    private static String size(Path dir) {
        long bytes = 0;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.toList()) if (Files.isRegularFile(p)) bytes += Files.size(p);
        } catch (IOException e) {
            return "";
        }
        return bytes >= 1 << 20 ? (bytes >> 20) + " MB" : Math.max(1, bytes >> 10) + " KB";
    }

    /** Removes empty directories left after a move. */
    private static void prune(Path dir, Path root) throws IOException {
        for (Path p = dir; p.startsWith(root) && !p.equals(root); p = p.getParent()) {
            try (var rest = Files.list(p)) {
                if (rest.findAny().isPresent()) return;
            }
            Files.delete(p);
        }
    }

    private static boolean dirtyTree(Path root) {
        try {
            Process p = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(root.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            return p.waitFor() == 0 && !out.isBlank();
        } catch (Exception e) {
            return false;              // Treat non-repositories as clean.
        }
    }

    private static String short_(Path root, Path p) {
        Path rel = p.startsWith(root) ? root.relativize(p) : p;
        return rel.toString().isEmpty() ? "." : rel.toString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 || noun.endsWith("s") ? "" : "s");
    }

    private static String join(List<String> xs) {
        return xs.size() <= 2 ? String.join(", ", xs)
                : xs.get(0) + " and " + (xs.size() - 1) + " more";
    }

    /** Wraps text to a terminal-friendly width. */
    static String wrap(String text, String indent) {
        var sb = new StringBuilder(indent);
        int column = 0;
        for (String word : text.split(" ")) {
            if (column + word.length() > 72) { sb.append("\n").append(indent); column = 0; }
            sb.append(word).append(' ');
            column += word.length() + 1;
        }
        return sb.toString().stripTrailing();
    }
}
