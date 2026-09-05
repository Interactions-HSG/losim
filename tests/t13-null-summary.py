"""Reads the runs t13-null.sh made and reports the distribution of their spreads.

Each group is four runs watched identically, so a spread inside one cannot have
been caused by telemetry. That distribution is what t13's statistic does when
nothing is moving it, and it is the evidence a defensible bound would be set from.
"""
import collections
import glob
import json
import re
import sys

groups = collections.defaultdict(dict)
for path in glob.glob(sys.argv[1] + "/g*-r*.json"):
    found = re.search(r"g(\d+)-r(\d+)\.json$", path)
    try:
        with open(path) as f:
            beta = json.load(f)["meta"]["scale"]["laws"]["allocMb"]["beta"]
        groups[int(found.group(1))][int(found.group(2))] = beta
    except Exception:
        # A run that did not fit a plan is not a sample. It is counted by its
        # absence below rather than folded in as though it were a measurement.
        pass

spreads = sorted(max(v.values()) - min(v.values()) for v in groups.values() if len(v) == 4)
partial = sum(1 for v in groups.values() if len(v) != 4)
if not spreads:
    print("  no complete group of four: nothing to say")
    sys.exit(0)

n = len(spreads)
print("  %d telemetry-free spreads: min %.4f  median %.4f  max %.4f"
      % (n, spreads[0], spreads[n // 2], spreads[-1]))
print("  at or over t13's 0.05 bound: %d of %d" % (sum(1 for s in spreads if s >= 0.05), n))
if partial:
    print("  %d group(s) incomplete and not counted" % partial)
