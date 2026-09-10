package dissaly.cli;

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
 * Project sources, toolchain discovery, compilation, and simulation runs.
 *
 * <p>A lab is a folder of gRPC sources. Running a simulation generates code from
 * its schemas, compiles the sources, and invokes {@link Main}.
 *
 * <p>No manifest is required. Java and protobuf files in the project are part of
 * the next build, except for reserved output and support directories.
 *
 * <p>Each run uses a separate JVM so failures and process state do not leak into
 * another run or into the server.
 */
public final class Lab {

    /** Where the student's own runs land, and where the viewer looks for them. */
    public static final String RESULTS = "build/results";

    private final Path root;
    private final Path runs;

    public Lab(Path root) {
        this(root, root.resolve(RESULTS));
    }

    /**
     * A lab whose results use a custom directory.
     */
    public Lab(Path root, Path runs) {
        this.root = root.toAbsolutePath().normalize();
        this.runs = runs.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    /** Where this lab's runs land — what the picker lists, and what the button writes. */
    public Path runs() { return runs; }

    /**
     * <p>A lab has a generated toolchain classpath with at least one local entry.
     */
    public boolean isLab() {
        String said = declared("classpath");
        return !said.isEmpty() && here(said);
    }

    // ------------------------------------------------------- a declared toolchain

    /**
     * Path to the build-generated toolchain properties.
     *
     * <p>The file contains entries such as:
     *
     * <pre>
     * classpath=/…/dissaly-1.1.0.jar:/…/grpc-api-1.83.1.jar:…
     * protoc=/…/protoc-osx-aarch_64.exe
     * protoc-gen-grpc-java=/…/protoc-gen-grpc-java-osx-aarch_64.exe
     * </pre>
     *
     * <p>A Gradle task can generate it from the resolved runtime classpath:
     *
     * <pre>
     * val dissalyToolchain by tasks.registering {
     *     val cp = configurations.named("runtimeClasspath")
     *     val out = layout.buildDirectory.file("dissaly-toolchain.properties")
     *     inputs.files(cp); outputs.file(out)
     *     doLast { out.get().asFile.writeText("classpath=${cp.get().asPath}\n") }
     * }
     * </pre>
     *
     * <p>Use the configuration rather than {@code sourceSets.main.runtimeClasspath}
     * so generating the file does not depend on compilation.
     *
     * <p>The file belongs under {@code build/} because it contains machine-local
     * absolute paths. The wrapper regenerates it before starting a JVM.
     */
    public static final String TOOLCHAIN = "build/dissaly-toolchain.properties";

    /**
     * Reads one toolchain property, or {@code ""} if it is unavailable.
     */
    private String declared(String key) {
        Path file = root.resolve(TOOLCHAIN);
        if (!Files.isRegularFile(file)) return "";
        var props = new java.util.Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            // An unreadable toolchain cannot identify a lab.
            return "";
        }
        return props.getProperty(key, "").trim();
    }

    /**
     * Returns a declared executable when it exists and is executable.
     */
    private Path tool(String name) {
        String said = declared(name);
        if (said.isEmpty()) return null;
        Path stated = Path.of(said);
        return Files.isExecutable(stated) ? stated : null;
    }

    /**
     * The protobuf schemas and Java sources in this lab.
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
     * Root directories excluded from source discovery.
     *
     * <p>The names apply only at the lab root; nested Java packages may use them.
     */
    private static final List<String> NOT_CODE =
            List.of("build", "docs", "viewer", "node_modules", "presentation",
                    "gen", "out", "classes", "input", "corpus", "simulations");

    /**
     * Output directories created by dissaly.
     */
    private static final List<String> OURS = List.of("build", "gen", "out", "classes", "node_modules");

    /**
     * Reports root directories containing Java that are reserved rather than
     * compiled.
     */
    public List<Path> reserved() {
        var out = new ArrayList<Path>();
        for (Path p : children(root)) {
            if (!Files.isDirectory(p)) continue;
            String name = p.getFileName().toString();
            if (!NOT_CODE.contains(name) || OURS.contains(name)) continue;
            var java = new ArrayList<Path>();
            collect(p, ".java", java, false);
            if (!java.isEmpty()) out.add(p);
        }
        return out;
    }

