#!/usr/bin/env python3
"""V5.0.8418 — evidence parsers for ci/runtime_device_checks_8418.sh (screens, background,
doze, restart recovery). Every verdict is read from the app's own logcat receipts, its UI
hierarchy or its persisted files; nothing is inferred or defaulted.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

PKG = "com.lifecyclebot.aate"
TICK = re.compile(r"\[BOT_LOOP_TICK\]\s+n=(\d+)\s+watch=")
# V5.0.8830 — the duplicate's lots (BotService dupLots8820=) ride along, so a FAIL names what was duplicated
BOOT = re.compile(r"CANONICAL_BOOTSTRAP_READY_6515\S*\s+events=(\d+)\s+positions=(\d+)\s+duplicateMints=(\d+)(?:\s+dupLots8820=(\S+))?")
DISPATCH = re.compile(r"PAPER_TICKET_DISPATCHED_6514\S*\s+attemptId=(\S+)")
CRASH = [
    re.compile(r"FATAL EXCEPTION.*"),          # paired with the Process: line below
    re.compile(r"ANR in " + re.escape(PKG)),
    re.compile(r"Process: " + re.escape(PKG) + r"\b"),
    re.compile(r"java\.lang\.VerifyError|Verifier rejected class"),
]
# Screens whose launch legitimately hands over to another app screen.
HANDOVER = {
    "SplashActivity": ["SplashActivity", "MainActivity", "SecurityActivity"],
    "SecurityActivity": ["SecurityActivity", "SplashActivity", "MainActivity"],
}
STORE_8324 = "store (§8324 restore without reset)"
RESTORE_8355 = re.compile(r"restored8355=(\d+)\s+expiredOnRestore8355=(\d+)\s+waitsRestored8355=(\d+)")


def ticks(text: str) -> list:
    return [int(m.group(1)) for m in TICK.finditer(text)]


def crash_lines(text: str) -> list:
    out = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if CRASH[1].search(line) or CRASH[3].search(line):
            out.append(line.strip())
        elif CRASH[2].search(line) and any("FATAL EXCEPTION" in l for l in lines[max(0, i - 3):i + 1]):
            out.append(line.strip())
    return out


def screen_layouts(src_main: pathlib.Path, activity: str) -> list:
    kt = src_main / "kotlin" / "com" / "lifecyclebot" / "ui" / f"{activity}.kt"
    names = []
    for act in HANDOVER.get(activity, [activity]):
        path = kt.with_name(f"{act}.kt")
        if path.exists():
            names += re.findall(r"(?<!dialog\.)setContentView\(R\.layout\.(\w+)\)", path.read_text(errors="replace"))
    return names


def layout_ids(src_main: pathlib.Path, layouts: list) -> set:
    ids = set()
    for name in layouts:
        path = src_main / "res" / "layout" / f"{name}.xml"
        if path.exists():
            ids |= set(re.findall(r'android:id="@\+id/(\w+)"', path.read_text(errors="replace")))
    return ids


def screen_verdict(dump_xml: str, focus: str, activity: str, src_main: pathlib.Path) -> tuple[bool, str]:
    allowed = HANDOVER.get(activity, [activity])
    focused = re.search(re.escape(PKG) + r"/(?:com\.lifecyclebot\.ui\.|\.ui\.)?(\w+)", focus or "")
    if not focused or focused.group(1) not in allowed:
        return False, f"focus={focus.strip()[:160]!r} expected one of {allowed}"
    try:
        root = ET.fromstring(dump_xml)
    except ET.ParseError as exc:
        return False, f"ui dump unparsable: {exc}"
    nodes = [n for n in root.iter("node") if n.get("package") == PKG]
    if not nodes:
        return False, "no rendered node belongs to the app"
    seen = {n.get("resource-id", "").split(":id/")[-1] for n in nodes if ":id/" in n.get("resource-id", "")}
    expected = layout_ids(src_main, screen_layouts(src_main, activity))
    if expected:
        hit = sorted(seen & expected)
        if not hit:
            return False, f"none of {len(expected)} layout ids rendered (seen {sorted(seen)[:8]})"
        return True, f"focus={focused.group(1)} nodes={len(nodes)} ids={hit[:5]}"
    texts = [n.get("text") for n in nodes if (n.get("text") or "").strip()]
    if not texts:
        return False, "programmatic screen rendered no text"
    return True, f"focus={focused.group(1)} nodes={len(nodes)} texts={len(texts)}"


def bootstrap(text: str):
    m = list(BOOT.finditer(text))
    if not m:
        return None
    g = m[-1].groups()
    out = {"events": int(g[0]), "positions": int(g[1]), "duplicateMints": int(g[2])}
    if g[3]:
        out["dupLots"] = g[3]
    return out


def report_verdict(report: str, require_restore: bool) -> tuple[bool, str]:
    store = [l.strip() for l in report.splitlines() if STORE_8324 in l]
    shadow = RESTORE_8355.search(report)
    if not store:
        return False, "Cortex report has no 'store (§8324 restore without reset)' line"
    if shadow is None:
        return False, "report has no 8355 shadow restore counters"
    detail = f"{store[-1][:220]} | restored8355={shadow.group(1)} expired={shadow.group(2)} waits={shadow.group(3)}"
    if require_restore and not re.search(r"bootSeasoned=\S+", store[-1]):
        return False, "8324 store line lacks bootSeasoned: " + detail
    return True, detail


def heapmap_verdict(text: str) -> tuple[bool, str]:
    """V5.0.8866 — the memory map HeapMap8866 wrote: read to the end, with objects, classes and holder chains."""
    lines = text.splitlines()
    if not lines or not lines[0].startswith("AATE heap map (§8866)"):
        return False, "not a heap map: " + (lines[0][:120] if lines else "empty")
    head = " ".join(lines[:6])
    num = lambda k: int(m.group(1)) if (m := re.search(r"\b" + k + r"=(\d+)", head)) else -1
    status = m.group(1) if (m := re.search(r"\bstatus=(\S+)", head)) else "?"
    stopped = next((l for l in lines[:8] if l.startswith("stopped=")), "")
    objects, classes, samples, mismatch = num("objects"), num("classes"), num("samples"), num("layoutMismatch")
    owners = [l for l in lines if re.match(r"\s+\S+MB\s+\S+%\s+n=\d+\s+static \S", l)]
    traced = re.search(r"([\d.]+)MB represented, ([\d.]+)MB traced", text)
    frac = float(traced.group(2)) / float(traced.group(1)) if traced and float(traced.group(1)) > 0 else 0.0
    detail = (f"status={status} objects={objects} classes={classes} samples={samples} layoutMismatch={mismatch} "
              f"staticOwners={len(owners)} traced={frac:.0%}" + (f" {stopped[:200]}" if stopped else ""))
    if status != "COMPLETE":
        return False, detail
    if objects < 50_000 or classes < 1_000 or samples < 500:
        return False, "too little read: " + detail
    if mismatch * 100 > objects:
        return False, "instance layouts not understood: " + detail
    if not owners or frac < 0.2:
        return False, "holders not traced: " + detail
    return True, detail


def find_node(dump_xml: str, mode: str, value: str):
    """Center of the first enabled node whose resource-id ends with /value (mode=id) or whose text equals value."""
    try:
        root = ET.fromstring(dump_xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        if mode == "id":
            hit = node.get("resource-id", "").endswith("/" + value)
        else:
            hit = (node.get("text") or "").strip().lower() == value.lower()
        if not hit or node.get("enabled", "true") != "true":
            continue
        nums = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
        if len(nums) == 4:
            return (nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2, (node.get("text") or "").strip()
    return None


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("max-tick"); s.add_argument("log")
    s = sub.add_parser("tick-count"); s.add_argument("log")
    s = sub.add_parser("crashes"); s.add_argument("log")
    s = sub.add_parser("screen"); s.add_argument("dump"); s.add_argument("focus"); s.add_argument("activity"); s.add_argument("src_main")
    s = sub.add_parser("find"); s.add_argument("dump"); s.add_argument("mode", choices=["id", "text"]); s.add_argument("value")
    s = sub.add_parser("bootstrap"); s.add_argument("log")
    s = sub.add_parser("dup-dispatch"); s.add_argument("before"); s.add_argument("after")
    s = sub.add_parser("report"); s.add_argument("report"); s.add_argument("--require-restore", action="store_true")
    s = sub.add_parser("heapmap"); s.add_argument("map")
    s = sub.add_parser("record"); s.add_argument("results"); s.add_argument("check"); s.add_argument("status"); s.add_argument("detail")
    s = sub.add_parser("enforce"); s.add_argument("results"); s.add_argument("--require", nargs="+", required=True)
    a = p.parse_args()

    def read(path):
        try:
            return pathlib.Path(path).read_text(errors="replace")
        except OSError:
            return ""

    if a.cmd == "max-tick":
        t = ticks(read(a.log)); print(max(t) if t else 0); return 0
    if a.cmd == "tick-count":
        print(len(ticks(read(a.log)))); return 0
    if a.cmd == "crashes":
        lines = crash_lines(read(a.log))
        for l in lines[:20]:
            print(l)
        return 1 if lines else 0
    if a.cmd == "screen":
        ok, detail = screen_verdict(read(a.dump), read(a.focus), a.activity, pathlib.Path(a.src_main))
        print(detail); return 0 if ok else 1
    if a.cmd == "find":
        hit = find_node(read(a.dump), a.mode, a.value)
        if hit is None:
            return 1
        print(f"{hit[0]} {hit[1]} {hit[2]}"); return 0
    if a.cmd == "bootstrap":
        b = bootstrap(read(a.log))
        if b is None:
            print("none"); return 1
        print(json.dumps(b)); return 0
    if a.cmd == "dup-dispatch":
        before = set(DISPATCH.findall(read(a.before)))
        dup = sorted(before & set(DISPATCH.findall(read(a.after))))
        print(f"before={len(before)} redispatched={dup[:10]}"); return 1 if dup else 0
    if a.cmd == "report":
        ok, detail = report_verdict(read(a.report), a.require_restore)
        print(detail); return 0 if ok else 1
    if a.cmd == "heapmap":
        ok, detail = heapmap_verdict(read(a.map))
        print(detail); return 0 if ok else 1
    if a.cmd == "record":
        path = pathlib.Path(a.results)
        data = json.loads(path.read_text()) if path.exists() else {}
        data[a.check] = {"status": a.status, "detail": a.detail[:2000]}
        path.write_text(json.dumps(data, indent=2)); return 0
    if a.cmd == "enforce":
        data = json.loads(read(a.results) or "{}")
        bad = 0
        for check in a.require:
            r = data.get(check)
            if r is None or r["status"] != "PASS":
                bad += 1
                print(f"::error title=Device check {check}::{(r or {}).get('status', 'NOT_RUN')} {(r or {}).get('detail', '')[:800]}")
            else:
                print(f"PASS {check}: {r['detail'][:300]}")
        return 1 if bad else 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
