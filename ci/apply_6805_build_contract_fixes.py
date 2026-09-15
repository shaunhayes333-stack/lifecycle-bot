from pathlib import Path

GOLDEN = Path("lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt")
PATCH_ROT = Path("lifecycle_apk/ci/patch_rot_scan.py")
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

version = ROOT_VERSION.read_text().strip()
if version != "5.0.6805":
    raise SystemExit(f"unexpected root AATE_VERSION after 6805 repair: {version}")
GRADLE_VERSION.write_text(version + "\n")

print("6805 build contract normalization applied")
