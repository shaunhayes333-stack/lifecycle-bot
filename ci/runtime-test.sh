#!/usr/bin/env bash
# V5.9.657 — Runtime Smoke Test on-emulator script.
# Called from .github/workflows/runtime-test.yml inside the
# reactivecircus/android-emulator-runner@v2 emulator-running step.
#
# The action runs each line of its `script:` parameter as a separate
# `sh -c` invocation, which breaks line continuations and loses `set -e`
# state. So all logic that needs persistent shell state lives here.

set -euo pipefail

CAPTURE_SECONDS="${CAPTURE_SECONDS:-180}"
WS="${GITHUB_WORKSPACE:-$(pwd)}"

cd lifecycle_apk

echo "::group::Build debug APK"
chmod +x gradlew || true
mkdir -p gradle/wrapper
curl -sL -o gradle/wrapper/gradle-wrapper.jar \
  "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"
./gradlew assembleDebug --no-daemon --stacktrace -PbuildNumber="${GITHUB_RUN_NUMBER:-0}"
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
echo "APK=$APK"
[ -n "$APK" ] || { echo "No APK produced"; exit 1; }
echo "::endgroup::"

echo "::group::Wait for emulator boot"
adb wait-for-device
# shellcheck disable=SC2016
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
sleep 5
adb devices
echo "::endgroup::"

echo "::group::Install APK + grant runtime perms"
# V5.9.657 — cached AVD may already have a com.lifecyclebot.aate
# install signed by a different debug keystore (previous CI run from
# a different runner image). adb install -r refuses to update across
# signature mismatches, so uninstall first. -k preserves data; we
# don't want that here (we want a clean slate every run anyway).
adb uninstall com.lifecyclebot.aate || true
adb install -r -t "$APK"
adb shell pm grant com.lifecyclebot.aate android.permission.POST_NOTIFICATIONS || true
adb shell pm grant com.lifecyclebot.aate android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant com.lifecyclebot.aate android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell appops set com.lifecyclebot.aate RUN_IN_BACKGROUND allow || true
echo "::endgroup::"

echo "::group::Clear logcat + launch LAUNCHER activity"
adb logcat -c
# V5.9.657 — first runtime-test run failed with:
#   "Activity class {com.lifecyclebot.aate/com.lifecyclebot.aate.ui.MainActivity}
#    does not exist."
# Two issues:
#   1. The kotlin namespace is `com.lifecyclebot` while applicationId is
#      `com.lifecyclebot.aate`. `am start -n PKG/.cls` uses PKG as the
#      class prefix (would yield com.lifecyclebot.aate.ui.MainActivity)
#      but the actual class lives under com.lifecyclebot.ui.MainActivity.
#   2. MainActivity is android:exported="false" — only SecurityActivity
#      has the MAIN/LAUNCHER intent-filter and exported=true. Use the
#      `monkey -c LAUNCHER` form so we always hit the LAUNCHER target
#      regardless of which class it points to.
adb shell monkey -p com.lifecyclebot.aate -c android.intent.category.LAUNCHER 1 || true
sleep 4
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null || true
adb pull /sdcard/ui.xml "$WS/ui_dump.xml" || true
echo "::endgroup::"

echo "::group::V5.9.661 — actually start the bot (smoke broadcast + UI fallback)"
# Path A (preferred): SmokeTestReceiver. Bypasses PIN + forces paper +
# starts BotService directly. Hard-guarded server-side on FLAG_DEBUGGABLE.
adb shell am broadcast \
    -a com.lifecyclebot.aate.SMOKE_AUTOSTART \
    -n com.lifecyclebot.aate/com.lifecyclebot.engine.SmokeTestReceiver \
    --ez paper true \
    || echo "::warning::SMOKE_AUTOSTART broadcast failed; falling back to UI tap"
sleep 3

