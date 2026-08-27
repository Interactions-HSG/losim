package losim.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A project of systems, and how to run one.
 *
 * <p>This exists so that nobody has to type anything. A lab is a folder of small
 * gRPC systems; running one means generating from its schema, compiling it, and
 * handing the scenario to {@link Main} — three tools in a row, each with its own
 * flags, none of which teaches anything about decentralized systems. Written as
 * a shell script it becomes a thing students have to read, get wrong, and be
 * supported through. Written here it is a button.
 *
 * <p><b>Nothing about a system is declared.</b> There is no manifest listing what
 * to build, because a manifest is one more file to keep in step with the folder
 * beside it — and a student who adds a service and forgets to register it would
 * get a run that silently omits their new code. A directory <i>is</i> a system if
 * it has Java in it; its schema is whatever {@code .proto} files it holds and its
 * world is whatever scenario it holds. Add a file, and it is in the next run.
 *
 * <p><b>Each run forks its own JVM.</b> A system that exhausts its heap is a
 * result this course is about, and it must not take the server down with it —
 * nor may two runs share a JVM, because then they share a heap and an order, and
 * an out-of-memory in one is a mystery in the other.
 */
public final class Lab {

    /** Where the student's own runs land, and where the viewer looks for them. */
    public static final String RUNS = "build/runs";

    private final Path root;
    private final Path lib;

