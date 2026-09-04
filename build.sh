#!/usr/bin/env bash
# Builds the losim library into a jar. Labs compile against the jar and the
# vendored gRPC jars — students never see, and cannot edit, the simulator sources.
#
# The version comes from ./VERSION and is stamped into the jar as a resource, so
# a jar can say which losim it is. Gradle reads the same file (build.gradle.kts);
# neither build computes it, so the two cannot drift.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

CP=$(ls vendor/jars/*.jar | tr '\n' ':')
rm -rf build/classes && mkdir -p build/classes
javac --release 21 -Xlint:-this-escape -cp "$CP" -d build/classes \
      $(find losim/src -name '*.java')

mkdir -p build/classes/losim
tr -d '[:space:]' < VERSION > build/classes/losim/version

# The price lists, as resources, because the Gradle build puts them there and the
# two jars must not differ in what a class can read. They are not what a lab
# bills with — `Lab` passes --prices from lib/prices/ on disk and always wins —
# but a jar that carries them on one build path and not the other is a jar whose
# behaviour depends on who compiled it, which is the kind of difference that is
# discovered as a billing discrepancy months later.
mkdir -p build/classes/losim/prices
cp prices/*.yaml build/classes/losim/prices/

# The same manifest Gradle writes, so the two jars differ in nothing that a
# program can observe. Main-Class is the one that is not cosmetic: without it
# `java -jar losim.jar` works from the published artifact and not from this one,
# which is the sort of difference that gets discovered in front of a class.
cat > build/manifest.mf <<MF
Implementation-Title: losim
Implementation-Version: $(tr -d '[:space:]' < VERSION)
Main-Class: losim.cli.Main
MF

jar --create --file build/losim.jar --manifest build/manifest.mf -C build/classes .

echo "built build/losim.jar ($(cat VERSION | tr -d '[:space:]'))"
