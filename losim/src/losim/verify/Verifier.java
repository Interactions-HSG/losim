package losim.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The verifier: what a lab's compiled code does that the simulation cannot model.
 *
 * <p><b>It flags; it never refuses.</b> Every rule it knows yields a wrong number
 * rather than a broken run, so the honest response is to run the code anyway and
 * mark what it made untrustworthy — {@code w3 read the real clock; its timeline is
 * not projectable} — rather than to stop. A hard gate would also have to be right
 * about generated code, which is a fight not worth having, and it would teach that
 * losim is a cage rather than an instrument.
 *
 * <p>It walks the lab's own classes and nothing else: from the services a machine
 * serves and the job that drives them, out through everything they reference that is
 * also on the lab's classpath. The JDK is not walked, gRPC is not walked, and
 * generated code is recognised by protoc's own markers and skipped — that last one
 * is precisely what flagging rather than refusing buys, since a gate would need a
 * special case where this needs none.
 */
public final class Verifier {

    /**
     * What the walk found.
     *
     * @param unavailable set when the check could not run at all, which is itself
     *                    something the trace should say rather than silently omit
     */
    public record Report(List<Finding> findings, List<String> walked,
                         List<String> generated, String unavailable) {

        public boolean clean() { return findings.isEmpty(); }
    }

    /**
     * losim's own packages, which are never a lab's code.
     *
     * <p>They would otherwise light up brightly — the clock reads {@code nanoTime} for
     * a living and the dispatcher parks threads — and every one of those findings would
     * be about the simulator rather than about the program it is measuring. Named
     * explicitly rather than by a {@code losim.} prefix, because a lab is free to put
     * its own classes in a package of that name.
     */
    private static final Set<String> OURS = Set.of("losim.api", "losim.cli", "losim.res",
            "losim.runtime", "losim.scale", "losim.scenario", "losim.time", "losim.trace",
            "losim.verify");

    private final List<Path> code;
    private final Set<String> available = new HashSet<>();
    private final Map<String, Bytecode.Clazz> loaded = new HashMap<>();
    private String unavailable;

    private Verifier(List<Path> code) { this.code = List.copyOf(code); }

