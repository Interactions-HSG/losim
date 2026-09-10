import java.io.IOException;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * A real lab, built once per test class, torn down after.
 *
 * <p>{@link dissaly.cli.Lab}, {@link dissaly.cli.Palette} and {@link dissaly.cli.Experiments}
 * are all orchestration over real subprocesses — protoc, javac, a forked
 * simulation JVM — so testing them against a fixture of pre-baked classes would
 * be testing something else. This builds the smallest lab that has one of every
 * case {@link dissaly.cli.Palette} has to tell apart: a class answering to
 * {@code dissaly.Job}, a service with rpcs declared safe to retry, an abstract base
 * nothing can run, a service nested where no path reaches it, and a class that is
 * none of those.
 *
 * <p>Not a {@code @Test} class itself, so JUnit's classpath scan passes over it —
 * it declares no {@code @Test} method for the scanner to find.
 */
final class Fixture {
    private Fixture() {}

    /** The simulation every fixture ships with, proven against a real run. */
    static final String SIMULATION = """
            seed: 1

            nodes:
              master:
                instance: m5.large
                zone: eu-central-1a
                runs: { dissaly.Job: src/WordCountJob.java }
              workers:
                instance: c5.large
                zone: eu-central-1a
                count: 3
                prefix: workers
                runs: { Worker: src/Counter.java }
            """;

    /**
     * Build a lab under {@code build/}, alongside the other test artifacts rather
     * than the OS temp directory, so a failed run's leftovers are found where the
     * rest of the build's leftovers are.
     */
    static Path build() throws IOException {
        Path root = Files.createTempDirectory(Path.of("build"), "test-lab-");

        Path jar = Path.of("build/losim.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IOException("no build/losim.jar — run `gradle jar` first");
        }
        toolchain(root, jar);

        Files.createDirectories(root.resolve("proto"));
        copy(Path.of("losim/test/proto/lab.proto"), root.resolve("proto/lab.proto"));

        Files.createDirectories(root.resolve("src"));
        copy(Path.of("losim/test/src/Counter.java"), root.resolve("src/Counter.java"));
        copy(Path.of("losim/test/src/WorkerBase.java"), root.resolve("src/WorkerBase.java"));
        copy(Path.of("losim/test/src/WordCountJob.java"), root.resolve("src/WordCountJob.java"));

        // A Job whose static initializer would misbehave if it ever ran: it
        // writes the marker file `Palette.of` must never cause to appear.
        String markerPath = marker(root).toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(root.resolve("src/NoisyJob.java"), """
                public final class NoisyJob extends dissaly.pb.JobGrpc.JobImplBase {
                    static {
                        try {
                            java.nio.file.Files.writeString(java.nio.file.Path.of("%s"), "touched");
                        } catch (java.io.IOException ignored) { }
                    }
                }
                """.formatted(markerPath));

        // A service that no `runs:` value could reach: a path names the class its
        // file is named after, and this one is nested inside that class. The
        // palette has to count it rather than offer it, or the console writes a
        // line that places Bundle — which serves nothing.
        Files.writeString(root.resolve("src/Bundle.java"), """
                public final class Bundle {
                    public static final class Inner extends dissaly.t.VolleyGrpc.VolleyImplBase { }
                }
                """);

        Files.createDirectories(root.resolve("simulations"));
        Files.writeString(root.resolve("simulations/main.yaml"), SIMULATION);

        return root;
    }

    /**
     * The one file losim reads to find its toolchain, written the way a lab's
     * build writes it.
     *
     * <p>Pointed straight at this repository's vendored jars and binaries rather
     * than at copies of them. A copy would be a second set of bytes to keep in
     * step with the first, and the file names absolute paths in either case — so
     * copying buys a fixture that can disagree with the build it was made from.
     *
     * <p>Deliberately no price list anywhere: {@code Bills} prints "no price
     * list… using the built-in defaults" to stderr exactly when one is missing,
     * and stdout and stderr are kept apart precisely so that line can never land
     * inside {@code .bill.json} and break its JSON. A fixture that shipped a
     * price list would never say it.
     */
    private static void toolchain(Path root, Path jar) throws IOException {
        var cp = new StringBuilder(jar.toAbsolutePath().toString());
        try (Stream<Path> s = Files.list(Path.of("vendor/jars"))) {
            for (Path p : s.sorted().toList()) {
                if (p.getFileName().toString().endsWith(".jar")) {
                    cp.append(java.io.File.pathSeparator).append(p.toAbsolutePath());
                }
            }
        }
        var said = new StringBuilder("losim=" + dissaly.Version.get() + "\n");
        said.append("classpath=").append(cp).append('\n');
        String platform = dissaly.cli.Lab.platform();
        for (String tool : new String[] {"protoc", "protoc-gen-grpc-java"}) {
            Path from = Path.of("vendor/bin", tool + "-" + platform);
            // An unsupported platform: `run` says so itself, and says it before it
            // deletes anything, which is the behaviour worth having a fixture for.
            if (!Files.isExecutable(from)) continue;
            said.append(tool).append('=').append(from.toAbsolutePath()).append('\n');
        }
        Path file = root.resolve(dissaly.cli.Lab.TOOLCHAIN);
        Files.createDirectories(file.getParent());
        Files.writeString(file, said.toString());
    }

    /** Where `NoisyJob`'s static initializer would leave evidence, if it ran. */
    static Path marker(Path root) {
        return root.resolve("touched.marker");
    }

    static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
