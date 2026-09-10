// The simulator: how it is built, and how it is published.
//
// **One build.** There used to be two — a shell script for the labs and this file
// for the Maven artifact — and the duplication was a standing hazard: a resource
// added to one and not the other makes a jar whose behaviour depends on who
// compiled it, which is how a billing difference goes unnoticed for a term. There
// is now nothing to keep in step.
//
// D10 survives the merge intact, because the thing D10 cares about was never the
// build tool. It is that the number a run produces must not depend on what a
// package manager resolved — so this build compiles against the *vendored* jars,
// the files in vendor/jars, and never resolves a compile dependency. The
// `dependencies` block below is a **declaration for consumers**, an input to the
// POM and to nothing else. It is pinned to the versions in vendor/jars, and if the
// two ever drift the check at the bottom fails the build rather than publishing a
// POM that lies.
//
//   gradle jar                          -> build/losim.jar
//   gradle publishToMavenLocal          try it
//   gradle publish                      to GitHub Packages (needs credentials)
//
// There is no wrapper committed. `gradle wrapper` will make one; CI and the
// devcontainer image both bring their own Gradle, so nothing here depends on a
// binary blob nobody can read.

import java.util.Properties
import java.util.zip.ZipFile

plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.interactions-hsg"
version = file("VERSION").readText().trim()
description = "A simulator for decentralized systems: real gRPC handlers, simulated time, machines and money."

// The vendored toolchain, which is the only classpath this compiles against.
val vendored = files(fileTree("vendor/jars") { include("*.jar") })

// Declared so that nothing here can fail for want of a repository: POM
// generation does not resolve anything, but a build that *cannot* resolve is a
// build that breaks in a confusing way the first time something asks it to.
// Note what is not affected: the classpath below, which is vendor/jars alone.
repositories { mavenCentral() }

sourceSets {
    main {
        java.setSrcDirs(listOf("losim/src"))
        resources.setSrcDirs(emptyList<String>())
        compileClasspath = vendored
        runtimeClasspath = output + vendored
    }
    // losim's own tests run under `dissaly dev test`, against the jar, with their own
    // generated protobuf. Gradle is not asked to reproduce that, so this source
    // set stays empty rather than being pointed at losim/test.
}

// The version, as a resource, at a path Version.get() knows. Same file, same
// path inside the jar, so a jar from either build answers Version.get() the same.
val stampVersion by tasks.registering {
    val out = layout.buildDirectory.file("version-resource/losim/version")
    inputs.file("VERSION")
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(version.toString())
    }
}

tasks.named<ProcessResources>("processResources") {
    from(stampVersion.map { layout.buildDirectory.dir("version-resource") })
    // The price lists, so that a consumer outside a lab has the numbers losim
    // bills with. A lab still reads lib/prices/ from disk; this is for everyone
    // who has a jar and no lib/.
    from("prices") { include("*.yaml"); into("losim/prices") }

    // The console and the manual, so that depending on losim is the whole of
    // getting losim. A lab used to carry both as committed directories, put there
    // by a maintainer running a script over its repository; now they ride in the
    // jar and `dissaly serve` finds them there when there is nothing on disk.
    //
    // Wholesale, both of them. The manual's pages link images and a favicon, and
    // Mintlify's docs.json names every page — a filtered copy would ship a sidebar
    // whose entries 404. About 1.7 MB uncompressed between them.
    from("viewer/out") { into("losim/viewer") }
    from("docs") { into("losim/docs") }

    // What `dissaly adopt` writes into the project it adopts. A resource rather than
    // a string constant, so it is written and reviewed as prose.
    from("losim/agents") { include("AGENTS.md"); into("losim") }

    // losim's own schema, so a system can `import "losim/job.proto"` without
    // fetching anything. It travels as the .proto only: the classes generated from
    // it are in this jar already, and a project that generated its own copy would
    // compile one that parent-first delegation never loads. So this is an include
    // path for protoc and never an input file to it.
    from("losim/proto") { into("losim/proto") }
}

// A jar that shipped the sidebar but not the pages would render a manual of dead
// links, and `Manual` would not complain: it falls back to a flat listing when
// docs.json cannot be read, so a *missing* nav is loud and a nav pointing at
// nothing is silent. This is the check that keeps it loud.
val docsBundled by tasks.registering {
    dependsOn(tasks.named("jar"))
    doLast {
        val built = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val zip = ZipFile(built)
        try {
            val nav = zip.getEntry("losim/docs/docs.json")
                ?: error("the jar carries no losim/docs/docs.json, so it has no manual")
            if (zip.getEntry("losim/viewer/index.html") == null)
                error("the jar carries no losim/viewer/index.html, so `dissaly serve` has no console")

            val text = zip.getInputStream(nav).readBytes().decodeToString()
            val pages = Regex("\"([a-z0-9-]+/[a-z0-9-]+)\"").findAll(text)
                .map { it.groupValues[1] }.distinct().toList()
            val missing = pages.filter { zip.getEntry("losim/docs/" + it + ".mdx") == null }
            if (missing.isNotEmpty())
                error("docs.json names " + missing.size + " page(s) the jar does not carry, so "
                      + "the manual would render dead links: " + missing.take(5).joinToString(", "))
            logger.lifecycle("the jar carries the console and " + pages.size + " manual pages")
        } finally {
            zip.close()
        }
    }
}