    /** Describes reserved directories containing Java, or {@code ""}. */
    public String reservedNote() {
        List<Path> held = reserved();
        if (held.isEmpty()) return "";
        var sb = new StringBuilder();
        for (Path p : held) {
            sb.append("Not compiled: ").append(root.relativize(p))
              .append("/ holds Java, but that name is reserved for the lab's own furniture.\n")
              .append("  Move it under src/ if it is code — a package may be called anything there.\n");
        }
        return sb.toString();
    }

    /**
     * Describes unusable machine-local toolchain entries, or {@code ""}.
     */
    public String toolchainNote() {
        if (!Files.isRegularFile(root.resolve(TOOLCHAIN))) return "";
        var sb = new StringBuilder();
        String said = declared("classpath");
        if (!said.isEmpty() && !here(said)) {
            sb.append("The classpath in ").append(TOOLCHAIN)
              .append(" is another machine's: nothing on it exists here.\n");
        }
        for (String key : List.of("protoc", "protoc-gen-grpc-java")) {
            String stated = declared(key);
            if (!stated.isEmpty() && !Files.isExecutable(Path.of(stated))) {
                sb.append("The ").append(key).append(" in ").append(TOOLCHAIN)
                  .append(" will not run here: ").append(stated).append("\n");
            }
        }
        if (!sb.isEmpty()) sb.append("  Run ./dissaly, which rewrites that file before it starts anything.\n");
        return sb.toString();
    }

    /** Where simulations live, and where the console writes a new one. */
    public static final String SIMULATIONS = "simulations";

    /**
     * All compilable sources and schemas in this lab.
     */
    public Code code() {
        if (!isLab()) return new Code(root, List.of(), List.of());
        return new Code(root, walk(root, ".proto"), walk(root, ".java"));
    }

    /**
     * Returns simulation files from {@code simulations/} and the lab root in
     * human-oriented order.
     */
    public List<Path> simulations() {
        List<Path> out = new ArrayList<>();
        Path dir = root.resolve(SIMULATIONS);
        for (Path p : children(dir)) if (yaml(p)) out.add(p);
        for (Path p : children(root)) if (yaml(p)) out.add(p);
        out.sort(Lab::byName);
        return out;
    }

    /**
     * Compares simulation files by stem, then by full filename.
     */
    public static int byName(Path a, Path b) {
        String x = a.getFileName().toString(), y = b.getFileName().toString();
        int by = humanOrder(stem(x), stem(y));
        return by != 0 ? by : humanOrder(x, y);
    }

    /** A file name without its extension — {@code two-machines} of {@code two-machines.yaml}. */
    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * Two names, in the order a reader expects them.
     *
     * <p>Digits are read as the number they spell rather than character by
     * character, so {@code 2-} comes before {@code 10-}; everything else compares
     * as text. Leading zeros do not change a number's place — {@code 02} and
     * {@code 2} are the same position — but they do decide it when the numbers
     * are otherwise equal, so that two names never compare as identical and the
     * sort stays a total order.
     */
    static int humanOrder(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int by = na.length() != nb.length()
                        ? Integer.compare(na.length(), nb.length())
                        : na.compareTo(nb);
                if (by != 0) return by;
                // Use the shorter spelling when numeric values are equal.
                by = Integer.compare(i - si, j - sj);
                if (by != 0) return by;
                continue;
            }
            if (x != y) return Character.compare(x, y);
            i++; j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Names displayed by the simulation picker. */
    public List<String> simulationNames() {
        return simulations().stream().map(p -> p.getFileName().toString()).toList();
    }

    /** Finds a simulation by filename, or returns {@code null}. */
    public Path simulation(String name) {
        if (name == null || name.isBlank()) return simulations().isEmpty() ? null : simulations().get(0);
        for (Path p : simulations()) if (p.getFileName().toString().equals(name)) return p;
        return null;
    }

    private static boolean yaml(Path p) {
        String n = p.getFileName().toString();
        return Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"));
    }

    /**
     * Whether source discovery should enter a directory.
     *
     * <p>Reserved names are excluded only at the lab root. Dot-directories are
     * excluded at every depth.
     */
    private static boolean opaque(Path dir, boolean root) {
        String name = dir.getFileName().toString();
        return name.startsWith(".") || (root && NOT_CODE.contains(name));
    }

