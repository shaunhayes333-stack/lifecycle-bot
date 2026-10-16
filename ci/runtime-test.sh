#!/usr/bin/env bash

# Runtime smoke: real UI Start/Stop/Start with persisted history.
# Liveness does not replace the separate canonical acceptance-window gate.
set -euo pipefail

CAPTURE_SECONDS="${CAPTURE_SECONDS:-180}"
WS="${GITHUB_WORKSPACE:-$(pwd)}"
LOGCAT_PID=""
CURRENT_RUNTIME_LOG=""
capture_runtime_log8077() {
    if [ -n "$LOGCAT_PID" ]; then kill "$LOGCAT_PID" 2>/dev/null || true; wait "$LOGCAT_PID" 2>/dev/null || true; fi
    CURRENT_RUNTIME_LOG="$1"
    adb logcat -v time > "$CURRENT_RUNTIME_LOG" &
    LOGCAT_PID=$!
}
read_runtime_log8077() {
    # Stream from before each UI action so Android's chatty/ring pruning cannot
    # erase a short-lived lifecycle receipt between polling reads.
    if [ -n "$CURRENT_RUNTIME_LOG" ]; then cat "$CURRENT_RUNTIME_LOG"; else adb logcat -d -v time; fi
}

# A subprocess can fail before a wait helper emits its timeout summary. The
# EXIT witness always reports the last observed startup stage on a red smoke
# step, without making partial startup count as a passing execution window.
runtime_failure_witness_7437() {
    local result=$?
    if [ -n "$LOGCAT_PID" ]; then kill "$LOGCAT_PID" 2>/dev/null || true; wait "$LOGCAT_PID" 2>/dev/null || true; fi
    if [ "$result" -ne 0 ]; then
        if [ -f "$WS/ui_first_cycle_logcat.txt" ]; then
            cat "$WS/ui_first_cycle_logcat.txt" > "$WS/logcat_full.txt"
            if [ -f "$WS/logcat_second_start.txt" ]; then cat "$WS/logcat_second_start.txt" >> "$WS/logcat_full.txt"; fi
        fi
        # Preserve actual blocked-thread stacks for a failed restart. This runs
        # only after failure and cannot turn an incomplete window into a pass.
        local failed_pid
        failed_pid=$(adb shell pidof com.lifecyclebot.aate 2>/dev/null | tr -d '\r') || true
        if [[ "${failed_pid:-}" =~ ^[0-9]+$ ]]; then
            timeout 10s adb shell run-as com.lifecyclebot.aate kill -3 "$failed_pid" || true
            sleep 2
            timeout 10s adb root >/dev/null 2>&1 || true
            timeout 10s adb wait-for-device || true
            timeout 10s adb pull /data/anr "$WS/runtime_anr" >/dev/null 2>&1 || true
        fi
        python3 - "$WS/logcat_full.txt" <<'PYWITNESS'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
stage_path = path.with_name("bootstrap_stage_witness.log")
text = (stage_path.read_text(errors="replace") if stage_path.exists() else "") + "\n" + (path.read_text(errors="replace") if path.exists() else "")
def seen(marker): return marker in text
def last(pattern):
    matches = re.findall(pattern, text)
    return matches[-1] if matches else "none"
print("::error::BOOTSTRAP_WITNESS_7437 "
      f"canonicalReady={seen('CANONICAL_BOOTSTRAP_READY_6515')} "
      f"canonicalPhase={last(r'CANONICAL_BOOTSTRAP_PHASE_7438 phase=([A-Z0-9_]+)')} "
      f"onCreatePhase={last(r'SERVICE_ONCREATE_PHASE_7438 phase=([A-Z0-9_]+)')} "
      f"canonicalFailure={last(r'CANONICAL_BOOTSTRAP_FAILED_6515.*?type=([A-Za-z0-9_]+)')} "
      f"lastServicePhase={last(r'SERVICE_BOOTSTRAP_PHASE_6516.*?phase=([A-Z0-9_]+)')} "
      f"serviceFailure={last(r'(?<![A-Z_])SERVICE_BOOTSTRAP_FAILED_6516\s+type=([A-Za-z0-9_]+)')} "
      f"processDeath={seen('Process: com.lifecyclebot.aate')} "
      f"uiStart={seen('UI_START_DISPATCHED_6517')}")
PYWITNESS
        # V5.0.8144 — the rejected class, the failing/stalled step and the slowest steps.
        python3 "$WS/ci/runtime_bootstrap_witness.py" annotate "$WS/bootstrap_stage_witness.log" "$WS/logcat_full.txt" || true
    fi
}
trap runtime_failure_witness_7437 EXIT

