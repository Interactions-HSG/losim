import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * A real lab, built once per test class, torn down after.
 *
 * <p>{@link losim.cli.Lab}, {@link losim.cli.Palette} and {@link losim.cli.Experiments}
 * are all orchestration over real subprocesses — protoc, javac, a forked
 * simulation JVM — so testing them against a fixture of pre-baked classes would
 * be testing something else. This builds the smallest lab that actually has all
 * three shapes {@link losim.cli.Palette} distinguishes: a {@code Job}, a service
 * with methods declared safe to retry, and a class that is neither.
 *
 * <p>Not a {@code @Test} class itself, so JUnit's classpath scan passes over it —
 * it declares no {@code @Test} method for the scanner to find.
 */
final class Fixture {
    private Fixture() {}

    /** The scenario every fixture ships with, proven against a real run. */
    static final String SCENARIO = """
            seed: 1
            job: WordCountJob
            expectedRun: 20 refSeconds

            machines:
              coordinator:
                instance: m5.large
                zone: eu-central-1a
              workers:
                instance: c5.large
                zone: eu-central-1a
                count: 3
                prefix: workers
                runs: [Counter]
            """;

    /**
     * Build a lab under {@code build/}, alongside the other test artifacts rather
     * than the OS temp directory, so a failed run's leftovers are found where the
     * rest of the build's leftovers are.
     */
    static Path build() throws IOException {
        Path root = Files.createTempDirectory(Path.of("build"), "test-lab-");
        Path lib = root.resolve("lib");
        Files.createDirectories(lib.resolve("jars"));
        Files.createDirectories(lib.resolve("bin"));
        Files.createDirectories(lib.resolve("prices"));

        Path jar = Path.of("build/losim.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IOException("no build/losim.jar — run ./build.sh first");
        }
        copy(jar, lib.resolve("losim.jar"));

        try (Stream<Path> s = Files.list(Path.of("vendor/jars"))) {
            for (Path p : s.toList()) {
                if (p.getFileName().toString().endsWith(".jar")) copy(p, lib.resolve("jars").resolve(p.getFileName()));
            }
        }

        String platform = losim.cli.Lab.platform();
        for (String bin : new String[] {"protoc-" + platform, "protoc-gen-grpc-java-" + platform}) {
            Path from = Path.of("vendor/bin", bin);
            if (!Files.isExecutable(from)) continue;   // an unsupported platform: run() will say so itself
            Path to = lib.resolve("bin").resolve(bin);
            copy(from, to);
            to.toFile().setExecutable(true);
        }

        // Deliberately no price list: `Bills` prints "no price list… using the
        // built-in defaults" to stderr exactly when one is missing, which is the
        // one line that turned `.bill.json` into not-JSON before the two streams
        // were kept apart. A fixture that shipped a price list would never say it.

        Files.createDirectories(root.resolve("proto"));
        copy(Path.of("losim/test/proto/lab.proto"), root.resolve("proto/lab.proto"));

        Files.createDirectories(root.resolve("src"));
        copy(Path.of("losim/test/src/Counter.java"), root.resolve("src/Counter.java"));
        copy(Path.of("losim/test/src/WorkerBase.java"), root.resolve("src/WorkerBase.java"));
        copy(Path.of("losim/test/src/WordCountJob.java"), root.resolve("src/WordCountJob.java"));

        // A job whose static initializer would misbehave if it ever ran: it
        // writes the marker file `Palette.of` must never cause to appear.
        String markerPath = marker(root).toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(root.resolve("src/NoisyJob.java"), """
                public final class NoisyJob implements losim.api.Job {
                    static {
                        try {
                            java.nio.file.Files.writeString(java.nio.file.Path.of("%s"), "touched");
                        } catch (java.io.IOException ignored) { }
                    }
                    @Override public void run(losim.api.Cluster cluster) throws Exception { }
                }
                """.formatted(markerPath));

        Files.createDirectories(root.resolve("scenarios"));
        Files.writeString(root.resolve("scenarios/main.yaml"), SCENARIO);

        return root;
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
