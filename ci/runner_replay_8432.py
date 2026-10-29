#!/usr/bin/env python3
"""V5.0.8432 — replay harness for historically missed runners.

Input: the device file `admission_runner_traces_8144.jsonl` (one AdmissionRunnerTrace8144 trace per line; the status
report names it under "Runner capture (§8017)"). Output: a table of every 4x+ runner the bot saw, the door that bound at
decision time, whether the bot's own record at that moment showed a safe executable positive-net entry (the 8331 rule), and
the roll-up per door / lane, so a build can be judged against the runners it had in front of it.

Pure Python, no network. `python3 ci/runner_replay_8432.py <traces.jsonl> [--min-peak 300] [--lane LANE] [--json]`.
"""
import argparse
import json
import sys
from collections import Counter, defaultdict

MIN_PEAK_DEFAULT = 300.0        # a 4x runner: +300 % peak over the decision price

SAFETY_WORDS = ("SAFETY", "RUG", "HONEYPOT", "FREEZE", "MINT_AUTH", "HARD_BLOCK", "BEHAVIOR_BLOCK", "SELLABILITY", "QUARANTIN")
DATA_WORDS = ("DATA_INCOMPLETE", "DEFER", "UNHYDRATED", "UNREAD", "HYDRATING", "WAIT")


def classify_8432(t):
    """Pure: (verdict, door) for one trace — HELD / WRONGLY_REFUSED / CORRECT (safety or no evidence) / DATA_GAP."""
    if t.get("held"):
        return "HELD", "held_entry"
    door = (t.get("binding") or t.get("refusedBy") or t.get("fdgVerdict") or "").strip() or "UNKNOWN"
    up = door.upper()
    if any(w in up for w in SAFETY_WORDS):
        return "CORRECT_SAFETY", door
    if any(w in up for w in DATA_WORDS):
        return "DATA_GAP", door
    # the 8331 rule: wrong only when the bot's own record at the decision showed a safe, executable, positive-net entry
    hard_ok = t.get("hardSafetyPassed") is True
    depth = t.get("exitDepthUsd")
    net = t.get("expectedNetPct")
    route = (t.get("route") or "").strip()
    evidenced = hard_ok and isinstance(depth, (int, float)) and depth > 0 and isinstance(net, (int, float)) and net > 0 and route
    return ("WRONGLY_REFUSED" if evidenced else "CORRECT_UNPROVEN"), door


def door_stem_8432(door):
    """Pure: the gate stem a door name rolls up under (lane suffixes and detail dropped)."""
    stem = door.split(":")[0].split("@")[0].split(" ")[0]
    for lane in ("_MOONSHOT", "_SHITCOIN", "_EXPRESS", "_QUALITY", "_BLUECHIP", "_CASHGEN", "_TREASURY", "_CORE", "_CYCLIC",
                 "_MANIPULATED", "_DIP_HUNTER", "_PROJECT_SNIPER"):
        if stem.endswith(lane):
            stem = stem[: -len(lane)]
    return stem or "UNKNOWN"


def load_8432(path):
    rows = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return rows


def replay_8432(rows, min_peak=MIN_PEAK_DEFAULT, lane=None):
    """Pure: the replay over parsed traces -> dict with runners, per-door and per-lane roll-ups."""
    runners = []
    by_verdict = Counter()
    by_door = Counter()
    by_lane = defaultdict(Counter)
    for t in rows:
        peak = t.get("peakPct")
        if not isinstance(peak, (int, float)) or peak < min_peak:
            continue
        if lane and (t.get("lane") or "").upper() != lane.upper():
            continue
        verdict, door = classify_8432(t)
        stem = door_stem_8432(door)
        by_verdict[verdict] += 1
        if verdict == "WRONGLY_REFUSED":
            by_door[stem] += 1
        by_lane[(t.get("lane") or "-").upper()][verdict] += 1
        runners.append({
            "symbol": t.get("symbol") or "?", "mint": (t.get("mint") or "")[:10], "lane": t.get("lane") or "-",
            "peakPct": round(float(peak), 1), "verdict": verdict, "door": stem,
            "expectedNetPct": t.get("expectedNetPct"), "exitDepthUsd": t.get("exitDepthUsd"), "route": t.get("route") or "",
        })
    runners.sort(key=lambda r: -r["peakPct"])
    return {"runners": runners, "byVerdict": dict(by_verdict), "wrongByDoor": dict(by_door.most_common()),
            "byLane": {k: dict(v) for k, v in by_lane.items()}, "minPeakPct": min_peak}


def render_8432(result):
    out = []
    v = result["byVerdict"]
    out.append(f"runners(>= +{result['minPeakPct']:.0f}%)={len(result['runners'])} " +
               " ".join(f"{k.lower()}={v[k]}" for k in sorted(v)))
    if result["wrongByDoor"]:
        out.append("wrongly refused by door: " + ", ".join(f"{k}={n}" for k, n in result["wrongByDoor"].items()))
    for lane, c in sorted(result["byLane"].items()):
        out.append(f"  {lane}: " + " ".join(f"{k.lower()}={n}" for k, n in sorted(c.items())))
    for r in result["runners"][:40]:
        out.append(f"  {r['symbol']:<10} +{r['peakPct']:>7.0f}%  {r['lane']:<14} {r['verdict']:<16} {r['door']}")
    return "\n".join(out)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("traces")
    ap.add_argument("--min-peak", type=float, default=MIN_PEAK_DEFAULT)
    ap.add_argument("--lane", default=None)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    result = replay_8432(load_8432(a.traces), a.min_peak, a.lane)
    print(json.dumps(result, indent=1) if a.json else render_8432(result))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
