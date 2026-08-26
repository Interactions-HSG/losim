package losim.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * What a class file actually says, read out of {@code javap}.
 *
 * <p>Compiled classes, not sources: what a machine does is what its bytecode does,
 * and a lab that ships a jar has no sources to read anyway. {@code javap -v} is
 * verbose but it is the only mode carrying the three things this needs — the line
 * numbers, so a finding reads like a compiler error; the bootstrap methods, so a
 * method reference like {@code System::nanoTime} is not invisible; and the class
 * annotations, so generated code is recognised as generated rather than by a guess
 * about its name.
 *
 * <p>One process for a whole fleet, not one per class. The complete disassembly of
 * a lab runs to a few hundred kilobytes and costs about what one {@code javac} of it
 * costs.
 */
final class Bytecode {

    /**
     * One instruction, in order.
     *
     * <p>Order is what separates {@code static final Map M = new HashMap<>()} — every
     * machine writing one map — from {@code static final List L = List.of(…)}, which
     * is a constant. Both are static finals of a container type and only one is a
     * finding, and the difference is visible solely in what the class initialiser put
     * there.
     */
    record Insn(String opcode, String kind, String owner, String member, int line) {
        boolean isCall()         { return kind.equals("Method") || kind.equals("InterfaceMethod"); }
        boolean isConstruction() { return opcode.startsWith("new") || opcode.endsWith("newarray"); }
        boolean writesStatic()   { return opcode.equals("putstatic"); }
    }

    /** One method, and everything it does. */
    record Method(String signature, List<Insn> insns) {
        boolean isInitialiser() { return signature.startsWith("static {}"); }
    }

    /** One static field, as declared. */
    record Static(String name, String descriptor, boolean isFinal) {

        /** The type, dotted, or null for a primitive. */
        String type() {
            String d = descriptor;
            while (d.startsWith("[")) d = d.substring(1);
            return d.startsWith("L") ? d.substring(1, d.length() - 1).replace('/', '.') : null;
        }

        boolean isArray() { return descriptor.startsWith("["); }
    }

    /** One class, disassembled. */
    record Clazz(String name, String source, String superName, boolean generated,
                 List<Method> methods, List<Static> statics, List<Insn> viaMethodRef,
                 Set<String> references) {

        /** The enclosing class of a nested one, or itself. */
        String outer() {
            int i = name.indexOf('$');
            return i < 0 ? name : name.substring(0, i);
        }

        Method initialiser() {
            for (Method m : methods) if (m.isInitialiser()) return m;
            return null;
        }
    }

    private Bytecode() {}

    /** Thrown when the disassembler is not there — a JRE rather than a JDK, usually. */
    static final class Unavailable extends Exception {
        Unavailable(String message) { super(message); }
    }

    /**
     * Disassembles every named class, in one process.
     *
     * <p>Names that are not on the path come back missing rather than failing: the
     * caller asks about everything a class mentions, and most of that is the JDK.
     */
    /** Names per invocation, so a large lab cannot outgrow the command line. */
    private static final int PER_CALL = 200;

    static List<Clazz> disassemble(List<Path> classpath, Collection<String> names)
            throws Unavailable, IOException, InterruptedException {
        if (names.isEmpty()) return List.of();

        Path javap = Path.of(System.getProperty("java.home"), "bin", "javap");
        if (!Files.isExecutable(javap))
            throw new Unavailable("there is no javap at " + javap + ", so nothing could be"
                    + " checked. That happens on a JRE; losim needs the JDK it was built with.");

        var classes = new ArrayList<Clazz>();
        var batch = new ArrayList<String>(PER_CALL);
        for (String name : names) {
            batch.add(name);
            if (batch.size() == PER_CALL) { classes.addAll(run(javap, classpath, batch)); batch.clear(); }
        }
        classes.addAll(run(javap, classpath, batch));
        return classes;
    }

