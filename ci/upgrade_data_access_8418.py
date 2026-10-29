"""V5.0.8418 — how the release upgrade test reads app-private data on the emulator.

Modes, best first:
  root     `adb root` gave uid 0: list /data/data/<pkg>/{shared_prefs,databases} by name.
  run-as   no root, but `run-as <pkg>` works (a debuggable build): list the same folders as the app.
  package  neither (a non-debuggable release on a user-build image): no file listing is possible,
           so data survival is proven by package-level checks only — firstInstallTime unchanged
           (in-place upgrade), the PIN not asked to be set again, canonical events not reset,
           and the app's data size from `dumpsys diskstats` not dropping to zero.
The mode is recorded in the verdict; a weaker mode is never reported as a file-level proof.
"""
import re

ROOT_8418 = "root"
RUN_AS_8418 = "run-as"
PACKAGE_8418 = "package"


def choose_mode_8418(root_uid, run_as_uid):
    """Pure: the access mode from `adb shell id -u` after `adb root` and `adb shell run-as <pkg> id -u`."""
    if (root_uid or "").strip() == "0":
        return ROOT_8418
    if re.fullmatch(r"\d+", (run_as_uid or "").strip()):
        return RUN_AS_8418
    return PACKAGE_8418


def list_command_8418(mode, pkg, sub):
    """Pure: the adb command that lists one app-private folder by name, or None in package mode."""
    if mode == ROOT_8418:
        return ["adb", "shell", "ls", "-1", f"/data/data/{pkg}/{sub}"]
    if mode == RUN_AS_8418:
        return ["adb", "shell", "run-as", pkg, "ls", "-1", sub]
    return None


def parse_listing_8418(sub, listing):
    """Pure: file names of one listing; SQLite sidecars and error lines are dropped."""
    out = set()
    for line in (listing or "").splitlines():
        name = line.strip()
        if not name or "No such file" in name or "not debuggable" in name or "Permission denied" in name \
                or "unknown package" in name or name.startswith("run-as:"):
            continue
        if name.endswith(("-journal", "-wal", "-shm")):
            continue
        # V5.0.8423 — a SharedPreferences `<name>.xml.bak` is the backup Android keeps only for the duration of one commit
        # (deleted when the write lands): seeing one in a listing is a write in flight, not a data file the upgrade must
        # carry (8419 build: "upgrade install lost app data files: shared_prefs/canonical_entry_evidence_8093.xml.bak").
        if name.endswith(".bak"):
            continue
        out.add(f"{sub}/{name}")
    return out


def diskstats_data_bytes_8418(dump, pkg):
    """Pure: the app's data size from `dumpsys diskstats` (Package Names / Data Sizes arrays), or None."""
    names = re.search(r"Package Names:\s*\[([^\]]*)\]", dump or "")
    sizes = re.search(r"App Data Sizes:\s*\[([^\]]*)\]", dump or "")
    if not names or not sizes:
        return None
    pkgs = [p.strip().strip('"') for p in names.group(1).split(",")]
    vals = [v.strip() for v in sizes.group(1).split(",")]
    if pkg not in pkgs or len(pkgs) != len(vals):
        return None
    v = vals[pkgs.index(pkg)]
    return int(v) if re.fullmatch(r"\d+", v) else None
