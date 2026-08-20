package losim.verify;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Rejects student code that reaches for nondeterminism.
 *
 * Determinism on the JVM is not something you configure — it is something you
 * defend. Each rule below closes a way a run could differ between two machines,
 * which would surface as a flaky grade rather than an honest failure.
 *
 * Implemented over {@code javap}, which ships with the JDK, so the toolchain
 * stays dependency-free.
 */
public final class Verifier {

    public record Rule(String pattern, String why) {}

    /** Bytecode references that make a run irreproducible. */
    public static final List<Rule> FORBIDDEN = List.of(
            new Rule("java/lang/Thread.\"<init>\"", "creates a thread the kernel does not own — use ctx.spawn(...)"),
            new Rule("java/lang/Thread.ofVirtual", "creates a thread the kernel does not own — use ctx.spawn(...)"),
            new Rule("java/lang/Thread.startVirtualThread", "creates a thread the kernel does not own — use ctx.spawn(...)"),
            new Rule("java/util/concurrent/CompletableFuture", "runs work on an executor the kernel does not own"),
            new Rule("java/util/concurrent/ForkJoinPool", "runs work on an executor the kernel does not own"),
            new Rule("java/util/concurrent/Executors", "runs work on an executor the kernel does not own"),
            new Rule("parallelStream", "schedules on the common pool — use a plain stream"),
            new Rule("java/lang/System.nanoTime", "reads real time — use ctx.clock()"),
            new Rule("java/lang/System.currentTimeMillis", "reads real time — use ctx.clock()"),
            new Rule("java/time/Instant.now", "reads real time — use ctx.clock()"),
            new Rule("java/time/LocalDateTime.now", "reads real time — use ctx.clock()"),
            new Rule("java/lang/Math.random", "unseeded randomness — use ctx.random()"),
            new Rule("java/util/UUID.randomUUID", "unseeded randomness — use ctx.random()"),
            new Rule("java/lang/System.identityHashCode", "identity hashing varies per run"),
            new Rule("java/net/Socket", "real network access — messages go through ctx"),
            new Rule("java/net/ServerSocket", "real network access — messages go through ctx"),
            new Rule("java/io/FileOutputStream", "real file access — use ctx.write(...)"),
            new Rule("java/io/FileInputStream", "real file access — use ctx.read(...)"),
            new Rule("java/nio/file/Files", "real file access — use ctx.write(...) / ctx.read(...)")
    );

    private Verifier() {}

    /** How many classes are actually there — nothing to check is not a pass. */
    public static long classCount(Path classesDir) throws Exception {
        if (!Files.isDirectory(classesDir)) return 0;
        try (Stream<Path> s = Files.walk(classesDir)) {
            return s.filter(p -> p.toString().endsWith(".class")).count();
        }
    }

    public static List<String> verifyTree(Path classesDir) throws Exception {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(classesDir)) return List.of(classesDir + ": not a directory");
        List<String> classNames = new ArrayList<>();
        try (Stream<Path> s = Files.walk(classesDir)) {
            s.filter(p -> p.toString().endsWith(".class")).sorted().forEach(p -> {
                String rel = classesDir.relativize(p).toString();
                classNames.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
            });
        }
        for (String cn : classNames) problems.addAll(verifyClass(classesDir, cn));
        return problems;
    }

    public static List<String> verifyClass(Path classesDir, String className) throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> out = javap(classesDir, className);

        String method = "?";
        boolean inCode = false;
        for (String raw : out) {
            String line = raw.strip();

            if (line.endsWith(");") || line.endsWith(">();") || (line.contains("(") && line.endsWith(";") && !line.startsWith("//"))) {
                if (!line.startsWith("Code:") && line.contains("(")) { method = shortSig(line); inCode = false; }
            }
            if (line.startsWith("Code:")) { inCode = true; continue; }

            // a mutable static is state shared by every VM on the machine
            if (isFieldDecl(line) && line.contains(" static ") && !line.contains(" final ")) {
                problems.add(where(className, "field " + fieldName(line))
                        + ": mutable static field — state must live in instance fields, "
                        + "because a static is shared by every VM in the run");
                continue;
            }

            if (!inCode) continue;
            for (Rule r : FORBIDDEN) {
                if (line.contains(r.pattern())) {
                    problems.add(where(className, method) + ": " + r.why()
                            + "\n      found: " + line.replaceAll("\\s+", " "));
                    break;
                }
            }
            if (line.contains("putstatic") && !method.contains("<clinit>")) {
                problems.add(where(className, method)
                        + ": writes to a static field — state must live in instance fields");
            }
        }
        return problems;
    }

    static boolean isFieldDecl(String line) {
        if (!line.endsWith(";") || line.contains("(")) return false;
        return line.startsWith("public ") || line.startsWith("private ")
                || line.startsWith("protected ") || line.startsWith("static ") || line.startsWith("final ");
    }

    static String fieldName(String line) {
        String s = line.substring(0, line.length() - 1).strip();
        int sp = s.lastIndexOf(' ');
        return sp < 0 ? s : s.substring(sp + 1);
    }

    static String shortSig(String line) {
        String s = line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
        int paren = s.indexOf('(');
        if (paren < 0) return s.strip();
        int sp = s.lastIndexOf(' ', paren);
        return (sp < 0 ? s : s.substring(sp + 1)).strip();
    }

    static String where(String className, String method) {
        return "  " + className + "." + method;
    }

    static List<String> javap(Path classesDir, String className) throws Exception {
        String javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString();
        ProcessBuilder pb = new ProcessBuilder(javap, "-p", "-c", "-cp", classesDir.toString(), className);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        proc.waitFor();
        return lines;
    }
}
