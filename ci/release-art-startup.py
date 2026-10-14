#!/usr/bin/env python3
"""Exercise the actual release APK in an isolated emulator, through the UI.

No credentials, funding or live trade is supplied. Startup and ART acceptance
are separate from the existing deterministic execution/economic smoke suite.
"""
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


apk = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path("release_art_evidence")
output.mkdir(exist_ok=True)
subprocess.run(["adb", "uninstall", "com.lifecyclebot.aate"], capture_output=True)
adb("install", "-r", str(apk))
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
    while time.monotonic() < deadline:
        text = (output / "logcat.txt").read_text(errors="replace")
        if re.search(r"Verifier rejected class com\.lifecyclebot|java\.lang\.VerifyError|SERVICE_BOOTSTRAP_FAILED_6516|FATAL EXCEPTION", text):
            raise RuntimeError("Release verifier/bootstrap failure; see logcat.txt")
        if "SERVICE_BOOTSTRAP_READY_6516" in text and "BOT_LOOP_TICK" in text:
            (output / "verdict.txt").write_text("PASS: release ART verification, service bootstrap, UI Start, first bot-loop tick\n")
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
                bounds = list(map(int, re.findall(r"\d+", target.get("bounds", ""))))
                if len(bounds) == 4:
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    start_tapped = target is start
                    print("Tapped", target.get("resource-id"), target.get("text"), flush=True)
        time.sleep(2)
    else:
        raise RuntimeError("Release did not prove bootstrap and first loop within 240 seconds")
finally:
    capture.terminate()
    capture.wait(timeout=10)
    log.close()
    adb("shell", "am", "force-stop", "com.lifecyclebot.aate")
