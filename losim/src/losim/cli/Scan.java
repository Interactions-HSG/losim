package losim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a gRPC project looks like from the outside, read as text.
 *
 * <p>Written for two callers that must never disagree: {@link Adopt}, which says
 * what is left to do, and {@code losim check}, which says whether it still is. One
 * detector, so "what is left" is a command rather than a memory of a terminal that
 * has scrolled away.
 *
 * <p><b>Text, never bytecode.</b> {@code losim adopt} runs from
 * {@code java -jar losim.jar} with nothing else on the classpath — that is the
 * bootstrap, before the project has a build that knows about losim — so nothing
 * here loads a class, resolves a type or asks a compiler. It reads {@code .proto},
 * {@code .java} and the build file the way a person skimming them would, and it is
 * wrong in the direction of saying too much rather than too little.
 *
 * <h2>Three classes of finding, and the third is the point</h2>
 *
 * <table>
 *   <tr><th>Refused</th><td>the loader, with a line number</td><td>it will not run</td></tr>
 *   <tr><th>Untrustworthy</th><td>the verifier, per machine, in the trace</td>
 *       <td>it runs, and a number is wrong</td></tr>
 *   <tr><th>Dead</th><td>here</td><td>it runs, does nothing, and nothing else will
 *       ever tell you</td></tr>
 * </table>
 *
 * <p>The third list exists because the quickstart is mostly <i>not</i> gRPC: a
 * {@code main} with argv parsing, a shutdown hook, credentials, a logger,
 * {@code awaitTermination}, the {@code application} plugin. Almost none of that is
 * visible to the verifier, and that is correct rather than a gap —
 * {@link losim.verify.Rule}'s admission test is that a rule names something
 * yielding <b>a wrong number rather than a broken run</b>. Dead scaffolding yields
 * neither, and adding rules for it would dilute the one list that means something.
 */
public final class Scan {

    private final Path root;
    private final List<Service> services = new ArrayList<>();
    private final List<Rpc> rpcs = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();
    private final List<Path> protos = new ArrayList<>();
    private final List<Path> sources = new ArrayList<>();
    private String build = "";
    private String buildFile = "";

    private Scan(Path root) { this.root = root; }

    /**
     * One service implementation, and where it is.
     *
     * @param nested whether it is declared inside another class rather than at the
     *               top level — the quickstart's is, which is the single most
     *               consequential thing about that file
     * @param line   the line its declaration is on, 1-based
     */
    public record Service(String name, String pkg, Path file, int line, boolean nested,
                          String base) {

        /** What {@code runs:} has to say, which is a class and not a file. */
        public String qualified() { return pkg.isEmpty() ? name : pkg + "." + name; }
    }

    /** One rpc from the schema, and what the {@code .proto} said about it. */
    public record Rpc(String service, String name, boolean streaming, boolean idempotent,
                      Path file, int line) {}

    /** Where something is, so a report can be read with an editor open beside it. */
    public record At(Path file, int line) {}

    /**
     * One thing to do, or one thing that will quietly do nothing.
     *
     * @param kind  what the reader is being told; see {@link Kind}
     * @param what  a short line, the finding itself
     * @param why   what happens if it is left alone
     * @param where the file and line, or null for a finding about the project
     */
    public record Finding(Kind kind, String what, String why, At where) {}

    /**
     * The three classes, plus the one that is neither a fix nor a refusal.
     *
     * <p>{@link #DEAD} is the class no other part of losim will ever mention: a
     * shutdown hook that never fires is not a wrong number, so the verifier is
     * right to be silent about it, and the silence is exactly why it is here.
     */
    public enum Kind {
        /** The run will not start until this is done. */
        REFUSED,
        /** It runs, and a figure it produces is not what it says it is. */
        UNTRUSTWORTHY,
        /** It runs, does nothing, and nothing else will tell you. */
        DEAD,
        /** Not wrong, but the run will be less than it could be. */
        MISSING,
    }

