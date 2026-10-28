#!/usr/bin/env python3
"""Exercise the actual release APK in an isolated emulator, through the UI.

No credentials, funding or live trade is supplied. Startup and ART acceptance
are separate from the existing deterministic execution/economic smoke suite.
"""
import argparse
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import upgrade_data_access_8418 as access8418  # noqa: E402


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)

def tap(node):
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(bounds) != 4:
        raise RuntimeError("UI control has no tappable bounds")
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))


PKG = "com.lifecyclebot.aate"
PROTECTED_STORES_8418 = {"databases/learning_kv.db", "databases/shadow_cold_8397.db",
                         "shared_prefs/canonical_economic_events_6486.xml"}
BOOT_8418 = re.compile(r"CANONICAL_BOOTSTRAP_READY_6515\S*\s+events=(\d+)\s+positions=(\d+)\s+duplicateMints=(\d+)")


def package_times_8418(dump):
    """firstInstallTime survives `install -r`; an uninstall+install would reset it."""
    first = re.search(r"firstInstallTime=([^\n]+)", dump)
    last = re.search(r"lastUpdateTime=([^\n]+)", dump)
    return (first.group(1).strip() if first else None, last.group(1).strip() if last else None)


def data_files_8418():
    """App-private file NAMES (root, else run-as), or None when neither can read them (package mode)."""
    names = set()
    for sub in ("shared_prefs", "databases"):
        cmd = access8418.list_command_8418(DATA_MODE_8418, PKG, sub)
        if cmd is None:
            return None
        listing = subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout
        names |= access8418.parse_listing_8418(sub, listing)
    return names


def data_bytes_8418():
    """The app's data size from dumpsys diskstats (no root needed; the service's cached value), or None."""
    dump = subprocess.run(["adb", "shell", "dumpsys", "diskstats"], capture_output=True, text=True, timeout=60).stdout
    return access8418.diskstats_data_bytes_8418(dump, PKG)


def bootstrap_8418(log_path):
    found = BOOT_8418.findall(log_path.read_text(errors="replace"))
    return tuple(int(x) for x in found[-1]) if found else None


def capture_8418(name):
    adb("logcat", "-c")
    handle = (output / name).open("w")
    return handle, subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=handle)


def stop_capture_8418(handle, proc):
    proc.terminate()
    proc.wait(timeout=10)
    handle.close()