    private static List<Clazz> run(Path javap, List<Path> classpath, List<String> names)
            throws IOException, InterruptedException {
        if (names.isEmpty()) return List.of();
        var command = new ArrayList<>(List.of(javap.toString(), "-v", "-p", "-cp", join(classpath)));
        command.addAll(names);

        var process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String out;
        try (var in = process.getInputStream()) { out = new String(in.readAllBytes()); }
        try (var err = process.getErrorStream()) { err.readAllBytes(); }
        process.waitFor();

        var classes = new ArrayList<Clazz>();
        for (String chunk : out.split("(?m)^(?=Classfile )"))
            if (chunk.startsWith("Classfile ")) classes.add(parse(chunk));
        return classes;
    }

    private static String join(List<Path> paths) {
        var sb = new StringBuilder();
        for (Path p : paths) {
            if (sb.length() > 0) sb.append(java.io.File.pathSeparatorChar);
            sb.append(p);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- parsing
    //
    // javap's shape, in the order it arrives: a header carrying the declaration and
    // the superclass; the constant pool, which is the complete list of every type the
    // class mentions; the members, between braces; then SourceFile, BootstrapMethods
    // and the class annotations. Everything between the braces is indented, so a line
    // in column zero is always a section and never a member.

    private static Clazz parse(String chunk) {
        String name = "", source = "", declaration = "", superName = "";
        boolean generated = false, inPool = false, inBody = false, inBootstrap = false;
        boolean inClassAnnotations = false;

        var methods = new ArrayList<Method>();
        var statics = new ArrayList<Static>();
        var viaMethodRef = new ArrayList<Insn>();
        var references = new LinkedHashSet<String>();

        // The member being read. Its instructions are held until the line-number
        // table arrives, because javap prints that after the code it describes.
        String member = null, descriptor = "";
        var offsets = new ArrayList<Integer>();
        var insns = new ArrayList<Insn>();
        var lines = new TreeMap<Integer, Integer>();

        for (String line : chunk.split("\n")) {
            boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0));

            if (topLevel) {
                inPool = inBootstrap = inClassAnnotations = false;
                if (line.equals("Constant pool:")) { inPool = true; continue; }
                if (line.equals("{")) { inBody = true; continue; }
                if (line.equals("}")) {
                    if (member != null) methods.add(finish(member, offsets, insns, lines));
                    member = null;
                    inBody = false;
                    continue;
                }
                if (line.startsWith("SourceFile: ")) { source = unquote(line.substring(12)); continue; }
                if (line.startsWith("BootstrapMethods:")) { inBootstrap = true; continue; }
                if (line.endsWith("Annotations:")) { inClassAnnotations = true; continue; }
                if (line.startsWith("Classfile ") || line.startsWith("InnerClasses")) continue;
                if (declaration.isEmpty() && !inBody) declaration = line;
                continue;
            }

            if (inClassAnnotations) {
                // grpc-java stamps its generated classes and protobuf's are caught by
                // their superclass, so generated code needs no rule about its name.
                if (line.contains("GrpcGenerated")) generated = true;
                continue;
            }
            if (inBootstrap) {
                // A method reference compiles to a bootstrap argument and appears
                // nowhere in the code: without this, System::nanoTime is invisible.
                var m = REF.matcher(line);
                if (m.find()) {
                    var t = split(m.group(1), name);
                    viaMethodRef.add(new Insn("invokestatic", "Method", t[0], t[1], 0));
                }
                continue;
            }
            if (inPool) {
                var m = POOL_CLASS.matcher(line);
                if (m.find()) references.add(m.group(1).replace('/', '.'));
                continue;
            }
            if (!inBody) {
                String t = line.trim();
                if (t.startsWith("this_class:"))       name = comment(line).replace('/', '.');
                else if (t.startsWith("super_class:")) superName = comment(line);
                continue;
            }

            // Between the braces. Two spaces of indent is a member declaration; more
            // than two is one of its attributes.
            if (line.startsWith("  ") && !line.startsWith("   ") && line.trim().endsWith(";")) {
                if (member != null) methods.add(finish(member, offsets, insns, lines));
                String decl = line.trim();
                member = decl.substring(0, decl.length() - 1);
                descriptor = "";
                offsets.clear(); insns.clear(); lines.clear();
                continue;
            }
            String t = line.trim();
            if (t.startsWith("descriptor: ")) { descriptor = t.substring(12); continue; }
            if (t.startsWith("flags: ")) {
                // A field rather than a method: only a method's descriptor starts with
                // a parameter list. Synthetic fields are the compiler's own — an enum's
                // $VALUES, an assertion switch — and belong to nobody's design.
                if (member != null && !descriptor.startsWith("(") && t.contains("ACC_STATIC")
                        && !t.contains("ACC_SYNTHETIC") && !t.contains("ACC_ENUM"))
                    statics.add(new Static(lastWord(member), descriptor, t.contains("ACC_FINAL")));
                continue;
            }

            var code = INSTRUCTION.matcher(line);
            if (code.matches()) {
                offsets.add(Integer.parseInt(code.group(1)));
                var target = split(code.group(4), name);
                insns.add(new Insn(code.group(2), code.group(3), target[0], target[1], 0));
                continue;
            }
            var bare = BARE.matcher(line);
            if (bare.matches()) {
                offsets.add(Integer.parseInt(bare.group(1)));
                insns.add(new Insn(bare.group(2), "", "", "", 0));
                continue;
            }
            var ln = LINE.matcher(line);
            if (ln.matches()) lines.put(Integer.parseInt(ln.group(2)), Integer.parseInt(ln.group(1)));
        }
        if (member != null) methods.add(finish(member, offsets, insns, lines));

        generated |= superName.startsWith("com/google/protobuf/")
                  || declaration.contains("com.google.protobuf.");
        return new Clazz(name, source, superName.replace('/', '.'), generated, methods,
                         statics, viaMethodRef, references);
    }