tasks.named("check") { dependsOn(docsBundled) }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.add("-Xlint:-this-escape")
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    // `build/losim.jar`, not `build/libs/losim-<version>.jar`. This is the one
    // build, so the jar lands where everything already looks for it: the tests,
    // the devcontainer, the workflows and the editor all name that path, and a
    // version in the filename would make every one of them go looking.
    archiveFileName.set("losim.jar")
    destinationDirectory.set(layout.buildDirectory)
    manifest {
        attributes(
            "Implementation-Title" to "losim",
            "Implementation-Version" to version,
            "Main-Class" to "dissaly.cli.Main",
        )
    }
}

// What a consumer needs, at the versions vendor/jars holds. Declared for the POM
// only: see the header, and the parity check below.
//
// Read from the file `dissaly dev vendor` downloads by, so there is one pinning
// rather than two that agree until somebody bumps one of them.
val pinned = Properties().apply {
    file("vendor/versions.properties").inputStream().use { load(it) }
}
fun pin(name: String) = pinned.getProperty(name)
    ?: throw GradleException("vendor/versions.properties pins no `$name`")

val grpcVersion = pin("grpc")
val protobufVersion = pin("protobuf")
val gsonVersion = pin("gson")
val guavaVersion = pin("guava")

dependencies {
    // `api`, not `implementation`: a lab's own handlers import grpc and protobuf
    // directly, so these are losim's interface and not its private business.
    // Gradle publishes api dependencies in the POM's `compile` scope, which is
    // what a consumer needs to both compile and run against them.
    //
    // Declaring them does not resolve them: sourceSets above replaced this
    // project's own classpath with vendor/jars, and POM generation reads the
    // coordinates rather than the artifacts.
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-inprocess:$grpcVersion")
    api("io.grpc:grpc-core:$grpcVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("com.google.code.gson:gson:$gsonVersion")
    api("com.google.guava:guava:$guavaVersion")
    // What protoc-gen-grpc-java writes into every generated stub.
    api("com.google.android:annotations:${pin("android-annotations")}")
}

publishing {
    publications {
        create<MavenPublication>("losim") {
            from(components["java"])
            pom {
                name.set("losim")
                description.set(project.description)
                url.set("https://github.com/Interactions-HSG/losim")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("interactions-hsg")
                        name.set("Interactions HSG")
                        url.set("https://github.com/Interactions-HSG")
                    }
                }
                scm {
                    url.set("https://github.com/Interactions-HSG/losim")
                    connection.set("scm:git:https://github.com/Interactions-HSG/losim.git")
                }
            }
        }
    }
    repositories {
        // The one students resolve from, and the reason it is a directory.
        //
        // GitHub Packages' Maven registry requires a personal access token to
        // *read*, and, unlike the Container registry, that is true whether the
        // package is public or private. A hundred first-years each making a
        // personal access token before they have written a line contradicts the
        // promise this course makes in as many words ("nothing to install, nothing to
        // configure"), so that registry cannot be the student-facing transport
        // however public the repository is made.
        //
        // A Maven repository is only a file tree with a known layout, so this
        // writes one and CI publishes it to GitHub Pages, which serves static
        // files over HTTPS to anybody. A lab then needs no credential at all:
        //
        //     repositories {
        //         maven { url = uri("https://interactions-hsg.github.io/losim-dist") }
        //     }
        //
        // Maven Central would also be anonymous and is the better long-run home;
        // it needs a Sonatype namespace, GPG signing and sources+javadoc jars,
        // none of which is in the way of shipping this week.
        maven {
            name = "Pages"
            url = uri(layout.buildDirectory.dir("maven-repo"))
        }

        // Secondary, for maintainers who do have tokens. Never the path a
        // student's build takes; see above.
        maven {
            name = "GitHubPackages"
            url = uri(
                providers.gradleProperty("losim.packages.url").orNull
                    ?: System.getenv("LOSIM_PACKAGES_URL")
                    ?: "https://maven.pkg.github.com/Interactions-HSG/losim"
            )
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// The POM above lists versions; vendor/jars *is* the version. A release whose
// POM promises grpc 1.83.1 while the jar was compiled against 1.84 would be
// wrong in a way nobody would notice until a student's stub failed to link. So
// the two are compared, and disagreeing stops the build.
val checkVendoredVersions by tasks.registering {
    val jars = vendored.files.map { it.name }.sorted()
    val expected = mapOf(
        "grpc-api" to grpcVersion,
        "grpc-core" to grpcVersion,
        "grpc-stub" to grpcVersion,
        "grpc-protobuf" to grpcVersion,
        "grpc-inprocess" to grpcVersion,
        "protobuf-java" to protobufVersion,
        "gson" to gsonVersion,
        "guava" to guavaVersion,
    )
    doLast {
        val wrong = expected.filterNot { (artifact, v) -> jars.contains("$artifact-$v.jar") }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "the POM and vendor/jars disagree: " +
                    wrong.entries.joinToString { "${it.key} ${it.value} is not in vendor/jars" } +
                    "\nvendor/jars holds: " + jars.joinToString(", ")
            )
        }
    }
}

tasks.named("jar") { dependsOn(checkVendoredVersions) }
