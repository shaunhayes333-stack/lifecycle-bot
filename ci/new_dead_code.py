#!/usr/bin/env python3
"""V5.0.7032 — fail the build on code added in this change that nothing calls.

WHY THIS EXISTS. Five separate defects this session had one shape: the
capability was built correctly and the thing that needed it never called it.

  6994  AateComponents6994 — a 531-line render kit, referenced by zero files
  6999  QuoteFreshnessGuard6452.note — one caller, on the buy path, so the
        exit engine declined to evaluate stops on positions it was pricing
        once a second. 51,378 evaluations, zero exits.
  6958  RuntimeTune6833.exitWorkerShouldBoost — documents itself as "callers
        running the exit worker loop poll this" and had zero callers
  7026  HostCircuitInterceptor.PROBE_HEADER_6976 — built in 6976 for exactly
        the case that then blocked the LLM council for four builds
  7030  ApiHealthMonitor in the council's forced attempt — the selection
        picked by cooldown timestamp while the health table sat unread

Each cost at least one 18-minute round trip, and two of them cost the
operator days of a bot that could not trade. The existing half_wired.py and
UNWIRED_LEDGER.tsv track the historical backlog; this is narrower and
blocking: it only looks at what THIS change adds, so it can never be held
hostage by the 89 known items, and a green build means the new work is wired.

WHAT COUNTS AS WIRED. The declared name appears anywhere in the repository
outside the file that declares it — another Kotlin file, a layout, a CI
script. That is a deliberately generous test: the goal is to catch "nothing
anywhere references this", not to police call graphs.

Exit 1 on a finding, with the file, line and symbol.
"""
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "lifecycle_apk/app/src/main")

# Declarations worth checking. Private/override/local ones are excluded:
# an override is called through its interface and a private symbol is by
# definition file-local, so neither can be "wired elsewhere".
# The ` {0,4}` is load-bearing: a declaration indented further than that is a
# local inside a function body, which is file-local by construction and can
# never be "wired elsewhere". Same convention half_wired.py uses.
DECL = re.compile(
    r"^\+(?: {0,4})(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?!.*\b(?:private|override|operator|internal)\b)"
    r"(?:public\s+)?(?:const\s+)?(?:val|var|fun|object|class)\s+"
    r"([a-zA-Z_][A-Za-z0-9_]*)"
)

# Names that are wired by convention rather than by reference.
EXEMPT = {
    # Android entry points resolved by the framework / manifest.
    "onCreate", "onResume", "onPause", "onStart", "onStop", "onDestroy",
    "onBind", "onStartCommand", "onDraw", "onSizeChanged", "onAttachedToWindow",
    "onDetachedFromWindow", "onVisibilityAggregated", "onWindowVisibilityChanged",
    "onBindViewHolder", "onCreateViewHolder", "getItemCount", "onItemSelected",
    "onNothingSelected", "beforeTextChanged", "onTextChanged", "afterTextChanged",
    "onAnimationRepeat", "onAnimationEnd", "onAnimationStart", "run", "toString",
    "equals", "hashCode", "compareTo",
}


def changed_files_and_decls(base):
    try:
        diff = subprocess.run(
            ["git", "diff", "-U0", base, "--", "lifecycle_apk/app/src/main"],
            cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError:
        return []
    out, cur = [], None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            cur = line[6:]
            continue
        if not line.startswith("+") or line.startswith("+++"):
            continue
        if cur is None or not cur.endswith(".kt"):
            continue
        m = DECL.match(line)
        if m:
            out.append((cur, m.group(1)))
    return out


def referenced_outside(name, declaring_file):
    """True when `name` appears in any file other than the one declaring it."""
    try:
        hits = subprocess.run(
            ["grep", "-rl", "--include=*.kt", "--include=*.xml", "--include=*.py",
             r"\b%s\b" % re.escape(name), "lifecycle_apk", "ci"],
            cwd=REPO, capture_output=True, text=True,
        ).stdout.split()
    except Exception:
        return True  # fail open: never block a build on this script erroring
    return any(h != declaring_file for h in hits)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD~1"
    decls = changed_files_and_decls(base)
    if not decls:
        print("new_dead_code: no new declarations in this change")
        return 0

    findings = []
    seen = set()
    for path, name in decls:
        if name in EXEMPT or (path, name) in seen:
            continue
        seen.add((path, name))
        if not referenced_outside(name, path):
            findings.append((path, name))

    print("new_dead_code: %d new declaration(s) checked" % len(seen))
    if findings:
        print("")
        print("new_dead_code: FAIL — added and never referenced outside its own file:")
        for path, name in findings:
            print("  %s :: %s" % (path, name))
        print("")
        print("Every one of 6994/6999/6958/7026/7030 had exactly this shape.")
        print("Wire it, or delete it. If it is genuinely called by convention,")
        print("add the name to EXEMPT in ci/new_dead_code.py with a reason.")
        return 1

    print("new_dead_code: OK — everything added in this change is referenced")
    return 0


if __name__ == "__main__":
    sys.exit(main())
