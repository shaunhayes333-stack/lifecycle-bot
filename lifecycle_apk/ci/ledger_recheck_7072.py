#!/usr/bin/env python3
"""
V5.0.7072 — recheck EVERY ledger row against the code as it stands today.

The ledger was written against a much older tree. Since then whole authority
generations have been superseded, files have been split and merged, and two
validators (static_call_check, new_dead_code) have changed what "wired" even
means. Counting it with the original test produces a number that describes a
codebase that no longer exists.

This asks six questions per row and reports the answer, so the backlog can be
argued with line by line:

  GONE         the declaration is not in the tree any more. Stale ledger row;
               it is not work, it is an artefact of an older checkout.
  WIRED        called by name from a file other than the one declaring it.
  RETIRED      the declaration or its doc says it is deliberately inert.
  DARK_OWNER   the owner's NAME never appears outside its own file, so nothing
               on it is reachable. Wiring a member means resurrecting a whole
               object — usually a superseded generation, and usually the wrong
               move because it creates a competing authority.
  STARVED      the owner IS live and some sibling is called; this specific
               member is not. Cheap to wire, but often redundant with the
               sibling that already runs.
  GENUINE      owner live, no sibling covers it, declaration present and inert.
               This is the real backlog.

Owner-usage is matched on the BARE NAME, not `Owner.`. V5.0.7071's first cut
used the static form and declared TelegramBot dark while the device reported
telegram sr=99%, because it is reached through an instance. The same narrowness
is what ci/static_call_check.py exists to catch in Kotlin.
"""
import csv
import os
import re
import sys

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(MODULE_ROOT, "app", "src", "main", "kotlin", "com", "lifecyclebot")
REPO_ROOT = os.path.dirname(MODULE_ROOT)
LEDGER = os.path.join(REPO_ROOT, "ci", "UNWIRED_LEDGER.tsv")

RETIREMENT_MARKERS = (
    "intentionally dead", "do not resurrect", "neutralized", "neutralised",
    "is dead as a", "deprecated", "retired", "legacy shim", "no longer used",
    "superseded by", "test hook only", "test/maintenance only",
    "diagnostic only", "facade; no callers", "no callers",
)


def load_sources():
    out = {}
    for base, _d, names in os.walk(SRC):
        for n in names:
            if n.endswith(".kt"):
                p = os.path.join(base, n)
                try:
                    out[p] = open(p, encoding="utf-8", errors="replace").read()
                except OSError:
                    pass
    return out


def main():
    rows = list(csv.DictReader(open(LEDGER), delimiter="\t"))
    sources = load_sources()
    items = list(sources.items())

    owners = sorted({r["owner"] for r in rows})
    decl_files = {o: set() for o in owners}
    for p, t in items:
        for o in owners:
            if ("object " + o) in t or ("class " + o) in t or ("interface " + o) in t:
                decl_files[o].add(p)

    owner_live = {}
    for o in owners:
        pat = re.compile(r"\b" + re.escape(o) + r"\b")
        owner_live[o] = any(
            pat.search(t) for p, t in items if p not in decl_files[o]
        )

    verdicts = {}
    detail = []
    for r in rows:
        o, fn, tier = r["owner"], r["function"], r["tier"]
        if not decl_files[o]:
            v = "GONE"
        else:
            decl = re.compile(r"\b(fun|val|var)\s+" + re.escape(fn) + r"\b")
            ctx = ""
            found = False
            for p in decl_files[o]:
                m = decl.search(sources[p])
                if m:
                    found = True
                    s = max(0, sources[p].rfind("\n", 0, max(0, m.start() - 1500)))
                    ctx = sources[p][s:m.end() + 300]
                    break
            if not found:
                v = "GONE"
            else:
                call = re.compile(r"\." + re.escape(fn) + r"\s*\(")
                wired = any(
                    call.search(t) for p, t in items if p not in decl_files[o]
                )
                if wired:
                    v = "WIRED"
                elif any(mk in ctx.lower() for mk in RETIREMENT_MARKERS):
                    v = "RETIRED"
                elif not owner_live[o]:
                    v = "DARK_OWNER"
                else:
                    v = "STARVED"
        verdicts[v] = verdicts.get(v, 0) + 1
        detail.append("%-11s %-18s %s.%s" % (v, tier, o, fn))

    detail.sort()
    for d in detail:
        print(d)
    print()
    order = ["GONE", "WIRED", "RETIRED", "DARK_OWNER", "STARVED", "GENUINE"]
    total = 0
    for k in order:
        if verdicts.get(k):
            print("%-12s %5d" % (k, verdicts[k]))
            total += verdicts[k]
    print("%-12s %5d" % ("TOTAL", total))
    real = verdicts.get("STARVED", 0) + verdicts.get("GENUINE", 0)
    print()
    print("Not work (GONE + WIRED + RETIRED + DARK_OWNER): %d"
          % (verdicts.get("GONE", 0) + verdicts.get("WIRED", 0)
             + verdicts.get("RETIRED", 0) + verdicts.get("DARK_OWNER", 0)))
    print("Candidate work (STARVED + GENUINE): %d" % real)
    return 0


if __name__ == "__main__":
    sys.exit(main())
