#!/usr/bin/env bash
# The studio: a page for watching your own system run.
#
# It watches build/ for traces, draws every scene, shows the bill, and renders
# manim videos in a sidecar. Run a lab in another terminal and the page notices.
# In Codespaces the port is forwarded automatically.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT/view.sh" serve "${@:-build}"
