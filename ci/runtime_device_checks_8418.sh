#!/usr/bin/env bash
# V5.0.8418 — device checks that run in the SAME emulator session right after
# ci/runtime-test.sh (which leaves the debug build running its second UI Start
# with 8192 persisted canonical events). Each check records PASS/FAIL with its
# evidence in $WS/device_checks_8418.json; the workflow's enforce step fails the
# run unless every check is PASS. Nothing here grants, fabricates or skips a
# receipt: verdicts come from the app's logcat lines, its UI hierarchy, its
# persisted files and Android's own dumpsys.
#
#   background_home   HOME, >=180 s: same pid, BotService still foreground, loop advancing
#   doze              battery unplug + deviceidle force-idle 60 s: not killed; loop resumes after unforce
#   screens           every app activity (am start; debug overlay exports them) renders its own
#                     layout ids / text, no crash/ANR, process survives
#   report_pre        Pipeline report (UI copy button -> cache file) carries the §8324 store and 8355 lines
#   restart           am force-stop -> relaunch -> UI Start: canonical bootstrap READY with
#                     duplicateMints=0, persisted events not reset, no attemptId dispatched twice,
#                     loop running, report shows the §8324 restore + 8355 shadow restore counters
set -uo pipefail

WS="${GITHUB_WORKSPACE:-$(pwd)}"
PKG=com.lifecyclebot.aate
OUT="$WS/device_checks_8418"
RESULTS="$WS/device_checks_8418.json"
SRC_MAIN="$WS/lifecycle_apk/app/src/main"
HELPER="$WS/ci/runtime_device_checks_8418.py"
LOG="$OUT/device_logcat.txt"
mkdir -p "$OUT"
rm -f "$RESULTS"
LOGCAT_PID=""
FAILED=0

h() { python3 "$HELPER" "$@"; }
record() {
    h record "$RESULTS" "$1" "$2" "$3"
    if [ "$2" = "PASS" ]; then echo "DEVICE_CHECK_8418 PASS $1: $3"; else FAILED=1; echo "::error title=Device check $1::$3"; fi
}
cleanup() {
    adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    if [ -n "$LOGCAT_PID" ]; then kill "$LOGCAT_PID" 2>/dev/null || true; wait "$LOGCAT_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT

# -T 1: only records written from now on; the ring buffer's older lines (earlier loop ticks)
# must never count as evidence produced after HOME / doze / force-stop.
start_capture() { adb logcat -v time -T 1 > "$LOG" & LOGCAT_PID=$!; sleep 2; }
mark() { wc -l < "$LOG" | tr -d ' '; }
since() { tail -n +"$(( $1 + 1 ))" "$LOG"; }
app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true; }
service_fg() {
    adb shell dumpsys activity services "$PKG/com.lifecyclebot.engine.BotService" > "$OUT/services.txt" 2>/dev/null || true
    grep -q 'ServiceRecord' "$OUT/services.txt" && grep -q 'isForeground=true' "$OUT/services.txt"
}
loops_since() { since "$1" > "$OUT/since.txt"; python3 "$WS/ci/runtime_liveness.py" count-loop "$OUT/since.txt"; }
wait_loops_since() {   # mark min timeout
    local deadline=$((SECONDS + $3)) n=0
    while [ "$SECONDS" -lt "$deadline" ]; do
        n=$(loops_since "$1"); [ "$n" -ge "$2" ] && { echo "$n"; return 0; }
        sleep 5
    done
    echo "$n"; return 1
}
wait_marker_since() {  # mark marker timeout
    local deadline=$((SECONDS + $3))
    while [ "$SECONDS" -lt "$deadline" ]; do
        since "$1" | grep -q "$2" && return 0
        sleep 3
    done
    return 1
}
dump_ui() {  # name
    adb shell uiautomator dump "/sdcard/$1" >/dev/null 2>&1 || true
    adb pull "/sdcard/$1" "$OUT/$1" >/dev/null 2>&1 || true
}
dismiss_system_dialogs() {  # dump file: accept Android's own background-run prompt, never an app dialog
    if grep -q 'text="Let app always run in background?"' "$1" 2>/dev/null; then
        local c; c=$(h find "$1" text "Allow") && { set -- $c; adb shell input tap "$1" "$2"; sleep 2; return 0; }
    fi
    return 1
}
crashes_since() { since "$1" > "$OUT/crash_probe.txt"; h crashes "$OUT/crash_probe.txt"; }

