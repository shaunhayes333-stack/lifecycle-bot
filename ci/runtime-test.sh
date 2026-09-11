#!/usr/bin/env bash

# Runtime smoke: real UI Start/Stop/Start with persisted history.
# Liveness does not replace the separate canonical acceptance-window gate.
set -euo pipefail

CAPTURE_SECONDS="${CAPTURE_SECONDS:-180}"
WS="${GITHUB_WORKSPACE:-$(pwd)}"

python3 -m unittest discover -s "$WS/ci" -p "test_runtime_liveness.py" -v

cd lifecycle_apk

echo "::group::Build debug APK"
chmod +x gradlew || true
mkdir -p gradle/wrapper
curl -sL --retry 5 --retry-delay 15 --retry-connrefused -o gradle/wrapper/gradle-wrapper.jar \
  "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"
export GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=60000 -Dorg.gradle.internal.http.socketTimeout=60000 ${GRADLE_OPTS:-}"
GRADLE_CMD="./gradlew"
for i in 1 2 3 4 5; do
  if ./gradlew --version --no-daemon; then
    GRADLE_CMD="./gradlew"
    break
  fi
  echo "Gradle wrapper bootstrap attempt $i failed; retrying in $((i*15))s..."
  sleep $((i*15))
done
if ! ./gradlew --version --no-daemon; then
  echo "Gradle wrapper unavailable (missing/bad gradle-wrapper.jar or CDN outage); falling back to system gradle"
  GRADLE_CMD="gradle"
  gradle --version
fi
PRODUCTION_VERSION="$(tr -d '[:space:]' < ../AATE_VERSION)"
[[ "$PRODUCTION_VERSION" =~ ^5[.]0[.]([0-9]+)$ ]] || { echo "Invalid source version"; exit 1; }
$GRADLE_CMD assembleDebug --no-daemon --stacktrace -PbuildNumber="${BASH_REMATCH[1]}" -PaateVersionName="$PRODUCTION_VERSION"
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
echo "APK=$APK"
[ -n "$APK" ] || { echo "No APK produced"; exit 1; }
echo "::endgroup::"

echo "::group::Wait for emulator boot"
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
sleep 5
adb devices
echo "::endgroup::"

echo "::group::Install APK + grant runtime perms"
adb uninstall com.lifecyclebot.aate || true
adb install -r -t "$APK"
DEVICE_SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "${DEVICE_SDK:-0}" -ge 33 ]; then
  adb shell pm grant com.lifecyclebot.aate android.permission.POST_NOTIFICATIONS || true
fi
adb shell appops set com.lifecyclebot.aate RUN_IN_BACKGROUND allow || true
echo "::endgroup::"

echo "::group::Seed max persisted canonical history (8192 valid events)"
SEED_XML="$WS/canonical_economic_events_6486.xml"
python3 - "$SEED_XML" <<'PYSEED'
import json, sys, xml.etree.ElementTree as ET
out = sys.argv[1]
root = ET.Element("map")
base = 1777071600000
for i in range(4096):
    mint = f"PersistedMint{i:032d}"
    pid = f"persisted-position-{i}"
    qty = "1000000000"
    buy_key = f"seed_buy_{i}"
    sell_key = f"seed_sell_{i}"
    buy = {
        "type":"BUY", "atMs":base + i*2, "mode":"paper",
        "positionId":pid, "mint":mint, "symbol":f"P{i}",
        "idempotencyKey":buy_key, "executedCostSol":0.001,
        "entryFeesSol":0.000001, "filledQty":qty,
        "fillPrice":0.000000000001, "tokenDecimals":9, "quantityScale":9,
    }
    sell = {
        "type":"SELL", "atMs":base + i*2 + 1, "mode":"paper",
        "positionId":pid, "mint":mint, "symbol":f"P{i}",
        "idempotencyKey":sell_key, "partial":False, "soldQty":qty,
        "allocatedCostBasisSol":0.001, "grossProceedsSol":0.0011,
        "exitFeesSol":0.000001, "netProceedsSol":0.001099,
        "realizedPnlSol":0.0001, "realizedReturnPct":10.0,
        "remainingQty":"0", "remainingCostBasisSol":0.0,
    }
    ET.SubElement(root, "string", {"name":f"event:paper:{buy_key}"}).text = json.dumps(buy, separators=(",",":"))
    ET.SubElement(root, "string", {"name":f"event:paper:{sell_key}"}).text = json.dumps(sell, separators=(",",":"))
