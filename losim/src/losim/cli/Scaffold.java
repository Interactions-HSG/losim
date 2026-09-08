package losim.cli;

import java.util.List;
import losim.Version;

/**
 * The files {@link Adopt} writes, as text.
 *
 * <p>Apart from {@code Adopt} so that what a project receives can be read in one
 * place, and so that changing it is changing a string rather than an algorithm.
 * Nothing here is a template engine: a build file with a placeholder in it is a
 * build file nobody can run to see whether it works.
 *
 * <p><b>None of these is a {@code .java}.</b> losim writes its own furniture and
 * the Java stays the author's; the division is not squeamishness but the two edits
 * the obvious design would make being unsafe. Extracting a nested handler is a
 * refactoring engine, and a bad extraction silently drops a field initialiser;
 * turning a client into a losim.Job implementation is a judgement about which of its
 * lines are the design and which are the transport, which is exactly what the
 * course teaches. {@code AGENTS.md} is how that half gets done anyway.
 */
final class Scaffold {
    private Scaffold() {}

    /** Where a lab resolves losim from, anonymously and with nothing to configure. */
    static final String REPO = "https://raw.githubusercontent.com/Interactions-HSG/losim/maven-repo/";

    static final String GROUP = "io.github.interactions-hsg";

    /** What this jar calls itself, which is what an adopted project should ask for. */
    static String version() {
        return Version.known() ? Version.get() : "2.0.2";
    }