pipeline_report() {  # output-file -> 0 when a generated report carrying the §8324 store line was pulled
    # The 6308 watchdog may first write a bounded emergency snapshot and the full report
    # later; every file the app reports writing is read, newest last.
    local out="$1" attempt m path c deadline
    for attempt in 1 2 3; do
        adb shell am start -W -n "$PKG/com.lifecyclebot.ui.PipelineHealthActivity" >/dev/null 2>&1 || true
        sleep 6
        dump_ui pipeline_report.xml
        dismiss_system_dialogs "$OUT/pipeline_report.xml" && dump_ui pipeline_report.xml
        c=$(h find "$OUT/pipeline_report.xml" id copyButton) || { echo "report attempt $attempt: copyButton not rendered"; sleep 4; continue; }
        m=$(mark)
        set -- $c
        adb shell input tap "$1" "$2"
        deadline=$((SECONDS + 150))
        while [ "$SECONDS" -lt "$deadline" ]; do
            for path in $(since "$m" | grep -o 'PIPELINE_REPORT_FILE_WRITTEN_6401 *path=[^ ]*' | sed 's/.*path=//'); do
                if adb shell run-as "$PKG" cat "$path" > "$out.part" 2>/dev/null && [ -s "$out.part" ]; then
                    mv "$out.part" "$out"
                    if grep -q '§8324 restore without reset' "$out"; then adb shell input keyevent KEYCODE_BACK; return 0; fi
                fi
            done
            sleep 5
        done
        echo "report attempt $attempt: no generated report with the §8324 store line within 150s"
        adb shell input keyevent KEYCODE_BACK
        sleep 5
    done
    [ -s "$out" ]
}

ui_start_if_stopped() {  # tap Start through the real UI when the restored runtime did not auto-resume
    local deadline=$((SECONDS + 60)) c text
    while [ "$SECONDS" -lt "$deadline" ]; do
        dump_ui restart_main.xml
        dismiss_system_dialogs "$OUT/restart_main.xml" && continue
        if c=$(h find "$OUT/restart_main.xml" id btnToggle); then
            text=$(echo "$c" | cut -d' ' -f3- | tr '[:upper:]' '[:lower:]')
            case "$text" in
                *start*) set -- $c; adb shell input tap "$1" "$2"; echo "tapped"; return 0 ;;
                *stop*|*cancel*) echo "auto-resumed"; return 0 ;;
            esac
        fi
        sleep 3
    done
    echo "no-start-control"; return 1
}

# ------------------------------------------------------------------------------
PID0=$(app_pid)
if ! [[ "$PID0" =~ ^[0-9]+$ ]] || ! service_fg; then
    record precondition FAIL "runtime-test.sh did not leave the bot running (pid=${PID0:-none})"
    exit 1
fi
start_capture

echo "::group::V5.0.8418 background: HOME for >=180s"
M=$(mark)
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell dumpsys window | grep -E 'mCurrentFocus' > "$OUT/focus_home.txt" || true
bg_ok=1; bg_detail=""
for i in 1 2 3 4 5 6; do
    sleep 30
    pid=$(app_pid)
    if [ "$pid" != "$PID0" ]; then bg_ok=0; bg_detail="process changed/died at ${i}x30s pid=$PID0->${pid:-none}"; break; fi
    if ! service_fg; then bg_ok=0; bg_detail="BotService not foreground at ${i}x30s"; break; fi
done
if [ "$bg_ok" -eq 1 ]; then
    n=$(wait_loops_since "$M" 2 120) || { bg_ok=0; bg_detail="loop heartbeat stalled in background: $n loop records since HOME"; }
    [ "$bg_ok" -eq 1 ] && bg_detail="pid=$PID0 foreground service held 180s; $n loop records since HOME; focus=$(tr -d '\r' < "$OUT/focus_home.txt" | head -c 120)"
fi
cr=$(crashes_since "$M") || { bg_ok=0; bg_detail="crash/ANR in background: $cr"; }
record background_home "$([ $bg_ok -eq 1 ] && echo PASS || echo FAIL)" "$bg_detail"
echo "::endgroup::"

echo "::group::V5.0.8418 doze: deviceidle force-idle"
adb shell dumpsys battery unplug >/dev/null 2>&1 || true
adb shell dumpsys deviceidle enable deep > "$OUT/doze_enable.txt" 2>&1 || true
adb shell dumpsys deviceidle force-idle > "$OUT/doze_force.txt" 2>&1 || true
cat "$OUT/doze_force.txt"
dz_ok=1; dz_detail=""
if ! grep -qi 'idle' "$OUT/doze_force.txt" || grep -qi 'unable' "$OUT/doze_force.txt"; then
    dz_ok=0; dz_detail="device did not enter forced deep idle: $(tr -d '\r\n' < "$OUT/doze_force.txt" | head -c 200)"
