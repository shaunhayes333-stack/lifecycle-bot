# AATE PRD — V5.0.6357

## Current build stack

Continuing from V5.0.6356 emergency triage:
- **6357** (`4039664ba` — CI running) **LaneBucketPivot whole-lane fallback removed** — WR -20% root cause. Every pump.fun score=0 mint was catching DEEP_WINNER (×1.35 upsize) because `winnerMeanForBucket` fell back to pooled-lane winners when the S0-19 band had <2 samples. That upsized garbage entries and drove the WR regression. Fix: require ≥2 winner-DNA rows IN THE SAME BAND. Whole-lane rows remain as context via the score>0 filter but never substitute for band evidence.

## Full V5.0.6350 → V5.0.6357 emergency triage stack

- **6323-6341** Foundation: canonical registry, WADDLE decimal repair, brain consensus, safety-hold demotion, loop stall fixes
- **6342** (`7a6e23639` ✅) **Lane Entry Contract** — governor HOLD veto + BLUECHIP/QUALITY identity
- **6343** (`dd4f2d0a2` ✅) **Canonical PnL Authority** — single source of realized-SOL truth (Cupsey partial-lot correction)
- **6344** (`1f85cb2f9` ✅) **Immutable FillLotLedger + strong unit types + Canonical PnL Conduit** — P0-1/2/3 wired
- **6345** (`45f6665bf` ✅) **Foundation Policy (PRE_ENTRY_DECISION_RECORD) + Executable-Price Stop Preflight** — P0-5 + P0-8
- **6346** (`9ac9ec6bb` ✅) **Canonical Learning Contract** — P0-4 exact-decimal + parity + cost-basis + LIVE_BROADCAST rejection
- **6347** (`4907b225c` ✅) **Scanner/Hydration Queue Separation** — P1-1 LIVE_READY / HYDRATING / PROBATION / SHADOW / REJECTED_WITH_TTL
- **6348** (`61585afef` ✅) **FIRST-TRADE READINESS health block + priority ranking** — P1-2, five pillars surfaced in AATE Pipeline Health Snapshot
- **6349** (`dfc1faa2a` ✅) **Golden-tape guard for 6344→6348** — file-shape + wire-up assertions
- **6350** (`49daf75f9` ✅) **Paper close retry-loop drain** — 779 stuck retries/15min → hard-cap 3 retries + force-terminal
- **6351** (`9dd292ecd` ✅) **FIRST_TRADE_READINESS false-alarm fix** — SCANNER + QUARANTINE pillars advisory
- **6352** (`7ac752277` ✅) **Loop-cycle emergency evict** — sheds HYDRATING backlog when cycle >20s
- **6353** (`d69ad98e5` ✅) **Rebalance held-block log aggregation** — 5855 events/15min → 1 per pass
- **6354** (`04dd83524` ❌ compile fail) Wire scanner emit + executor lane contract into router (compile bug: `laneAffinity` is `Set<String>` not `String`)
- **6355** (`7f7ea7ed3` ✅) **Compile fix for 6354** — `laneAffinity.firstOrNull() ?: "STANDARD"`. Scanner router now fully wired.
- **6356** (`f9c3abdf8` ✅) **Rate-limit LiveProbabilityEngine ForensicLogger spam** — RAPID_PIVOT_SHAPED_4572 / SIZE_SHAPE_5999 / RAW_REALITY_CLAMP_6000 firing 30-50 events per ms → gated by ForensicEmitRateLimiter6356 (30s cooldown per lane per label). Root cause of the max-cycle 123435ms ANR spike.

## Emergency triage delivered from V5.0.6349 pipeline snapshot symptoms

| Symptom | Fix | Version |
|---|---|---|
| 779 PAPER_CLOSE_STUCK_TTL_RETRY events / 15min → 100+ open paper positions | 30s TTL + 3-retry hard cap + force-terminal | V5.0.6350 |
| FIRST_TRADE_READINESS false ready=N because SCANNER_LIVE_READY_QUEUE never wired | Advisory pillars don't fail readiness | V5.0.6351 |
| Loop cycles avg 8.4s / max 33.9s / max 123s regressed | Emergency evict HYDRATING when cycle >20s | V5.0.6352 |
| LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED 5855 events/15min | Aggregate to 1 per rebalance pass | V5.0.6353 |
| SCANNER_LIVE_READY_QUEUE:P2 forever because no wire-up | Wired scanner emit + executor lane contract into router | V5.0.6354→6355 |
| LIVE_PROBABILITY_*_4572/5999/6000 spamming 30-50/ms per lane | 30s cooldown rate limiter around ForensicLogger.lifecycle | V5.0.6356 |

## Backlog (priority-ordered)

### 🔴 P0 — Wire remaining library-only modules into live paths
- **Pre-entry ticket → PreEntryDecisionRecord6345.emit**: call from the live-buy ticket assembler right before the buy lease is acquired.
- **Learning aggregators → CanonicalLearningContract6346.assess**: retrofit AdaptiveLearningEngine, LaneEdgeConcentrator sample, expectancy classifier, tactic switcher.
- **Every stop placer → ExecutablePriceStopPreflight6345.preflight**: call before stop persistence in Executor and V3JournalRecorder.

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD (block sell path when authority quarantines).
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block on the buy ticket.
- Extend `ForensicEmitRateLimiter6356` to other high-frequency emitters if the next snapshot still shows disk churn.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode
- Resume `PerpsLaneGate.kt` now that the core accounting/learning contracts + pipeline health are landed.

### 🟢 P3+ — Off-thread rendering, neural bridge, LLM Lab

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- TacticSwitcher persists per-bucket state to LearningPersistence
- LaneEdgeConcentrator amplifies per-bucket (lane × scoreBand) by expectancy
- LaneEntryContract6342 hard-vetoes on governor HOLD + enforces lane identity
- CanonicalPnLAuthority6343 is the sole legal realized-SOL calculator
- FillLotLedger6344 is the sole legal cost-basis source (immutable, first-write-wins)
- CanonicalLearningContract6346 is the sole legal canonical-eligibility gate
- ScannerHydrationQueues6347 is now the router of record; drained on cycle overrun via LoopCycleEmergencyEvict6352
- FirstTradeReadiness6348 has advisory-vs-hard pillar distinction so the tile does not lie
- PaperPositionCloseAuthority force-terminals after 3 stuck retries via V5.0.6350
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

All V5.0.6344 → V5.0.6353, V5.0.6355, V5.0.6356 ✅ SUCCESS on GH Actions.
V5.0.6354 red (compile) → V5.0.6355 fix ✅ SUCCESS.