    // ------------------------------------------------------------------- reading

    public static Scan of(Path root) throws IOException {
        Scan s = new Scan(root.toAbsolutePath().normalize());
        s.read();
        s.scaled();
        s.judge();
        return s;
    }

    public List<Service> services()  { return List.copyOf(services); }
    public List<Rpc> rpcs()          { return List.copyOf(rpcs); }
    public List<Finding> findings()  { return List.copyOf(findings); }
    public List<Path> protos()       { return List.copyOf(protos); }
    public List<Path> sources()      { return List.copyOf(sources); }
    public String buildFile()        { return buildFile; }

    /** The build file's own text, for a question nothing here thought to ask. */
    public String buildFileText()    { return build; }

    /** Whatever the build file says its gRPC version is, or "". */
    public String grpcVersion()      { return version("io\\.grpc[:\"]", "grpcVersion"); }

    /** Whatever the build file says its protobuf version is, or "". */
    public String protobufVersion()  { return version("com\\.google\\.protobuf[:\"]", "protobufVersion"); }

    private void read() throws IOException {
        for (String name : List.of("build.gradle.kts", "build.gradle", "pom.xml")) {
            Path p = root.resolve(name);
            if (!Files.isRegularFile(p)) continue;
            buildFile = name;
            build = Files.readString(p);
            break;
        }
        walk(".proto", protos);
        walk(".java", sources);
        walk(".yaml", scenarios);
        walk(".yml", scenarios);
        for (Path p : protos) proto(p);
        for (Path p : sources) java(p);
    }