fi
if [ "$dz_ok" -eq 1 ]; then
    sleep 60
    adb shell dumpsys deviceidle get deep > "$OUT/doze_state.txt" 2>&1 || true
    pid=$(app_pid)
    if [ "$pid" != "$PID0" ]; then dz_ok=0; dz_detail="process killed in doze pid=$PID0->${pid:-none}";
    elif ! service_fg; then dz_ok=0; dz_detail="BotService lost foreground in doze"; fi
fi
adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
adb shell dumpsys battery reset >/dev/null 2>&1 || true
if [ "$dz_ok" -eq 1 ]; then
    M=$(mark)
    n=$(wait_loops_since "$M" 1 150) && dz_detail="survived 60s forced deep idle (state=$(tr -d '\r\n' < "$OUT/doze_state.txt")), pid=$PID0, $n loop records after unforce" \
        || { dz_ok=0; dz_detail="loop did not resume within 150s after doze unforce"; }
fi
record doze "$([ $dz_ok -eq 1 ] && echo PASS || echo FAIL)" "$dz_detail"
echo "::endgroup::"

echo "::group::V5.0.8418 screens: open every activity"
mapfile -t SCREENS < <(python3 - "$SRC_MAIN/AndroidManifest.xml" <<'PY'
import re, sys
names = re.findall(r'<activity[^>]*?android:name="\.ui\.(\w+)"', open(sys.argv[1]).read(), re.S)
print("\n".join(dict.fromkeys(n for n in names)))
PY
)
sc_ok=1; sc_detail=""
for A in "${SCREENS[@]}"; do
    M=$(mark)
    adb shell am start -W -n "$PKG/com.lifecyclebot.ui.$A" > "$OUT/start_$A.txt" 2>&1 || true
    if grep -qE 'Error|Exception' "$OUT/start_$A.txt"; then
        sc_ok=0; sc_detail+="$A: am start failed ($(tr -d '\r\n' < "$OUT/start_$A.txt" | head -c 160)); "; continue
    fi
    verdict="not rendered"; passed=0
    for _ in 1 2 3 4 5 6 7 8; do
        sleep 2
        dump_ui "screen_$A.xml"
        dismiss_system_dialogs "$OUT/screen_$A.xml" && continue
        adb shell dumpsys window | grep -E 'mCurrentFocus' > "$OUT/focus_$A.txt" || true
        if verdict=$(h screen "$OUT/screen_$A.xml" "$OUT/focus_$A.txt" "$A" "$SRC_MAIN"); then passed=1; break; fi
    done
    cr=$(crashes_since "$M") || { passed=0; verdict="crash/ANR: $cr"; }
    pid=$(app_pid)
    [ "$pid" = "$PID0" ] || { passed=0; verdict="process died pid=$PID0->${pid:-none}"; }
    echo "screen $A: $([ $passed -eq 1 ] && echo PASS || echo FAIL) $verdict"
    if [ "$passed" -ne 1 ]; then sc_ok=0; sc_detail+="$A: $verdict; "; else sc_detail+="$A ok; "; fi
    adb shell input keyevent KEYCODE_BACK
    sleep 1
    [ "$pid" = "$PID0" ] || break
done
[ "${#SCREENS[@]}" -ge 10 ] || { sc_ok=0; sc_detail="only ${#SCREENS[@]} activities parsed from the manifest; $sc_detail"; }
record screens "$([ $sc_ok -eq 1 ] && echo PASS || echo FAIL)" "${#SCREENS[@]} screens: $sc_detail"
echo "::endgroup::"

echo "::group::V5.0.8418 pipeline report before restart"
if pipeline_report "$OUT/report_before_restart.txt" && d=$(h report "$OUT/report_before_restart.txt"); then
    record report_pre PASS "$d"
else
    record report_pre FAIL "$(h report "$OUT/report_before_restart.txt" 2>&1 | head -c 400)"
fi
echo "::endgroup::"