def drive_to_first_loop(log_path, ui_name):
    """Launch through the LAUNCHER and the real UI (PIN, disclaimer, Start) until the service
    bootstrap and first bot-loop tick are in log_path. Returns the PIN stages the UI showed."""
    adb("shell", "monkey", "-p", "com.lifecyclebot.aate", "-c", "android.intent.category.LAUNCHER", "1")
    deadline = time.monotonic() + 240
    start_tapped = False
    pin_stages = set()
    launcher_anr_dismissals = 0
    while time.monotonic() < deadline:
        text = log_path.read_text(errors="replace")
        if re.search(r"Verifier rejected class com\.lifecyclebot|java\.lang\.VerifyError|SERVICE_BOOTSTRAP_FAILED_6516|FATAL EXCEPTION", text):
            raise RuntimeError("Release verifier/bootstrap failure; see logcat.txt")
        if "SERVICE_BOOTSTRAP_READY_6516" in text and "BOT_LOOP_TICK" in text:
            return pin_stages
        if not start_tapped:
            # 8269 emulator upgrade run: the first UiAutomator dump returned
            # "ERROR: null root node" and no XML. A transient empty tree
            # must NOT turn a compiled, upgrade-installable APK into a false
            # application failure; retry within the existing 240s deadline.
            # A persistent empty tree still fails and never earns PASS.
            try:
                dump = adb("shell", "uiautomator", "dump", "/sdcard/release_ui.xml")
                if "ERROR:" in dump or "null root node" in dump.lower():
                    print("UI tree not ready, retrying:", dump.strip(), flush=True)
                    time.sleep(2)
                    continue
                xml = adb("shell", "cat", "/sdcard/release_ui.xml")
                nodes = list(ET.fromstring(xml).iter("node"))
                (output / ui_name).write_text(xml)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError) as exc:
                print("UI tree unavailable, retrying:", type(exc).__name__, flush=True)
                time.sleep(2)
                continue
            # Fresh Android images show an immersive-mode tutorial over the app.
            # Dismiss observed first-run dialogs before trying the actual Start control.
            overlay = next((n for n in nodes if n.get("resource-id") == "android:id/ok"
                            and n.get("text", "").lower() == "got it"), None)
            permission = next((n for n in nodes if n.get("resource-id", "").endswith("/permission_allow_button")), None)
            # V5.0.8231 — a system "<app> isn't responding" dialog (the launcher ANRs on fresh images) hides the app's
            # UI; "Wait" dismisses it. Observed on the 8230 run: the app bootstrapped, the Start control was never reachable.
            anr_wait = next((n for n in nodes if n.get("resource-id") == "android:id/aerr_wait"), None)
            anr_target = None
            if anr_wait is not None:
                # On GitHub's Android 16 image, Pixel Launcher itself can ANR while AATE's
                # SecurityActivity is already displayed. Blindly clicking "Wait" in that
                # unrelated launcher dialog forever caused a false-red release (8365 main,
                # run 40082621484). Keep an actual AATE ANR under the original timeout.
                title_node = next((n for n in nodes if n.get("resource-id") == "android:id/alertTitle"), None)
                title = (title_node.get("text", "") if title_node is not None else "").strip()
                low = title.lower()
                if not title:
                    print("ANR title not yet available; retrying UI", flush=True)
                    time.sleep(2)
                    continue
                if "pixel launcher" in low or "nexuslauncher" in low:
                    launcher_anr_dismissals += 1
                    if launcher_anr_dismissals > 5:
                        raise RuntimeError("Emulator Pixel Launcher repeatedly ANR; release app verdict unavailable")
                    anr_target = next((n for n in nodes if n.get("resource-id") == "android:id/aerr_close"), None)
                    if anr_target is None:
                        raise RuntimeError("Emulator Pixel Launcher ANR has no Close app control")
                    print("Closing unrelated emulator Pixel Launcher ANR; AATE process is not the ANR", flush=True)
                elif "aate" in low or "lifecycle" in low:
                    anr_target = anr_wait
                    print("AATE ANR observed; waiting only within the existing 240s bootstrap deadline", flush=True)
                else:
                    raise RuntimeError("Unrelated emulator system ANR blocks release verdict: " + title)
            disclaimer = next((n for n in nodes if n.get("text", "").upper() == "I AGREE"), None)
            start = next((n for n in nodes if n.get("resource-id", "").endswith("/btnToggle")
                          and n.get("enabled") == "true" and "start" in n.get("text", "").lower()), None)
            target = next((n for n in (anr_target, overlay, permission, disclaimer, start) if n is not None), None)
            if target is not None:
                tap(target)
                start_tapped = target is start
                print("Tapped", target.get("resource-id"), target.get("text"), flush=True)
            else:
                title = next((n.get("text", "") for n in nodes
                              if n.get("resource-id", "").endswith("/tvSecurityTitle")), "")
                if title in {"Set Your PIN", "Confirm PIN", "Enter PIN"} and title not in pin_stages:
                    # Public fixture for this fresh, disposable emulator only.
                    # Exercise the actual UI; no production auth bypass or app-data edit.
                    for key in ("key1", "key2", "key3", "key4", "keyOk"):
                        node = next(n for n in nodes if n.get("resource-id", "").endswith("/" + key))
                        tap(node)
                        time.sleep(0.2)
                    pin_stages.add(title)
                    print("Completed emulator PIN stage:", title, flush=True)
        time.sleep(2)
    raise RuntimeError("Release did not prove bootstrap and first loop within 240 seconds: " + log_path.name)


args = argparse.ArgumentParser(description=__doc__)
args.add_argument("apk", type=pathlib.Path)
args.add_argument("--previous", type=pathlib.Path, required=True)
options = args.parse_args()
apk = options.apk.resolve()
output = pathlib.Path("release_art_evidence")
output.mkdir(exist_ok=True)
# V5.0.8418 — root on this disposable google_apis emulator, only to read app-private file NAMES
# before/after the upgrade. Done first: adbd restarts, which would cut a running logcat capture.
# Without root the checks fall back to run-as, then to package-level checks (upgrade_data_access_8418).
root = subprocess.run(["adb", "root"], capture_output=True, text=True, timeout=60)
subprocess.run(["adb", "wait-for-device"], timeout=120)
root_uid_8418 = subprocess.run(["adb", "shell", "id", "-u"], capture_output=True, text=True, timeout=30).stdout
DATA_MODE_8418 = access8418.choose_mode_8418(root_uid_8418, None)
subprocess.run(["adb", "uninstall", PKG], capture_output=True)
# Exercise Android's real signature/version upgrade checks. The previous
# published APK is installed first in this disposable, unfunded emulator.
(output / "previous_install.txt").write_text(adb("install", str(options.previous.resolve())))
previous_package = adb("shell", "dumpsys", "package", PKG)
(output / "previous_package.txt").write_text(previous_package)
# V5.0.8418 — the previous release must RUN and create its data (PIN, config, canonical
# events, learning DBs) before the candidate is installed over it; an upgrade over an empty
# install proves only that the signatures match.
handle, proc = capture_8418("previous_logcat.txt")
try:
    previous_pins = drive_to_first_loop(output / "previous_logcat.txt", "previous_ui.xml")
    time.sleep(20)  # let the running previous release persist its stores
finally:
    stop_capture_8418(handle, proc)
previous_boot = bootstrap_8418(output / "previous_logcat.txt")
if DATA_MODE_8418 != access8418.ROOT_8418:
    # run-as needs the package installed, so it is probed only now.
    run_as_uid_8418 = subprocess.run(["adb", "shell", "run-as", PKG, "id", "-u"], capture_output=True, text=True, timeout=30).stdout
    DATA_MODE_8418 = access8418.choose_mode_8418(root_uid_8418, run_as_uid_8418)
