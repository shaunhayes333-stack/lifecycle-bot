from pathlib import Path

GOLDEN = Path("lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt")
AATE_TEST = Path("lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6805CausalIntegrityRepairTest.kt")
PATCH_ROT = Path("lifecycle_apk/ci/patch_rot_scan.py")
SPINE = Path("lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt")
ROOT_VERSION = Path("AATE_VERSION")
GRADLE_VERSION = Path("lifecycle_apk/AATE_VERSION")

text = GOLDEN.read_text()
old = 'val smoke = java.io.File("../ci/runtime-test.sh").readText()'
new = 'val smoke = java.io.File("../../ci/runtime-test.sh").readText()'
if old not in text and new not in text:
    raise SystemExit("6805 Golden Tape runtime smoke path anchor missing")
if old in text:
    text = text.replace(old, new, 1)
GOLDEN.write_text(text)

# The additional-authority generator emits this source-pin assertion inside a
# Kotlin string. Python consumes the generator's backslashes, so normalize the
# generated Kotlin to retain escaped inner quotes before Kotlin compilation.
test_text = AATE_TEST.read_text()
bad_assert = '        assertTrue(causal.contains("terminalScopeKeys6805 = ks.filter { it.startsWith("BAND|") }"))'
good_assert = '        assertTrue(causal.contains("terminalScopeKeys6805 = ks.filter { it.startsWith(\\"BAND|\\") }"))'
if bad_assert in test_text:
    test_text = test_text.replace(bad_assert, good_assert, 1)
elif good_assert not in test_text:
    raise SystemExit("6805 causal source-pin assertion anchor missing")
AATE_TEST.write_text(test_text)

# V5.0.6805 retires journal/forensic replay as an account authority. The
# patch-rot guard must protect the replacement architecture rather than require
# the retired reconciliation read to return.
rot = PATCH_ROT.read_text()
old_rot = '    require(errors, unified, "ForensicReconciliation6635.reconcile6635()", "UNIFIED_ACCOUNT_OBSERVATION_6678")'
new_rot = '''    forbid(errors, unified, "ForensicReconciliation6635.reconcile6635()", "UNIFIED_ACCOUNT_REPLAY_AUTHORITY_RETIRED_6805")
    forbid(errors, unified, "JournalEconomicAuthority6616.currentSnapshot()", "UNIFIED_ACCOUNT_JOURNAL_AUTHORITY_RETIRED_6805")
    require(errors, unified, "CanonicalCapitalAuthority6450.snapshot()", "UNIFIED_ACCOUNT_CANONICAL_CAPITAL_AUTHORITY_6805")'''
if old_rot in rot:
    rot = rot.replace(old_rot, new_rot, 1)
elif new_rot not in rot:
    raise SystemExit("6805 patch-rot unified-account anchor missing")
PATCH_ROT.write_text(rot)

# The 6758 failure diagnostic used the old ForensicReconciliation snapshot even
# after the 6805 acceptance calculation was moved to canonical capital. That left
# an unresolved variable and, more importantly, preserved a replay dependency in
# the acceptance path. Report the already-captured canonical capital snapshot.
spine = SPINE.read_text()
old_diag = '''                    val forensicStr = forensic?.let {
                        "reconciled=${it.reconciled} cash=${it.cashSol} basis=${it.basisSol} realized=${it.realizedSol} qty=${it.quantityRaw}"
                    } ?: "reconciled=UNKNOWN"
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "EXECUTION_SPINE_ACCEPTANCE_6647_FAIL_DIAG_6758",
                        "windowStartMs=${start.atMs} durationMs=$duration failures=${result.failures.joinToString("|")} phantomBreakdown=[$laneBreakdown] forensic=$forensicStr openPositions=${canonicalOpenPositions.size} exitStart=${observation.exitStart} exitDone=${observation.exitDone}",
                    )'''
new_diag = '''                    val canonicalCapitalStr6805 = canonicalCapital6805?.let {
                        "source=CANONICAL_CAPITAL_AUTHORITY_6450 cash=${it.cashSol} openMV=${it.openMarketValueSol} realized=${it.realizedPnlSol} fees=${it.feesSol} equity=${it.totalEquitySol} delta=${it.conservationDeltaSol}"
                    } ?: "source=CANONICAL_CAPITAL_AUTHORITY_6450 status=UNAVAILABLE"
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "EXECUTION_SPINE_ACCEPTANCE_6647_FAIL_DIAG_6758",
                        "windowStartMs=${start.atMs} durationMs=$duration failures=${result.failures.joinToString("|")} phantomBreakdown=[$laneBreakdown] canonical=$canonicalCapitalStr6805 openPositions=${canonicalOpenPositions.size} exitStart=${observation.exitStart} exitDone=${observation.exitDone}",
                    )'''
if old_diag in spine:
    spine = spine.replace(old_diag, new_diag, 1)
elif new_diag not in spine:
    raise SystemExit("6805 acceptance diagnostic anchor missing")
if "forensic?.let" in spine:
    raise SystemExit("6805 acceptance still contains stale forensic variable reference")
SPINE.write_text(spine)

version = ROOT_VERSION.read_text().strip()
if version != "5.0.6805":
    raise SystemExit(f"unexpected root AATE_VERSION after 6805 repair: {version}")
GRADLE_VERSION.write_text(version + "\n")

print("6805 build contract normalization applied")
