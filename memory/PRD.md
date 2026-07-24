# AATE PRD — V5.0.6349

## Current build stack (V5.0.6344 → V5.0.6349 all ✅ SUCCESS on GitHub Actions CI)

- **6323-6330** Foundation: canonical registry, 6324 modules, wiring, WADDLE decimal repair, brain consensus fusion
- **6331** (`25ad96139` ✅) Demote `LIVE_LANE_HARD_PAUSED_6247` → soft-shape
- **6332** (`0519817fe` ✅) Concentrated Conviction — governor bleeds via SIZE not FLOOR
- **6333** (`d037f75c4` ✅) Denylist tier split (HARD vs ADVISORY)
- **6334** (`f747f57b2` ✅) LaneEdgeConcentrator — self-tune capital toward winning buckets
- **6335** (`be17d16c4` ✅) Slash governor floor uplifts — unlocked the live pipe
- **6336** (`3e635f794` ✅) Concentrator classifies by expectancy, not WR
- **6337-6338** (`526026668` ✅) Retro-backfill BUY qty at SELL write
- **6339-6340** (`977c0ee31` ✅) Paper↔Live divergence detector
- **6341** (`567e11b7c` ✅) Demote SAFETY_NOT_READY_STALE from hard-block to soft-shape
- **6342** (`7a6e23639` ✅) **Lane Entry Contract** — governor HOLD veto + BLUECHIP/QUALITY identity
- **6343** (`dd4f2d0a2` ✅) **Canonical PnL Authority** — single source of realized-SOL truth (Cupsey partial-lot correction)
- **6344** (`1f85cb2f9` ✅) **Immutable FillLotLedger + strong unit types + Canonical PnL Conduit** — P0-1/2/3 wired
- **6345** (`45f6665bf` ✅) **Foundation Policy (PRE_ENTRY_DECISION_RECORD) + Executable-Price Stop Preflight** — P0-5 + P0-8
- **6346** (`9ac9ec6bb` ✅) **Canonical Learning Contract** — P0-4 exact-decimal + parity + cost-basis + LIVE_BROADCAST rejection
- **6347** (`4907b225c` ✅) **Scanner/Hydration Queue Separation** — P1-1 LIVE_READY / HYDRATING / PROBATION / SHADOW / REJECTED_WITH_TTL
- **6348** (`61585afef` ✅) **FIRST-TRADE READINESS health block + priority ranking** — P1-2, five pillars surfaced in AATE Pipeline Health Snapshot
- **6349** (`dfc1faa2a` ✅) **Golden-tape guard for 6344→6348** — file-shape + wire-up assertions

## What each new module owns

| Module | Owns | Emits |
|---|---|---|
| CanonicalPnLAuthority6343 | Sole legal realized-SOL calculator | CANONICAL_PNL_{OK,QUARANTINED}_6343 |
| UnitTypes6344 | SolAmount / UsdAmount / TokenQuantity / PriceSolPerToken / PriceUsdPerToken | UNIT_*_NON_FINITE_6344 |
| FillLotLedger6344 | Append-only lot ledger PK = wallet+mint+buyTxSig | FILL_LOT_LEDGER_{APPEND,RESTORED,SELL_LOT_NOT_FOUND}_6344 |
| RealizedPnlConduit6344 | Single funnel into 6343 for every writer | CANONICAL_PNL_DIVERGENCE_6344 (shadow) |
| PreEntryDecisionRecord6345 | Pre-entry evidence receipt (PASS/WARN/VETO) | PRE_ENTRY_DECISION_{PASS,WARN,VETO}_6345 |
| ExecutablePriceStopPreflight6345 | Clamp stops to executable-quote reality | STOP_PREFLIGHT_{OK,ABOVE_BID_CLAMPED,UNREACHABLE_CLAMPED,QUOTE_MISSING}_6345 |
| CanonicalLearningContract6346 | Exact-decimal + parity + cost-basis eligibility | CANONICAL_LEARNING_{ADMITTED,QUARANTINED}_6346 |
| ScannerHydrationQueues6347 | Labelled bucket router (5 buckets) | SCANNER_QUEUE_*_6347 |
| FirstTradeReadiness6348 | Five-pillar Y/N verdict + remediation hints | FIRST_TRADE_READINESS_6348 (health-tile line) |

