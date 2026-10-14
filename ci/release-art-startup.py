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


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)

def tap(node):
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(bounds) != 4:
        raise RuntimeError("UI control has no tappable bounds")
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))


args = argparse.ArgumentParser(description=__doc__)
args.add_argument("apk", type=pathlib.Path)
args.add_argument("--previous", type=pathlib.Path, required=True)
options = args.parse_args()
apk = options.apk.resolve()
output = pathlib.Path("release_art_evidence")
output.mkdir(exist_ok=True)
subprocess.run(["adb", "uninstall", "com.lifecyclebot.aate"], capture_output=True)
# Exercise Android's real signature/version upgrade checks. The previous
# published APK is installed first in this disposable, unfunded emulator.
(output / "previous_install.txt").write_text(adb("install", str(options.previous.resolve())))
(output / "previous_package.txt").write_text(adb("shell", "dumpsys", "package", "com.lifecyclebot.aate"))
(output / "upgrade_install.txt").write_text(adb("install", "-r", str(apk)))
package = adb("shell", "dumpsys", "package", "com.lifecyclebot.aate")
(output / "candidate_package.txt").write_text(package)
version = pathlib.Path("AATE_VERSION").read_text().strip()
version_match = re.fullmatch(r"5\.0\.(\d+)", version)
if version_match is None:
    raise RuntimeError("Invalid candidate AATE_VERSION")
expected_code = 500 + int(version_match[1])
if not re.search(rf"versionCode={expected_code}\b", package) or f"versionName={version}" not in package:
    raise RuntimeError("Installed release version does not match the publishing build")
adb("logcat", "-c")
log = (output / "logcat.txt").open("w")
capture = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=log)
try:
    compiled = adb("shell", "cmd", "package", "compile", "-m", "verify", "-f", "com.lifecyclebot.aate")
    (output / "art_compile.txt").write_text(compiled)
    if "Success" not in compiled:
        raise RuntimeError("ART package verification command did not succeed")
    adb("shell", "monkey", "-p", "com.lifecyclebot.aate", "-c", "android.intent.category.LAUNCHER", "1")
    deadline = time.monotonic() + 240
    start_tapped = False
    pin_stages = set()
    while time.monotonic() < deadline:
        text = (output / "logcat.txt").read_text(errors="replace")
        if re.search(r"Verifier rejected class com\.lifecyclebot|java\.lang\.VerifyError|SERVICE_BOOTSTRAP_FAILED_6516|FATAL EXCEPTION", text):
            raise RuntimeError("Release verifier/bootstrap failure; see logcat.txt")
        if "SERVICE_BOOTSTRAP_READY_6516" in text and "BOT_LOOP_TICK" in text:
            (output / "verdict.txt").write_text("PASS: published-APK upgrade, release version identity, ART verification, service bootstrap, UI Start, first bot-loop tick\n")
            break
        if not start_tapped:
            adb("shell", "uiautomator", "dump", "/sdcard/release_ui.xml")
            xml = adb("shell", "cat", "/sdcard/release_ui.xml")
            (output / "ui.xml").write_text(xml)
            nodes = list(ET.fromstring(xml).iter("node"))
            # Fresh Android images show an immersive-mode tutorial over the app.
            # Dismiss observed first-run dialogs before trying the actual Start control.
            overlay = next((n for n in nodes if n.get("resource-id") == "android:id/ok"
                            and n.get("text", "").lower() == "got it"), None)
            permission = next((n for n in nodes if n.get("resource-id", "").endswith("/permission_allow_button")), None)
            disclaimer = next((n for n in nodes if n.get("text", "").upper() == "I AGREE"), None)
            start = next((n for n in nodes if n.get("resource-id", "").endswith("/btnToggle")
                          and n.get("enabled") == "true" and "start" in n.get("text", "").lower()), None)
            target = next((n for n in (overlay, permission, disclaimer, start) if n is not None), None)
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
    else:
        raise RuntimeError("Release did not prove bootstrap and first loop within 240 seconds")
finally:
    capture.terminate()
    capture.wait(timeout=10)
    log.close()
    adb("shell", "am", "force-stop", "com.lifecyclebot.aate")