    /**
     * Every file of one kind, skipping what is output rather than input.
     *
     * <p>{@code build/} and {@code gen/} hold generated Java by the thousand, and a
     * report that named a protoc-generated {@code ImplBase} as something the reader
     * should go and edit would be worse than no report.
     */
    private void walk(String extension, List<Path> into) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().endsWith(extension))
             .filter(p -> {
                 Path rel = root.relativize(p);
                 for (Path part : rel) {
                     String name = part.toString();
                     if (name.startsWith(".") || name.equals("build") || name.equals("gen")
                             || name.equals("out") || name.equals("target")
                             || name.equals("node_modules")) return false;
                 }
                 return true;
             })
             .sorted()
             .forEach(into::add);
        }
    }

    // --------------------------------------------------------------------- proto

    private static final Pattern SERVICE = Pattern.compile("(?m)^\\s*service\\s+(\\w+)\\s*\\{");
    private static final Pattern RPC = Pattern.compile(
            "(?m)^\\s*rpc\\s+(\\w+)\\s*\\(\\s*(stream\\s+)?[\\w.]+\\s*\\)\\s*"
            + "returns\\s*\\(\\s*(stream\\s+)?[\\w.]+\\s*\\)\\s*([{;])");
    private static final Pattern IDEMPOTENT = Pattern.compile("idempotency_level\\s*=\\s*(\\w+)");

    private void proto(Path file) throws IOException {
        String text = Files.readString(file);
        var services = new ArrayList<int[]>();      // {start, end} of each service body
        var names = new ArrayList<String>();
        Matcher m = SERVICE.matcher(text);
        while (m.find()) {
            int body = closing(text, m.end() - 1);
            services.add(new int[]{m.end(), body});
            names.add(m.group(1));
        }
        Matcher r = RPC.matcher(text);
        while (r.find()) {
            String service = "";
            for (int i = 0; i < services.size(); i++) {
                if (r.start() > services.get(i)[0] && r.start() < services.get(i)[1]) {
                    service = names.get(i);
                }
            }
            // The options block, if the rpc has one: `{ option … }` rather than `;`.
            String options = "";
            if (r.group(4).equals("{")) {
                int end = closing(text, r.end() - 1);
                options = text.substring(r.end(), Math.max(r.end(), end));
            }
            boolean idempotent = false;
            Matcher i = IDEMPOTENT.matcher(options);
            if (i.find()) idempotent = !i.group(1).equals("IDEMPOTENCY_UNKNOWN");
            rpcs.add(new Rpc(service, r.group(1),
                    r.group(2) != null || r.group(3) != null, idempotent,
                    file, lineOf(text, r.start())));
        }
    }

    // ---------------------------------------------------------------------- java

    private static final Pattern IMPL_BASE = Pattern.compile(
            "(?m)^(\\s*)(?:public\\s+|final\\s+|static\\s+|abstract\\s+)*class\\s+(\\w+)\\s+"
            + "extends\\s+([\\w.]*?(\\w+)Grpc\\.\\4ImplBase)\\b");
    private static final Pattern BINDABLE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|final\\s+|static\\s+|abstract\\s+)*class\\s+(\\w+)[^{]*"
            + "implements\\s+[^{]*\\bBindableService\\b");

    /** What a machine has none of, and what it costs to leave in. */
    private static final List<String[]> DEAD_GIVEAWAYS = List.of(
            new String[]{"addShutdownHook", "a machine is killed by a fault, not by SIGTERM,"
                    + " so the hook never fires"},
            new String[]{"awaitTermination", "the cluster owns every lifecycle; nothing here"
                    + " waits for a server to end"},
            new String[]{"InsecureServerCredentials", "there is no transport to secure —"
                    + " a call never leaves the JVM"},
            new String[]{"InsecureChannelCredentials", "there is no transport to secure —"
                    + " a call never leaves the JVM"},
            new String[]{"newFixedThreadPool", "the pool is the machine, sized by the"
                    + " instance type in the scenario"},
            new String[]{"System.out.print", "it goes to the run's own stdout, attributed to"
                    + " no machine and absent from the trace and the film."
                    + " Losim.current().log(…) puts it in that machine's event channel"},
            new String[]{"System.err.print", "it goes to the run's own stdout, attributed to"
                    + " no machine and absent from the trace and the film."
                    + " Losim.current().log(…) puts it in that machine's event channel"},
            new String[]{"Logger.getLogger", "a logger writes to the run's own stdout,"
                    + " attributed to no machine and absent from the trace and the film."
                    + " Losim.current().log(…) puts it in that machine's event channel"});

    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$.]*)\\s*;");

    /** Classes that implement {@code Job} and not {@code Scalable}, by simple name. */
    private final Set<String> plainJobs = new LinkedHashSet<>();

    private final List<Path> scenarios = new ArrayList<>();

    private void java(Path file) throws IOException {
        String text = Files.readString(file);
        Matcher pkg = PACKAGE.matcher(text);
        String in = pkg.find() ? pkg.group(1) : "";

        Matcher m = IMPL_BASE.matcher(text);
        while (m.find()) {
            services.add(new Service(m.group(2), in, file, lineOf(text, m.start()),
                    !m.group(1).isEmpty(), m.group(3)));
        }
        Matcher j = PLAIN_JOB.matcher(text);
        while (j.find()) if (!text.contains("Scalable")) plainJobs.add(j.group(1));

        Matcher b = BINDABLE.matcher(text);
        while (b.find()) {
            boolean alsoImplBase = services.stream()
                    .anyMatch(s -> s.file().equals(file) && s.name().equals(b.group(1)));
            if (alsoImplBase) continue;
            findings.add(new Finding(Kind.REFUSED, b.group(1)
                    + " implements BindableService without extending a protoc ImplBase",
                    "runs: names a class, and losim reads what a class serves off the"
                    + " generated base it extends. A service built by hand has no such"
                    + " base, so nothing can say what it offers or what its rpcs cost.",
                    new At(file, lineOf(text, b.start()))));
        }

        if (text.contains("static void main(")) {
            findings.add(new Finding(Kind.DEAD, "main(String[]) and its arguments",
                    "a machine has no command line. Worse than dead: with no scenario"
                    + " named, losim runs the first class it finds with a main — so"
                    + " pressing the arrow could start this one, and it would try to bind"
                    + " a port inside a lab.",
                    new At(file, lineOf(text, text.indexOf("static void main(")))));
        }
        for (String pattern : List.of("newServerBuilderForPort", "ServerBuilder.forPort",
                                      "Grpc.newServerBuilderForPort")) {
            int at = text.indexOf(pattern);
            if (at < 0) continue;
            findings.add(new Finding(Kind.DEAD, "it builds its own server",
                    "the scenario places services on machines, and the cluster builds every"
                    + " server. A server built here is never intercepted, so nothing it"
                    + " answers is timed, priced or drawn.",
                    new At(file, lineOf(text, at))));
            break;
        }
        for (String pattern : List.of("ManagedChannelBuilder", "Grpc.newChannelBuilder",
                                      "newChannelBuilderForAddress")) {
            int at = text.indexOf(pattern);
            if (at < 0) continue;
            findings.add(new Finding(Kind.UNTRUSTWORTHY, "it builds its own channel",
                    "a peer is found by what it serves, never by a host and a port:"
                    + " cluster.channelTo(peer). A channel built here carries no"
                    + " interceptor, so its calls are absent from the wire, the bill and"
                    + " the film — and the verifier marks the machine for it.",
                    new At(file, lineOf(text, at))));
            break;
        }
        // The 2.0.0 break, caught before the compiler catches it — and with better
        // words, because javac will only say the method does not exist.
        for (String gone : List.of("cluster.records()", "cluster.units()")) {
            int at = text.indexOf(gone);
            if (at < 0) continue;
            findings.add(new Finding(Kind.REFUSED, gone + " no longer exists",
                    "a job that has a size implements losim.api.Scalable: it declares what"
                    + " its input is made of, and is handed it. The number is then in the"
                    + " scenario's input: block, where a sweep can vary it — read it with"
                    + " at.count(\"...\"). AGENTS.md has the conversion.",
                    new At(file, lineOf(text, at))));
            break;
        }
        for (String[] dead : DEAD_GIVEAWAYS) {
            int at = text.indexOf(dead[0]);
            if (at < 0) continue;
            findings.add(new Finding(Kind.DEAD, dead[0], dead[1],
                    new At(file, lineOf(text, at))));
        }
    }

    private static final Pattern PLAIN_JOB = Pattern.compile(
            "class\\s+(\\w+)[^{]*\\bimplements\\b[^{]*\\bJob\\b");

    private static final Pattern SCENARIO_JOB = Pattern.compile("(?m)^job:\\s*(\\S+)");
    private static final Pattern SCENARIO_SCALE = Pattern.compile("(?m)^scale:\\s*([0-9.]+)");

    /**
     * A scenario asking for a model, driven by a job that cannot be asked for more.
     *
     * <p>Not wrong — it is refused at the run, loudly and with the line. It is here
     * because {@code losim check} answers before the first build, and this is the
     * one thing a lab mid-migration will hit that costs it every scaled run it has.
     */
    private void scaled() throws IOException {
        for (Path file : scenarios) {
            String text = Files.readString(file);
            Matcher job = SCENARIO_JOB.matcher(text);
            Matcher scale = SCENARIO_SCALE.matcher(text);
            if (!job.find() || !scale.find()) continue;
            if (Double.parseDouble(scale.group(1)) <= 1) continue;
            String named = job.group(1);
            String bare = named.substring(named.lastIndexOf('.') + 1);
            if (!plainJobs.contains(bare)) continue;
            findings.add(new Finding(Kind.MISSING, bare + " cannot be run at another size",
                    "this scenario is a model of " + scale.group(1) + " times the run, and a"
                    + " plain losim.api.Job has no way of being asked to do more — its size"
                    + " is a constant in its own Java. Implement losim.api.Scalable and put"
                    + " the sizes in an input: block. AGENTS.md has the conversion.",
                    new At(file, lineOf(text, job.start()))));
        }
    }

    // -------------------------------------------------------------------- judging

    private void judge() {
        if (buildFile.equals("pom.xml")) {
            findings.add(new Finding(Kind.REFUSED, "this is a Maven project",
                    "losim writes a Gradle build. Convert it, or add the dependency and"
                    + " the toolchain task by hand — the manual has both.", null));
        }
        if (build.contains("grpc-netty")) {
            findings.add(new Finding(Kind.DEAD, "grpc-netty-shaded",
                    "there is no socket transport in a simulated network, and nothing on"
                    + " the lab classpath provides one. It is not carried over.", null));
        }
        if (build.contains("application") && build.contains("mainClass")) {
            findings.add(new Finding(Kind.DEAD, "the application plugin",
                    "the project stops being an application: nothing here has a main to"
                    + " start, and `losim run` is what runs it.", null));
        }
        for (Rpc r : rpcs) {
            if (r.streaming()) {
                findings.add(new Finding(Kind.REFUSED, r.service() + "." + r.name()
                        + " is a streaming rpc",
                        "losim prices a call as one request and one response: a per-unit"
                        + " cost would be slept once per message, and the fixed cost paid"
                        + " when the client stopped sending rather than before the handler"
                        + " ran. The numbers would come out consistent and wrong. Make it"
                        + " unary.", new At(r.file(), r.line())));
            }
        }
        for (Service s : services) {
            if (!s.nested()) continue;
            findings.add(new Finding(Kind.UNTRUSTWORTHY, s.name()
                    + " is nested inside another class",
                    "the trust verifier walks a nested class together with the class"
                    + " enclosing it, so everything the enclosing bootstrap does — its own"
                    + " server, its channel, its statics — is read as this service's."
                    + " Move it to a file of its own.",
                    new At(s.file(), s.line())));
        }
        boolean anyIdempotent = rpcs.stream().anyMatch(Rpc::idempotent);
        if (!rpcs.isEmpty() && !anyIdempotent) {
            Rpc first = rpcs.get(0);
            findings.add(new Finding(Kind.MISSING, "no rpc declares an idempotency_level",
                    "no retry policy can attach to one that does not:"
                    + " `option idempotency_level = IDEMPOTENT;` says running it twice is"
                    + " safe, and a scenario cannot retry a call the schema will not"
                    + " vouch for.", new At(first.file(), first.line())));
        }
    }

    // -------------------------------------------------------------------- helpers

    /** The classes this project offers, in the order a scenario would name them. */
    public List<String> placeable() {
        var out = new LinkedHashSet<String>();
        // Qualified, because `runs:` names a class on a classpath rather than a
        // file: `runs: [GreeterImpl]` finds nothing when the class is in a package.
        for (Service s : services) out.add(s.qualified());
        return List.copyOf(out);
    }

    /** Every finding of one class, in the order they were found. */
    public List<Finding> of(Kind kind) {
        return findings.stream().filter(f -> f.kind() == kind).toList();
    }

    /**
     * What the build file says a version is, read two ways.
     *
     * <p>The literal coordinate first — {@code io.grpc:grpc-stub:1.83.1} — and then
     * the variable almost every real build uses instead, {@code def grpcVersion =
     * '1.83.1'}. Without the second, the quickstart's own build reports no version
     * at all, and the adopted project would be silently pinned to losim's defaults
     * rather than to what the project was built against.
     */
    private String version(String coordinate, String variable) {
        Matcher m = Pattern.compile(coordinate + "[^\"']*[:\"']([0-9][\\w.\\-]*)").matcher(build);
        if (m.find()) return m.group(1);
        Matcher v = Pattern.compile("\\b" + variable + "\\s*=\\s*['\"]([0-9][\\w.\\-]*)").matcher(build);
        return v.find() ? v.group(1) : "";
    }

    /** Where the brace opened at {@code from} closes, or the end of the text. */
    private static int closing(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return text.length();
    }

    private static int lineOf(String text, int at) {
        int line = 1;
        for (int i = 0; i < at && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
