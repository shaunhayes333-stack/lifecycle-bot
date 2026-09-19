#!/usr/bin/env python3
"""
V5.0.7079 — the ledger recheck, in one pass instead of 284,000.

ci/ledger_recheck_7072.py answers the right questions and takes minutes: for
each of 244 tracked rows it regex-scans all 1165 source files, so the work is
rows x files. That cost is why the list gets argued about from memory instead of
re-derived, which is how it went a day stale in the first place.

This builds three indexes in ONE pass over the tree and then answers every row
from them:

    calls[file]   every `.member(` name invoked in that file
    decls[sym]    files declaring `object|class|interface <sym>`
    mentions[sym] files mentioning the bare name at all

Same verdicts as 7072, same definitions, so the two are comparable:

  GONE        declaration absent from the tree — a stale row, not work
  WIRED       called by name from a file other than the one declaring it
  RETIRED     the declaration or its doc says it is deliberately inert
  DARK_OWNER  the owner's NAME never appears outside its own file, so nothing
              on it is reachable; wiring a member resurrects a whole object,
              usually a superseded generation
  STARVED     owner live, a sibling is called, this member is not
  GENUINE     owner live, no sibling covers it, present and inert — real backlog

Owner-usage is matched on the BARE NAME, never `Owner.`. V5.0.7071 used the
static form and called TelegramBot dark while the device reported telegram
sr=99%, because it is reached through an instance.
"""
import csv
import os
import re
import sys

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(MODULE_ROOT, "app", "src", "main", "kotlin", "com", "lifecyclebot")
REPO_ROOT = os.path.dirname(MODULE_ROOT)
LEDGER = os.path.join(REPO_ROOT, "ci", "UNWIRED_LEDGER.tsv")

TRACKED = {"A_PREDICT", "B_RISK", "C_EXIT"}

RETIREMENT_MARKERS = (
    "intentionally dead", "do not resurrect", "neutralized", "neutralised",
    "is dead as a", "deprecated", "retired", "legacy shim", "no longer used",
    "superseded by", "test hook only", "test/maintenance only",
    "diagnostic only", "facade; no callers", "no callers",
)

CALL = re.compile(r"\.([A-Za-z_][A-Za-z0-9_]*)\s*\(")
WORD = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")


def main():
    rows = [r for r in csv.DictReader(open(LEDGER), delimiter="\t")
            if r["tier"] in TRACKED]
    owners = {r["owner"] for r in rows}
    functions = {r["function"] for r in rows}

    sources = {}
    calls = {}
    mentions = {o: set() for o in owners}
    decls = {o: set() for o in owners}

    for base, _d, names in os.walk(SRC):
        for n in names:
            if not n.endswith(".kt"):
                continue
            p = os.path.join(base, n)
            try:
                t = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            sources[p] = t
            calls[p] = set(CALL.findall(t))
            words = set(WORD.findall(t))
            for o in owners & words:
                mentions[o].add(p)
                if ("object " + o) in t or ("class " + o) in t or ("interface " + o) in t:
                    decls[o].add(p)

    # Member names called from OUTSIDE each owner's declaring files.
    #
    # A plain global union minus the home files' calls would be wrong: a name
    # called both inside and outside would vanish. So count how many files call
    # each name, subtract the home files' contribution, and keep the names with
    # a caller left. Two passes over the index, no rescan of source.
    from collections import Counter
    global_count = Counter()
    for cs in calls.values():
        global_count.update(cs)
    called_outside = {}
    for o in owners:
        home = decls[o]
        if not home:
            called_outside[o] = set(global_count)
            continue
        home_count = Counter()
        for p in home:
            home_count.update(calls[p])
        called_outside[o] = {
            name for name, n in global_count.items() if n - home_count.get(name, 0) > 0
        }

    owner_live = {o: bool(mentions[o] - decls[o]) for o in owners}

    verdicts = {}
    detail = []
    genuine = []
    starved = []
    for r in rows:
        o, fn, tier = r["owner"], r["function"], r["tier"]
        home = decls[o]
        if not home:
            v = "GONE"
        else:
            declpat = re.compile(r"\b(fun|val|var)\s+" + re.escape(fn) + r"\b")
            ctx = None
            for p in home:
                m = declpat.search(sources[p])
                if m:
                    s = max(0, sources[p].rfind("\n", 0, max(0, m.start() - 1500)))
                    ctx = sources[p][s:m.end() + 300]
                    break
            if ctx is None:
                v = "GONE"
            elif fn in called_outside[o]:
                v = "WIRED"
            elif any(mk in ctx.lower() for mk in RETIREMENT_MARKERS):
                v = "RETIRED"
            elif not owner_live[o]:
                v = "DARK_OWNER"
            else:
                # Does ANY sibling of this owner get called from outside?
                sibs = set()
                for p in home:
                    sibs |= set(re.findall(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)", sources[p]))
                sibs.discard(fn)
                v = "STARVED" if (sibs & called_outside[o]) else "GENUINE"
        verdicts[v] = verdicts.get(v, 0) + 1
        detail.append("%-11s %-10s %s.%s" % (v, tier, o, fn))
        if v == "GENUINE":
            genuine.append("%-10s %s.%s  (%s)" % (tier, o, fn, r["file"]))
        elif v == "STARVED":
            starved.append("%-10s %s.%s  (%s)" % (tier, o, fn, r["file"]))

    if "--detail" in sys.argv:
        for d in sorted(detail):
            print(d)
        print()

    for k in ["GONE", "WIRED", "RETIRED", "DARK_OWNER", "STARVED", "GENUINE"]:
        print("%-12s %5d" % (k, verdicts.get(k, 0)))
    print("%-12s %5d" % ("TOTAL", len(rows)))
    print()
    work = verdicts.get("STARVED", 0) + verdicts.get("GENUINE", 0)
    print("Not work (GONE + WIRED + RETIRED + DARK_OWNER): %d" % (len(rows) - work))
    print("Candidate work (STARVED + GENUINE): %d" % work)

    if genuine:
        print("\nGENUINE — owner live, no sibling covers it:")
        for g in sorted(genuine):
            print("  " + g)
    if "--starved" in sys.argv and starved:
        print("\nSTARVED — owner live, a sibling already runs:")
        for s in sorted(starved):
            print("  " + s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