# Path B (fallback): drive the UI. Re-dump the hierarchy and look for
# resource-id=btnToggle ('Start Bot' button on activity_main). If found,
# tap its center. Useful when (A) didn't deliver the broadcast (e.g.
# build wasn't debuggable on this runner image).
adb shell uiautomator dump /sdcard/ui2.xml 2>/dev/null || true
adb pull /sdcard/ui2.xml "$WS/ui_dump_after_broadcast.xml" || true
BTN_BOUNDS=$(grep -oE 'resource-id="[^"]*btnToggle"[^/]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$WS/ui_dump_after_broadcast.xml" 2>/dev/null \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 || true)
if [ -n "$BTN_BOUNDS" ]; then
    # bounds="[x1,y1][x2,y2]" — compute center.
    COORDS=$(echo "$BTN_BOUNDS" | grep -oE '[0-9]+')
    X1=$(echo "$COORDS" | sed -n '1p'); Y1=$(echo "$COORDS" | sed -n '2p')
    X2=$(echo "$COORDS" | sed -n '3p'); Y2=$(echo "$COORDS" | sed -n '4p')
    CX=$(( (X1 + X2) / 2 )); CY=$(( (Y1 + Y2) / 2 ))
    echo "btnToggle bounds=$BTN_BOUNDS  → tapping ($CX,$CY) as UI fallback"
    adb shell input tap "$CX" "$CY" || true
    sleep 2
else
    echo "btnToggle not in UI dump — staying with broadcast-only path"
fi
echo "::endgroup::"

echo "::group::Capture logcat for ${CAPTURE_SECONDS}s"
adb logcat -v time > "$WS/logcat_full.txt" &
LOGCAT_PID=$!
sleep "$CAPTURE_SECONDS"
kill "$LOGCAT_PID" || true
sleep 2
echo "::endgroup::"

echo "::group::Filter logcat to forensic + trader lines"
# Mirror the operator's exported-error-log filter shape.
grep -E "FORENSIC|BotService|FDG|FluidLearn|SAFETY|V3Engine|CryptoAlt|MemeT|ShitCoin|Moonshot|BlueChip|Quality|Treasury|Pump|Birdeye|Jupiter|Executor|TradeAuth|TokenLifecycle" \
  "$WS/logcat_full.txt" \
  > "$WS/logcat_filtered.txt" || true
wc -l "$WS/logcat_filtered.txt" || true
echo "=== first 60 lines of filtered ==="
head -n 60 "$WS/logcat_filtered.txt" || true
echo "=== last 60 lines of filtered ==="
tail -n 60 "$WS/logcat_filtered.txt" || true
echo "::endgroup::"

echo "::group::Pipeline funnel summary"
# V5.9.657 — counts of each forensic phase. `grep -c` exits 1 when zero
# matches but still prints "0", so `... || echo 0` would emit "0\n0".
# Use `|| true` to swallow the non-zero exit and keep grep's own "0".
FN_INTAKE=$(grep -c "INTAKE\]"      "$WS/logcat_full.txt" || true)
FN_SAFETY=$(grep -c "SAFETY\]"      "$WS/logcat_full.txt" || true)
FN_V3=$(    grep -c "V3\]"          "$WS/logcat_full.txt" || true)
FN_LANE=$(  grep -c "LANE_EVAL\]"   "$WS/logcat_full.txt" || true)
FN_NOPAIR=$(grep -c "NO_PAIR_NO_FALLBACK" "$WS/logcat_full.txt" || true)
FN_BUY=$(   grep -cE "EXECUTE|DynScan EXECUTE|paperBuy|liveBuy" "$WS/logcat_full.txt" || true)
FN_SELL=$(  grep -cE "liveSell|paperSell|EXIT_FILLED" "$WS/logcat_full.txt" || true)
# V5.9.661 — heartbeats added for the new operator-facing markers so
# we can tell the loop is actually running (not just that the APK
# launched). BOT_LOOP_TICK proves botLoop is iterating; SCAN_CB
# proves processTokenCycle is being called; TRADEJRNL_REC proves
# Executor is journalling. SMOKE proves the receiver fired.
FN_LOOP=$(    grep -c "BOT_LOOP_TICK" "$WS/logcat_full.txt" || true)
FN_SCANCB=$(  grep -c "SCAN_CB"       "$WS/logcat_full.txt" || true)
FN_JRNL=$(    grep -c "TRADEJRNL_REC" "$WS/logcat_full.txt" || true)
FN_SMOKE=$(   grep -c "SMOKE_AUTOSTART" "$WS/logcat_full.txt" || true)
cat > "$WS/funnel_summary.txt" <<SUMMARY
===== Pipeline funnel (after ${CAPTURE_SECONDS}s capture) =====
  SMOKE_AUTOSTART:       $FN_SMOKE     (V5.9.661 receiver hits — should be ≥1)
  BOT_LOOP_TICK:         $FN_LOOP      (botLoop iterations — should grow over time)
  SCAN_CB enter:         $FN_SCANCB    (processTokenCycle invocations)
  INTAKE:                $FN_INTAKE
  SAFETY:                $FN_SAFETY
  V3:                    $FN_V3
  LANE_EVAL:             $FN_LANE
  NO_PAIR_NO_FALLBACK:   $FN_NOPAIR
  EXECUTE/BUY:           $FN_BUY
  SELL:                  $FN_SELL
  TRADEJRNL_REC:         $FN_JRNL      (V5.9.658 journal write hits)
===== Interpretation =====
  SMOKE=0             -> SmokeTestReceiver never fired (debuggable=false?)
  SMOKE>0 LOOP=0      -> receiver fired but BotService didn't enter botLoop
  LOOP>0 SCAN_CB=0    -> botLoop running but watchlist empty (no tokens yet)
  SCAN_CB>0 SAFETY=0  -> processTokenCycle running but rejecting all tokens early
  SAFETY=0 V3=0       -> processTokenCycle skipping or timing out
  V3>0 LANE_EVAL=0    -> V3 disabled or short-circuiting
  EXECUTE=0           -> all gates pass but Executor not invoked
  EXECUTE>0 JRNL=0    -> Executor running but TradeHistoryStore not writing
SUMMARY
cat "$WS/funnel_summary.txt"
echo "::endgroup::"
