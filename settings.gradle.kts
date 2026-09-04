// Gradle exists here for one job: to publish the simulator as a versioned Maven
// artifact. It is not how losim is built for the labs — `./build.sh` is, and
// `./check.sh` and `tests/run.sh` call that one. See build.gradle.kts.
rootProject.name = "losim"
