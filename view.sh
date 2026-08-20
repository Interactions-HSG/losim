#!/usr/bin/env bash
# The viewer. Generic over labs: it reads only trace.json.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PYTHONPATH="$ROOT/view${PYTHONPATH:+:$PYTHONPATH}" exec python3 -m losim_view "$@"
