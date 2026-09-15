from pathlib import Path

GOLDEN = Path("lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt")
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

version = ROOT_VERSION.read_text().strip()
if version != "5.0.6805":
    raise SystemExit(f"unexpected root AATE_VERSION after 6805 repair: {version}")
GRADLE_VERSION.write_text(version + "\n")

print("6805 build contract normalization applied")
