// Gradle builds the simulator and publishes it as a versioned Maven artifact.
// It is the only build: `bin/losim` runs `gradle -q jar` before every command.
// See build.gradle.kts for why that does not weaken D10.
rootProject.name = "losim"
