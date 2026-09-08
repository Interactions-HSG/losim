package losim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code losim adopt} — puts losim under a gRPC project that already works.
 *
 * <p>The assumption this exists to correct: that a student arrives holding a
 * repository losim built for them. They do not. They arrive with a project shaped
 * like the grpc-java quickstart — Gradle, {@code src/main/proto}, a server that
 * binds a port, a client that dials one — and nothing in losim used to meet them
 * there. The docs said not to write that code; nothing said what happened to the
 * code they already had.
 *
 * <h2>It writes files. It never writes Java.</h2>
 *
 * <p>Both edits the obvious design would make are unsafe in the shape the
 * quickstart actually has. The handler is a static nested class inside the server
 * bootstrap, so deleting the file deletes the handler and extracting the class is
 * a refactoring engine — a bad extraction silently drops a field initialiser. And
 * turning the client into a {@link losim.api.Job} is a judgement about which of
 * its lines are the design and which are the transport, which is the thing the
 * course is for.
 *
 * <p>The boundary already in force is not "losim writes nothing" — the console
 * writes scenarios today. It is <b>losim writes its own furniture; the Java is
 * yours</b>. So this moves files, writes a build, and prints a line-numbered
 * account of every Java edit still needed. The account is not advice:
 * {@code losim check} re-runs the same detector, so what is left is a command
 * rather than a memory of a terminal that has scrolled away.
 *
 * <h2>The other shape: a lab with a committed {@code lib/}</h2>
 *
 * <p>Labs from before 1.5.0 carry the simulator as jars in {@code lib/}, and the
 * viewer and the manual as directories beside them. Those are the same 23 MB the
 * jar now holds, and losim no longer reads any of it. Such a repository is already
 * the right shape — {@code proto/}, {@code src/}, {@code simulations/} — so nothing
 * moves and no scenario is rewritten: the build file is written, and {@code lib/},
 * {@code viewer/} and {@code docs/} are untracked and ignored.
 *
 * <p><b>Untracked, not deleted.</b> {@code git rm --cached} takes them out of the
 * index and leaves every byte on disk, so the conversion is one commit to revert
 * and nothing of anybody's is destroyed by a command they ran to find out what it
 * would do.
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
        System.out.println("losim adopt — reading " + short_(root, root) + " as a gRPC project");
        System.out.println();

        // A lab from before 1.5.0: the simulator committed as jars. It is already
        // the shape losim runs, so this converts what it carries rather than where
        // its code is.
        boolean vendored = Files.isRegularFile(root.resolve("lib/losim.jar"));

        Shape shape = Shape.of(root);
        if (shape.protos().isEmpty() && shape.services().isEmpty()) {
            System.err.println("""
                  Nothing here looks like a gRPC project: no .proto, and no class extending a
                  protoc-generated ImplBase. Point this at the project you want to simulate,
                  or write the schema first — the manual starts at /first/the-schema.""");
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
                      code, shared by every scenario in it. Point this at the module that holds
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

        found(shape, root, vendored);
        Plan plan = plan(shape, root, vendored);
        plan.print(root);
        plan.apply(root);

        // Read again, because the files have moved. A report naming
        // `src/main/java/…` after `src/main/java/` is gone sends somebody to a path
        // that no longer exists, and AGENTS.md would keep sending them there.
        Shape moved = Shape.of(root);
        write(moved, root, force, vendored);
        report(moved, root, vendored);
        return 0;
    }

    // --------------------------------------------------------------------- found

    private static void found(Shape shape, Path root, boolean vendored) {
        var lines = new LinkedHashMap<String, String>();
        if (!shape.buildFile().isEmpty()) {
            var bits = new ArrayList<String>();
            if (!shape.grpcVersion().isEmpty()) bits.add("grpc " + shape.grpcVersion());
            if (!shape.protobufVersion().isEmpty()) bits.add("protobuf " + shape.protobufVersion());
            lines.put(shape.buildFile(), bits.isEmpty() ? "no versions it could name" : String.join(", ", bits));
        }
        lines.put(count(shape.protos().size(), ".proto"),
                  join(shape.protos().stream().map(p -> short_(root, p)).toList()));
        for (Shape.Service s : shape.services()) {
            var rpcs = shape.rpcs().stream()
                    .map(r -> r.name() + (r.streaming() ? " (streaming)" : ""))
                    .toList();
            lines.put(count(1, "service"), s.name() + (rpcs.isEmpty() ? ""
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
              handlers on many machines over a simulated network, so the socket, the port and
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

    private static Plan plan(Shape shape, Path root, boolean vendored) throws IOException {
        var moves = new ArrayList<String[]>();
        if (!vendored) {
            if (Files.isDirectory(root.resolve("src/main/proto"))) moves.add(new String[]{"src/main/proto/", "proto/"});
            if (Files.isDirectory(root.resolve("src/main/java"))) moves.add(new String[]{"src/main/java/", "src/"});
        }

        var writes = new LinkedHashMap<String, String>();
        writes.put("build.gradle.kts", "losim " + Scaffold.version()
                + ", protoc, and the toolchain task");
        writes.put("losim", "the launcher: it builds, then runs");
        writes.put("AGENTS.md", "what is left, for your agent");
        // A lab from before 1.5.0 has scenarios of its own, and a first scenario
        // written into it would be a file nobody asked for beside the ones they wrote.
        if (!vendored) writes.put("simulations/1-one-call.yaml", "two machines and one call");
        writes.put(".gitignore", "+= gen/ build/" + (vendored ? " lib/ viewer/ docs/" : ""));

        var untracked = new ArrayList<String>();
        if (vendored) {
            untracked.add("lib");
            // Only losim's own copies. A `docs/` somebody wrote is theirs, and a
            // `viewer/` that is not the export is not losim's to take out of a
            // repository — so each is identified by the file losim put in it.
            if (Files.isRegularFile(root.resolve("viewer/index.html"))) untracked.add("viewer");
            if (Files.isRegularFile(root.resolve("docs/docs.json"))) untracked.add("docs");
        }

        var kept = new ArrayList<String>();
        if (Files.isRegularFile(root.resolve("build.gradle"))) {
            kept.add("build.gradle -> build.gradle.bak, because Gradle will not have two");
        }
        if (shape.buildFileText().contains("grpc-netty")) {
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
    private static void write(Shape shape, Path root, boolean force, boolean vendored)
            throws IOException {
        put(root, "build.gradle.kts",
                Scaffold.build(shape.grpcVersion(), shape.protobufVersion()), force);
        put(root, "losim", Scaffold.launcher(), force);
        put(root, "AGENTS.md", Agents.forProject(shape, root), force);
        if (!vendored) put(root, "simulations/1-one-call.yaml", firstScenario(shape), force);

        // Appended rather than written: a project's own ignore file is its own.
        Path ignore = root.resolve(".gitignore");
        String have = Files.isRegularFile(ignore) ? Files.readString(ignore) : "";
        if (!have.contains("gen/")) {
            Files.writeString(ignore, have + (have.endsWith("\n") || have.isEmpty() ? "" : "\n")
                    + "\n" + Scaffold.gitignore());
            have = Files.readString(ignore);
        }
        // The three directories that were just untracked, so that `git status` after
        // this is the conversion and not 900 deleted files.
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
        // Gradle refuses a project with two build files, and the old one is the
        // record of what this project was. Aside, not away.
        Path old = root.resolve("build.gradle");
        if (Files.isRegularFile(old)) Files.move(old, root.resolve("build.gradle.bak"));
        Path launcher = root.resolve("losim");
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
     * The first scenario, naming the classes it expects <b>after</b> the split.
     *
     * <p>Deliberately: a scenario naming a class that does not exist yet fails at
     * load with a message that names the class and says what {@code runs:} takes,
     * which is a better first failure than one that silently works against
     * {@code HelloWorldServer$GreeterImpl} and marks every number untrustworthy.
     */
    private static String firstScenario(Shape shape) {
        List<String> runs = shape.placeable().isEmpty() ? List.of("YourService") : shape.placeable();
        var takes = new ArrayList<String[]>();
        for (String cls : runs) {
            for (Shape.Rpc r : shape.rpcs()) {
                if (r.streaming()) continue;
                takes.add(new String[]{cls, r.name()});
            }
        }
        return Scaffold.scenario("YourJob", runs, takes);
    }

    // -------------------------------------------------------------------- report

    /**
     * What is left, by class, with a line number for each.
     *
     * <p>The output is the product. Everything above this has moved four files;
     * this is the part somebody reads — and it is {@link Check}'s renderer, so
     * that "what is left" and "what was left" cannot come to differ.
     */
    private static void report(Shape shape, Path root, boolean vendored) {
        System.out.println(vendored ? """
              No .java was touched and no scenario rewritten. What is left is yours —
              AGENTS.md has all of it, in the same classes, so your agent can work from it:"""
            : """
              Nothing inside a .java was touched. What is left is yours — AGENTS.md has all of
              it, in the same classes, so your agent can work from it:""");
        System.out.println();
        Check.report(root, shape);
        System.out.println();
        System.out.println("""
              next
                ./losim build          generate, compile, and say what is wrong
                ./losim check          re-run these findings without running anything
                ./losim serve          the lab, on :8000
                gradle --write-locks losimToolchain
                                       once, then commit gradle.lockfile — your
                                       classpath is resolved now, and this pins it""");
    }

    // ------------------------------------------------------------------- helpers

    /**
     * One directory to another, as a merge when the target already exists.
     *
     * <p>{@code src/main/java -> src} is exactly that case: {@code src} is there,
     * holding {@code main}. A plain rename fails, and — worse — fails silently if
     * it is guarded by "does the target exist", which is how this first shipped a
     * project whose report said the Java had moved and whose Java had not.
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
        // Not a git repository, or not tracked. The move still has to happen; what
        // is lost is the history following it, which is worth a line and not a stop.
        if (code != 0) {
            Files.move(source, target);
            System.out.println("    (moved " + root.relativize(source)
                    + " without git; history will not follow)");
        }
    }

    /**
     * A directory out of the index, with every byte of it left on disk.
     *
     * <p>{@code --cached} on purpose. What is being removed is 23 MB of a
     * simulator somebody may well want to look at, or to go back to; a command
     * that deleted it would be a command nobody could safely run to find out what
     * it does.
     */
    private static void untrack(Path root, String dir) throws Exception {
        int code = new ProcessBuilder("git", "rm", "-r", "--cached", "-q", dir)
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        if (code != 0) {
            System.out.println("    (" + dir + "/ is not tracked here; left alone)");
        }
    }

    /** Roughly how much is in a directory, for a line that says what is being moved out. */
    private static String size(Path dir) {
        long bytes = 0;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.toList()) if (Files.isRegularFile(p)) bytes += Files.size(p);
        } catch (IOException e) {
            return "";
        }
        return bytes >= 1 << 20 ? (bytes >> 20) + " MB" : Math.max(1, bytes >> 10) + " KB";
    }

    /** Deletes what is left of a moved-out-of directory, while it is empty. */
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
            return false;              // not a git repository; nothing to be dirty
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

    /** Wrapped to something a terminal shows without folding, under its own number. */
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
