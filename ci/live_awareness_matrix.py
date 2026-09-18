#!/usr/bin/env python3
"""V5.0.6992 — which traders and protection tools are LIVE-AWARE.

THE QUESTION
============
Operator: "ensure your checks for live trading are platform wide across all
traders crypto and markets and tools, lanes etc ... all the protection systems,
rug tools, all the predictive stack must be live trading aware."

"Live-aware" is not one thing, so this reports four separate columns rather
than a single verdict, and a tool can legitimately need only some of them:

  MODE   does it know whether it is trading real money at all?
         (RuntimeModeAuthority / isPaper / paperMode)
  SEED   does it consult paper's learning through an assessed bridge?
         (PaperLiveIntelligenceBridge, PaperSeededPrior6991)
  STREAK does it respect the cold-streak / losing-streak reflexes?
         (ColdStreakDamper, ExecutableEntryAuthority6450)
  PROT   does it consult the protection stack?
         (ToxicModeCircuitBreaker, HardRugPreFilter, LiveSafetyCircuitBreaker,
          SecurityGuard, LiveEntrySafetyHold, RugDetector)

A row with MODE but nothing else is the interesting shape: it knows real money
is at stake and consults none of the machinery built to protect it.

This is a reference matrix, not a gate. It reports; a human reads. Exit 0.
"""

from __future__ import annotations

import os
import re
import sys

SRC = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"

# Files that actually decide or execute a trade. Scanners, stores, models and
# panels are excluded: a price fetcher has no business consulting a rug filter,
# and listing it as "missing" would be noise dressed as a finding.
TRADERS = [
    ("MEME/Solana", "engine/Executor.kt"),
    ("CYCLIC", "engine/CyclicTradeEngine.kt"),
    ("CRYPTO_ALT", "perps/CryptoAltTrader.kt"),
    ("PERPS", "perps/PerpsTraderAI.kt"),
    ("PERPS_EXEC", "perps/PerpsExecutionEngine.kt"),
    ("MARKETS_LIVE", "perps/MarketsLiveExecutor.kt"),
    ("FOREX", "perps/ForexTrader.kt"),
    ("METALS", "perps/MetalsTrader.kt"),
    ("COMMODITIES", "perps/CommoditiesTrader.kt"),
    ("STOCKS", "perps/TokenizedStockTrader.kt"),
    ("CRYPTO_UNIVERSE", "perps/crypto/CryptoUniverseExecutor.kt"),
    ("QUALITY", "v3/scoring/QualityTraderAI.kt"),
    ("BLUECHIP", "v3/scoring/BlueChipTraderAI.kt"),
    ("SHITCOIN", "v3/scoring/ShitCoinTraderAI.kt"),
    ("MOONSHOT", "v3/scoring/MoonshotTraderAI.kt"),
    ("CASHGEN", "v3/scoring/CashGenerationAI.kt"),
]

COLUMNS = {
    "MODE": [
        r"RuntimeModeAuthority", r"\bisPaper\b", r"\bpaperMode\b", r"isLive\(\)",
    ],
    "SEED": [
        r"PaperLiveIntelligenceBridge", r"PaperSeededPrior6991",
    ],
    "STREAK": [
        r"ColdStreakDamper", r"ExecutableEntryAuthority6450",
        r"LosingStreakReflex", r"consecutiveLossesFor6488",
    ],
    # V5.0.6993 — the first cut of this listed only protection CLASS names and
    # reported CYCLIC as unprotected. CyclicTradeEngine is in fact well
    # defended: it reads ts.safety's tier, hardBlockReasons, lpLockPct,
    # topHolderPct and rugcheckScore, checks BannedTokens.isBanned, and runs a
    # sellability guard before FDG. It just consults the SafetyReport rather
    # than calling a named filter object, which is the normal way a trader
    # downstream of the scanner does this. Matching only class names confused
    # "does not protect itself" with "does not protect itself the way I
    # happened to grep for".
    "PROT": [
        r"ToxicModeCircuitBreaker", r"HardRugPreFilter", r"LiveSafetyCircuitBreaker",
        r"SecurityGuard", r"LiveEntrySafetyHold", r"RugDetector",
        r"LearnedRugPattern", r"RugPreFilter",
        # Consuming the safety report is protection too.
        r"SafetyTier", r"hardBlockReasons", r"rugcheckScore", r"BannedTokens",
        r"\.safety\b", r"isBlocked", r"lpLockPct", r"topHolderPct",
    ],
}

# Assets that cannot rug. A forex pair, a metal or a tokenised blue-chip stock
# has no LP to pull and no deployer to dump, so the absence of a rug filter in
# these traders is correct rather than a gap, and reporting it would be noise
# dressed as a finding.
NON_RUGGABLE = {"FOREX", "METALS", "COMMODITIES", "STOCKS", "PERPS", "PERPS_EXEC"}
COMPILED = {k: re.compile("|".join(v)) for k, v in COLUMNS.items()}


def strip_comments(text: str) -> str:
    def blank(m: re.Match) -> str:
        return "\n" * m.group(0).count("\n")
    text = re.sub(r"/\*.*?\*/", blank, text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else SRC
    rows = []
    for label, rel in TRADERS:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            rows.append((label, rel, None))
            continue
        try:
            src = strip_comments(open(path, encoding="utf-8", errors="replace").read())
        except OSError:
            rows.append((label, rel, None))
            continue
        hits = {col: bool(rx.search(src)) for col, rx in COMPILED.items()}
        rows.append((label, rel, hits))

    cols = list(COLUMNS.keys())
    print("live_awareness_matrix — V5.0.6992\n")
    print(f"{'TRADER':<18}{'MODE':>6}{'SEED':>6}{'STREAK':>8}{'PROT':>6}   file")
    print("-" * 78)
    gaps = []
    for label, rel, hits in rows:
        if hits is None:
            print(f"{label:<18}{'?':>6}{'?':>6}{'?':>8}{'?':>6}   MISSING {rel}")
            continue
        cells = "".join(f"{('yes' if hits[c] else '--'):>{6 if c!='STREAK' else 8}}" for c in cols)
        print(f"{label:<18}{cells}   {rel}")
        if hits["MODE"] and not hits["SEED"]:
            gaps.append((label, rel, "no paper->live seeding"))
        if hits["MODE"] and not hits["STREAK"]:
            gaps.append((label, rel, "no streak reflex"))
        if hits["MODE"] and not hits["PROT"] and label not in NON_RUGGABLE:
            gaps.append((label, rel, "no protection stack"))

    print("\n=== GAPS (knows about real money, consults nothing that protects it)")
    if not gaps:
        print("  none")
    for label, rel, why in gaps:
        print(f"  {label:<18} {why:<26} {rel}")
    print("\nA gap is a candidate, not a verdict — a trader may reach these")
    print("through a shared gate rather than directly. Read before believing.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