echo "::group::V5.0.8418 restart recovery: force-stop -> relaunch"
EV_BEFORE=$(adb shell run-as "$PKG" grep -o 'name=.event:' shared_prefs/canonical_economic_events_6486.xml 2>/dev/null | wc -l | tr -d '\r ')
LEARN_BEFORE=$(adb shell run-as "$PKG" stat -c %s databases/learning_kv.db 2>/dev/null | tr -d '\r' || true)
adb shell run-as "$PKG" ls -l shared_prefs databases > "$OUT/files_before_restart.txt" 2>&1 || true
cp "$WS/logcat_full.txt" "$OUT/log_before_restart.txt" 2>/dev/null || true
cat "$LOG" >> "$OUT/log_before_restart.txt"
adb shell am force-stop "$PKG"
sleep 3
rs_ok=1; rs_detail=""
[ -z "$(app_pid)" ] || { rs_ok=0; rs_detail="process survived force-stop; "; }
M=$(mark)
adb shell am start -n "$PKG/com.lifecyclebot.ui.MainActivity" >/dev/null 2>&1 || true
sleep 5
how=$(ui_start_if_stopped) || { rs_ok=0; rs_detail+="no Start control after relaunch; "; }
if wait_marker_since "$M" 'CANONICAL_BOOTSTRAP_READY_6515' 420; then
    since "$M" > "$OUT/log_after_restart.txt"
    boot=$(h bootstrap "$OUT/log_after_restart.txt") || boot=none
else
    boot=none; rs_ok=0; rs_detail+="CANONICAL_BOOTSTRAP_READY_6515 not reached in 420s; "
fi
n=$(wait_loops_since "$M" 1 180) || { rs_ok=0; rs_detail+="no bot loop after restart; "; }
since "$M" > "$OUT/log_after_restart.txt"
grep -q 'SERVICE_BOOTSTRAP_READY_6516' "$OUT/log_after_restart.txt" || { rs_ok=0; rs_detail+="SERVICE_BOOTSTRAP_READY_6516 missing; "; }
EV_AFTER=$(adb shell run-as "$PKG" grep -o 'name=.event:' shared_prefs/canonical_economic_events_6486.xml 2>/dev/null | wc -l | tr -d '\r ')
LEARN_AFTER=$(adb shell run-as "$PKG" stat -c %s databases/learning_kv.db 2>/dev/null | tr -d '\r' || true)
adb shell run-as "$PKG" ls -l shared_prefs databases > "$OUT/files_after_restart.txt" 2>&1 || true
floor=$(( EV_BEFORE < 8192 ? EV_BEFORE : 8192 ))
if [ "$boot" = none ]; then rs_ok=0; rs_detail+="no bootstrap receipt; "
else
    dup=$(echo "$boot" | python3 -c 'import json,sys; print(json.load(sys.stdin)["duplicateMints"])')
    bev=$(echo "$boot" | python3 -c 'import json,sys; print(json.load(sys.stdin)["events"])')
    [ "$dup" = "0" ] || { rs_ok=0; rs_detail+="duplicateMints=$dup after restore; "; }
    [ "$bev" -ge "$floor" ] || { rs_ok=0; rs_detail+="bootstrap restored $bev events < $floor persisted before restart; "; }
fi
[ "${EV_AFTER:-0}" -ge "$floor" ] || { rs_ok=0; rs_detail+="persisted canonical events reset: before=$EV_BEFORE after=$EV_AFTER; "; }
if [[ "${LEARN_BEFORE:-}" =~ ^[0-9]+$ ]]; then
    [[ "${LEARN_AFTER:-}" =~ ^[0-9]+$ ]] || { rs_ok=0; rs_detail+="learning_kv.db vanished after restart; "; }
fi
dd=$(h dup-dispatch "$OUT/log_before_restart.txt" "$OUT/log_after_restart.txt") || { rs_ok=0; rs_detail+="attemptId dispatched again after restart: $dd; "; }
cr=$(crashes_since "$M") || { rs_ok=0; rs_detail+="crash/ANR after restart: $cr; "; }
if pipeline_report "$OUT/report_after_restart.txt" && rd=$(h report "$OUT/report_after_restart.txt" --require-restore); then :; else
    rs_ok=0; rd="$(h report "$OUT/report_after_restart.txt" --require-restore 2>&1 | head -c 300)"; rs_detail+="report: $rd; "
fi
pid=$(app_pid)
[[ "$pid" =~ ^[0-9]+$ ]] || { rs_ok=0; rs_detail+="process not alive at end; "; }
record restart "$([ $rs_ok -eq 1 ] && echo PASS || echo FAIL)" \
    "${rs_detail}start=$how boot=$boot persistedEvents=$EV_BEFORE->$EV_AFTER learning_kv=${LEARN_BEFORE:-absent}->${LEARN_AFTER:-absent} loops=$n $dd | $rd"
echo "::endgroup::"

cat "$RESULTS"
exit "$FAILED"