ET.ElementTree(root).write(out, encoding="utf-8", xml_declaration=True)
print(f"seeded_events=8192 file={out}")
PYSEED
adb push "$SEED_XML" /data/local/tmp/canonical_economic_events_6486.xml
adb shell chmod 644 /data/local/tmp/canonical_economic_events_6486.xml
adb shell run-as com.lifecyclebot.aate mkdir -p shared_prefs
adb shell run-as com.lifecyclebot.aate cp /data/local/tmp/canonical_economic_events_6486.xml shared_prefs/canonical_economic_events_6486.xml
adb shell rm -f /data/local/tmp/canonical_economic_events_6486.xml
SEED_COUNT=$(adb shell run-as com.lifecyclebot.aate grep -o 'event:paper:' shared_prefs/canonical_economic_events_6486.xml | wc -l | tr -d '\r ')
echo "Persisted canonical seed count=$SEED_COUNT"
[ "$SEED_COUNT" = "8192" ] || { echo "::error::Persisted seed incomplete: $SEED_COUNT"; exit 1; }
echo "::endgroup::"

echo "::group::Clear logcat + launch LAUNCHER activity"
adb logcat -c
adb shell monkey -p com.lifecyclebot.aate -c android.intent.category.LAUNCHER 1 || true
sleep 4
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null || true
adb pull /sdcard/ui.xml "$WS/ui_dump.xml" || true
echo "::endgroup::"

echo "::group::V5.0.6517 — UI-only Start → Stop → Start-again acceptance"
adb shell am broadcast \
    -a com.lifecyclebot.aate.SMOKE_AUTOSTART \
    -n com.lifecyclebot.aate/com.lifecyclebot.engine.SmokeTestReceiver \
    --ez paper true \
    --ez open_main false \
    --ez start_service false
sleep 2
adb shell am force-stop com.lifecyclebot.aate
sleep 1
adb shell am start -n com.lifecyclebot.aate/com.lifecyclebot.ui.MainActivity \
    --activity-clear-top --activity-single-top
sleep 5
adb shell uiautomator dump /sdcard/ui_after_launch.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_after_launch.xml "$WS/ui_after_launch.xml" >/dev/null 2>&1 || true

