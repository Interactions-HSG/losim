package losim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** Every service the schema declares, by the name gRPC puts on the wire. */
    private final List<String> declared = new ArrayList<>();
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
                          String base, boolean entry) {

        /**
         * The name this class is loaded under — the same one the loader derives
         * from the path a {@code runs:} entry names, so the two can be compared.
         */
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
        walk(".yaml", simulations);
        walk(".yml", simulations);
        for (Path p : protos) proto(p);
        for (Path p : sources) java(p);
        for (Path p : simulations) yaml(p);
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

    private static final Pattern PROTO_PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern SERVICE = Pattern.compile("(?m)^\\s*service\\s+(\\w+)\\s*\\{");
    private static final Pattern RPC = Pattern.compile(
            "(?m)^\\s*rpc\\s+(\\w+)\\s*\\(\\s*(stream\\s+)?[\\w.]+\\s*\\)\\s*"
            + "returns\\s*\\(\\s*(stream\\s+)?[\\w.]+\\s*\\)\\s*([{;])");
    private static final Pattern IDEMPOTENT = Pattern.compile("idempotency_level\\s*=\\s*(\\w+)");

    private void proto(Path file) throws IOException {
        String text = Files.readString(file);
        // The schema's own package, so a service is recorded under the name gRPC
        // puts on the wire. A simulation may file it under either that or its last
        // segment, and a scan that knew only one of the two would quietly check
        // nothing for whoever wrote the other.
        Matcher p = PROTO_PACKAGE.matcher(text);
        String in = p.find() ? p.group(1) + "." : "";
        var services = new ArrayList<int[]>();      // {start, end} of each service body
        var names = new ArrayList<String>();
        Matcher m = SERVICE.matcher(text);
        while (m.find()) {
            int body = closing(text, m.end() - 1);
            services.add(new int[]{m.end(), body});
            names.add(in + m.group(1));
            declared.add(in + m.group(1));
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
                    + " instance type in the simulation"},
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

    private final List<Path> simulations = new ArrayList<>();

    private void java(Path file) throws IOException {
        String text = Files.readString(file);
        Matcher pkg = PACKAGE.matcher(text);
        String in = pkg.find() ? pkg.group(1) : "";

        Matcher m = IMPL_BASE.matcher(text);
        while (m.find()) {
            // losim.Job is the one service losim ships, so a class extending its
            // ImplBase is where a simulation starts. Distinguished from an
            // assignment's own service called Job by the package it comes from,
            // which is the only thing text can go on.
            boolean entry = m.group(4).equals("Job")
                    && (m.group(3).startsWith("losim.pb.") || text.contains("losim.pb"));
            services.add(new Service(m.group(2), in, file, lineOf(text, m.start()),
                    !m.group(1).isEmpty(), m.group(3), entry));
        }
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
                    "a machine has no command line. Worse than dead: with no simulation"
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
                    "the simulation places services on machines, and the cluster builds every"
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
        // The 3.0.0 break, caught before the compiler catches it — and with better
        // words, because javac will only say the type does not exist. Every one of
        // these is the same mistake: the thing that starts the work used to be a
        // Java object losim constructed, and is now a service a node runs.
        for (String[] gone : GONE_FROM_JAVA) {
            Matcher g = Pattern.compile(gone[0]).matcher(text);
            if (!g.find()) continue;
            findings.add(new Finding(Kind.REFUSED, gone[1], gone[2],
                    new At(file, lineOf(text, g.start()))));
        }
        for (String[] dead : DEAD_GIVEAWAYS) {
            int at = text.indexOf(dead[0]);
            if (at < 0) continue;
            findings.add(new Finding(Kind.DEAD, dead[0], dead[1],
                    new At(file, lineOf(text, at))));
        }
    }

    /**
     * What a 2.x assignment says, what to call it now, and why.
     *
     * <p>Matched as text, so a project that has not been compiled since the break
     * is told what to do rather than handed a page of javac. Each is a refusal
     * because none of these types exists: the run does not start.
     */
    private static final List<String[]> GONE_FROM_JAVA = List.of(
            new String[]{"\\bimplements\\s+[^{]*\\bScalable\\b",
                    "it implements losim.api.Scalable, which no longer exists",
                    "what starts the work is a service now: extend"
                    + " losim.pb.JobGrpc.JobImplBase and put what built the input in"
                    + " Load, which runs off the clock. The sizes move to the"
                    + " simulation's input: block. AGENTS.md has the conversion."},
            new String[]{"\\bimplements\\s+[^{]*\\bJob\\b",
                    "it implements losim.api.Job, which no longer exists",
                    "what starts the work is a service now: extend"
                    + " losim.pb.JobGrpc.JobImplBase, and a node runs it the way it runs"
                    + " any other service. AGENTS.md has the conversion."},
            new String[]{"\\bCluster\\b",
                    "it uses losim.api.Cluster, which no longer exists",
                    "everything Cluster offered is on Losim.current(): peersServing,"
                    + " channelTo, node, seed, log. A Job is handed nothing, because it"
                    + " is a handler like every other handler."},
            new String[]{"\\bInput\\.Shape\\b|\\bInput\\.of\\(",
                    "it declares an input shape, which no longer exists",
                    "the simulation's input: block says source, unit and count, and"
                    + " losim.Job's Load is handed all three already shrunk to the size"
                    + " this run is doing."});

    // ---------------------------------------------------------------------- yaml

    /**
     * One {@code key:} in a simulation, and the keys it is written inside.
     *
     * @param path  the enclosing keys, outermost first — {@code [nodes, w9, runs]}
     * @param value what followed the colon on the same line, or "" for a block
     */
    private record Key(List<String> path, String name, String value, int line) {}

    /**
     * Every key in a YAML file, by indentation, with what it is written inside.
     *
     * <p>Not a YAML parser and not trying to be. It reads what indentation says,
     * which is all these detectors ask: whether a key is at the top level, which
     * service a {@code failures:} block belongs to, and whether a {@code runs:} was
     * written as a list. Anything it misreads it misreads into saying nothing,
     * because a path it could not follow matches no rule below.
     */
    private static final Pattern YAML_KEY =
            Pattern.compile("^(\\s*)([A-Za-z_][\\w.\\-]*)\\s*:\\s*(.*?)\\s*$");
    private static final Pattern YAML_ITEM = Pattern.compile("^(\\s*)-\\s*(.*?)\\s*$");

    private static List<Key> keys(String text) {
        var out = new ArrayList<Key>();
        var stack = new ArrayList<int[]>();          // indent of each open key
        var names = new ArrayList<String>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String bare = line.strip();
            if (bare.isEmpty() || bare.startsWith("#")) continue;

            Matcher item = YAML_ITEM.matcher(line);
            if (item.matches()) {
                // A list item under whatever key is open. Recorded as a key named
                // "-" so a rule can ask whether a block is a list without having to
                // look at the raw lines again.
                int indent = item.group(1).length();
                while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) {
                    stack.remove(stack.size() - 1);
                    names.remove(names.size() - 1);
                }
                out.add(new Key(List.copyOf(names), "-", item.group(2), i + 1));
                continue;
            }

            Matcher m = YAML_KEY.matcher(line);
            if (!m.matches()) continue;
            int indent = m.group(1).length();
            while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) {
                stack.remove(stack.size() - 1);
                names.remove(names.size() - 1);
            }
            out.add(new Key(List.copyOf(names), m.group(2), m.group(3), i + 1));
            stack.add(new int[]{indent});
            names.add(m.group(2));
        }
        return out;
    }

    /**
     * Top-level keys 3.0 deleted, and what to write instead.
     *
     * <p>Refusals rather than warnings because the loader refuses them too: it
     * allows six keys and names the line of any seventh. This says the same thing
     * before the first build, which is where somebody converting a lab is standing.
     */
    private static final Map<String, String> GONE_FROM_YAML = Map.of(
            "job", "A node runs it: `runs: { losim.Job: src/YourJob.java }`. Which node"
                 + " the work starts on is the system's own arrangement, not a key that"
                 + " selects among several.",
            "start", "A node runs it: `runs: { losim.Job: src/YourJob.java }`.",
            "mode", "There is nothing left to select. `scale: 1` is a direct simulation,"
                  + " and anything above it is a model of one.",
            "machines", "It is `nodes:`. A machine is what Lecture 1 calls a node.",
            "takes", "It is `simulatedDuration:`, keyed on the .java file rather than the"
                   + " class, with fixed: and perUnit: carrying their units in the value.",
            "chaos", "It is `failures:`, written inside the node it happens to, with per:"
                   + " for a standing rate and at: for one instant.",
            "faults", "It is `failures:`, written inside the node it happens to, with at:"
                    + " for one instant and per: for a standing rate.",
            "tightMargin", "Nothing replaces it. It was a comment the loader read, and a"
                         + " comment is a comment.");

    private void yaml(Path file) throws IOException {
        String text = Files.readString(file);
        List<Key> keys = keys(text);

        for (Key k : keys) {
            if (!k.path().isEmpty()) continue;
            String instead = GONE_FROM_YAML.get(k.name());
            if (instead == null) continue;
            findings.add(new Finding(Kind.REFUSED, k.name() + ": is not read any more",
                    instead, new At(file, k.line())));
        }

        for (Key k : keys) {
            if (k.name().equals("runs")) runs(file, keys, k);
            if (k.name().equals("-") && k.path().size() >= 1
                    && k.path().get(k.path().size() - 1).equals("runs")) {
                findings.add(new Finding(Kind.REFUSED, "runs: is written as a list",
                        "it is a map from the service name a peer is found by to the"
                        + " .java file that implements it —"
                        + " `runs: { Thumbnailer: src/Shrinker.java }`. A list names one"
                        + " thing where two are needed, and neither of them is the one"
                        + " peersServing looks up.", new At(file, k.line())));
            }
            failures(file, keys, k);
        }
    }

    private static final Pattern INLINE = Pattern.compile("([\\w.]+)\\s*:\\s*([^,{}]+)");

    /** Each service a {@code runs:} places, and the file it says implements it. */
    private void runs(Path file, List<Key> keys, Key runs) {
        var placed = new ArrayList<Key>();
        if (runs.value().startsWith("{")) {
            Matcher m = INLINE.matcher(runs.value());
            while (m.find()) placed.add(new Key(List.of(), m.group(1), m.group(2).strip(),
                                               runs.line()));
        } else if (runs.value().startsWith("[")) {
            findings.add(new Finding(Kind.REFUSED, "runs: is written as a list",
                    "it is a map from the service name a peer is found by to the .java"
                    + " file that implements it —"
                    + " `runs: { Thumbnailer: src/Shrinker.java }`.",
                    new At(file, runs.line())));
            return;
        } else {
            for (Key k : keys) if (under(k, runs)) placed.add(k);
        }
        for (Key k : placed) {
            // A list item is not a placement of anything — the list itself is the
            // mistake, and it is refused once rather than once per entry.
            if (k.name().equals("-")) continue;
            // The longhand: `Service:` with `file:` and `failures:` beneath it. The
            // path is what says which, so nothing here has to guess.
            if (k.value().isEmpty()) continue;
            if (k.value().endsWith(".java")) continue;
            findings.add(new Finding(Kind.REFUSED, k.name() + " is placed as "
                    + k.value() + ", which is not a .java file",
                    "runs: names the file that implements the service, relative to the"
                    + " project root — src/Shrinker.java, not a class. losim reads the"
                    + " file's package line to find the class, so one string names one"
                    + " piece of code and there is no second way to spell it.",
                    new At(file, k.line())));
        }
    }

    /**
     * An rpc that a {@code failures:} block names and its service does not serve.
     *
     * <p>The first of three answers to the same question, and the only one that
     * answers before the project compiles. The loader cannot: it never loads a
     * class, so it records the name and defers. {@code Machines} can, and does, by
     * asking the bound server what it actually serves — which is why a typo is
     * caught early here and is impossible to run past there.
     */
    private void failures(Path file, List<Key> keys, Key block) {
        if (!block.name().equals("failures")) return;
        List<String> path = block.path();
        // Rpc level, and only rpc level: [nodes, <node>, runs, <service>, failures].
        // A node's own failures: is a list and its path ends at the node.
        if (path.size() < 2 || !path.get(path.size() - 2).equals("runs")) return;
        String service = path.get(path.size() - 1);
        var served = rpcs.stream().filter(r -> names(r.service(), service))
                .map(Rpc::name).toList();
        if (served.isEmpty()) return;             // no .proto for it here to be sure with
        for (Key k : keys) {
            if (!under(k, block) || k.name().equals("-")) continue;
            if (served.contains(k.name())) continue;
            findings.add(new Finding(Kind.REFUSED, service + " serves no rpc called "
                    + k.name(),
                    "it serves " + String.join(", ", served) + ". A failure that belongs"
                    + " to nothing is a failure that quietly never fires, so it is a"
                    + " refusal rather than a warning.", new At(file, k.line())));
        }
    }

    /**
     * Whether a {@code runs:} key names this service.
     *
     * <p>Either form does. {@code lab.Thumbnailer} is what gRPC puts on the wire
     * and {@code Thumbnailer} is what almost everybody writes, and both reach the
     * same server — so a scan that insisted on one of them would fall silent for
     * everybody who wrote the other, which is worse than not checking at all.
     */
    private static boolean names(String declared, String written) {
        return declared.equals(written)
                || declared.substring(declared.lastIndexOf('.') + 1).equals(written);
    }

    /** Whether {@code k} is written directly inside {@code parent}. */
    private static boolean under(Key k, Key parent) {
        List<String> inside = k.path();
        if (inside.size() != parent.path().size() + 1) return false;
        if (!inside.get(inside.size() - 1).equals(parent.name())) return false;
        return inside.subList(0, parent.path().size()).equals(parent.path());
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
            findings.add(new Finding(Kind.REFUSED, s.name()
                    + " is nested inside another class",
                    "runs: names a file, and the class it reaches is the one that file is"
                    + " named after — so there is no line anybody could write that would"
                    + " place this. Even if there were: the trust verifier walks a nested"
                    + " class together with the class enclosing it, so everything the"
                    + " enclosing bootstrap does — its own server, its channel, its"
                    + " statics — would be read as this service's. Move it to a file of"
                    + " its own.",
                    new At(s.file(), s.line())));
        }
        if (!services.isEmpty() && services.stream().noneMatch(Service::entry)) {
            findings.add(new Finding(Kind.MISSING, "nothing here implements losim.Job",
                    "a simulation starts when losim calls Job.Run on the one node that"
                    + " runs it, so until some class extends losim.pb.JobGrpc.JobImplBase"
                    + " there is nothing to start. Load builds the workload off the"
                    + " clock; Run is the simulation. AGENTS.md has the conversion.",
                    null));
        }
        boolean anyIdempotent = rpcs.stream().anyMatch(Rpc::idempotent);
        if (!rpcs.isEmpty() && !anyIdempotent) {
            Rpc first = rpcs.get(0);
            findings.add(new Finding(Kind.MISSING, "no rpc declares an idempotency_level",
                    "no retry policy can attach to one that does not:"
                    + " `option idempotency_level = IDEMPOTENT;` says running it twice is"
                    + " safe, and a simulation cannot retry a call the schema will not"
                    + " vouch for.", new At(first.file(), first.line())));
        }
    }

    // -------------------------------------------------------------------- helpers

    /**
     * The name a {@code runs:} entry files this service under.
     *
     * <p>The schema's name and not the class's: {@code src/Shrinker.java} is what
     * a node runs, and {@code lab.Thumbnailer} is what it thereby serves and what
     * its peers find it by. Read off the {@code .proto} rather than off the
     * {@code ImplBase} the class extends, because the package a service is
     * announced under is the schema's and a generated class need not share it.
     */
    public String serviceOf(Service s) {
        // losim.Job is the one service no project declares: it is losim's, shipped
        // in losim's own schema, and the scan already knows this class answers to
        // it by the base it extends.
        if (s.entry()) return "losim.Job";
        String bare = s.base().replaceAll("^.*?(\\w+)Grpc\\..*$", "$1");
        // The schema's list rather than the rpcs', so a service that declares no
        // rpc — or one this scan could not read — is still named in full.
        for (String d : declared) if (names(d, bare)) return d;
        return bare;
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
