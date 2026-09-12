#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import os
import re
import runpy

ROOT = Path(__file__).resolve().parents[2]
base = ROOT / "lifecycle_apk/ci/apply_verified_recovery_6743.py"

# The first validator deliberately stopped after discovering that a SECOND
# provenance-name bypass exists in economicNotionalCheck6537(). All earlier
# edits happen before that self-audit, so retain them and remove the second
# contradictory bypass explicitly here.
try:
    runpy.run_path(str(base), run_name="__main__")
except SystemExit as exc:
    msg = str(exc)
    expected = "6743 self-audit: source-name economic invariant bypass still present"
    if expected not in msg:
        raise
    print("6743 v2: first-pass audit exposed second runtime invariant bypass; repairing it")

qia_rel = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/QuantityInvariantAuthority6500.kt"
qia_path = ROOT / qia_rel
qia = qia_path.read_text()
pattern = re.compile(
    r'''        // These legacy/carry sources are explicitly SOL/token or otherwise\n'''
    r'''        // lack a proven USD/token unit\. They may preserve inventory, but this\n'''
    r'''        // USD-notional invariant cannot classify them\.\n'''
    r'''        if \(src\.contains\("DERIVED_CARRY_COST_QTY_6631"\) \|\|\n'''
    r'''            src\.contains\("DURABLE_CARRY_COST_QTY_REPAIR_6519"\) \|\|\n'''
    r'''            src\.contains\("REPLAY_CARRY"\) \|\| src\.contains\("RECOVERED_CARRY"\) \|\|\n'''
    r'''            src\.contains\("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"\) \|\|\n'''
    r'''            src\.contains\("DERIVED_FROM_COST"\)\n'''
    r'''        \) return null\n'''
)
qia2, n = pattern.subn(
    '''        // V5.0.6743 §RUNTIME_NO_PROVENANCE_BYPASS — if entryPrice is\n'''
    '''        // positive it is claiming a USD/token basis and must pass the\n'''
    '''        // physical notional test. Unknown basis is represented as zero\n'''
    '''        // before this function, never as a positive exempted value.\n''',
    qia,
    count=1,
)
if n != 1:
    raise SystemExit(f"6743 v2 expected one economicNotionalCheck6537 provenance bypass, got {n}")
qia_path.write_text(qia2)

# Complete source-level contract audit after BOTH invariant bypasses are gone.
CPA = ROOT / "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt"
CPR = ROOT / "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt"
RTR = ROOT / "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRoundTripReconciler6738.kt"
PLG = ROOT / "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/PaperLedgerDivergenceGuard6731.kt"

cpa = CPA.read_text(); qia = qia_path.read_text(); cpr = CPR.read_text(); rt = RTR.read_text(); guard = PLG.read_text()
if "entryCostSol / qtyToken6631" in cpa or "OPEN_POSITION_DERIVED_FROM_COST_QTY_6631" in cpa:
    raise SystemExit("6743 v2 audit: SOL/token -> USD/token producer remains")
if "carry_or_derived_basis_skipped" in qia or "OPEN_POSITION_DERIVED_FROM_COST_QTY_6631" in qia:
    raise SystemExit("6743 v2 audit: provenance-name economic invariant bypass remains")
compare = cpr.split("fun compareToLedger(", 1)[1].split("fun lastSnapshot()", 1)[0]
if "establishReplayCarry6489(" in compare or "reconcileReplayCarry6498(" in compare:
    raise SystemExit("6743 v2 audit: parity verifier still mutates replay carry")
if "PaperCapitalAuthority6577.snapshot()" not in compare:
    raise SystemExit("6743 v2 audit: parity compare is not a single atomic ledger read")
if "st.buyAtMs > 0L && st.exitAtMs > 0L && st.sellAtMs > 0L && st.learningAtMs > 0L" not in rt:
    raise SystemExit("6743 v2 audit: round trip does not require all four stages")
if "if (st.divergenceReason.isNotBlank())" not in rt:
    raise SystemExit("6743 v2 audit: divergence is not sticky")
if 'return Verdict(false, "PAPER_LEDGER_PARITY_UNAVAILABLE_6743"' not in guard:
    raise SystemExit("6743 v2 audit: missing parity still authorizes new admission")

paths = [
    "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt",
    qia_rel,
    "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt",
    "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/PaperLedgerDivergenceGuard6731.kt",
    "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRoundTripReconciler6738.kt",
    "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6631InvariantBrokenPurgeCoverageTest.kt",
    "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6733LedgerParityAndSkewTest.kt",
    "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6738RoundTripAndExitRecoveryTest.kt",
    "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6742RevisionParityAndRoundTripWiringTest.kt",
    "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6743SourceAuthorityCompletionTest.kt",
    "AATE_VERSION",
    "lifecycle_apk/AATE_VERSION",
]
for rel in paths:
    if not (ROOT / rel).exists():
        raise SystemExit(f"6743 v2 audit: expected output missing: {rel}")

outdir = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "aate-6743"
outdir.mkdir(parents=True, exist_ok=True)
manifest = []
for rel in paths:
    b = (ROOT / rel).read_bytes()
    manifest.append({"path": rel, "sha256": hashlib.sha256(b).hexdigest()})
(outdir / "manifest.json").write_text(json.dumps({"files": manifest}, indent=2))
(outdir / "paths.txt").write_text("\n".join(paths) + "\n")
print(f"V5.0.6743 v2 staged and audited {len(paths)} production/test/version files")