    /**
     * The build.
     *
     * <p>Three things it deliberately does not do. It does not apply the
     * protobuf-gradle-plugin: losim generates into {@code gen/} beside the schema
     * on purpose, so the editor resolves generated types there, and the plugin
     * would generate a second copy into {@code build/generated}, add a Plugin
     * Portal resolution and skew its protoc against losim's. It does not set a
     * main class, because nothing here has a main. And it locks its dependencies,
     * because a lab's classpath used to be seventeen committed jars and is now a
     * resolved graph — which is a real change to what D10 promises, and one worth
     * pinning rather than hoping about.
     */
    static String build(String grpc, String protobuf) {
        String protobufVersion = protobuf.isEmpty() ? "4.36.0" : protobuf;
        String grpcVersion = grpc.isEmpty() ? "1.83.1" : grpc;
        return """
            // Written by `losim adopt`. Everything here is the simulator's furniture;
            // your Java and your schema are untouched.
            //
            // Imported rather than written out below: inside a Gradle build script
            // `java` is the Java plugin's extension, so `java.io.File` resolves to
            // that and fails. It is the one thing in this file that looks wrong and
            // is not.
            import java.io.File

            plugins {
                java
            }

            repositories {
                mavenCentral()
                // losim itself. A plain file tree over HTTPS, so a lab needs no
                // credential and nothing to configure — see docs/start/adopt.
                maven { url = uri("%s") }
            }

            val losimVersion = "%s"
            val protobufVersion = "%s"
            val grpcVersion = "%s"

            dependencies {
                // gRPC and protobuf arrive with it: losim declares them as `api`
                // dependencies because your handlers import them directly.
                implementation("%s:losim:$losimVersion")
            }

            // `release`, not a toolchain: a toolchain says "find me a JDK 21", and a
            // machine with 25 on it and no 21 then cannot build at all. This says
            // "compile against 21's API with whatever JDK is here", which is the
            // constraint that was actually meant and is what losim itself uses.
            tasks.withType<JavaCompile>().configureEach {
                options.release.set(21)
                options.compilerArgs.add("-nowarn")
            }

            // Where the code is, after `losim adopt` moved it: your Java in src/, and
            // the protobuf sources losim generates beside the schema rather than off
            // in a build directory — so the editor resolves generated types where you
            // can also read them. Both, or `gradle build` compiles handlers whose
            // request types do not exist.
            sourceSets {
                main {
                    java.setSrcDirs(listOf("src", "gen"))
                    resources.setSrcDirs(listOf<String>())
                }
                test {
                    java.setSrcDirs(listOf("test"))
                    resources.setSrcDirs(listOf<String>())
                }
            }

            // A lab's classpath used to be jars committed into the repository, and it
            // is a resolved graph now. Locking is what puts back the guarantee that
            // cost — but this line on its own only says it should:
            //
            //     gradle --write-locks losimToolchain     # then commit gradle.lockfile
            //
            // The task has to be named. Gradle writes a lock for the configurations
            // an invocation actually resolves, and a bare `--write-locks` resolves
            // none, so it succeeds and writes nothing at all. That one names the
            // three that decide what a run resolves: the classpath, and the two
            // compilers.
            dependencyLocking { lockAllConfigurations() }

            // ---------------------------------------------------------------- protoc
            //
            // The compiler and the gRPC codegen plugin as ordinary dependencies, which
            // is what Maven publishes them as. They arrive without an executable bit,
            // so the toolchain task below copies them out and sets one.
            val protocTool: Configuration by configurations.creating
            val grpcPlugin: Configuration by configurations.creating

            val classifier = run {
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                val cpu = if (arch.contains("aarch64") || arch.contains("arm")) "aarch_64" else "x86_64"
                when {
                    os.contains("mac") -> "osx-$cpu"
                    os.contains("win") -> "windows-x86_64"
                    else -> "linux-$cpu"
                }
            }

            dependencies {
                protocTool("com.google.protobuf:protoc:$protobufVersion:$classifier@exe")
                grpcPlugin("io.grpc:protoc-gen-grpc-java:$grpcVersion:$classifier@exe")
            }

            // ------------------------------------------------------------- the toolchain
            //
            // What losim reads instead of going looking: the build's own resolved
            // classpath, and the two binaries it just fetched.
            //
            // `configurations.named("runtimeClasspath")`, never
            // `sourceSets.main.runtimeClasspath` — that includes the source set's own
            // output, so this task would depend on compileJava, which needs gen/, which
            // losim generates by reading this file. A task that cannot run until it has
            // already run is worse than no task.
            val losimToolchain by tasks.registering {
                val cp = configurations.named("runtimeClasspath")
                val protoc = protocTool
                val plugin = grpcPlugin
                val bin = layout.buildDirectory.dir("losim/bin")
                val line = layout.buildDirectory.file("losim/classpath")
                val out = layout.buildDirectory.file("losim-toolchain.properties")
                inputs.files(cp, protoc, plugin)
                // All three, or a `build/` that was cleaned while the properties file
                // survived is a task Gradle calls up to date and a launcher with no
                // classpath to read.
                outputs.files(out, line)
                outputs.dir(bin)
                doLast {
                    val dir = bin.get().asFile
                    dir.mkdirs()
                    fun executable(from: File, name: String): File {
                        val to = File(dir, name)
                        from.copyTo(to, overwrite = true)
                        to.setExecutable(true)
                        return to
                    }
                    val p = executable(protoc.singleFile, "protoc")
                    val g = executable(plugin.singleFile, "protoc-gen-grpc-java")
                    out.get().asFile.writeText(
                        "losim=$losimVersion\\n" +
                        "classpath=${cp.get().asPath}\\n" +
                        "protoc=${p.absolutePath}\\n" +
                        "protoc-gen-grpc-java=${g.absolutePath}\\n"
                    )
                    // The same classpath again, as one plain line. `./losim` has to
                    // read it before it can start a JVM, and java.util.Properties
                    // escapes `:` and `\\` on the way out — so a shell reading the
                    // file above with sed gets a classpath that is subtly not the
                    // one Gradle resolved. Java reads the .properties; the shell
                    // reads this.
                    line.get().asFile.writeText(cp.get().asPath)
                }
            }
            """.formatted(REPO, version(), protobufVersion, grpcVersion, GROUP);
    }

