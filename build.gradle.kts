// The simulator, as a Maven artifact.
//
// **This is not how a lab builds losim, and it is not how CI builds it.**
// `./build.sh` is: one javac, one jar, against the jars in vendor/. That stays
// the build, because D10 says the number a run produces must not depend on what
// a package manager resolved, and a Gradle build that fetched grpc from the
// network would make it depend on exactly that.
//
// So this file compiles against the *vendored* jars, the identical files in
// vendor/jars that build.sh puts on its classpath, and never resolves a compile
// dependency. What it adds is the one thing a directory of jars cannot have: a
// coordinate and a version, so that anything outside a lab can depend on losim
// the ordinary way.
//
// **What is and is not claimed about the two jars.** That sentence is about the
// build's *inputs*, not its output: compiling the same sources against the same
// jars does not by itself make the resulting jars byte-identical, so do not read
// it as a guarantee that it does. Both this build and build.sh ship the price
// lists as resources, under the same manifest, so the two jars agree on all 144
// entries and every one of the 132 classes. They are not byte-identical and are
// not meant to be: zip ordering and timestamps differ. What is guaranteed is the
// thing that matters: the same sources, compiled against the same jars, carrying
// the same resources, so no program can tell which build made the jar it was
// handed.
//
// If you add a resource to one, add it to the other. A jar whose behaviour
// depends on who compiled it is how a billing difference goes unnoticed for a
// term.
//
// The dependency list below is therefore a *declaration for consumers*, not an
// input to this build. It is pinned to the versions in vendor/jars, and if the
// two ever drift the check at the bottom fails the build rather than publishing
// a POM that lies.
//
//   gradle publishToMavenLocal          try it
//   gradle publish                      to GitHub Packages (needs credentials)
//
// There is no wrapper committed. `gradle wrapper` will make one; the release
// workflow installs Gradle instead, so nothing here depends on a binary blob
// nobody can read.

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
    // losim's own tests run under ./check.sh, against the jar, with their own
    // generated protobuf. Gradle is not asked to reproduce that, so this source
    // set stays empty rather than being pointed at losim/test.
}

// The version, as a resource, exactly as build.sh writes it. Same file, same
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
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.add("-Xlint:-this-escape")
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "losim",
            "Implementation-Version" to version,
            "Main-Class" to "losim.cli.Main",
        )
    }
}

// What a consumer needs, at the versions vendor/jars holds. Declared for the POM
// only: see the header, and the parity check below.
val grpcVersion = "1.83.1"
val protobufVersion = "4.36.0"
val gsonVersion = "2.14.0"
val guavaVersion = "33.6.0-jre"

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
    api("com.google.android:annotations:4.1.1.4")
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