python3 -m unittest discover -s "$WS/ci" -p "test_runtime_liveness.py" -v
python3 -m unittest discover -s "$WS/ci" -p "test_runtime_bootstrap_witness.py" -v

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
# V5.0.7453 — same single authority as Gradle/build.yml.
PRODUCTION_VERSION="$(tr -d '[:space:]' < AATE_VERSION)"
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
        "fillPrice":0.12, "tokenDecimals":9, "quantityScale":9,
        "lane":"CRYPTO_ALT", "assetClassTag":"CRYPTO_ALT",
    }
    sell = {
        "type":"SELL", "atMs":base + i*2 + 1, "mode":"paper",
        "positionId":pid, "mint":mint, "symbol":f"P{i}",
        "idempotencyKey":sell_key, "partial":False, "soldQty":qty,
        "allocatedCostBasisSol":0.001, "grossProceedsSol":0.0011,
        "exitFeesSol":0.000001, "netProceedsSol":0.001099,
        "realizedPnlSol":0.0001, "realizedReturnPct":10.0,
        "remainingQty":"0", "remainingCostBasisSol":0.0,
        "exitPriceUsd":0.132, "solUsdAtExit":120.0,
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
# Set this before the first Activity/Application launch, not just the later
# debug receiver. All trader and canonical learning paths remain exercised;
# the synthetic fixture is local and must not upload into the shared hive.
python3 - "$WS/runtime_bot_config.xml" <<'PYCONFIG'
import sys, xml.etree.ElementTree as ET
root = ET.Element("map")
ET.SubElement(root, "boolean", name="paper_mode", value="true")
ET.SubElement(root, "boolean", name="collective_learning_enabled", value="false")
ET.ElementTree(root).write(sys.argv[1], encoding="utf-8", xml_declaration=True)
PYCONFIG
adb push "$WS/runtime_bot_config.xml" /data/local/tmp/runtime_bot_config.xml
adb shell chmod 644 /data/local/tmp/runtime_bot_config.xml
adb shell run-as com.lifecyclebot.aate cp /data/local/tmp/runtime_bot_config.xml shared_prefs/bot_config.xml
adb shell rm -f /data/local/tmp/runtime_bot_config.xml
echo "::endgroup::"

echo "::group::Clear logcat + launch LAUNCHER activity"
adb logcat -c
capture_runtime_log8077 "$WS/ui_first_cycle_logcat.txt"
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

# V5.0.7168 — one dump was one chance, and it has been losing.
#
# Runtime Smoke Test has failed on every build from 5.0.7155 to 5.0.7166 at
# exactly the same line:
#
#   ##[error]UI target missing/disabled mode=id value=btnToggle dump=ui_start_1.xml
#
# btnToggle is not missing. MainActivity:2396 still does findViewById(R.id
# .btnToggle) and :2432 sets isEnabled = true. What is missing is patience:
# this function took ONE uiautomator dump, and both `dump` and `pull` are
# `|| true`, so a dump that was slow, raced the first frame, or failed
# outright left an empty or stale file and the parse found nothing. The run
# then reports the app has no start button.
#
# The cost of that is the whole gate. Every assertion after this line —
# start, stop, start again, first BOT_LOOP_TICK, and the receipt-derived
# paper-buy evidence the job exits on — never ran. Twelve builds shipped with
# the project's only end-to-end runtime proof dark, reporting a UI fault that
# does not exist.
#
# Poll instead of guessing a settle time: re-dump until the target appears or
# the deadline passes. A genuinely missing button still fails, just after
# ~30s of asking rather than once. And when it does fail, print the dump so
# the next failure is diagnosable from the log instead of the artifact.
UI_TAP_TIMEOUT_S_7168=30

ui_tap() {
    local mode="$1" value="$2" dump="$3"
    local deadline=$((SECONDS + UI_TAP_TIMEOUT_S_7168))
    local coords=""
    local attempts=0
    while :; do
        attempts=$((attempts + 1))
        adb shell uiautomator dump "/sdcard/$dump" >/dev/null 2>&1 || true
        adb pull "/sdcard/$dump" "$WS/$dump" >/dev/null 2>&1 || true
        coords=$(ui_find_target_7168 "$WS/$dump" "$mode" "$value") || coords=""
        [ -n "$coords" ] && break
        [ "$SECONDS" -ge "$deadline" ] && break
        sleep 2
    done
    if [ -z "$coords" ]; then
        echo "::error::UI target missing/disabled mode=$mode value=$value dump=$dump"
        echo "=== ui_tap gave up after $attempts dumps over ${UI_TAP_TIMEOUT_S_7168}s ==="
        echo "=== last dump ($WS/$dump) head ==="
        head -c 4000 "$WS/$dump" 2>/dev/null || echo "(no dump file)"
        echo ""
        read_runtime_log8077 > "$WS/logcat_full.txt" || true
        adb shell dumpsys activity activities > "$WS/activity_dump.txt" || true
        adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' || true
        return 1
    fi
    echo "UI tap mode=$mode value=$value coords=$coords attempts=$attempts"
    adb shell input tap $coords
}

ui_find_target_7168() {
    python3 - "$1" "$2" "$3" <<'PYTAP'
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
}

RUNTIME_SINCE_ARGS=()
wait_log_marker() {
    local marker="$1" timeout="$2" label="$3"
    local deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        read_runtime_log8077 > "$WS/runtime_marker_probe.txt" || return 1
        if python3 "$WS/ci/runtime_liveness.py" has-marker "$WS/runtime_marker_probe.txt" "$marker" "${RUNTIME_SINCE_ARGS[@]}"; then
            echo "$label marker reached: $marker"
            return 0
        fi
        sleep 2
    done
    echo "::error::$label timed out waiting for $marker"
    read_runtime_log8077 > "$WS/logcat_full.txt" || true
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
        read_runtime_log8077 > "$WS/runtime_marker_probe.txt" || return 1
        # The Android ring buffer may evict the earliest startup line during the
        # 360-second persisted-history acceptance. Preserve only phase evidence
        # from every poll before another read can overwrite it.
        grep -E 'SERVICE_ONCREATE_PHASE_7438|CANONICAL_BOOTSTRAP_PHASE_7438|CANONICAL_BOOTSTRAP_(READY|FAILED)_6515|SERVICE_BOOTSTRAP_(PHASE|READY|FAILED)_6516|UI_START_DISPATCHED_6517|Process: com.lifecyclebot.aate|ANR in com.lifecyclebot.aate' \
            "$WS/runtime_marker_probe.txt" | tail -n 60 >> "$WS/bootstrap_stage_witness.log" || true
        # V5.0.8144 — the verifier's own text and the step witnesses, which the
        # phase grep above dropped: they name the class and the call, not a phase.
        grep -E 'SERVICE_BOOTSTRAP_(STEP|STALL|FAILED_STEP|SLOWEST)_8144|AATE_BOOT_FAIL_7855|Verifier rejected class|java\.lang\.VerifyError' \
            "$WS/runtime_marker_probe.txt" | tail -n 80 >> "$WS/bootstrap_stage_witness.log" || true
        if python3 "$WS/ci/runtime_liveness.py" has-marker "$WS/runtime_marker_probe.txt" "$markers"; then
            echo "$label proof reached: $markers"
            return 0
        fi
        # V5.0.8144 — a service bootstrap that FAILED after this Start never becomes
        # ready; report it now instead of waiting out the window. Still a red run.
        if python3 "$WS/ci/runtime_bootstrap_witness.py" failed "$WS/runtime_marker_probe.txt"; then
            echo "::error::$label service bootstrap failed terminally; not waiting out the timeout"
            break
        fi
        sleep 2
    done
    echo "::error::$label timed out waiting for proof $markers"
    read_runtime_log8077 > "$WS/logcat_full.txt" || true
    adb shell dumpsys activity activities > "$WS/activity_dump.txt" || true
    # Preserve the last reached bootstrap stage in a check-run annotation.
    # Artifacts and job logs may be inaccessible to remote diagnosis, but this
    # fail-closed stage witness identifies the real startup blocker without
    # counting a partial bootstrap as a passing execution window.
    local canonical_stage service_stage canonical_failed service_failed
    local stage_history="$WS/bootstrap_stage_witness.log"
    [ -s "$stage_history" ] || stage_history="$WS/logcat_full.txt"
    canonical_stage=$(grep 'CANONICAL_BOOTSTRAP_READY_6515' "$stage_history" | tail -1 | cut -c1-180 || true)
    canonical_failed=$(grep 'CANONICAL_BOOTSTRAP_FAILED_6515' "$stage_history" | tail -1 | sed -n 's/.*type=\([A-Za-z0-9_]*\).*/\1/p' || true)
    service_stage=$(grep 'SERVICE_BOOTSTRAP_PHASE_6516' "$stage_history" | tail -1 | sed -n 's/.*phase=\([A-Z0-9_]*\).*/\1/p' || true)
    service_failed=$(grep -E '(^|[^A-Z_])SERVICE_BOOTSTRAP_FAILED_6516 +type=' "$stage_history" | tail -1 | sed -n 's/.*SERVICE_BOOTSTRAP_FAILED_6516 *type=\([A-Za-z0-9_]*\).*/\1/p' || true)
    local canonical_phase oncreate_phase
    canonical_phase=$(grep 'CANONICAL_BOOTSTRAP_PHASE_7438' "$stage_history" | tail -1 | sed -n 's/.*phase=\([A-Z0-9_]*\).*/\1/p' || true)
    oncreate_phase=$(grep 'SERVICE_ONCREATE_PHASE_7438' "$stage_history" | tail -1 | sed -n 's/.*phase=\([A-Z0-9_]*\).*/\1/p' || true)
    echo "::error title=BOOTSTRAP_STAGE_7435::canonicalReady=$([[ -n "$canonical_stage" ]] && echo yes || echo no) canonicalPhase=${canonical_phase:-none} onCreatePhase=${oncreate_phase:-none} canonicalFailure=${canonical_failed:-none} lastServicePhase=${service_stage:-none} serviceFailure=${service_failed:-none}"
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
FIRST_UI_TAPS=$(read_runtime_log8077 | grep -c "UI_RUNTIME_TOGGLE_TAP_6517" || true)
FIRST_STARTS=$(read_runtime_log8077 | grep -c "UI_START_DISPATCHED_6517" || true)
FIRST_STOPS=$(read_runtime_log8077 | grep -c "LIFECYCLE_STOP_COMPLETE" || true)


adb logcat -c
capture_runtime_log8077 "$WS/logcat_second_start.txt"
sleep 3
# logcat -c can leave earlier records in the subsequent read. A first-session
# loop must never certify that the restarted runtime has actually entered.
second_start_epoch=$(adb shell date +%s | tr -d '\r')
RUNTIME_SINCE_ARGS=(--since-epoch "$second_start_epoch")
ui_tap id btnToggle ui_start_2.xml
wait_log_marker "UI_RUNTIME_TOGGLE_TAP_6517" 20 "second UI tap"
wait_log_marker "UI_START_DISPATCHED_6517" 20 "second UI dispatch"
wait_log_marker "BOT_LOOP_TICK" 90 "second runtime loop"
echo "::endgroup::"

echo "::group::Capture current acceptance window after second UI Start"
# A 120-second window begins only after the service accepts the *second*
# start. Loading 8192 economic events can delay that boundary substantially:
# a fixed sleep from UI tap previously ended while the current window was
# only 19 seconds old (CI 38327788673). Never call that an acceptance result.
window_started_8081=0
for ((i=0; i<150; i++)); do
    if grep -q 'EXECUTION_SPINE_WINDOW_STARTED_6662' "$WS/logcat_second_start.txt"; then
        window_started_8081=1
        break
    fi
    sleep 2
done
if [ "$window_started_8081" -ne 1 ]; then
    echo "::error::ACCEPTANCE_WINDOW_NEVER_STARTED_8081 after second UI Start"
    exit 1
fi
echo "Current 120s acceptance window began; awaiting real evaluated verdict."
window_result_8081=0
# Allow the exact evaluation to finish, rather than trusting a fixed timer.
# A FAIL verdict is retained as evidence and rejected by runtime_evidence.py.
for ((i=0; i<150; i++)); do
    if grep -qE 'EXECUTION_SPINE_ACCEPTANCE_6647_(OK|FAIL)' "$WS/logcat_second_start.txt"; then
        window_result_8081=1
        break
    fi
    sleep 2
done
if [ "$window_result_8081" -ne 1 ]; then
    echo "::error::ACCEPTANCE_WINDOW_NO_COMPLETED_RESULT_8081 after 300s"
    exit 1
fi
kill "$LOGCAT_PID" || true
wait "$LOGCAT_PID" 2>/dev/null || true
LOGCAT_PID=""
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