    /** Finds every file with one extension, excluding reserved directories. */
    private List<Path> walk(Path dir, String ext) { return walk(dir, ext, true); }

    /**
     * @param furniture whether this directory's own children may be the lab's
     *                  furniture. True of the lab root, and false of {@code gen/},
     *                  whose children are packages {@code protoc} named from a
     *                  {@code java_package} — so a schema declaring itself in
     *                  {@code docs} or {@code input} compiles like any other.
     */
    private List<Path> walk(Path dir, String ext, boolean furniture) {
        List<Path> out = new ArrayList<>();
        collect(dir, ext, out, furniture);
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private void collect(Path here, String ext, List<Path> out, boolean root) {
        for (Path p : children(here)) {
            if (Files.isDirectory(p)) {
                if (!opaque(p, root)) collect(p, ext, out, false);
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

    // ------------------------------------------------------------------ running

    /** Generates and compiles the lab sources. */
    public Path compile(Consumer<String> log) throws IOException, InterruptedException {
        Code c = code();
        String reserved = reservedNote();
        if (!reserved.isEmpty()) log.accept(reserved);
        String ignored = toolchainNote();
        if (!ignored.isEmpty()) log.accept(ignored);
        if (!c.started()) {
            log.accept("There is no code in this lab yet.\n");
            return null;
        }
        String missing = c.protos().isEmpty() ? null : noProtoc();
        if (missing != null) { log.accept(missing); return null; }
        Path gen = root.resolve("gen");
        Path classes = classes();
        wipe(gen);
        wipe(classes);
        Files.createDirectories(gen);
        Files.createDirectories(classes);
        if (!c.protos().isEmpty() && generate(c.protos(), gen, log) != 0) return null;
        return compile(c.sources(), gen, classes, log) == 0 ? classes : null;
    }

    /** Returns the directory used for compiled lab classes. */
    public Path classes() {
        return root.resolve("build").resolve("dissaly").resolve("classes");
    }

    /**
     * Returns the compiled classes directory, or {@code ""} if it is absent.
     */
    public String classesIfBuilt() {
        Path classes = classes();
        return Files.isDirectory(classes) ? classes.toString() : "";
    }

    /**
     * Whether compiled classes are at least as new as the source files.
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
     * Returns the result path for a simulation, or {@code null} if it is unknown.
     */
    public Path trace(String simulation) {
        Path chosen = simulation(simulation);
        if (chosen == null) return null;
        String stem = chosen.getFileName().toString().replaceAll("\\.ya?ml$", "");
        return runs.resolve(stem + ".json");
    }

    public int run(String simulation, Consumer<String> log) throws IOException, InterruptedException {
        Code c = code();
        String reserved = reservedNote();
        if (!reserved.isEmpty()) log.accept(reserved);
        String ignored = toolchainNote();
        if (!ignored.isEmpty()) log.accept(ignored);
        if (!c.started()) {
            log.accept("There is no code in this lab yet — that is the exercise.\n");
            return 2;
        }

        // Check the toolchain before deleting generated output.
        String missing = c.protos().isEmpty() ? null : noProtoc();
        if (missing != null) { log.accept(missing); return 1; }

        // Keep generated sources in gen/ so the editor can resolve and inspect
        // the generated types.
        Path gen = root.resolve("gen");
        Path classes = classes();
        wipe(gen);
        wipe(classes);
        Files.createDirectories(gen);
        Files.createDirectories(classes);

        if (!c.protos().isEmpty() && generate(c.protos(), gen, log) != 0) return 1;
        if (compile(c.sources(), gen, classes, log) != 0) return 1;

        Path chosen = simulation(simulation);
        if (chosen == null && simulation != null && !simulation.isBlank()) {
            log.accept("There is no simulation called " + simulation + " in this lab.\n");
            return 2;
        }

        if (chosen == null) {
            // Without a simulation, run the lab's main class directly.
            String main = mainClassOf(c.sources());
            if (main == null) {
                log.accept("Nothing in this lab has a `public static void main`, and there\n"
                        + "is no simulation beside it either — so there is nothing here to start.\n");
                return 2;
            }
            log.accept("\n");
            return exec(List.of(java(), "-cp", cp(classes), main), log);
        }

        Files.createDirectories(runs);
        Path trace = trace(simulation);
        log.accept("\n");
        // The caller owns the viewer for this run.
        int code = exec(List.of(java(), "-cp", cp(), "dissaly.cli.Main", "simulate", "--no-view",
                chosen.toString(), "--cp", classes.toString(), "--out", trace.toString()), log);

        // Write billing data alongside a completed trace.
        if (Files.exists(trace)) bill(trace, log);
        return code;
    }

    /**
     * Returns a toolchain diagnostic, or {@code null} when both protobuf tools
     * are available.
     */
    private String noProtoc() {
        if (tool("protoc") != null && tool("protoc-gen-grpc-java") != null) return null;
        return """
                No protobuf compiler here. The build fetches one and writes where it \
                is into %s, so either that task has not run, or the file it wrote \
                came from a machine this is not.

                  Run ./dissaly, which runs the task first.
                """.formatted(TOOLCHAIN);
    }

    /**
     * Extracts dissaly's bundled schema to a regular file for protoc.
     *
     * @return the include root, or null in a jar built without the schema
     */
    private Path ownSchema() throws IOException {
        String at = "dissaly/job.proto";
        Path root = this.root.resolve("build/dissaly/proto");
        Path out = root.resolve(at);
        try (var in = Lab.class.getResourceAsStream("/dissaly/proto/" + at)) {
            if (in == null) return null;
            Files.createDirectories(out.getParent());
            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return root;
    }

    private int generate(List<Path> protos, Path gen, Consumer<String> log) throws IOException, InterruptedException {
        Path protoc = tool("protoc");
        Path plugin = tool("protoc-gen-grpc-java");
        String missing = noProtoc();
        if (missing != null) {
            log.accept(missing);
            return 1;
        }
        List<String> argv = new ArrayList<>(List.of(protoc.toString(),
                "--plugin=protoc-gen-grpc-java=" + plugin,
                "--java_out=" + gen, "--grpc-java_out=" + gen));
        // Each schema directory is an include path for local imports.
        for (Path p : protos) { argv.add("-I"); argv.add(p.getParent().toString()); }
        // Add the bundled schema as an include path, but do not regenerate its
        // classes; the project already supplies them.
        Path own = ownSchema();
        if (own != null) { argv.add("-I"); argv.add(own.toString()); }
        for (Path p : protos) argv.add(p.toString());
        log.accept("Reading the schema…\n");
        return exec(argv, log);
    }

    private int compile(List<Path> own, Path gen, Path classes, Consumer<String> log)
            throws IOException, InterruptedException {
        List<Path> sources = new ArrayList<>(walk(gen, ".java", false));
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
                "dissaly.cli.Main", "bill", trace.toString(), "--json"));
        // Prefer a project-local price list when one exists.
        Path prices = root.resolve("prices/eu-central-1.yaml");
        if (Files.exists(prices)) { argv.add("--prices"); argv.add(prices.toString()); }
        // Keep JSON stdout separate from diagnostic stderr.
        int code = exec(argv, json::append, log);
        if (code == 0 && !json.isEmpty()) Files.writeString(out, json.toString());
        else log.accept("(no bill for this run)\n");
    }

    /**
     * Finds the qualified class with a {@code main} method when no simulation is
     * configured.
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

    /**
     * Returns the build-resolved classpath when it is usable here.
     */
    public String cp() {
        String said = declared("classpath");
        return here(said) ? said : "";
    }

    /**
     * Whether at least one declared classpath entry exists locally.
     */
    private static boolean here(String classpath) {
        if (classpath.isEmpty()) return false;
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank() && Files.exists(Path.of(entry))) return true;
        }
        return false;
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
     * Runs a process and merges stdout and stderr into {@code log}.
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
     * Runs a process with separate stdout and stderr consumers.
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
        }, "dissaly-stderr");
        aside.setDaemon(true);
        aside.start();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) out.accept(line + "\n");
        }
        int code = p.waitFor();
        // Allow stderr to drain before returning to the caller.
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
