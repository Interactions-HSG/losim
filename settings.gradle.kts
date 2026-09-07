// Gradle builds the simulator and publishes it as a versioned Maven artifact.
// It is the only build: `./check.sh` and `tests/run.sh` call `gradle -q jar`.
// See build.gradle.kts for why that does not weaken D10.
rootProject.name = "losim"
