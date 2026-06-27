#!/usr/bin/env python3
"""V5.0.4282 — compact build gate dashboard for every CI run."""
from pathlib import Path
import os, subprocess, re
ROOT = Path(__file__).resolve().parents[1]

def run(cmd):
    try: return subprocess.check_output(cmd, cwd=ROOT.parent, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception: return ""

sha = os.getenv("GITHUB_SHA", run(["git","rev-parse","HEAD"]))[:8]
run_id = os.getenv("GITHUB_RUN_ID", "local")
files = run(["git","show","--name-only","--format=",sha]).splitlines() if sha else []
prod = [f for f in files if "/src/main/" in f]
gt = [f for f in files if "GoldenTapeRegressionTest.kt" in f]
queue = ROOT / "audits/asi_ssi_audit_queue_2026-06-27.md"
queue_text = queue.read_text() if queue.exists() else ""
closed = re.findall(r"Status: implemented in V5\.0\.(\d+)", queue_text)
latest_closed = sorted(set(closed), key=int)[-8:]
summary = [
    "## AATE Build Gate Summary",
    f"- run: {run_id}",
    f"- sha: {sha}",
    f"- production files touched: {len(prod)}",
    *(f"  - `{f}`" for f in prod[:12]),
    f"- Golden Tape touched: {'yes' if gt else 'no'}",
    "- A37 closeout manifest: src/main/kotlin/com/lifecyclebot/engine/AsiSsiAuditCloseoutManifest.kt",
    f"- latest audit bundles marked implemented: {', '.join('5.0.'+x for x in latest_closed) if latest_closed else 'none'}",
    "- inherited known failures: older superseded red builds may remain in Actions history; latest SHA is authoritative",
]
print("\n".join(summary))
out = os.getenv("GITHUB_STEP_SUMMARY")
if out:
    Path(out).write_text("\n".join(summary) + "\n")