    /**
     * The launcher, the way {@code gradlew} is one.
     *
     * <p>It regenerates the toolchain rather than trusting it, which is why
     * {@link Lab} re-reads that file on every call: the paths in it are one
     * machine's, and a working directory copied from somebody else's laptop — which
     * is what marking a submission is — arrives holding theirs.
     */
    static String launcher() {
        return """
            #!/usr/bin/env sh
            # losim, in this project. `./losim simulate simulations/1-one-call.yaml`, `./losim serve`.
            #
            # It builds before it runs, so there is no separate step to have forgotten
            # and no stale classpath to explain. Written by `losim adopt`; commit it.
            set -e
            cd "$(dirname "$0")"

            G=./gradlew
            [ -x "$G" ] || G="$(command -v gradle || true)"
            [ -n "$G" ] || {
              echo "no gradle here — the devcontainer has one, or: brew install gradle" >&2
              exit 1
            }

            "$G" -q losimToolchain

            # Written unescaped by the task above, for exactly this line: reading the
            # .properties file with sed would give a classpath java.util.Properties
            # had escaped and this shell would not unescape.
            CP="$(cat build/losim/classpath)"
            [ -n "$CP" ] || { echo "the build wrote no classpath" >&2; exit 1; }

            exec java -cp "$CP" losim.cli.Main "$@"
            """;
    }

    /**
     * What a simulation looks like before anybody has decided anything.
     *
     * @param entry the {@code .java} implementing {@code losim.Job}, which is where
     *              losim enters the system. Usually a file that does not exist yet:
     *              naming it is how the first refusal points at the one piece of
     *              work adopting a project leaves behind.
     * @param runs  {service, file} for everything else, all on one node
     * @param costs {file, rpc}, in the order they should be written
     */
    static String simulation(String entry, List<String[]> runs, List<String[]> costs) {
        var sb = new StringBuilder();
        sb.append("""
            # Your first simulation, written by `losim adopt`.
            #
            # Two nodes and one call, which is the smallest thing that is still a
            # distributed system. Everything past that — more nodes, a network that
            # costs something, a node that dies halfway — is a line at a time.
            seed: 1

            nodes:
              # losim calls Job.Load here off the clock, and then Job.Run. That second
              # call is the simulation: when it returns, the simulation is over.
              coordinator:
                instance: m5.large
                zone: eu-central-1a
                runs: { losim.Job: %s }
            """.formatted(entry));
        if (runs.isEmpty()) {
            sb.append("""

                # There is one node and no call, because nothing here is yet a service a
                # node could be given — `losim check` says why, with the line. A second
                # node is three lines: a name, an instance, and what it runs.
                """);
        } else {
            sb.append("""

                  worker:
                    instance: c5.large
                    zone: eu-central-1a
                    runs:
                """);
            for (String[] row : runs) {
                sb.append("      ").append(row[0]).append(": ").append(row[1]).append('\n');
            }
        }
        sb.append("""

            # What losim hands Job.Load, before the clock starts. `count:` is the one
            # number the engine varies: at scale: 1 it is what you wrote, and above it
            # losim shrinks it, measures what comes back and fits the rest.
            #
            # `unit:` is what one of them is called, singular — frame, line, order. It
            # is the same word Losim.current().units(n) counts and perUnit: prices.
            #
            # Add `source: data/whatever` to read the workload from a file or a folder.
            # Left out, as here, Load generates it from Losim.current().seed(), which
            # costs nothing on the clock and varies across a sweep.
            input:
              unit:  item
              count: 100
            """);
        if (costs.isEmpty()) return sb.toString();
        sb.append("""

            # What each call costs on the reference machine — two vCPUs, running alone.
            #
            # Everything below is 0, which means every call is instant: no queueing, no
            # contention, no deadline pressure and no critical path. Nothing measures
            # this for you and nothing can, so these are yours to fill in. See
            # /ref/simulated-duration.
            simulatedDuration:
            """);
        String last = "";
        for (String[] row : costs) {
            if (!row[0].equals(last)) sb.append("  ").append(row[0]).append(":\n");
            last = row[0];
            sb.append("    ").append(row[1]).append(": { fixed: 0 refMs }\n");
        }
        return sb.toString();
    }

    /** What is generated rather than written, and so is never committed. */
    static String gitignore() {
        return """
            # losim generates protobuf sources beside the schema, where the editor finds
            # them, and everything else under build/.
            gen/
            build/
            """;
    }
}
