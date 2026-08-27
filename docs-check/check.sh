#!/usr/bin/env bash
# The docs' own check: does the manual give away an assignment?
#
# Two things in this course are the student's to write, so the manual must not
# contain them: the batch-processing job, and logical time. A worked example
# that slipped into a page would fail no build, would read like helpful
# documentation, and would quietly hand in the answer.
#
# So it is checked before the site is published — and the check is itself
# tested. Every rule has a sample it must catch and a sample it must not, and
# this script refuses to pass a rule that nothing proves works. A guard nobody
# ever saw catch anything is not a guard.
#
#   docs-check/check.sh            self-test the rules, then scan docs/
#   docs-check/check.sh --rules    print the rules and what each one is for
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RULES=docs-check/leaks.txt
SHAPES=docs-check/shapes.txt
FIXTURES=docs-check/fixtures

# Text under docs/, minus this directory: the site as it will be published.
site_files() {
  find docs -type f \
       \
       \( -name '*.mdx' -o -name '*.md' -o -name '*.json' -o -name '*.txt' \
       -o -name '*.yaml' -o -name '*.yml' -o -name '*.java' -o -name '*.sh' \
       -o -name '*.proto' -o -name '*.js' -o -name '*.ts' -o -name '*.tsx' \
       -o -name '*.css' -o -name '*.svg' \) | sort
}

rules()  { grep -v '^[[:space:]]*#' "$RULES"  | grep -v '^[[:space:]]*$'; }
shapes() { grep -v '^[[:space:]]*#' "$SHAPES" | grep -v '^[[:space:]]*$'; }

# One file against every rule. Prints a line per hit; returns 1 if any hit.
scan_file() {
  local f="$1" hit=0 line pattern reason
  while IFS= read -r line; do
    pattern="${line%%:::*}"; reason="${line#*:::}"
    while IFS= read -r found; do
      [ -z "$found" ] && continue
      printf '  %s:%s\n      %s\n      matched /%s/ — %s\n' \
             "$f" "${found%%:*}" "$(printf '%s' "${found#*:}" | cut -c1-100)" \
             "$pattern" "$reason"
      hit=1
    done < <(grep -nEi -- "$pattern" "$f" || true)
  done < <(rules)

  while IFS= read -r line; do
    local terms="${line%%:::*}" all=1 term
    reason="${line#*:::}"
    IFS='+' read -ra parts <<< "$terms"
    for term in "${parts[@]}"; do
      term="$(printf '%s' "$term" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
      grep -qEi -- "$term" "$f" || { all=0; break; }
    done
    if [ "$all" = 1 ]; then
      printf '  %s\n      the whole page: %s\n      all of: %s\n' "$f" "$reason" "$terms"
      hit=1
    fi
  done < <(shapes)
  return $hit
}

if [ "${1:-}" = "--rules" ]; then
  printf 'names refused (%s):\n\n' "$RULES"
  rules  | while IFS= read -r l; do printf '  %-58s %s\n' "${l%%:::*}" "${l#*:::}"; done
  printf '\nshapes refused (%s):\n\n' "$SHAPES"
  shapes | while IFS= read -r l; do printf '  %-58s %s\n' "${l%%:::*}" "${l#*:::}"; done
  exit 0
fi

# ── the check's own test ─────────────────────────────────────────────────────
# Each bad-*.txt must be caught, each good-*.txt must not, and every rule must
# be the reason some bad-*.txt was caught. The last of those is what stops a
# rule from being quietly wrong: a regex with a typo catches nothing, passes
# every scan, and looks exactly like a rule that is working.
echo "== the check, checked =="
selftest=0
covered=$(mktemp); trap 'rm -f "$covered"' EXIT

for f in "$FIXTURES"/bad-*.txt; do
  [ -e "$f" ] || { echo "  no bad fixtures at all — the rules are unproven"; exit 2; }
  out=$(scan_file "$f" || true)
  if [ -z "$out" ]; then
    printf '  MISS  %s should have been caught and was not\n' "$f"
    selftest=1
  else
    printf '  caught  %-42s %d rule(s)\n' "$(basename "$f")" \
           "$(printf '%s' "$out" | grep -c 'matched /\|all of:')"
    printf '%s\n' "$out" | sed -n 's/.*matched \/\(.*\)\/ — .*/\1/p'  >> "$covered"
    printf '%s\n' "$out" | sed -n 's/^      all of: //p'              >> "$covered"
  fi
done

for f in "$FIXTURES"/good-*.txt; do
  [ -e "$f" ] || break
  out=$(scan_file "$f" || true)
  if [ -n "$out" ]; then
    printf '  FALSE POSITIVE  %s is innocent and was flagged:\n%s\n' "$f" "$out"
    selftest=1
  else
    printf '  cleared %s\n' "$(basename "$f")"
  fi
done

while IFS= read -r line; do
  pattern="${line%%:::*}"
  grep -qxF -- "$pattern" "$covered" || {
    printf '  UNPROVEN  no fixture is caught by /%s/ — write one, or the rule is decoration\n' \
           "$pattern"
    selftest=1
  }
done < <(rules; shapes)

[ "$selftest" = 0 ] || { echo; echo "the check itself is broken. Nothing was scanned."; exit 2; }

# ── the site's shape ─────────────────────────────────────────────────────────
echo
node docs-check/structure.mjs docs || exit 1

# ── the scan ─────────────────────────────────────────────────────────────────
echo
echo "== the site =="
n=0; leaked=0
while IFS= read -r f; do
  n=$((n + 1))
  out=$(scan_file "$f" || true)
  if [ -n "$out" ]; then leaked=1; printf '%s\n' "$out"; fi
done < <(site_files)

echo
if [ "$leaked" = 0 ]; then
  printf '%d files, nothing given away.\n' "$n"
  exit 0
fi
printf '%d files, and the manual gives an assignment away. Rewrite the page —\n' "$n"
printf 'losim is teachable without either of them, which is the point of the rules.\n'
exit 1
