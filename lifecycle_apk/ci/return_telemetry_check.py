#!/usr/bin/env python3
"""V5.0.7043 — fail the build when a "this returned" counter is emitted on a
path that does not return.

WHY THIS EXISTS. Three defects this session were the same shape: a counter that
reports a refusal which did not happen, read by a human as evidence about the
outside world.

  7027  ProtectiveExitScheduler `eval` counted heartbeat pings with no price
        identically to real threshold comparisons, so "eval=40000 SL=0" was
        quoted in six consecutive diagnoses — mine included — as proof the
        market never breached a stop. It was proof of nothing.
  7035  PaperLiveParityCreed `resetsOnFlip` was `paperKeys>0 && liveKeys==0`,
        which is true for every learner in any paper-only session regardless of
        whether inheritance works. It cried RESETS_ON_FLIP at two learners that
        had been repaired builds earlier.
  7043  PreV3ReturnTelemetry6525.stamp called on a path whose very next comment
        reads "No pre-V3 return. Cycle proceeds". 261 candidates reported lost
        before scoring that were never lost, and a 0.5% conversion headline
        computed from it.

Each one sent someone at the wrong subsystem. The measurement cost nothing to
take and the wrong conclusion cost a build or a day.

WHAT IT CHECKS. Every PreV3ReturnTelemetry6525.stamp / stampMint call site in
changed files must be followed, within 15 lines, by a `return` or `continue`.
Line comments are stripped first — the false site was originally missed because
the word "return" appears in the comment that says it does NOT return, which is
its own small lesson about grepping for words instead of code.
"""
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = "lifecycle_apk/app/src/main/kotlin"
STAMP = re.compile(r"PreV3ReturnTelemetry6525\.(stamp|stampMint)\s*\(")
EXIT = re.compile(r"(^|\s)(return|continue)\b")


def changed_files(base):
    try:
        out = subprocess.run(["git", "diff", "--name-only", base, "--", SRC],
                             cwd=REPO, capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError:
        return None
    return [f for f in out.split() if f.endswith(".kt")]


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD~1"
    files = changed_files(base)
    if files is None:
        print("return_telemetry_check: SKIPPED — base '%s' unavailable" % base)
        return 0
    if not files:
        print("return_telemetry_check: no Kotlin files changed")
        return 0

    findings = []
    scanned = 0
    for rel in files:
        p = os.path.join(REPO, rel)
        if not os.path.exists(p):
            continue
        raw = open(p, encoding="utf-8").read().split("\n")
        clean = [re.sub(r"//.*", "", l) for l in raw]
        for i, line in enumerate(clean):
            if not STAMP.search(line):
                continue
            scanned += 1
            window = "\n".join(clean[i + 1:i + 16])
            if not EXIT.search(window):
                reason = re.search(r'"([A-Z0-9_]+)"', raw[i])
                findings.append((rel, i + 1, reason.group(1) if reason else "?"))

    print("return_telemetry_check: %d stamp site(s) checked" % scanned)
    if findings:
        print("")
        print("return_telemetry_check: FAIL — PRE_V3_RETURN emitted without returning:")
        for rel, ln, reason in findings:
            print("  %s:%d  reason=%s" % (rel, ln, reason))
        print("")
        print("PreV3ReturnTelemetry's contract is 'this candidate RETURNED before")
        print("V3'; the choke audit sums these as candidates LOST. Either return,")
        print("or emit a counter that says what actually happened.")
        return 1

    print("return_telemetry_check: OK — every return counter sits on a path that returns")
    return 0


if __name__ == "__main__":
    sys.exit(main())