    public Lab(Path root, Path lib) {
        this.root = root.toAbsolutePath().normalize();
        this.lib = lib.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    /**
     * Whether this directory is a lab at all.
     *
     * <p>The test is that losim is here <i>as a library</i> — which is what a lab
     * is, and what the simulator's own repository is not. Without it, pointing
     * the server at the wrong directory lists that directory's furniture as
     * things to run: the simulator's own sources came back as a "system" whose
     * run button compiled sixty files. Better to say "this is not a lab" once
     * than to offer somebody thirteen buttons, none of them theirs.
     */
    public boolean isLab() { return Files.isRegularFile(lib.resolve("losim.jar")); }

    /**
     * One system: a folder with code in it.
     *
     * @param id       its path from the project root — {@code 0-tour/1-two-machines}
     * @param worlds   every scenario in it, {@code main} first. A system with more
     *                 than one is a system with variants — the same code against a
     *                 crueller afternoon — and each is run and drawn separately,
     *                 because the whole point of having two is comparing them
     * @param protos   its schema, which may be empty and may be more than one file
     * @param sources  every {@code .java} in it, wherever the student put them
     */
    public record Task(String id, Path dir, List<Path> worlds, List<Path> protos, List<Path> sources) {

        /** The one that runs when nobody named another. */
        public Path scenario() { return worlds.isEmpty() ? null : worlds.get(0); }

        /** A scenario by its file name, or null. */
        public Path world(String name) {
            for (Path p : worlds) if (p.getFileName().toString().equals(name)) return p;
            return null;
        }

        /** What the picker offers beside this system. */
        public List<String> worldNames() {
            return worlds.stream().map(p -> p.getFileName().toString()).toList();
        }

        /** Whether there is anything here yet. Task 2 starts as an empty folder. */
        public boolean started() { return !sources.isEmpty(); }

        /** A fleet, or one machine? The second needs no scenario and gets no film. */
        public boolean distributed() { return !worlds.isEmpty(); }
    }

    // ------------------------------------------------------------------ finding

    /**
     * Two different questions, which is why there are two lists.
     *
     * <p><b>Not a system</b> — a directory that is never a thing to run in its own
     * right. Some of these are the project's furniture; the rest are a system's
     * own parts. `1-mapreduce/src` came back as a second entry beside
     * `1-mapreduce`, with the same four files and a button that did the same
     * thing, which is how this list came to be.
     *
     * <p><b>Not scanned</b> — a directory whose contents are not a system's
     * sources even when it sits inside one. Generated code and compiled classes
     * are the whole of it: handing `gen/` to javac twice is how a build starts
     * reporting duplicate classes.
     */
    private static final List<String> NOT_A_SYSTEM =
            List.of("build", "lib", "docs", "viewer", "node_modules", "presentation",
                    "gen", "out", "classes", "input", "corpus",
                    "src", "checks", "test", "tests", "proto", "scenarios");

    private static final List<String> NOT_SCANNED =
            List.of("build", "lib", "docs", "viewer", "node_modules", "gen", "out", "classes");

    /**
     * Every system in the project, in the order a student meets them.
     *
     * <p>Two levels deep, because a ladder of small systems wants a folder around
     * it ({@code 0-tour/3-bigger-messages}) and a single task does not
     * ({@code 1-wordcount}). Three levels would start finding {@code src/main}.
     */
    public List<Task> tasks() {
        if (!isLab()) return List.of();
        List<Task> found = new ArrayList<>();
        for (Path top : children(root)) {
            if (skip(top)) continue;
            List<Path> nested = children(top).stream().filter(p -> !skip(p)).toList();
            boolean any = false;
            for (Path in : nested) {
                Task t = read(in);
                if (t != null) { found.add(t); any = true; }
            }
            // A folder that only groups other systems is not itself one — but a
            // folder with its own code is, even when it also has subfolders.
            Task self = read(top);
            if (self != null && (!any || self.started())) found.add(self);
        }
        found.sort(Comparator.comparing(Task::id));
        return found;
    }

    /** The one with this id, or null. */
    public Task task(String id) {
        for (Task t : tasks()) if (t.id().equals(id)) return t;
        return null;
    }

    /** Could this directory itself be a system? */
    private boolean skip(Path dir) {
        String name = dir.getFileName().toString();
        return name.startsWith(".") || NOT_A_SYSTEM.contains(name) || !Files.isDirectory(dir);
    }

    /** Should a system's file walk go into this directory? */
    private static boolean opaque(Path dir) {
        String name = dir.getFileName().toString();
        return name.startsWith(".") || NOT_SCANNED.contains(name);
    }

    /** A directory is a system if it holds Java, a schema or a scenario of its own. */
    private Task read(Path dir) {
        List<Path> sources = under(dir, ".java");
        List<Path> protos = under(dir, ".proto");
        List<Path> worlds = new ArrayList<>(under(dir, ".yaml"));
        worlds.addAll(under(dir, ".yml"));
        if (sources.isEmpty() && protos.isEmpty() && worlds.isEmpty()) {
            // A task can be declared and empty — Task 2 starts as a folder with a
            // brief in it and nothing else, and it has to be in the list from the
            // first day or the student cannot see what they are aiming at.
            if (!Files.isRegularFile(dir.resolve("README.md"))) return null;
        }

        // `main` first, so a system can carry variants beside it without one of
        // them being chosen by alphabet.
        worlds.sort(Comparator.comparing((Path p) -> p.getFileName().toString().startsWith("main.") ? 0 : 1)
                              .thenComparing(p -> p.getFileName().toString()));
        return new Task(rel(dir), dir, worlds, protos, sources);
    }

    /**
     * Files of one extension inside a system, not descending into another one.
     *
     * <p>The stop is what makes {@code 0-tour} a group rather than a giant system
     * containing all five rungs: a directory that is a system in its own right
     * belongs to itself.
     */
    private List<Path> under(Path dir, String ext) {
        List<Path> out = new ArrayList<>();
        collect(dir, dir, ext, out);
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private void collect(Path from, Path here, String ext, List<Path> out) {
        for (Path p : children(here)) {
            if (Files.isDirectory(p)) {
                if (opaque(p)) continue;
                // Its own scenario or schema makes it somebody else's system —
                // which is what keeps `0-tour` a group of five rather than one
                // enormous system containing all five rungs' code at once.
                boolean own = !here.equals(from) || !isSystemOfItsOwn(p);
                if (own) collect(from, p, ext, out);
            } else if (p.getFileName().toString().endsWith(ext)) {
                out.add(p);
            }
        }
    }

    private boolean isSystemOfItsOwn(Path dir) {
        for (Path p : children(dir)) {
            String n = p.getFileName().toString();
            if (n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".proto")) return true;
        }
        return false;
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String rel(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ running

    /**
     * Generate, compile, run, bill — and say which step failed, in its own words.
     *
     * <p>Every step's own output goes to {@code log} as it arrives rather than at
     * the end, because the interesting part of a run that hangs is what it had
     * printed before it stopped.
     *
     * @return 0 if the run completed and its checks held
     */
    /**
     * Generate and compile a system, and say where the classes are.
     *
     * <p>Separate from {@link #run} because there is more than one thing worth
     * doing with a compiled submission: running it in its own world is one, and
     * running it in a world it has never seen is another.
     */
    public Path compile(Task t, Consumer<String> log) throws IOException, InterruptedException {
        if (!t.started()) {
            log.accept("There is no code in " + t.id() + " yet.\n");
            return null;
        }
        Path work = root.resolve("build").resolve(t.id().replace('/', '-'));
        Path gen = t.dir().resolve("gen");
        Path classes = work.resolve("classes");
        wipe(gen);
        wipe(classes);
        Files.createDirectories(gen);
        Files.createDirectories(classes);
        if (!t.protos().isEmpty() && generate(t, gen, log) != 0) return null;
        return compile(t, gen, classes, log) == 0 ? classes : null;
    }

    public int run(Task t, String world, Consumer<String> log) throws IOException, InterruptedException {
        if (!t.started()) {
            log.accept("There is no code in " + t.id() + " yet — that is the exercise.\n");
            return 2;
        }

        Path work = root.resolve("build").resolve(t.id().replace('/', '-'));
        // Generated code lands **beside the schema it came from**, not off in a
        // build directory. Two reasons, and the second is the real one: the
        // editor finds it there, so `tour.ping.Ping` resolves and completion
        // works on generated types without anything being configured — and a
        // student who wants to see what `protoc` actually made can open it.
        Path gen = t.dir().resolve("gen");
        Path classes = work.resolve("classes");
        wipe(gen);
        wipe(classes);
        Files.createDirectories(gen);
        Files.createDirectories(classes);

        if (!t.protos().isEmpty() && generate(t, gen, log) != 0) return 1;
        if (compile(t, gen, classes, log) != 0) return 1;

        Path runs = root.resolve(RUNS);
        Files.createDirectories(runs);

        Path chosen = world == null || world.isBlank() ? t.scenario() : t.world(world);
        if (chosen == null && world != null && !world.isBlank()) {
            log.accept("There is no scenario called " + world + " in " + t.id() + ".\n");
            return 2;
        }

        if (chosen == null) {
            // Ordinary Java on one machine: there is no fleet to simulate and
            // nothing to draw, so the code speaks for itself.
            log.accept("\n");
            return exec(List.of(java(), "-cp", cp(classes), mainClassOf(t, classes)), log);
        }

        // Named after the system and the world, so two variants of one system are
        // two runs in the picker rather than one overwriting the other.
        String stem = t.id().replace('/', '-');
        String name = chosen.getFileName().toString().replaceAll("\\.ya?ml$", "");
        if (!name.equals("main")) stem = stem + "-" + name;
        Path trace = runs.resolve(stem + ".json");
        log.accept("\n");
        // `--no-view`: this run already has a viewer — the one that started it.
        int code = exec(List.of(java(), "-cp", cp(), "losim.cli.Main", "run", "--no-view",
                chosen.toString(), "--cp", classes.toString(), "--out", trace.toString()), log);

        // The bill is part of the run, not a second command: a design decision
        // that costs nothing is not a design decision, and a student who has to
        // ask for the price will not ask.
        if (Files.exists(trace)) bill(trace, log);
        return code;
    }

    private int generate(Task t, Path gen, Consumer<String> log) throws IOException, InterruptedException {
        Path protoc = lib.resolve("bin").resolve("protoc-" + platform());
        Path plugin = lib.resolve("bin").resolve("protoc-gen-grpc-java-" + platform());
        if (!Files.isExecutable(protoc) || !Files.isExecutable(plugin)) {
            log.accept("No protobuf compiler for " + platform() + " in " + lib.resolve("bin") + ".\n");
            return 1;
        }
        List<String> argv = new ArrayList<>(List.of(protoc.toString(),
                "--plugin=protoc-gen-grpc-java=" + plugin,
                "--java_out=" + gen, "--grpc-java_out=" + gen));
        // An include path per schema directory, because an `import` is resolved
        // against the include path and nobody said where these files must live.
        for (Path p : t.protos()) { argv.add("-I"); argv.add(p.getParent().toString()); }
        for (Path p : t.protos()) argv.add(p.toString());
        log.accept("Reading the schema…\n");
        return exec(argv, log);
    }

    private int compile(Task t, Path gen, Path classes, Consumer<String> log)
            throws IOException, InterruptedException {
        List<Path> sources = new ArrayList<>(under(gen, ".java"));
        sources.addAll(t.sources());
        List<String> argv = new ArrayList<>(List.of(javac(), "-nowarn", "--release", "21",
                "-cp", cp(), "-d", classes.toString()));
        for (Path p : sources) argv.add(p.toString());
        log.accept("Compiling " + sources.size() + " files…\n");
        return exec(argv, log);
    }

    private void bill(Path trace, Consumer<String> log) throws IOException, InterruptedException {
        String name = trace.getFileName().toString().replaceAll("\\.json$", "");
        Path out = trace.resolveSibling(name + ".bill.json");
        StringBuilder json = new StringBuilder();
        List<String> argv = new ArrayList<>(List.of(java(), "-cp", cp(),
                "losim.cli.Main", "bill", trace.toString(), "--json"));
        Path prices = lib.resolve("prices/eu-central-1.yaml");
        if (!Files.exists(prices)) prices = root.resolve("prices/eu-central-1.yaml");
        if (Files.exists(prices)) { argv.add("--prices"); argv.add(prices.toString()); }
        // Quietly: the viewer shows the money, and a wall of numbers after every
        // run is how a student learns to stop reading the output.
        int code = exec(argv, json::append);
        if (code == 0 && !json.isEmpty()) Files.writeString(out, json.toString());
        else log.accept("(no bill for this run)\n");
    }

    /**
     * The class with a {@code main} in a system that has no scenario.
     *
     * <p>Task 1 is ordinary Java, so something has to be started. Rather than
     * requiring a name, this takes the one class that has a {@code main} — and
     * says so plainly when there is none, because "could not find or load main
     * class" is not a sentence a first-year should have to decode.
     */
    private String mainClassOf(Task t, Path classes) {
        for (Path p : t.sources()) {
            try {
                if (Files.readString(p).contains("static void main(")) {
                    return p.getFileName().toString().replaceAll("\\.java$", "");
                }
            } catch (IOException ignored) { /* unreadable source is javac's to report */ }
        }
        return "Main";
    }

    // ------------------------------------------------------------- the toolchain

    /** The classpath a lab compiles and runs against: the simulator and gRPC. */
    public String cp() {
        StringBuilder sb = new StringBuilder();
        Path jars = lib.resolve("jars");
        for (Path p : children(jars)) {
            if (p.getFileName().toString().endsWith(".jar")) sb.append(p).append(java.io.File.pathSeparator);
        }
        sb.append(lib.resolve("losim.jar"));
        return sb.toString();
    }

    private String cp(Path extra) {
        return cp() + java.io.File.pathSeparator + extra;
    }

    /** The JVM running this one, so a lab cannot end up on a different Java. */
    private static String java() {
        return Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String javac() {
        return Path.of(java.lang.System.getProperty("java.home"), "bin", "javac").toString();
    }

    /** The classifier the vendored binaries are named with. */
    public static String platform() {
        String os = java.lang.System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = java.lang.System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = switch (arch) {
            case "aarch64", "arm64" -> "aarch_64";
            case "amd64", "x86_64" -> "x86_64";
            default -> arch;
        };
        if (os.contains("mac") || os.contains("darwin")) return "osx-" + cpu;
        if (os.contains("win")) return "windows-" + cpu;
        return "linux-" + cpu;
    }

    /**
     * Run a tool and stream what it says.
     *
     * <p>Merged streams on purpose: javac writes errors to one and notes to the
     * other, and a student reading them interleaved is reading what happened.
     */
    private int exec(List<String> argv, Consumer<String> log) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true).start();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) log.accept(line + "\n");
        }
        return p.waitFor();
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