    /** Attaches the source line to every instruction, now that the table has arrived. */
    private static Method finish(String signature, List<Integer> offsets, List<Insn> insns,
                                 TreeMap<Integer, Integer> lines) {
        var out = new ArrayList<Insn>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            var at = lines.floorEntry(offsets.get(i));
            Insn n = insns.get(i);
            out.add(new Insn(n.opcode(), n.kind(), n.owner(), n.member(),
                             at == null ? 0 : at.getValue()));
        }
        return new Method(signature, List.copyOf(out));
    }

    // ---------------------------------------------------------------- small stuff

    private static final Pattern INSTRUCTION = Pattern.compile(
            "\\s+(\\d+): (\\w+)\\s+.*//\\s*(Method|InterfaceMethod|Field|class)\\s+(.+?)\\s*");
    private static final Pattern BARE = Pattern.compile("\\s+(\\d+): (\\w+)\\s*(?:\\s+[\\w\\[\\], #]*)?");
    private static final Pattern LINE = Pattern.compile("\\s+line (\\d+): (\\d+)\\s*");
    private static final Pattern POOL_CLASS = Pattern.compile(
            "#\\d+ = Class\\s+#\\d+\\s+//\\s+(\\S+)");
    private static final Pattern REF = Pattern.compile("REF_\\w+ (\\S+?):\\(");

    /** {@code java/lang/System.nanoTime:()J} -> owner and member, dotted. */
    private static String[] split(String rest, String self) {
        int end = rest.indexOf(":(");
        if (end < 0) end = rest.lastIndexOf(':');
        String head = end < 0 ? rest : rest.substring(0, end);
        int dot = head.lastIndexOf('.');
        String owner = dot < 0 ? self : head.substring(0, dot).replace('/', '.');
        String member = (dot < 0 ? head : head.substring(dot + 1)).replace("\"", "");
        return new String[]{owner.replace('/', '.'), member};
    }

    /** The last word of a declaration: {@code static final int K} -> {@code K}. */
    private static String lastWord(String declaration) {
        int space = declaration.lastIndexOf(' ');
        return space < 0 ? declaration : declaration.substring(space + 1);
    }

    private static String comment(String line) {
        int i = line.indexOf("//");
        return i < 0 ? "" : line.substring(i + 2).trim();
    }

    private static String unquote(String s) {
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
