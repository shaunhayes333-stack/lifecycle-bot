#!/usr/bin/env python3
"""
V5.0.7071 — triage the unwired ledger into the three classes it actually holds.

ci/ledger_progress.py counts an entry as unwired when no file outside the
declaring one calls it by name. That single test merges three very different
situations, and treating them as one backlog is why 205 has been a misleading
number:

  RETIRED   the declaration is deliberately inert — neutralised, superseded or
            explicitly marked "do not resurrect". Wiring it would re-introduce
            behaviour that was removed on purpose. Belongs out of the
            denominator, not in the backlog.

  STARVED   the capability IS reachable in production through a sibling
            accessor on the same object, so the defence exists and runs; this
            particular name just is not the one the live path calls. The work,
            if any, is data or a different call, not a wiring job. V5.0.7070's
            creator blacklist was exactly this.

  GENUINE   nothing on the declaring object is called from production. This is
            the real backlog.

Prints one line per entry so the classification can be argued with, and a
summary that gives the honest remaining count.
"""
import csv
import os
import re
import sys

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(MODULE_ROOT, "app", "src", "main", "kotlin", "com", "lifecyclebot")
REPO_ROOT = os.path.dirname(MODULE_ROOT)

LEDGER = os.path.join(REPO_ROOT, "ci", "UNWIRED_LEDGER.tsv")
TIERS = ("A_PREDICT", "B_RISK", "C_EXIT")

RETIREMENT_MARKERS = (
    "intentionally dead",
    "do not resurrect",
    "neutralized",
    "neutralised",
    "is dead as a",
    "deprecated",
    "retired",
    "legacy shim",
    "no longer used",
    "superseded by",
    "test hook only",
    "test/maintenance only",
    "diagnostic only",
)


def kotlin_sources():
    out = {}
    for base, _dirs, names in os.walk(SRC):
        for n in names:
            if not n.endswith(".kt"):
                continue
            full = os.path.join(base, n)
            try:
                with open(full, "r", encoding="utf-8", errors="replace") as fh:
                    out[full] = fh.read()
            except OSError:
                pass
    return out


def find_decl(sources, owner, fn):
    """Return (path, context) for the declaration of owner.fn, else (None, '')."""
    decl = re.compile(r"\b(fun|val|var)\s+" + re.escape(fn) + r"\b")
    best = None
    for path, text in sources.items():
        if ("object " + owner) not in text and ("class " + owner) not in text:
            continue
        m = decl.search(text)
        if not m:
            continue
        start = max(0, text.rfind("\n", 0, max(0, m.start() - 1400)))
        return path, text[start:m.end() + 200]
    return best, ""


def main():
    rows = list(csv.DictReader(open(LEDGER), delimiter="\t"))
    major = [r for r in rows if r["tier"] in TIERS]
    sources = kotlin_sources()

    # Precompute, per owner, whether ANY member is referenced from another file.
    owner_files = {}
    for path, text in sources.items():
        for r in major:
            o = r["owner"]
            if ("object " + o) in text or ("class " + o) in text:
                owner_files.setdefault(o, set()).add(path)

    owner_used = {}
    for o, decl_paths in owner_files.items():
        used = False
        # ANY mention, not just `Owner.` — V5.0.7071 first cut used the static
        # form and mislabelled every INSTANCE-based class as dark. BotBrain is
        # passed as `brain: BotBrain?` into SmartSizer and is very much live;
        # TelegramBot runs at sr=99%; BirdeyeApi is constructed, not referenced
        # statically. Three false "dark" verdicts from one narrow regex, which
        # is the same mistake ci/static_call_check.py exists to catch.
        pat = re.compile(r"\b" + re.escape(o) + r"\b")
        for path, text in sources.items():
            if path in decl_paths:
                continue
            if pat.search(text):
                used = True
                break
        owner_used[o] = used

    counts = {"RETIRED": 0, "STARVED": 0, "GENUINE": 0, "NO_DECL": 0}
    per_tier = {t: {"RETIRED": 0, "STARVED": 0, "GENUINE": 0, "NO_DECL": 0} for t in TIERS}
    lines = []

    for r in major:
        owner, fn, tier = r["owner"], r["function"], r["tier"]
        call = re.compile(r"\." + re.escape(fn) + r"\s*\(")
        decl_path, ctx = find_decl(sources, owner, fn)

        wired = False
        for path, text in sources.items():
            if decl_path and path == decl_path:
                continue
            if call.search(text):
                wired = True
                break
        if wired:
            continue  # ledger_progress already counts this as wired

        if decl_path is None:
            klass = "NO_DECL"
        elif any(mk in ctx.lower() for mk in RETIREMENT_MARKERS):
            klass = "RETIRED"
        elif owner_used.get(owner, False):
            klass = "STARVED"
        else:
            klass = "GENUINE"

        counts[klass] += 1
        per_tier[tier][klass] += 1
        lines.append("%-8s %-10s %s.%s" % (klass, tier, owner, fn))

    lines.sort()
    for ln in lines:
        print(ln)
    print()
    print("%-12s %8s %8s %8s %8s" % ("tier", "RETIRED", "STARVED", "GENUINE", "NO_DECL"))
    for t in TIERS:
        p = per_tier[t]
        print("%-12s %8d %8d %8d %8d" % (t, p["RETIRED"], p["STARVED"], p["GENUINE"], p["NO_DECL"]))
    print("%-12s %8d %8d %8d %8d" % (
        "TOTAL", counts["RETIRED"], counts["STARVED"], counts["GENUINE"], counts["NO_DECL"]))
    print()
    print("Honest remaining backlog (GENUINE + NO_DECL): %d"
          % (counts["GENUINE"] + counts["NO_DECL"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
