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
 * scenarios are whatever it holds. Add a file, and it is in the next run.
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
    private final Path runs;

    public Lab(Path root, Path lib) {
        this(root, lib, root.resolve(RUNS));
    }

    /**
     * A lab whose runs are written somewhere other than {@code build/runs}.
     *
     * <p>The server takes {@code --runs}, and a server that listed one directory
     * while its run button wrote into another would show a student a picker their
     * own run never appeared in. So the directory is the lab's, not the server's,
     * and there is one of it.
     */
    public Lab(Path root, Path lib, Path runs) {
        this.root = root.toAbsolutePath().normalize();
        this.lib = lib.toAbsolutePath().normalize();
        this.runs = runs.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    /** Where this lab's runs land — what the picker lists, and what the button writes. */
    public Path runs() { return runs; }

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
     * The code in this lab: one folder of gRPC jobs.
     *
     * <p>There is no list of systems here any more, because there never really
     * were several. {@code 0-tour} was five directories holding one
     * {@code ping.proto} between them and five scenarios — the same code against
     * five different afternoons, written out five times so that each could have a
     * folder to sit in. What varies between those runs is the scenario, so the
     * scenario is what there are many of.
     *
     * @param protos  the schema, which may be empty and may be more than one file
     * @param sources every {@code .java} in the lab, wherever the student put it
     */
    public record Code(Path dir, List<Path> protos, List<Path> sources) {

        /** Whether there is anything here yet. A lab starts as an empty folder. */
        public boolean started() { return !sources.isEmpty(); }
    }

    // ------------------------------------------------------------------ finding

    /**
     * Directories that are never the student's code.
     *
     * <p>The project's own furniture, plus the two that are output rather than
     * input: handing {@code gen/} to javac twice is how a build starts reporting
     * duplicate classes.
     */
    private static final List<String> NOT_CODE =
            List.of("build", "lib", "docs", "viewer", "node_modules", "presentation",
                    "gen", "out", "classes", "input", "corpus", "scenarios");

    /** Where scenarios live, and where the console writes a new one. */
    public static final String SCENARIOS = "scenarios";

    /**
     * Everything in this lab that compiles, as one unit.
     *
     * <p>One compile, one classpath, one set of classes — so a scenario can place
     * any class the lab defines on any machine, which is the whole point of
     * separating what the code <i>can</i> do from where it runs.
     */
    public Code code() {
        if (!isLab()) return new Code(root, List.of(), List.of());
        return new Code(root, walk(root, ".proto"), walk(root, ".java"));
    }

    /**
     * Every scenario in the lab, by name.
     *
     * <p>{@code scenarios/} is the home and the place the console writes to. A
     * {@code .yaml} sitting loose in the lab root counts too, so that a lab which
     * has one does not show an empty list and leave a student wondering where it
     * went.
     */
    public List<Path> scenarios() {
        List<Path> out = new ArrayList<>();
        Path dir = root.resolve(SCENARIOS);
        for (Path p : children(dir)) if (yaml(p)) out.add(p);
        for (Path p : children(root)) if (yaml(p)) out.add(p);
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** What the picker offers. */
    public List<String> scenarioNames() {
        return scenarios().stream().map(p -> p.getFileName().toString()).toList();
    }

    /** A scenario by its file name, or null. */
    public Path scenario(String name) {
        if (name == null || name.isBlank()) return scenarios().isEmpty() ? null : scenarios().get(0);
        for (Path p : scenarios()) if (p.getFileName().toString().equals(name)) return p;
        return null;
    }

    private static boolean yaml(Path p) {
        String n = p.getFileName().toString();
        return Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"));
    }

    /** Should the walk go into this directory? */
    private static boolean opaque(Path dir) {
        String name = dir.getFileName().toString();
        return name.startsWith(".") || NOT_CODE.contains(name);
    }

    /** Every file of one extension in the lab, skipping what is not the student's. */
    private List<Path> walk(Path dir, String ext) {
        List<Path> out = new ArrayList<>();
        collect(dir, ext, out);
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private void collect(Path here, String ext, List<Path> out) {
        for (Path p : children(here)) {
            if (Files.isDirectory(p)) {
                if (!opaque(p)) collect(p, ext, out);
            } else if (p.getFileName().toString().endsWith(ext)) {
                out.add(p);
            }
        }
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
    public Path compile(Consumer<String> log) throws IOException, InterruptedException {
        Code c = code();
        if (!c.started()) {
            log.accept("There is no code in this lab yet.\n");
            return null;
        }
        Path gen = root.resolve("gen");
        Path classes = classes();
        wipe(gen);
        wipe(classes);
        Files.createDirectories(gen);
        Files.createDirectories(classes);
        if (!c.protos().isEmpty() && generate(c.protos(), gen, log) != 0) return null;
        return compile(c.sources(), gen, classes, log) == 0 ? classes : null;
    }

    /**
     * Where this system's run in this world is written, or null if it has no world.
     *
     * <p>Here rather than at each caller. The name was being derived in three
     * places — the run, the task list and the log — from two different things, so
     * a system whose only scenario was `slow.yaml` wrote `id-slow.json` while both
     * endpoints looked for `id.json` and the finished run never appeared.
     */
    /** Where the lab's compiled classes go, whether or not they are there yet. */
    public Path classes() {
        return root.resolve("build").resolve("classes");
    }

    /**
     * Whether what is compiled is newer than everything it was compiled from.
     *
     * <p>So that reading a system's palette does not mean rebuilding it. A wrong
     * answer here is only ever a stale list — the classes are still rebuilt before
     * anything is run — and the alternative is a page that takes five seconds to
     * open because it regenerates protobuf every time somebody looks at it.
     */
    public boolean compiled() {
        Path classes = classes();
        if (!Files.isDirectory(classes)) return false;
        try {
            long built = newest(classes, ".class");
            if (built == 0) return false;
            long wrote = Math.max(newest(root, ".java"), newest(root, ".proto"));
            return built >= wrote;
        } catch (IOException e) {
            return false;
        }
    }

    private static long newest(Path dir, String ext) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            long best = 0;
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(ext)).toList()) {
                best = Math.max(best, Files.getLastModifiedTime(p).toMillis());
            }
            return best;
        }
    }

    /**
     * Where this scenario's run is written.
     *
     * <p>Named after the scenario, because the scenario is now the thing there are
     * many of. `two-machines.yaml` runs into `two-machines.json`, so the picker
     * reads the way the folder does.
     */
    public Path trace(String scenario) {
        Path chosen = scenario(scenario);
        if (chosen == null) return null;
        String stem = chosen.getFileName().toString().replaceAll("\\.ya?ml$", "");
        return runs.resolve(stem + ".json");
    }

    public int run(String scenario, Consumer<String> log) throws IOException, InterruptedException {
        Code c = code();
        if (!c.started()) {
            log.accept("There is no code in this lab yet — that is the exercise.\n");
            return 2;
        }

        // Generated code lands **beside the schema it came from**, not off in a
        // build directory. Two reasons, and the second is the real one: the
        // editor finds it there, so `tour.ping.Ping` resolves and completion
        // works on generated types without anything being configured — and a
        // student who wants to see what `protoc` actually made can open it.
        Path gen = root.resolve("gen");
        Path classes = classes();
        wipe(gen);
        wipe(classes);
        Files.createDirectories(gen);
        Files.createDirectories(classes);

        if (!c.protos().isEmpty() && generate(c.protos(), gen, log) != 0) return 1;
        if (compile(c.sources(), gen, classes, log) != 0) return 1;

        Path chosen = scenario(scenario);
        if (chosen == null && scenario != null && !scenario.isBlank()) {
            log.accept("There is no scenario called " + scenario + " in this lab.\n");
            return 2;
        }

        if (chosen == null) {
            // Ordinary Java on one machine: there is no fleet to simulate and
            // nothing to draw, so the code speaks for itself.
            String main = mainClassOf(c.sources());
            if (main == null) {
                log.accept("Nothing in this lab has a `public static void main`, and there\n"
                        + "is no scenario beside it either — so there is nothing here to start.\n");
                return 2;
            }
            log.accept("\n");
            return exec(List.of(java(), "-cp", cp(classes), main), log);
        }

        Files.createDirectories(runs);
        Path trace = trace(scenario);
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

    private int generate(List<Path> protos, Path gen, Consumer<String> log) throws IOException, InterruptedException {
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
        for (Path p : protos) { argv.add("-I"); argv.add(p.getParent().toString()); }
        for (Path p : protos) argv.add(p.toString());
        log.accept("Reading the schema…\n");
        return exec(argv, log);
    }

    private int compile(List<Path> own, Path gen, Path classes, Consumer<String> log)
            throws IOException, InterruptedException {
        List<Path> sources = new ArrayList<>(walk(gen, ".java"));
        sources.addAll(own);
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
        // The two streams kept apart, which every other call here merges on
        // purpose. `--json` writes JSON on stdout and says everything else on
        // stderr; merged, a single line of "no price list, using the defaults"
        // lands at the top of the file and the bill stops being JSON at all.
        // Nothing then reads it, and the run silently has no money on it.
        //
        // Quietly otherwise: the viewer shows the money, and a wall of numbers
        // after every run is how a student learns to stop reading the output.
        int code = exec(argv, json::append, log);
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
     *
     * <p>The name is the <b>qualified</b> one, read off the source's own
     * {@code package} line. Returning the file's simple name is what produced
     * exactly the message above: the manual teaches {@code src/lab/Main.java}
     * with {@code package lab;} at the top of it, and a JVM asked for
     * {@code Main} cannot find {@code lab.Main}.
     *
     * @return the class to start, or null if nothing here has a {@code main}
     */
    private String mainClassOf(List<Path> sources) {
        for (Path p : sources) {
            try {
                String source = Files.readString(p);
                if (!source.contains("static void main(")) continue;
                String simple = p.getFileName().toString().replaceAll("\\.java$", "");
                String pkg = packageOf(source);
                return pkg.isEmpty() ? simple : pkg + "." + simple;
            } catch (IOException ignored) { /* unreadable source is javac's to report */ }
        }
        return null;
    }

    /** What a source says it is in, or "" for the default package. */
    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;");

    private static String packageOf(String source) {
        var m = PACKAGE.matcher(source);
        return m.find() ? m.group(1).replaceAll("\\s+", "") : "";
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
    /**
     * Run something and put everything it says in one place.
     *
     * <p>Merged on purpose: javac's errors and its progress belong in the order
     * they happened, and a student reading two interleaved streams as one is
     * reading what actually occurred.
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

    /**
     * The same, with the two streams kept apart.
     *
     * <p>For the one caller whose stdout is a file rather than something to read:
     * a command that writes JSON writes it on stdout and says everything else on
     * stderr, and merging them puts a sentence inside the JSON.
     */
    private int exec(List<String> argv, Consumer<String> out, Consumer<String> err)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).directory(root.toFile()).start();
        Thread aside = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) err.accept(line + "\n");
            } catch (IOException ignored) { /* the process is gone; so is its stderr */ }
        }, "losim-stderr");
        aside.setDaemon(true);
        aside.start();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) out.accept(line + "\n");
        }
        int code = p.waitFor();
        // Joined, so a warning cannot arrive after the caller has decided the
        // run is over and stopped showing anything.
        aside.join(2000);
        return code;
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