    /** Indexes what is actually on the lab's classpath, which is what bounds the walk. */
    public static Verifier over(List<Path> code) {
        var v = new Verifier(code);
        for (Path root : code) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String name = root.relativize(p).toString();
                    name = name.substring(0, name.length() - 6)
                               .replace(java.io.File.separatorChar, '.');
                    int dot = name.lastIndexOf('.');
                    if (dot < 0 || !OURS.contains(name.substring(0, dot))) v.available.add(name);
                });
            } catch (IOException e) {
                v.unavailable = "could not read " + root + ": " + e.getMessage();
            }
        }
        return v;
    }

    /**
     * Everything reachable from these classes, and what it trips.
     *
     * @param services the fleet's own service classes, so a static handle on one can
     *                 be reported as what it is — a machine reaching into another —
     *                 rather than as a shared field in general
     */
    public Report from(Collection<String> roots, Set<String> services) {
        return from(roots, services, true);
    }

    /**
     * The same walk, optionally without skipping what protoc wrote.
     *
     * <p>The second form exists to make the skip's worth measurable rather than
     * assumed: generated code trips these rules freely — a {@code *Grpc} class holds
     * six mutable statics — so "generated code is ignored without a special case" is
     * a claim about a report, and a claim about a report has to be checkable.
     */
    public Report from(Collection<String> roots, Set<String> services, boolean skipGenerated) {
        var findings = new LinkedHashSet<Finding>();
        var walked = new TreeSet<String>();
        var generated = new TreeSet<String>();
        var seen = new HashSet<String>();
        var frontier = new LinkedHashSet<String>();
        for (String r : roots) if (available.contains(r)) frontier.add(r);

        while (!frontier.isEmpty()) {
            // A nested class carries no marker of its own, so its enclosing class comes
            // along: protoc stamps the outer one, and that is what says "generated".
            var batch = new LinkedHashSet<String>();
            for (String n : frontier) {
                batch.add(n);
                int i = n.indexOf('$');
                if (i > 0 && available.contains(n.substring(0, i))) batch.add(n.substring(0, i));
            }
            batch.removeAll(seen);
            load(batch);
            frontier.clear();

            for (String name : batch) {
                seen.add(name);
                Bytecode.Clazz c = loaded.get(name);
                if (c == null) continue;
                if (isGenerated(c)) {
                    generated.add(name);
                    if (skipGenerated) continue;
                }
                walked.add(name);
                findings.addAll(check(c, services));
                for (String ref : c.references())
                    if (available.contains(ref) && !seen.contains(ref)) frontier.add(ref);
            }
        }
        var ordered = new ArrayList<>(findings);
        ordered.sort(Comparator.comparing(Finding::owner).thenComparing(Finding::where));
        return new Report(List.copyOf(ordered), List.copyOf(walked), List.copyOf(generated),
                          unavailable);
    }

    private void load(Set<String> names) {
        var missing = new ArrayList<String>();
        for (String n : names) if (!loaded.containsKey(n)) missing.add(n);
        if (missing.isEmpty() || unavailable != null) return;
        try {
            for (Bytecode.Clazz c : Bytecode.disassemble(code, missing)) loaded.put(c.name(), c);
        } catch (Bytecode.Unavailable e) {
            unavailable = e.getMessage();
        } catch (IOException | InterruptedException e) {
            unavailable = "the disassembler failed: " + e;
        }
    }

    private boolean isGenerated(Bytecode.Clazz c) {
        if (c.generated()) return true;
        Bytecode.Clazz outer = loaded.get(c.outer());
        return outer != null && outer != c && outer.generated();
    }

    // ------------------------------------------------------------ what it looks at

    private List<Finding> check(Bytecode.Clazz c, Set<String> services) {
        var out = new ArrayList<Finding>();

        for (Bytecode.Method m : c.methods())
            for (Bytecode.Insn i : m.insns()) {
                if (!i.isCall()) continue;
                Rule rule = Rule.forCall(i.owner(), i.member());
                if (rule != null)
                    out.add(new Finding(rule, c.name(), at(c, i.line()), member(m.signature()),
                                        readable(i)));
            }

        // A method reference names its target nowhere in the code. Without this,
        // `System::nanoTime` is a rule that anyone could walk straight past.
        for (Bytecode.Insn i : c.viaMethodRef()) {
            Rule rule = Rule.forCall(i.owner(), i.member());
            if (rule != null)
                out.add(new Finding(rule, c.name(), at(c, 0), "a method reference", readable(i)));
        }

        // A class that IS a thread starts itself by inheritance, so the call site says
        // `Worker.start` and matches nothing. Only the declaration gives it away.
        if (c.superName().equals("java.lang.Thread"))
            out.add(new Finding(Rule.UNATTRIBUTED_THREAD, c.name(), at(c, 0), "",
                                "extends Thread"));

        out.addAll(shared(c, services));
        return out;
    }

    /**
     * Static fields the whole fleet shares.
     *
     * <p>The distinction that has to be got right is between state and a constant.
     * {@code static final Map M = new HashMap<>()} is one map for eight machines;
     * {@code static final String[] WORDS = {"a", "the"}} is a table, and flagging it
     * would make the report the kind of thing people learn to scroll past. Both are
     * static finals of a mutable type, and the only place they differ is in what the
     * class initialiser put there — so that is what gets read, rather than guessed at
     * from the declaration.
     */
    private List<Finding> shared(Bytecode.Clazz c, Set<String> services) {
        var out = new ArrayList<Finding>();
        for (Bytecode.Static f : c.statics()) {
            if (f.name().equals("serialVersionUID")) continue;
            String type = f.type();
            if (f.isFinal() && !holdsState(c, f.name())) continue;
            if (f.isFinal() && !f.isArray() && !isContainer(type) && !available.contains(type))
                continue;

            Rule rule = type != null && services.contains(type)
                    ? Rule.MACHINES_TOUCHING : Rule.SHARED_STATE;
            out.add(new Finding(rule, c.name(), at(c, lineOfStatic(c, f.name())), "",
                    (f.isFinal() ? "static final " : "static ") + simple(f) + " " + f.name()));
        }
        return out;
    }

    /**
     * Whether what the class initialiser put in this field is state or a constant.
     *
     * <p>One rule: <b>a constant is something assembled without calling anything.</b>
     * {@code {"a", "the"}} is a table of literals and no method runs to build it;
     * {@code new HashMap<>()} runs a constructor, {@code Logger.getLogger(…)} runs a
     * factory, and both hand back an object with a life of its own. An empty array is
     * the third case and belongs with the first two — {@code new long[4096]} is a
     * scratch buffer for whichever machine reaches it, not a table of anything.
     *
     * <p>A field with no initialiser code at all is a compile-time constant, which is
     * what {@code static final int} and {@code static final String} compile to.
     */
    private static boolean holdsState(Bytecode.Clazz c, String field) {
        Bytecode.Method init = c.initialiser();
        if (init == null) return false;
        Bytecode.Insn seed = null;
        var since = new ArrayList<Bytecode.Insn>();
        for (Bytecode.Insn i : init.insns()) {
            if (i.writesStatic() && i.member().equals(field) && i.owner().equals(c.name())) {
                if (seed == null || immutableFactory(seed)) return false;
                if (!seed.isConstruction()) return true;            // some other factory
                return since.isEmpty() || since.stream().anyMatch(x -> x.opcode().startsWith("invoke"));
            }
            if (i.isConstruction() || i.opcode().equals("invokestatic")) { seed = i; since.clear(); }
            else if (seed != null) since.add(i);
        }
        return false;
    }

    /** The factories that hand back something nobody can change afterwards. */
    private static boolean immutableFactory(Bytecode.Insn seed) {
        if (!seed.opcode().equals("invokestatic")) return false;
        String owner = seed.owner(), member = seed.member();
        boolean collection = owner.equals("java.util.List") || owner.equals("java.util.Set")
                          || owner.equals("java.util.Map");
        if (collection && (member.equals("of") || member.equals("ofEntries")
                        || member.equals("copyOf"))) return true;
        return owner.equals("java.util.Collections")
                && (member.startsWith("unmodifiable") || member.startsWith("empty")
                 || member.startsWith("singleton"));
    }

    private static int lineOfStatic(Bytecode.Clazz c, String field) {
        for (Bytecode.Method m : c.methods())
            for (Bytecode.Insn i : m.insns())
                if (i.writesStatic() && i.member().equals(field) && i.owner().equals(c.name()))
                    return i.line();
        return 0;
    }

    /**
     * The types that are a place to put things.
     *
     * <p>Named rather than matched by package, because {@code java.util} also holds
     * {@code Random}, {@code Optional} and {@code UUID}, and a shared one of those
     * makes no measurement wrong. What belongs here is what a fleet can accumulate
     * into.
     */
    private static final Set<String> CONTAINERS = Set.of(
            "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
            "java.util.SortedMap", "java.util.NavigableMap", "java.util.SortedSet",
            "java.util.NavigableSet", "java.util.Queue", "java.util.Deque",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.IdentityHashMap", "java.util.WeakHashMap", "java.util.EnumMap",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.EnumSet", "java.util.BitSet", "java.util.Properties",
            "java.lang.StringBuilder", "java.lang.StringBuffer");

    private static boolean isContainer(String type) {
        return type != null
                && (CONTAINERS.contains(type) || type.startsWith("java.util.concurrent."));
    }

    // ------------------------------------------------------------------ phrasing

    private static String at(Bytecode.Clazz c, int line) {
        String file = c.source().isEmpty() ? simpleName(c.name()) : c.source();
        return line > 0 ? file + ":" + line : file;
    }

    private static String readable(Bytecode.Insn i) {
        String member = i.member().equals("<init>") ? "new " + simpleName(i.owner())
                                                    : simpleName(i.owner()) + "." + i.member();
        return member + "()";
    }

    /** {@code protected losim.t.Counts map(losim.t.Chunk)} -> {@code map}. */
    private static String member(String signature) {
        if (signature.startsWith("static {}")) return "the class initialiser";
        int open = signature.indexOf('(');
        String head = open < 0 ? signature : signature.substring(0, open);
        int space = head.lastIndexOf(' ');
        String name = space < 0 ? head : head.substring(space + 1);
        return name.isEmpty() ? signature : name;
    }

    private static String simple(Bytecode.Static f) {
        String type = f.type();
        return (type == null ? primitive(f.descriptor()) : simpleName(type))
                + (f.isArray() ? "[]" : "");
    }

    private static String primitive(String descriptor) {
        return switch (descriptor.replace("[", "")) {
            case "I" -> "int";     case "J" -> "long";   case "D" -> "double";
            case "F" -> "float";   case "Z" -> "boolean"; case "B" -> "byte";
            case "C" -> "char";    case "S" -> "short";  default -> descriptor;
        };
    }

    private static String simpleName(String dotted) {
        int i = Math.max(dotted.lastIndexOf('.'), dotted.lastIndexOf('$'));
        return i < 0 ? dotted : dotted.substring(i + 1);
    }
}