ui_tap() {
    local mode="$1" value="$2" dump="$3"
    adb shell uiautomator dump "/sdcard/$dump" >/dev/null 2>&1 || true
    adb pull "/sdcard/$dump" "$WS/$dump" >/dev/null 2>&1 || true
    local coords
    coords=$(python3 - "$WS/$dump" "$mode" "$value" <<'PYTAP'
import re, sys, xml.etree.ElementTree as ET
path, mode, value = sys.argv[1:]
try:
    root = ET.parse(path).getroot()
except Exception:
    raise SystemExit(1)
for node in root.iter("node"):
    attr = node.attrib.get("resource-id", "") if mode == "id" else node.attrib.get("text", "")
    match = attr.endswith(value) if mode == "id" else attr.lower() == value.lower()
    if not match or node.attrib.get("enabled", "true") != "true":
        continue
    nums = [int(x) for x in re.findall(r"\d+", node.attrib.get("bounds", ""))]
    if len(nums) == 4:
        print((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)
        raise SystemExit(0)
raise SystemExit(1)
PYTAP
) || true
    if [ -z "$coords" ]; then
        echo "::error::UI target missing/disabled mode=$mode value=$value dump=$dump"
        adb logcat -d -v time > "$WS/logcat_full.txt" || true
        adb shell dumpsys activity activities > "$WS/activity_dump.txt" || true
        adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' || true
        return 1
    fi
    echo "UI tap mode=$mode value=$value coords=$coords"
    adb shell input tap $coords
}

wait_log_marker() {
    local marker="$1" timeout="$2" label="$3"
    local deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        adb logcat -d -v time > "$WS/runtime_marker_probe.txt" || return 1
        if python3 "$WS/ci/runtime_liveness.py" has-marker "$WS/runtime_marker_probe.txt" "$marker"; then
            echo "$label marker reached: $marker"
            return 0
        fi
        sleep 2
    done
    echo "::error::$label timed out waiting for $marker"
    adb logcat -d -v time > "$WS/logcat_full.txt" || true
    adb shell dumpsys activity activities > "$WS/activity_dump.txt" || true
    adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' || true
    echo "=== timeout diagnostics: AATE lifecycle/bootstrap tail ==="
    adb logcat -d -v time | grep -E \
        'AATE|BotService|AndroidRuntime|FATAL EXCEPTION|ANR|UI_START|LIFECYCLE_START|SERVICE_BOOTSTRAP|CANONICAL_BOOTSTRAP|START_DEFERRED|START_RESUMED' \
        | tail -n 240 || true
    return 1
}

wait_log_marker_any() {
    local markers="$1" timeout="$2" label="$3"
    local deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        adb logcat -d -v time > "$WS/runtime_marker_probe.txt" || return 1
        if python3 "$WS/ci/runtime_liveness.py" has-marker "$WS/runtime_marker_probe.txt" "$markers"; then
            echo "$label proof reached: $markers"
            return 0
        fi
        sleep 2
    done
    echo "::error::$label timed out waiting for proof $markers"
    adb logcat -d -v time > "$WS/logcat_full.txt" || true
    adb shell dumpsys activity activities > "$WS/activity_dump.txt" || true
    return 1
}

if grep -q 'text="I AGREE"' "$WS/ui_after_launch.xml" 2>/dev/null; then
    echo "First-run risk disclaimer detected; accepting through the UI"
    ui_tap text "I AGREE" ui_disclaimer_accept.xml
    sleep 2
    adb shell uiautomator dump /sdcard/ui_post_disclaimer.xml >/dev/null 2>&1 || true
    adb pull /sdcard/ui_post_disclaimer.xml "$WS/ui_post_disclaimer.xml" >/dev/null 2>&1 || true
    if grep -q 'text="I AGREE"' "$WS/ui_post_disclaimer.xml" 2>/dev/null; then
        echo "::error::Risk disclaimer remained open after acceptance"
        exit 1
    fi
fi

ui_tap id btnToggle ui_start_1.xml
wait_log_marker "UI_RUNTIME_TOGGLE_TAP_6517" 20 "first UI tap"
wait_log_marker "UI_START_DISPATCHED_6517" 20 "first UI dispatch"
wait_log_marker_any "SERVICE_BOOTSTRAP_READY_6516|BOT_LOOP_TICK" 360 "persisted bootstrap"
FIRST_BOOTSTRAP_CONFIRMED=1
wait_log_marker "BOT_LOOP_TICK" 60 "first runtime loop"

adb shell uiautomator dump /sdcard/ui_post_start_system.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_post_start_system.xml "$WS/ui_post_start_system.xml" >/dev/null 2>&1 || true
if grep -q 'package="com.android.settings"' "$WS/ui_post_start_system.xml" 2>/dev/null &&
   grep -q 'text="Let app always run in background?"' "$WS/ui_post_start_system.xml" 2>/dev/null; then
    echo "Android background-run permission prompt detected; accepting through the platform UI"
    ui_tap text "Allow" ui_background_allow.xml
    sleep 2
fi

sleep 3
ui_tap id btnToggle ui_stop_button.xml
sleep 1
ui_tap text "Stop bot" ui_stop_confirm.xml
wait_log_marker "LIFECYCLE_STOP_COMPLETE" 90 "confirmed UI stop"
FIRST_UI_TAPS=$(adb logcat -d | grep -c "UI_RUNTIME_TOGGLE_TAP_6517" || true)
FIRST_STARTS=$(adb logcat -d | grep -c "UI_START_DISPATCHED_6517" || true)
FIRST_STOPS=$(adb logcat -d | grep -c "LIFECYCLE_STOP_COMPLETE" || true)
adb logcat -d -v time > "$WS/ui_first_cycle_logcat.txt"

adb logcat -c
sleep 3
ui_tap id btnToggle ui_start_2.xml
wait_log_marker "UI_RUNTIME_TOGGLE_TAP_6517" 20 "second UI tap"
wait_log_marker "UI_START_DISPATCHED_6517" 20 "second UI dispatch"
wait_log_marker "BOT_LOOP_TICK" 90 "second runtime loop"
echo "::endgroup::"

echo "::group::Capture logcat for ${CAPTURE_SECONDS}s after second UI Start"
adb logcat -v time > "$WS/logcat_second_start.txt" &
LOGCAT_PID=$!
sleep "$CAPTURE_SECONDS"
kill "$LOGCAT_PID" || true
sleep 2
cat "$WS/ui_first_cycle_logcat.txt" "$WS/logcat_second_start.txt" > "$WS/logcat_full.txt"
echo "::endgroup::"

echo "::group::Filter logcat to forensic + trader lines"
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
FN_INTAKE=$(grep -c "INTAKE\]"      "$WS/logcat_full.txt" || true)
FN_SAFETY=$(grep -c "SAFETY\]"      "$WS/logcat_full.txt" || true)
FN_V3=$(    grep -c "V3\]"          "$WS/logcat_full.txt" || true)
FN_LANE=$(  grep -c "LANE_EVAL\]"   "$WS/logcat_full.txt" || true)
FN_NOPAIR=$(grep -c "NO_PAIR_NO_FALLBACK" "$WS/logcat_full.txt" || true)
FN_BUY=$(   grep -cE "EXECUTE|DynScan EXECUTE|paperBuy|liveBuy" "$WS/logcat_full.txt" || true)
FN_SELL=$(  grep -cE "liveSell|paperSell|EXIT_FILLED" "$WS/logcat_full.txt" || true)
FN_LOOP=$(python3 "$WS/ci/runtime_liveness.py" count-loop "$WS/logcat_full.txt")
FN_SCANCB=$(  grep -c "SCAN_CB"       "$WS/logcat_full.txt" || true)
FN_JRNL=$(    grep -c "TRADEJRNL_REC" "$WS/logcat_full.txt" || true)
FN_SMOKE=$(   grep -c "SMOKE_AUTOSTART" "$WS/logcat_full.txt" || true)
FN_UI_TAP=$(  grep -c "UI_RUNTIME_TOGGLE_TAP_6517" "$WS/logcat_full.txt" || true)
FN_UI_START=$(grep -c "UI_START_DISPATCHED_6517" "$WS/logcat_full.txt" || true)
FN_UI_STOP=$( grep -c "LIFECYCLE_STOP_COMPLETE" "$WS/logcat_full.txt" || true)
FN_TICKET_DISPATCHED=$(grep -c "PAPER_TICKET_DISPATCHED_6514"  "$WS/logcat_full.txt" || true)
FN_TICKET_RESUMED=$(   grep -c "PAPER_TICKET_RESUMED_6548"     "$WS/logcat_full.txt" || true)
FN_TICKET_RETRY=$(     grep -c "PAPER_TICKET_RETRY_PENDING_6548" "$WS/logcat_full.txt" || true)
FN_TICKET_COMMITTED=$( grep -c "PAPER_TICKET_COMMITTED_6548"   "$WS/logcat_full.txt" || true)
FN_PAPER_BUY_OK=$(     grep -c "PAPER_BUY_OK"                  "$WS/logcat_full.txt" || true)
FN_PAPER_SELL_OK=$(    grep -c "PAPER_SELL_OK"                 "$WS/logcat_full.txt" || true)
FN_MARK_BLOCK_WS=$(    grep -c "SOURCE_NOT_WHITELISTED:DEXSCREENER" "$WS/logcat_full.txt" || true)
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
  UI_TOGGLE_TAPS:        $FN_UI_TAP    (must prove Start → Stop → Start)
  UI_START_DISPATCHES:   $FN_UI_START  (must be ≥2)
  UI_STOP_COMPLETES:     $FN_UI_STOP   (must be ≥1)
===== V5.0.6548 execution-commit path (end-to-end trade proof) =====
  PAPER_TICKET_DISPATCHED_6514:    $FN_TICKET_DISPATCHED
  PAPER_TICKET_RETRY_PENDING_6548: $FN_TICKET_RETRY       (defers that keep ownership)
  PAPER_TICKET_RESUMED_6548:       $FN_TICKET_RESUMED     (same attemptId re-picked)
  PAPER_TICKET_COMMITTED_6548:     $FN_TICKET_COMMITTED   (terminal OPEN, cash mutated)
  PAPER_BUY_OK:                    $FN_PAPER_BUY_OK
  PAPER_SELL_OK:                   $FN_PAPER_SELL_OK
  SOURCE_NOT_WHITELISTED_DEX*:     $FN_MARK_BLOCK_WS      (must be ≈0 after 6548 P0-B)
===== Interpretation =====
  SMOKE=0             -> SmokeTestReceiver never fired (debuggable=false?)
  SMOKE>0 LOOP=0      -> receiver fired but BotService didn't enter botLoop
  LOOP>0 SCAN_CB=0    -> botLoop running but watchlist empty (no tokens yet)
  SCAN_CB>0 SAFETY=0  -> processTokenCycle running but rejecting all tokens early
  SAFETY=0 V3=0       -> processTokenCycle skipping or timing out
  V3>0 LANE_EVAL=0    -> V3 disabled or short-circuiting
  EXECUTE=0           -> all gates pass but Executor not invoked
  EXECUTE>0 JRNL=0    -> Executor running but TradeHistoryStore not writing
  DISPATCHED>0 COMMITTED=0 RETRY_PENDING>0 -> owned but async metadata missing
  DISPATCHED>0 COMMITTED=0 RETRY_PENDING=0 -> hard reject path — check terminal blocks
  DISPATCHED==COMMITTED (roughly) -> ✅ end-to-end paper buy pipeline is committing
SUMMARY
FN_CANON_READY=$(grep -c "CANONICAL_BOOTSTRAP_READY_6515" "$WS/logcat_full.txt" || true)
FN_SERVICE_READY=$(grep -c "SERVICE_BOOTSTRAP_READY_6516" "$WS/logcat_full.txt" || true)
FN_PROCESS_DEATH=$(grep -c "Process: com.lifecyclebot.aate" "$WS/logcat_full.txt" || true)
FN_ANR=$(grep -c "ANR in com.lifecyclebot.aate" "$WS/logcat_full.txt" || true)
FN_VERIFY_ERROR=$(grep -cE "VerifyError|Verifier rejected" "$WS/logcat_full.txt" || true)
PID_ALIVE=$(adb shell pidof com.lifecyclebot.aate | tr -d '\r' || true)
cat >> "$WS/funnel_summary.txt" <<PERSISTED
===== Persisted-state startup gate (8192 events) =====
  CANONICAL_BOOTSTRAP_READY_6515: $FN_CANON_READY
  SERVICE_BOOTSTRAP_READY_6516:   $FN_SERVICE_READY
  PROCESS_PID_AT_END:             ${PID_ALIVE:-NONE}
  PROCESS_DEATH_MARKERS:          $FN_PROCESS_DEATH
  ANR_MARKERS:                    $FN_ANR
  VERIFY_ERROR_MARKERS:           $FN_VERIFY_ERROR
PERSISTED
cat "$WS/funnel_summary.txt"
if [ "${FIRST_BOOTSTRAP_CONFIRMED:-0}" -ne 1 ] || [ -z "$PID_ALIVE" ] || [ "$FN_PROCESS_DEATH" -gt 0 ] || [ "$FN_ANR" -gt 0 ] || [ "$FN_VERIFY_ERROR" -gt 0 ] || [ "$FN_LOOP" -lt 1 ]; then
    echo "::error::Persisted-state Start failed: canonical=$FN_CANON_READY service=$FN_SERVICE_READY pid=${PID_ALIVE:-NONE} deaths=$FN_PROCESS_DEATH anr=$FN_ANR verify=$FN_VERIFY_ERROR loop=$FN_LOOP"
    exit 1
fi
echo "Persisted UI Start/Stop PASS: 8192 events, Start → Stop → Start, process alive, second loop active"
echo "::endgroup::"
