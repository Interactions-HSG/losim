#!/usr/bin/env bash
# Builds the release: everything an assignment carries from losim, as archives.
#
# **Not a student command**, and not a second definition of what any of it is. It
# calls `publish.sh`, the same code that writes these directories into an
# assignment, into a staging directory and zips what comes out. One definition,
# used by the maintainer refreshing a template and by the release CI both, so a
# lab that was published cannot differ from a lab that was updated.
#
#   ./dist.sh   ->  build/losim-lib.zip, build/losim-viewer.zip,
#                   build/losim-docs.zip  and  build/VERSION
#
# Three archives rather than one because a lab does not necessarily own all three
# directories: more than one lab can share a viewer and a manual by symlink (see
# `publish.sh`), and `losim update` has to be able to replace one without
# assuming the others are its to touch. Splitting them also keeps `--check` and
# a docs refresh off the 22 MB path — the viewer is 1.1 MB and the manual 0.6.
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
./publish.sh build/dist > /dev/null

rm -f build/losim-lib.zip build/losim-viewer.zip build/losim-docs.zip
( cd build/dist && zip -qr ../losim-lib.zip    lib )
( cd build/dist && zip -qr ../losim-viewer.zip viewer )
( cd build/dist && zip -qr ../losim-docs.zip   docs )

# The version, as an asset of its own, so that `losim update --check` costs a few
# bytes rather than a download of the whole library.
printf '%s\n' "$VERSION" > build/VERSION

echo "losim $VERSION"
for z in losim-lib losim-viewer losim-docs; do
  echo "  build/$z.zip  $(du -h "build/$z.zip" | cut -f1)"
done
echo "  build/VERSION"
