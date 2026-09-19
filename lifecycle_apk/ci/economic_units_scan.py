#!/usr/bin/env python3
"""
V5.0.7065 — directive §1 / §7, enforced in CI.

WHY THIS IS A CI CHECK AND NOT A RUNTIME ONE
============================================
Runtime guards catch a bad value once it exists. Every defect this scan is
aimed at was introduced by someone writing a correct-looking line of Kotlin:

    V5.0.7029  proceedsSol = sellQty * actualPrice          (divisor omitted)
    V5.0.7057  gainMultiple = (qty * priceUsd) / costSol    (USD over SOL)
    V5.0.6496  returned a USD figure from a function documented to return SOL

Three separate authors' worth of the same mistake, each one shipped, each one
found months later by reading a ledger that had already moved money. A runtime
invariant is the right second line of defence — EconomicUnitInvariant7061 is
exactly that — but the cheapest place to stop this is before it compiles.

WHAT IT ENFORCES
================
§1  The USD->SOL conversion is written ONCE. A new inline
    `... / solUsd`-shaped division outside the sanctioned files is a FAIL;
    route it through EconomicUnitInvariant7061.usdToSol instead.

§7  Paper cash has ONE authoritative mutation path. `cashPico` may only be
    written inside PaperAccountLedger6430, and the alternate sell-credit
    entry points may not acquire production callers.

BASELINE, NOT A BIG-BANG REWRITE. The sanctioned list below is the set of
sites that legitimately perform the conversion today. The scan fails on
anything NEW, which is what stops the class from growing while the existing
sites are migrated one at a time. Shrinking the list is progress; adding to it
needs a reason written next to the entry.
"""
import os
import re
import sys

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(MODULE_ROOT, "app", "src", "main", "kotlin", "com", "lifecyclebot")

# §1 — files allowed to spell the USD->SOL conversion out. Each one is either
# the authority itself or a site whose migration is staged.
CONVERSION_SANCTIONED = {
    # The authority. This is where the division is supposed to live.
    "engine/truth/EconomicUnitInvariant7061.kt",

    # Guards that reconstruct proceeds in order to REFUSE them. A guard that
    # called the same helper as the code it is checking would be verifying an
    # expression against itself, so these legitimately hold their own copy.
    "engine/truth/PaperPartialEconomicsGate7056.kt",
    "engine/truth/ContaminatedPartialQuarantine7032.kt",
    "engine/truth/CanonicalPaperPartialOperation6510.kt",

    # Mark/quote boundaries: a USD-per-token mark expressed as SOL-per-token.
    # Executor.proceedsSol7029 already routes through the authority; these
    # files retain other conversions pending migration.
    "engine/BotService.kt",
    "engine/Executor.kt",
    "engine/truth/CanonicalCapitalAuthority6450.kt",
    "engine/truth/CanonicalPositionAuthority6441.kt",

    # PRE-EXISTING, STAGED FOR MIGRATION — not endorsed, just not regressed.
    # engine/UnitTypes6344.kt is a SECOND typed-unit system that predates
    # EconomicUnitInvariant7061 and does the same job with different names.
    # Two unit authorities is itself a defect; merging them is its own build,
    # and doing it in the same change that introduces the check would mean
    # landing the rewrite with no check in place to catch it.
    "engine/UnitTypes6344.kt",
    "engine/truth/PositionCostBasisRepair6412.kt",

    # SIZING CAPS, NOT MONEY. These express a USD limit (market cap share,
    # liquidity share) in SOL to compare against an order size. The result is
    # never booked, credited or realized, so a wrong one costs a bad position
    # size rather than a corrupted ledger — a different failure mode with a
    # different urgency.
    "engine/SmartSizer.kt",
    "engine/ScalingMode.kt",
    "perps/MarketsLiveExecutor.kt",
}

# §7 — only this file may write the cash register.
CASH_WRITER_FILE = "engine/truth/PaperAccountLedger6430.kt"
CASH_FIELD = re.compile(r"\bcashPico\s*(?:\.set\(|\.addAndGet\(|=(?!=))")

# §7 — alternate sell-credit entry points. These must stay callerless in
# production; the atomic commit is the only sanctioned crediting path.
CLOSED_SELL_CREDIT_APIS = ("onSellProvenanced6737", "repairCashFromDisplayed6448")
CALLER_EXEMPT_FILES = {
    CASH_WRITER_FILE,
    "engine/truth/InvariantSelfCheck6430.kt",  # exercises the ledger by design
}

# A division by a SOL/USD rate, written inline. Deliberately narrow: it matches
# the rate NAME rather than any division, so ordinary arithmetic is untouched
# and the check cannot drown the build in false positives.
INLINE_CONVERSION = re.compile(r"/\s*(solUsd|solUsdPrice|solPriceUsd|lastKnownSolPrice)\w*")


def kotlin_files():
    for base, _dirs, names in os.walk(SRC):
        for n in names:
            if n.endswith(".kt"):
                full = os.path.join(base, n)
                yield full, os.path.relpath(full, SRC).replace(os.sep, "/")


def main():
    conversion_hits = []
    cash_hits = []
    closed_api_hits = []
    scanned = 0

    for full, rel in kotlin_files():
        scanned += 1
        try:
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                lines = fh.readlines()
        except OSError:
            continue
        for i, raw in enumerate(lines, 1):
            line = raw.split("//", 1)[0]
            if not line.strip():
                continue
            if rel not in CONVERSION_SANCTIONED and INLINE_CONVERSION.search(line):
                conversion_hits.append((rel, i, raw.strip()))
            if rel != CASH_WRITER_FILE and CASH_FIELD.search(line):
                cash_hits.append((rel, i, raw.strip()))
            if rel not in CALLER_EXEMPT_FILES:
                for api in CLOSED_SELL_CREDIT_APIS:
                    if ("." + api + "(") in line:
                        closed_api_hits.append((rel, i, api))

    failed = False

    if conversion_hits:
        failed = True
        print("economic_units_scan: FAIL — §1 inline USD->SOL conversion outside the authority:")
        for rel, i, text in conversion_hits:
            print("  %s:%d  %s" % (rel, i, text[:110]))
        print()
        print("  Route it through EconomicUnitInvariant7061.usdToSol(...).")
        print("  7029 omitted this divisor, 7057 inverted it, 6496 skipped it")
        print("  entirely. Five copies of one division is how that keeps happening.")
        print()

    if cash_hits:
        failed = True
        print("economic_units_scan: FAIL — §7 paper cash written outside %s:" % CASH_WRITER_FILE)
        for rel, i, text in cash_hits:
            print("  %s:%d  %s" % (rel, i, text[:110]))
        print()

    if closed_api_hits:
        failed = True
        print("economic_units_scan: FAIL — §7 alternate sell-credit path acquired a caller:")
        for rel, i, api in closed_api_hits:
            print("  %s:%d  calls %s" % (rel, i, api))
        print()
        print("  Only the atomic canonical sell commit may credit cash.")
        print("  Journal replay, legacy projection, UI facade and mark resolver")
        print("  must all be projections of that commit, never writers beside it.")
        print()

    if failed:
        return 1

    print("economic_units_scan: %d Kotlin files scanned" % scanned)
    print("economic_units_scan: OK — one conversion, one cash writer, one sell-credit path")
    return 0


if __name__ == "__main__":
    sys.exit(main())
