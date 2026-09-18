#!/usr/bin/env python3
"""Find HALF-WIRED authorities: objects where some public functions are called
externally and siblings are not.

Every genuine money bug found this session had this exact shape:
  6948  shouldSuppressSl wired, shouldSuppressTp/trailStopPctFromPeak dead
  6949  evaluateExit wired, its TIGHTEN_STOP verdict discarded
  6950  observeWalletBalance wired, canExpandRisk dead
A fully-dead object is usually unfinished work. A half-dead one is usually a
decision that silently does the opposite of what its author intended.
"""
import csv
import os
import re
import subprocess
from collections import defaultdict

REPO = "/home/user/lifecycle-bot"
SRC = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"
FN = re.compile(r"^\s{0,4}(?:internal\s+|public\s+)?fun\s+([a-zA-Z_][A-Za-z0-9_]*)\s*\(")

rows = list(csv.DictReader(open(os.path.join(REPO, "ci/UNWIRED_LEDGER.tsv")), delimiter="\t"))
major = [r for r in rows if r["tier"] in ("A_PREDICT", "B_RISK", "C_EXIT")]

# group unwired ledger entries by owning file
byfile = defaultdict(list)
for r in major:
    byfile[r["file"]].append((r["owner"], r["function"]))


# V5.0.6985 — THIRD FALSE VERDICT IN THIS DETECTOR, AND THE COSTLIEST SO FAR.
#
# BotBrain.effectiveExitThreshold was reported DEAD. It is not: V5.0.6954 wired
# it, and Executor.kt:15651 calls it on every exit-threshold read —
#
#     private fun brainAdjustedExitThreshold6954(rawThreshold: Double): Double {
#         val b = brain ?: return rawThreshold
#         return b.effectiveExitThreshold(rawThreshold)      <-- here
#
# The 6963 owner-qualifier rule looks for `BotBrain.effectiveExitThreshold(`.
# A call through an INSTANCE receiver carries the variable's name, not the
# type's, so nothing matched. The 6971 in-file scan does not help either —
# the call lives in a different file.
#
# That is three failure modes now: bare-name collisions gave false LIVE (6963),
# same-object calls gave false DEAD (6971), and instance receivers give false
# DEAD (here). Each one sent me to re-triage functions that were already wired.
#
# The fix keeps 6963's guarantee without its blind spot. A bare `fn(` match is
# only ambiguous when more than one object declares that name. So: count every
# `fun NAME(` declaration in the tree, and when a name is declared EXACTLY ONCE,
# an unqualified call to it anywhere is unambiguous evidence — no collision is
# possible by construction. Ambiguous names keep the strict owner-qualified
# rule. This is the pre-6963 behaviour, applied only where it cannot be wrong.
def _declaration_counts():
    counts = defaultdict(int)
    root = os.path.join(REPO, SRC)
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".kt"):
                continue
            try:
                src = open(os.path.join(dirpath, name), encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
            src = re.sub(r"//[^\n]*", "", src)
            for line in src.splitlines():
                m = re.match(r"\s*(?:override\s+|private\s+|internal\s+|public\s+|suspend\s+|inline\s+)*"
                             r"fun\s+(?:<[^>]*>\s*)?([a-zA-Z_][A-Za-z0-9_]*)\s*\(", line)
                if m:
                    counts[m.group(1)] += 1
    return counts


DECL_COUNTS = _declaration_counts()


def called_externally(fn, owner_path, owner=None):
    # V5.0.6963 — a bare `fn(` match is not evidence that THIS object's fn is
    # called. SellQuantityBoundary6459.recordBuyFill was reported LIVE because
    # FillLotLedger6504.recordBuyFill exists and shares the name. Require the
    # owner qualifier (Owner.fn( or Owner\n  .fn() when we know the owner.
    # V5.0.6985 — a globally unique function name cannot collide, so an
    # unqualified call to it is unambiguous and catches instance receivers
    # (`b.effectiveExitThreshold(...)`) that the owner-qualified pattern misses.
    if owner and DECL_COUNTS.get(fn, 0) > 1:
        pat = re.escape(owner) + r"\s*(?:\.|\?\.)\s*" + re.escape(fn) + r"\s*\("
    else:
        pat = r"(?<![A-Za-z0-9_])" + re.escape(fn) + r"\s*\("
    p = subprocess.run(
        ["grep", "-rlPz", pat, "--include=*.kt", SRC], cwd=REPO,
        capture_output=True, text=True,
    )
    if p.returncode not in (0, 1):
        raise SystemExit(f"grep failed for {fn}: {p.stderr[:200]}")
    files = [f for f in p.stdout.splitlines() if os.path.normpath(f) != owner_path]
    return len(files)


# V5.0.6971 — a function called only from INSIDE its own object is USED, just
# not as an external authority. Requiring the owner qualifier (6963) fixed
# name-collision false POSITIVES and created false NEGATIVES: a same-object call
# carries no `Owner.` prefix. AdaptiveLearningEngine.extractPatterns was reported
# dead while line 667 calls it on every 25th trade. Count in-file calls too, and
# report the three states separately.
CALL_IN_FILE = None


def called_in_file(fn, owner_path):
    """Non-declaration call sites for `fn` inside its own file."""
    try:
        src = open(os.path.join(REPO, owner_path), encoding="utf-8", errors="replace").read()
    except OSError:
        return 0
    # V5.0.6971 — strip comments first. HotPathLaneGate.mayProceedHotPath was
    # reported "used in file" on the strength of a KDoc line mentioning it.
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    hits = 0
    for line in src.splitlines():
        if re.search(r"\bfun\s+" + re.escape(fn) + r"\s*\(", line):
            continue  # the declaration itself
        if re.search(r"(?<![A-Za-z0-9_.])" + re.escape(fn) + r"\s*\(", line):
            hits += 1
    return hits


out = []
for relfile, entries in sorted(byfile.items()):
    path = os.path.normpath(os.path.join(SRC, relfile))
    full = os.path.join(REPO, path)
    if not os.path.exists(full):
        continue
    owner = entries[0][0]
    src = open(full, encoding="utf-8", errors="replace").read()
    declared = []
    for line in src.splitlines():
        m = FN.match(line)
        if m:
            declared.append(m.group(1))
    if not declared:
        continue
    wired, infile, dead = [], [], []
    for fn in sorted(set(declared)):
        if called_externally(fn, path, owner):
            wired.append(fn)
        elif called_in_file(fn, path):
            infile.append(fn)      # used internally — NOT inert
        else:
            dead.append(fn)        # genuinely uncalled anywhere
    unwired_here = {f for _, f in entries}
    trulyDead = unwired_here & set(dead)
    internal = unwired_here & set(infile)
    if wired and (trulyDead or internal):
        out.append((len(wired), relfile, entries[0][0], wired,
                    sorted(trulyDead), sorted(internal)))

out.sort(reverse=True)
print(f"HALF-WIRED AUTHORITIES: {len(out)} objects\n")
for nw, relfile, owner, wired, dead, internal in out:
    print(f"{owner}  ({relfile})")
    print(f"   LIVE ({nw}): {', '.join(wired[:6])}{' …' if len(wired) > 6 else ''}")
    if dead:
        print(f"   DEAD (no caller anywhere): {', '.join(dead)}")
    if internal:
        print(f"   IN-FILE ONLY (used, not an external authority): {', '.join(internal)}")
    print()
