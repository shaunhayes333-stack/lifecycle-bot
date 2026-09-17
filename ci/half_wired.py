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


def called_externally(fn, owner_path, owner=None):
    # V5.0.6963 — a bare `fn(` match is not evidence that THIS object's fn is
    # called. SellQuantityBoundary6459.recordBuyFill was reported LIVE because
    # FillLotLedger6504.recordBuyFill exists and shares the name. Require the
    # owner qualifier (Owner.fn( or Owner\n  .fn() when we know the owner.
    if owner:
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
    wired, dead = [], []
    for fn in sorted(set(declared)):
        (wired if called_externally(fn, path, owner) else dead).append(fn)
    unwired_here = {f for _, f in entries}
    # half-wired = the object has live callers AND ledger entries that are dead
    if wired and (unwired_here & set(dead)):
        out.append((len(wired), relfile, entries[0][0], wired, sorted(unwired_here & set(dead))))

out.sort(reverse=True)
print(f"HALF-WIRED AUTHORITIES: {len(out)} objects\n")
for nw, relfile, owner, wired, dead in out:
    print(f"{owner}  ({relfile})")
    print(f"   LIVE ({nw}): {', '.join(wired[:6])}{' …' if len(wired) > 6 else ''}")
    print(f"   DEAD: {', '.join(dead)}")
    print()