## What is live-wired vs library-only

| Wire-up | Status |
|---|---|
| Executor buy-verify → FillLotLedger6344.appendBuy | LIVE (V5.0.6344) |
| Executor sell-finalize → RealizedPnlConduit6344.finalize (shadow) | LIVE (V5.0.6344) |
| BotService.onCreate → FillLotLedger6344.init | LIVE (V5.0.6344) |
| PipelineHealthCollector snapshot → FirstTradeReadiness6348 line | LIVE (V5.0.6348) |
| Scanner path → ScannerHydrationQueues6347 | LIBRARY ONLY (next push) |
| Every learning aggregator → CanonicalLearningContract6346 | LIBRARY ONLY (next push) |
| Pre-entry ticket → PreEntryDecisionRecord6345.emit | LIBRARY ONLY (next push) |
| Every stop placer → ExecutablePriceStopPreflight6345 | LIBRARY ONLY (next push) |

## Real progress across the session (BUY-ok trajectory)

- 6334 snapshot: BUY ok = **0** (safety hold sticky-armed) → 6335: **10** → 6336: **31**
- 6341: SAFETY_STALE hard-block demoted (was 539 blocks in one session — pipe now flows through)
- 6342: no Pump.fun mint can be labeled BLUECHIP; no MINT_ROUTE can be QUALITY
- 6343: PnL computation now has a single authoritative path with real invariants
- 6344: cost basis is now immutable and keyed on (wallet, mint, buyTxSig); Cupsey 76×-off rows are quarantined automatically
- 6348: operator can now read FIRST_TRADE_READINESS_6348 verdict directly in the health snapshot

## Backlog (priority-ordered)

### 🔴 P0 — Wire the library-only modules into live paths
- **Scanner path → ScannerHydrationQueues6347**: retrofit the scanner emit path to route candidates into buckets; retrofit the executor live loop to drain LIVE_READY only.
- **Pre-entry ticket → PreEntryDecisionRecord6345.emit**: call from the live-buy ticket assembler right before the buy lease is acquired.
- **Learning aggregators → CanonicalLearningContract6346.assess**: retrofit AdaptiveLearningEngine, LaneEdgeConcentrator sample, expectancy classifier, tactic switcher.
- **Every stop placer → ExecutablePriceStopPreflight6345.preflight**: call before stop persistence in Executor and V3JournalRecorder.

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD (block sell path when authority quarantines).
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block on the buy ticket.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode
- Resume `PerpsLaneGate.kt` now that the core accounting/learning contracts are landed.

### 🟢 P3+ — Off-thread rendering, neural bridge, LLM Lab

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- TacticSwitcher persists per-bucket state to LearningPersistence
- LaneEdgeConcentrator amplifies per-bucket (lane × scoreBand) by expectancy
- LosingPatternMemory cross-checks live vs paper distribution (6339)
- LaneEntryContract6342 hard-vetoes on governor HOLD + enforces lane identity
- CanonicalPnLAuthority6343 is the sole legal realized-SOL calculator
- FillLotLedger6344 is the sole legal cost-basis source (immutable, first-write-wins)
- CanonicalLearningContract6346 is the sole legal canonical-eligibility gate
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

- 6344 ✅ SUCCESS  (FillLotLedger + UnitTypes + RealizedPnlConduit inline test suites)
- 6345 ✅ SUCCESS  (PreEntryDecisionRecord + ExecutablePriceStopPreflight inline test suites)
- 6346 ✅ SUCCESS  (CanonicalLearningContract inline test suite — 6 cases)
- 6347 ✅ SUCCESS  (ScannerHydrationQueues inline test suite — 6 cases)
- 6348 ✅ SUCCESS  (FirstTradeReadiness inline test suite — 5 cases)
- 6349 ✅ SUCCESS  (Directive6344Through6348GoldenTapeTest — 13 file-shape + wire-up assertions)

No golden-tape regressions across the six-commit stack.
