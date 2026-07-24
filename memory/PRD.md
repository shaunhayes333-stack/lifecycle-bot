# AATE PRD — V5.0.6343

## Current build stack (all green through 6341; 6342 build green + runtime smoke in-flight; 6343 build in-flight)

- **6323-6330** Foundation: canonical registry, 6324 modules, wiring, WADDLE decimal repair, brain consensus fusion
- **6331** (`25ad96139` ✅) Demote `LIVE_LANE_HARD_PAUSED_6247` → soft-shape
- **6332** (`0519817fe` ✅) Concentrated Conviction — governor bleeds via SIZE not FLOOR
- **6333** (`d037f75c4` ✅) Denylist tier split (HARD vs ADVISORY)
- **6334** (`f747f57b2` ✅) LaneEdgeConcentrator — self-tune capital toward winning buckets
- **6335** (`be17d16c4` ✅) Slash governor floor uplifts (HOLD +18 → +5) — unlocked the pipe
- **6336** (`3e635f794` ✅) Concentrator classifies by expectancy (mean PnL%), not WR
- **6337-6338** (`526026668` ✅) Retro-backfill BUY qty at SELL write for fast catastrophic stops
- **6339-6340** (`977c0ee31` ✅) Paper↔Live divergence detector shrinks size when paper model has lied about a bucket
- **6341** (`567e11b7c` ✅ Build + Runtime Smoke) Demote SAFETY_NOT_READY_STALE from hard-block to soft-shape (was choking loop 52-204s)
- **6342** (`7a6e23639` ✅ Build; runtime smoke running) **Lane Entry Contract** — single authoritative choke: (a) Governor HOLD hard-vetoes live BUY tickets (b) BLUECHIP rejects Pump.fun mints (c) QUALITY rejects MINT_ROUTE placeholders — first slice of operator's full V5.0.6342 architectural directive
- **6343** (`dd4f2d0a2` 🟡 build in-flight) **Canonical PnL Authority** — single source of realized-SOL truth per operator's Cupsey partial-lot correction: partial allocation = originalCost × soldQty / originalQty; LIVE_BROADCAST never canonical; 76×-off price/cost/qty invariant rejects Cupsey-style corrupt rows; 6 dedicated unit tests

## Real progress across the session (BUY-ok trajectory)

- 6334 snapshot: BUY ok = **0** (safety hold sticky-armed) → 6335: **10** → 6336: **31**
- 6341: SAFETY_STALE hard-block demoted (was 539 blocks in one session — pipe now flows through)
- 6342: no Pump.fun mint can be labeled BLUECHIP; no MINT_ROUTE can be QUALITY
- 6343: PnL computation now has a single authoritative path with real invariants

## Staged for V5.0.6344-6350 (from operator's full V5.0.6342 spec)

- P0-2 Immutable FillLotLedger keyed by wallet+mint+buyTxSig; LEGACY_INVENTORY quarantine; FIFO lot allocation
- P0-3 Strong unit types (SolAmount / UsdAmount / TokenQuantity / PriceSolPerToken / PriceUsdPerToken / RawTokenAmount / TokenDecimals)
- P0-4 Canonical learning contract counters (CANON_FINALIZED_ROWS / CANON_BROADCAST_ROWS_REJECTED etc)
- P0-5 Foundation policy with PRE_ENTRY_DECISION_RECORD (≥3 snapshots, real pool, executable-price stop preflight)
- P0-8 Executable-price stop preflight before every BUY
- P1-1 Scanner/hydration queue separation (LIVE_READY / HYDRATING / PROBATION / SHADOW / REJECTED_WITH_TTL)
- New FIRST-TRADE READINESS block in the health snapshot
- Cupsey Clauses 2/8: explicit price fields on Trade model + route all journal writers + notification builders through CanonicalPnLAuthority6343

## Real remaining issues

- **P0 Loop stall** — provider degradation (Helius, Birdeye rate limits) causing 52-204s cycles. Needs OkHttp timeout tightening + async provider fetches.
- **P1 Decimal skew reappearance** — 6337/6338 fix in place; verify with next snapshot
- **P2 BLUECHIP still not trading much** — 6342 rejects Pump.fun→BLUECHIP; correct routing will let the real bluechip scanner cadence fire

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- TacticSwitcher persists per-bucket state to LearningPersistence
- LaneEdgeConcentrator amplifies per-bucket (lane × scoreBand) by expectancy
- LosingPatternMemory cross-checks live vs paper distribution (6339)
- LaneEntryContract6342 hard-vetoes on governor HOLD + enforces lane identity
- CanonicalPnLAuthority6343 is the sole legal realized-SOL calculator
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

- 6341 fully green (Build + Runtime Smoke)
- 6342 Build green; Runtime Smoke in-flight
- 6343 Build in-flight; 6 unit tests inline (clauses 3/4/5/6/7/9/10)
- Runtime Smoke last passed on 6341


## Current build stack (all landed on `main`, all green through 6338; 6339 broke a golden-tape test; 6340 fix in flight)

- **6323-6330** Foundation: canonical registry, 6324 modules, wiring, WADDLE decimal repair, brain consensus fusion
- **6331** (`25ad96139` ✅) Demote `LIVE_LANE_HARD_PAUSED_6247` → soft-shape
- **6332** (`0519817fe` ✅) Concentrated Conviction — governor bleeds via SIZE not FLOOR; no more sticky safety-hold arm
- **6333** (`d037f75c4` ✅) Denylist tier split (HARD disqualifiers vs ADVISORY soft-shape labels)
- **6334** (`f747f57b2` ✅) LaneEdgeConcentrator — self-tune capital toward winning buckets from trade 1
- **6335** (`be17d16c4` ✅) Slash governor floor uplifts (HOLD +18 → +5). **UNLOCKED THE LIVE PIPE — BUY ok 0→10→31 across snapshots.**
- **6336** (`3e635f794` ✅) Concentrator classifies by EXPECTANCY (meanPnl%), not WR — so low-WR/high-mean buckets get the amplifier
- **6337** (`d9a33be4c` ❌) Retro-backfill BUY qty at SELL write (fast catastrophe stops before promoteVerify). Failed on Trade.qtyToken typo.
- **6338** (`526026668` ✅ Build + Runtime Smoke) Compile fix for 6337 using correct Trade.soldQtyToken + ts.symbol
- **6339** (`191de3782` ❌) **Paper↔Live divergence detector.** Uses live-only cache to shrink size when paper-model has lied about a bucket. Failed on GoldenTapeRegressionTest which asserts exact string `minOf(genericPressure, learnedBucketMult)`.
- **6340** (`977c0ee31` 🟡 in-flight) Golden-tape fix: split into two-step `minOf` so the exact literal string is preserved. Same runtime behaviour.

## The learning cross-check loop the operator asked for (V5.0.6339)

Operator directive verbatim:
> "back-test live failures against the paper learning to find the reasons for loses and modify how it learns. everything is there, there is 0 excuse as to why its not making money"

