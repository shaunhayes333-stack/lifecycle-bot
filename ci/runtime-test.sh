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
# V5.0.6549 §RUNTIME_SMOKE_GRADLE_FLAKE_FIX — the smoke workflow was
# consistently failing with "Downloading from https://services.gradle.org
# /distributions/gradle-8.7-bin.zip failed: timeout (10000ms)". Emulator
# step gets a shorter default network budget than the build workflow.
# Mirror the build.yml retry-with-backoff loop so a single transient
# 504/timeout on the gradle CDN no longer kills the smoke test.
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
$GRADLE_CMD assembleDebug --no-daemon --stacktrace -PbuildNumber="${GITHUB_RUN_NUMBER:-0}"
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

# V5.0.6516a — persisted-device startup pressure. The old smoke always
# uninstalled/cleaned the app, so BotService.onCreate() never saw the large
# histories that fatal-ANR'd the operator's real install. Seed the exact
# canonical SharedPreferences schema at its hard cap before launching.
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

echo "::group::V5.0.6517 — UI-only Start → Stop → Start-again acceptance"
# Receiver performs DEBUG-only PIN setup and opens MainActivity, but MUST NOT
# start BotService. Every runtime command below comes from a real btnToggle tap.
# V5.0.6549b — the receiver-initiated startActivity() gets refused by
# Android 10+ background-activity-start restrictions on the smoke
# emulator (ui_start_1.xml consistently captured the LAUNCHER rather
# than MainActivity, so btnToggle was never resolvable). Fix: keep the
# broadcast for PIN/paper-mode SharedPreferences setup, but launch
# MainActivity directly via `adb shell am start` — adb shell has the
# START_ACTIVITIES_FROM_BACKGROUND privilege and can open exported=false
# activities in the same package.
adb shell am broadcast \
    -a com.lifecyclebot.aate.SMOKE_AUTOSTART \
    -n com.lifecyclebot.aate/com.lifecyclebot.engine.SmokeTestReceiver \
    --ez paper true \
    --ez open_main false \
    --ez start_service false
sleep 2
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
    [ -n "$coords" ] || { echo "::error::UI target missing/disabled mode=$mode value=$value dump=$dump"; return 1; }
    echo "UI tap mode=$mode value=$value coords=$coords"
    adb shell input tap $coords
}

wait_log_marker() {
    local marker="$1" timeout="$2" label="$3"
    local deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if adb logcat -d | grep -q "$marker"; then
            echo "$label marker reached: $marker"
            return 0
        fi
        sleep 2
    done
    echo "::error::$label timed out waiting for $marker"
    return 1
}

# First real UI Start from a cold service + max persisted state.
ui_tap id btnToggle ui_start_1.xml
wait_log_marker "UI_RUNTIME_TOGGLE_TAP_6517" 20 "first UI tap"
wait_log_marker "SERVICE_BOOTSTRAP_READY_6516" 180 "persisted bootstrap"
wait_log_marker "BOT_LOOP_TICK" 60 "first runtime loop"

# Real UI Stop, including the confirmation dialog.
sleep 3
ui_tap id btnToggle ui_stop_button.xml
sleep 1
ui_tap text "Stop bot" ui_stop_confirm.xml
wait_log_marker "LIFECYCLE_STOP_COMPLETE" 90 "confirmed UI stop"
FIRST_UI_TAPS=$(adb logcat -d | grep -c "UI_RUNTIME_TOGGLE_TAP_6517" || true)
FIRST_STARTS=$(adb logcat -d | grep -c "UI_START_DISPATCHED_6517" || true)
FIRST_STOPS=$(adb logcat -d | grep -c "LIFECYCLE_STOP_COMPLETE" || true)
adb logcat -d -v time > "$WS/ui_first_cycle_logcat.txt"

# Second real UI Start proves Stop did not poison the latch or listener.
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
FN_UI_TAP=$(  grep -c "UI_RUNTIME_TOGGLE_TAP_6517" "$WS/logcat_full.txt" || true)
FN_UI_START=$(grep -c "UI_START_DISPATCHED_6517" "$WS/logcat_full.txt" || true)
FN_UI_STOP=$( grep -c "LIFECYCLE_STOP_COMPLETE" "$WS/logcat_full.txt" || true)
# V5.0.6549 §END_TO_END_TRADE_PROCESSING_PROOF — operator directive:
# "ensure trades are actually processing end to end". The prior smoke
# summary stopped at LANE_EVAL, so it could not tell an execution
# stall (76 EXEC_OPEN_ALLOWED → 0 committed, per V5.0.6547 forensic)
# from a healthy pipeline. These counters expose the ownership +
# commit path introduced in V5.0.6548:
#   PAPER_TICKET_RESUMED_6548 — same immutable attemptId picked back
#     up after a nonterminal defer (proves ownership survives).
#   PAPER_TICKET_RETRY_PENDING_6548 — defer stamped into per-mint slot.
#   PAPER_TICKET_COMMITTED_6548 — terminal OPEN, i.e. paper buy did
#     commit end-to-end (fill lot + cash mutation + journal row).
#   PAPER_BUY_OK — legacy 6488 confirmation counter.
#   PAPER_SELL_OK — legacy sell-side counterpart.
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
# V5.0.6516a — hard persisted-start gates. A clean emulator launch is not
# sufficient: require both bootstrap barriers, a living process, and an active loop.
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
if [ "$FN_CANON_READY" -lt 1 ] || [ "$FN_SERVICE_READY" -lt 1 ] || [ -z "$PID_ALIVE" ] ||    [ "$FN_PROCESS_DEATH" -gt 0 ] || [ "$FN_ANR" -gt 0 ] || [ "$FN_VERIFY_ERROR" -gt 0 ] || [ "$FN_LOOP" -lt 1 ]; then
    echo "::error::Persisted-state Start failed: canonical=$FN_CANON_READY service=$FN_SERVICE_READY pid=${PID_ALIVE:-NONE} deaths=$FN_PROCESS_DEATH anr=$FN_ANR verify=$FN_VERIFY_ERROR loop=$FN_LOOP"
    exit 1
fi
echo "Persisted UI Start/Stop PASS: 8192 events, Start → Stop → Start, process alive, second loop active"
echo "::endgroup::"
