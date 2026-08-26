#!/usr/bin/env bash
# Vendors the gRPC toolchain into the repository.
#
# losim must behave identically in the devcontainer, on a laptop and in a
# Codespace (D10), which rules out resolving dependencies at build time. So the
# jars live in git and build.sh stays javac + jar. This script is how they got
# here; it is not run by the build, and needs a network.
set -euo pipefail
cd "$(dirname "$0")"

GRPC=1.83.1          # grpc-java
PROTOBUF=4.36.0      # NOT the 3.25.9 grpc-protobuf pins: 3.25 calls a deprecated
                     # sun.misc.Unsafe that warns loudly on JDK 25. 4.x is
                     # binary-compatible with grpc 1.83 and silent. Verified by spike.
COMMON_PROTOS=2.64.1
JUNIT=5.14.0         # A student debugs a handler in an ordinary JUnit test with no
JUNIT_PLATFORM=1.14.0 # simulation running (D2), so JUnit is part of the toolchain
                     # rather than something they have to go and find.
GUAVA=33.6.0-jre
M2=https://repo1.maven.org/maven2

mkdir -p jars test-jars bin LICENSES

jar() {                       # jar <group/path> <artifact> <version>
  local path="$1" art="$2" ver="$3" f="jars/$2-$3.jar"
  [ -f "$f" ] && { echo "  have $2-$3"; return; }
  echo "  get  $2-$3"
  curl -fsSL --max-time 120 -o "$f" "$M2/$path/$art/$ver/$art-$ver.jar"
}

echo "jars:"
for a in grpc-api grpc-core grpc-stub grpc-protobuf grpc-protobuf-lite grpc-inprocess grpc-context; do
  jar io/grpc "$a" "$GRPC"
done
jar com/google/protobuf        protobuf-java               "$PROTOBUF"
jar com/google/api/grpc        proto-google-common-protos  "$COMMON_PROTOS"
jar com/google/guava           guava                       "$GUAVA"
jar com/google/guava           failureaccess               1.0.2
jar com/google/code/gson       gson                        2.14.0
jar com/google/android         annotations                 4.1.1.4
jar org/codehaus/mojo          animal-sniffer-annotations  1.27
jar com/google/errorprone      error_prone_annotations     2.50.0
jar io/perfmark                perfmark-api                0.27.0
jar com/google/code/findbugs   jsr305                      3.0.2

# JUnit is kept apart from the runtime jars, because it belongs on the classpath
# of a test and nowhere else. A lab's own code must not be able to reach it.
echo "test jars:"
tjar() {                      # tjar <group/path> <artifact> <version>
  local path="$1" art="$2" ver="$3" f="test-jars/$2-$3.jar"
  [ -f "$f" ] && { echo "  have $2-$3"; return; }
  echo "  get  $2-$3"
  curl -fsSL --max-time 120 -o "$f" "$M2/$path/$art/$ver/$art-$ver.jar"
}
tjar org/junit/jupiter   junit-jupiter-api                "$JUNIT"
tjar org/junit/platform  junit-platform-console-standalone "$JUNIT_PLATFORM"
tjar org/opentest4j      opentest4j                        1.3.0
tjar org/apiguardian     apiguardian-api                   1.1.2

# protoc and the gRPC codegen plugin, for both platforms a student may open the
# repo on. Committed so a .proto *can* be edited; nothing needs them to run,
# because the generated sources are committed too.
exe() {                       # exe <group/path> <artifact> <version> <classifier>
  local path="$1" art="$2" ver="$3" cls="$4" f="bin/$2-$4"
  [ -f "$f" ] && { echo "  have $2-$4"; return; }
  echo "  get  $2-$4"
  curl -fsSL --max-time 120 -o "$f" "$M2/$path/$art/$ver/$art-$ver-$cls.exe"
  chmod +x "$f"
}

echo "binaries:"
for p in osx-aarch_64 linux-x86_64; do
  exe com/google/protobuf protoc               "$PROTOBUF" "$p"
  exe io/grpc             protoc-gen-grpc-java "$GRPC"     "$p"
done

echo "licences:"
curl -fsSL --max-time 60 -o LICENSES/Apache-2.0.txt https://www.apache.org/licenses/LICENSE-2.0.txt
cat > LICENSES/README.md <<'EOF'
Everything in `vendor/jars`, `vendor/test-jars` and `vendor/bin` is third-party
and vendored unmodified from Maven Central.

| project | licence |
|---|---|
| grpc-java (`io.grpc:*`) | Apache-2.0 |
| protobuf-java, protoc (`com.google.protobuf:*`) | BSD-3-Clause |
| proto-google-common-protos | Apache-2.0 |
| Guava, failureaccess, gson, error_prone_annotations (`com.google.*`) | Apache-2.0 |
| animal-sniffer-annotations (`org.codehaus.mojo`) | MIT |
| perfmark-api (`io.perfmark`) | Apache-2.0 |
| jsr305 (`com.google.code.findbugs`) | BSD-3-Clause |
| JUnit 5 (`org.junit.*`), opentest4j, apiguardian-api | EPL-2.0 / Apache-2.0 |

Apache-2.0 text: `Apache-2.0.txt`. Versions are pinned in `../fetch.sh`.
EOF
curl -fsSL --max-time 60 -o LICENSES/BSD-3-Clause-protobuf.txt \
  https://raw.githubusercontent.com/protocolbuffers/protobuf/main/LICENSE
curl -fsSL --max-time 60 -o LICENSES/EPL-2.0.txt \
  https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt

echo
echo "vendored $(ls jars/*.jar | wc -l | tr -d ' ') jars, $(ls test-jars/*.jar | wc -l | tr -d ' ') test jars, $(ls bin/* | wc -l | tr -d ' ') binaries, $(du -sh . | cut -f1) total"