print("Upgrade data access mode:", DATA_MODE_8418, flush=True)
(output / "data_access_mode.txt").write_text(DATA_MODE_8418 + "\n" + (root.stdout + root.stderr).strip() + "\n")
files_before = data_files_8418()
bytes_before = data_bytes_8418()
if files_before is not None:
    (output / "previous_files.txt").write_text("\n".join(sorted(files_before)) + "\n")
    if not files_before:
        raise RuntimeError("previous release created no shared_prefs/databases; nothing to carry across the upgrade")
elif previous_boot is None:
    raise RuntimeError("package mode: the previous release logged no CANONICAL_BOOTSTRAP_READY_6515, so its data cannot be shown to exist")
# Upgrade in place, as the owner does: no uninstall, same signature, over the used install.
# The previous process is stopped first so every line in logcat.txt is the candidate's own
# (a MY_PACKAGE_REPLACED restart included).
adb("shell", "am", "force-stop", PKG)
time.sleep(2)
handle, proc = capture_8418("logcat.txt")
try:
    (output / "upgrade_install.txt").write_text(adb("install", "-r", str(apk)))
    package = adb("shell", "dumpsys", "package", PKG)
    (output / "candidate_package.txt").write_text(package)
    version = pathlib.Path("AATE_VERSION").read_text().strip()
    version_match = re.fullmatch(r"5\.0\.(\d+)", version)
    if version_match is None:
        raise RuntimeError("Invalid candidate AATE_VERSION")
    expected_code = 500 + int(version_match[1])
    if not re.search(rf"versionCode={expected_code}\b", package) or f"versionName={version}" not in package:
        raise RuntimeError("Installed release version does not match the publishing build")
    first_before, _ = package_times_8418(previous_package)
    first_after, last_after = package_times_8418(package)
    if not first_before or first_before != first_after:
        raise RuntimeError(f"firstInstallTime changed {first_before} -> {first_after}: the upgrade was not in place")
    files_after_install = data_files_8418()
    lost = sorted(files_before - files_after_install) if files_before is not None and files_after_install is not None else []
    if lost:
        raise RuntimeError("upgrade install lost app data files: " + ", ".join(lost))
    compiled = adb("shell", "cmd", "package", "compile", "-m", "verify", "-f", PKG)
    (output / "art_compile.txt").write_text(compiled)
    if "Success" not in compiled:
        raise RuntimeError("ART package verification command did not succeed")
    candidate_pins = drive_to_first_loop(output / "logcat.txt", "ui.xml")
    time.sleep(10)
    if "Set Your PIN" in candidate_pins and "Set Your PIN" in previous_pins:
        raise RuntimeError("upgraded release asked to SET a PIN again: the previous release's security prefs did not survive")
    candidate_boot = None
    for _ in range(30):
        candidate_boot = bootstrap_8418(output / "logcat.txt")
        if candidate_boot is not None:
            break
        time.sleep(2)
    if candidate_boot is None:
        raise RuntimeError("upgraded release logged no CANONICAL_BOOTSTRAP_READY_6515")
    if candidate_boot[2] != 0:
        raise RuntimeError(f"upgraded release restored duplicateMints={candidate_boot[2]}")
    if previous_boot is not None and candidate_boot[0] < previous_boot[0]:
        raise RuntimeError(f"canonical events reset across upgrade: {previous_boot[0]} -> {candidate_boot[0]}")
    files_after = data_files_8418()
    bytes_after = data_bytes_8418()
    if bytes_before and bytes_after == 0:
        raise RuntimeError(f"app data size dropped to 0 across the upgrade (diskstats {bytes_before} -> {bytes_after})")
    lost = sorted(files_before - files_after) if files_before is not None and files_after is not None else []
    # The installer itself may lose nothing (checked above). At startup the new release may
    # retire an obsolete file, but never the learning or canonical economic stores.
    protected_lost = [f for f in lost if f in PROTECTED_STORES_8418]
    if protected_lost:
        raise RuntimeError("upgraded release deleted learning/canonical stores at startup: " + ", ".join(protected_lost))
    if files_after is not None:
        (output / "candidate_files.txt").write_text("\n".join(sorted(files_after)) + "\n")
    files_proof_8418 = (f"created {len(files_before)} data files; installer kept every file; retired at startup={lost}"
                        if files_before is not None else
                        f"file names unreadable without root/run-as (package checks only: diskstats data {bytes_before} -> {bytes_after})")
    (output / "verdict.txt").write_text(
        "PASS: published-APK upgrade, release version identity, ART verification, service bootstrap, UI Start, first bot-loop tick\n"
        f"PASS 8418 [data access={DATA_MODE_8418}]: previous release ran (pins={sorted(previous_pins)} boot={previous_boot}); "
        f"{files_proof_8418}; install -r in place (firstInstallTime={first_after} lastUpdateTime={last_after}); "
        f"candidate pins={sorted(candidate_pins)} boot={candidate_boot}\n")
    print((output / "verdict.txt").read_text(), flush=True)
finally:
    stop_capture_8418(handle, proc)
    adb("shell", "am", "force-stop", PKG)
