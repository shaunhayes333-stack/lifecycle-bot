from pathlib import Path

path = Path("lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt")
text = path.read_text()
old = '                mirror.contains("lastClosedPositionIdByMint") &&\n'
new = (
    '                mirror.contains("lastClosedPositionIdByModeMint") &&\n'
    '                mirror.contains("activePositionIdByModeMint") &&\n'
)
if old not in text:
    raise SystemExit("6686 GoldenTape stale mint-only identity assertion not found")
text = text.replace(old, new, 1)
path.write_text(text)
print("V5.0.6686 GoldenTape canonical identity contract updated to mode+mint")
