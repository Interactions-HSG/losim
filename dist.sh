#!/usr/bin/env bash
# Builds the release: the lib/ directory an assignment carries, as one archive.
#
# **Not a student command**, and not a second definition of what lib/ is. It calls
# `publish.sh --lib-only`, the same code that writes lib/ into an assignment,
# into a staging directory and zips the result. One definition, used by the
# maintainer refreshing a template and by the release CI both, so a lab that was
# published cannot differ from a lab that was updated.
#
#   ./dist.sh          ->  build/losim-lib.zip  and  build/VERSION
#
# Zip rather than tar because `losim update` unpacks it with java.util.zip, which
# is in the JDK, and a student's container has no guarantee of anything else. The
# cost is the executable bit on lib/bin/, which Update.java sets after unpacking.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VERSION="$(tr -d '[:space:]' < VERSION)"
[ -n "$VERSION" ] || { echo "VERSION is empty" >&2; exit 1; }

rm -rf build/dist && mkdir -p build/dist
./publish.sh --lib-only build/dist > /dev/null

rm -f build/losim-lib.zip
( cd build/dist && zip -qr ../losim-lib.zip lib )

# The version, as an asset of its own, so that `losim update --check` costs a few
# bytes rather than a download of the whole library.
printf '%s\n' "$VERSION" > build/VERSION

echo "losim $VERSION"
echo "  build/losim-lib.zip  $(du -h build/losim-lib.zip | cut -f1)"
echo "  build/VERSION"
