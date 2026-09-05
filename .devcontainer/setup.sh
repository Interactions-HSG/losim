#!/usr/bin/env bash
# Runs once when the container is created. Leaves a Codespace ready to build and
# check losim — the same commands, producing the same numbers, as on a laptop.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "building losim..."
./build.sh

# The manual previews with Mintlify's CLI. Fetched here so that `docs-check/dev.sh`
# works without a wait later — and deliberately allowed to fail: the docs are not
# the simulator, and a container whose build depends on a registry being up is a
# container that sometimes does not build. losim needs none of this.
# Nothing is fetched: the manual is served by the JDK already in this image, not
# by a CLI pulled over npm, so a container that comes up is a container where
# everything works — no registry involved.


cat <<'TXT'

ready. The lab is on :8000 and the manual on :3000 — .devcontainer/start.sh puts
them there on every attach, so neither is something you have to start.

Try:
  ./build.sh     the simulator, into build/losim.jar
  ./check.sh     losim's own checks — every phase's acceptance criteria
  tests/run.sh   the reference suite — gRPC systems, run the way a student runs them
  docs-check/dev.sh    the manual, at http://localhost:3000 — no node needed
  docs-check/check.sh  the manual's own check — that it gives no assignment away

TXT
