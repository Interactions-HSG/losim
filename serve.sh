#!/usr/bin/env bash
# Serve the generated views. In Codespaces the port is forwarded automatically.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -n "$(ls "$ROOT"/build/*.html 2>/dev/null || true)" ] || {
  echo "nothing to show yet — run ./run-all.sh first"; exit 1; }
echo "serving $ROOT/build on http://localhost:8000"
cd "$ROOT/build" && exec python3 -m http.server 8000
