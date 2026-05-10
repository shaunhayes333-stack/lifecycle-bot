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
adb install -r -t "$APK"
adb shell pm grant com.lifecyclebot.aate android.permission.POST_NOTIFICATIONS || true
adb shell pm grant com.lifecyclebot.aate android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant com.lifecyclebot.aate android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell appops set com.lifecyclebot.aate RUN_IN_BACKGROUND allow || true
echo "::endgroup::"

echo "::group::Clear logcat + launch MainActivity"
adb logcat -c
adb shell am start -W -n com.lifecyclebot.aate/.ui.MainActivity
sleep 3
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null || true
adb pull /sdcard/ui.xml "$WS/ui_dump.xml" || true
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
# Counts of each forensic phase. Use plain ASCII tags inside the grep so
# the regex doesn't depend on emoji width.
FN_INTAKE=$(grep -c "INTAKE\]" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
FN_SAFETY=$(grep -c "SAFETY\]" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
FN_V3=$(grep -c "V3\]" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
FN_LANE=$(grep -c "LANE_EVAL\]" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
FN_NOPAIR=$(grep -c "NO_PAIR_NO_FALLBACK" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
FN_BUY=$(grep -cE "EXECUTE|DynScan EXECUTE|paperBuy|liveBuy" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
FN_SELL=$(grep -cE "liveSell|paperSell|EXIT_FILLED" "$WS/logcat_full.txt" 2>/dev/null || echo 0)
cat > "$WS/funnel_summary.txt" <<SUMMARY
===== Pipeline funnel (after ${CAPTURE_SECONDS}s capture) =====
  INTAKE:                $FN_INTAKE
  SAFETY:                $FN_SAFETY
  V3:                    $FN_V3
  LANE_EVAL:             $FN_LANE
  NO_PAIR_NO_FALLBACK:   $FN_NOPAIR
  EXECUTE/BUY:           $FN_BUY
  SELL:                  $FN_SELL
===== Interpretation =====
  INTAKE >0 SAFETY=0  -> tokens reach intake but never enter scan loop
  SAFETY=0 V3=0       -> processTokenCycle skipping or timing out
  V3 >0  LANE_EVAL=0  -> V3 disabled or short-circuiting
  NO_PAIR_NO_FALLBACK -> V5.9.656 fast-path failed, no price seed
  EXECUTE=0           -> all gates pass but Executor not invoked
SUMMARY
cat "$WS/funnel_summary.txt"
echo "::endgroup::"
