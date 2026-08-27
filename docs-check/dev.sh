#!/usr/bin/env bash
# The manual, at http://localhost:3000.
#
# Served by losim itself, as its own process on its own port. That separation is
# the point rather than an implementation detail: the manual is where you look
# when something will not start, so it must not be served by the thing that will
# not start. Nothing a lab does — no compile, no fork, no classpath — can reach
# this process.
#
# It used to be Mintlify's CLI over npm. That is gone: the package 404s on the
# public registry, and a manual that cannot be read because somebody unpublished
# a dependency is not a manual. The published Mintlify site stays authoritative
# for design; this renders the same MDX beside the work, with no node at all.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f build/losim.jar ] || ./build.sh

CP="$(ls vendor/jars/*.jar 2>/dev/null | tr '\n' ':')build/losim.jar"
exec java -cp "$CP" losim.cli.Main serve docs --port "${PORT:-3000}" --docs docs
