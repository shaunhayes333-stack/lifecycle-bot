# AATE PRD — V5.0.6365

## Current build stack

- **6365** (`362ecd6a4` ✅) **REVERT V5.0.6361 canonical learning shim** — Operator said V5.0.6360 was growing the wallet at >80% WR, current build is bleeding with 100+ open positions. Diff'd V5.0.6360→V5.0.6361 and found `V3JournalRecorder.recordClose` was wrapped in a `CanonicalLearningContract6346.assess` gate using a synthetic Trade shim with hardcoded `entryQtyToken=0.0, soldQtyToken=0.0` (recordClose has no qty parameter). Any close reaching this recorder with `sizeSol<=0` OR `entryPrice<=0` (stale positions, partial refunds, price-glitched exits) hit the contract's SELL missing-basis branch and was QUARANTINED — silently starving `ScoreExpectancyTracker / HoldDurationTracker / ExitReasonTracker / LaneExitTuner / TacticSwitcher / ColdStreakDamper / DamageControlGate / LanePolicy / RetrainingDecay`. Over hours the learning drifted and the bot could no longer reject its own losers. Wrap reverted; V5.0.6361's Executor paper full-exit qty preservation is unchanged.

- **6364** (`fd8331cf0` ✅) SOURCE-OF-CREATION: probation zero-liq HELD + cycle-time throttle-arm removed
- **6363** (`523ab4ed8` ✅) Scanner circuit breaker + brain-mult floor + throttle observability
- **6362** (`b52f383dc` ✅) Locale-free formatter (ANR cure) + Supervisor emergency throttle RE-ARM
- **6361** (`0749a6404` ✅) Paper full-exit qty preservation (KEPT) + CanonicalLearningContract E2E wire-up (REVERTED in V5.0.6365)
- **6360** (`029d677b4` ✅) LAST KNOWN-GOOD baseline before regression

## V5.0.6360 → V5.0.6365 regression hunt

| Version | Change | Verdict at V5.0.6365 |
|---|---|---|
| 6361 (executor) | Paper full-exit qty preservation | KEEP — legit fix at correct layer |
| **6361 (V3 recorder)** | **Canonical contract shim wrap** | **REVERTED — root cause of WR/wallet regression** |
| 6362 | Locale-free formatter | KEEP — cures ANR |
| 6362 | Supervisor throttle re-arm (worker-timeout path) | KEEP |
| 6362 | Supervisor throttle re-arm (cycle-time path) | REVERTED in V5.0.6364 (self-reinforcing loop) |
| 6363 | Scanner circuit breaker | KEEP |
| 6363 | Brain multiplier floor at 0.50 | KEEP — safety floor on dust-crush from small-sample tuples |
| 6363 | Quarantine log rate-limit | KEEP |
| 6363 | Throttle heartbeat | REMOVED in V5.0.6364 (behavior no longer applies) |
| 6364 | Probation zero-liq HELD | KEEP |
| 6364 | Cycle-time throttle-arm removal | KEEP |

## What operator should see after V5.0.6365 lands

1. **Learning aggregators receive every close again** — same behaviour as V5.0.6360. No `CANONICAL_LEARNING_AGGREGATOR_SKIPPED_6361` events (that label is gone).
2. **`TacticSwitcher / ColdStreakDamper / LanePolicy / RetrainingDecay` should quickly re-learn from the recent close waves** — expected effect: bleeding lanes get demoted, tactics rotate, sizing adjusts within the first ~50 closes post-deploy.
3. **Open-position count should shrink** as sells/exits process normally, learning kicks in, and the bot stops opening losers.

## Backlog

### 🟠 P0 — Still deferred
- Move locale-free formatting inside `ForensicLogger` (fix once, all callers safe).
- Verify sell dispatcher isolation.
- Enforce canonical eligibility at the CORRECT layer (Executor sell path / FillLotLedger6344, both have real qty).

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD.
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode (`PerpsLaneGate.kt`)

### 🟢 P3+ — Off-thread rendering, neural bridge, LLM Lab

## Testing / CI

V5.0.6344 → V5.0.6365 all ✅ SUCCESS on GH Actions. V5.0.6365 restores the V5.0.6360 learning fanout exactly.

- **6362** (`b52f383dc` ✅) Locale-free formatter (ANR cure) + Supervisor emergency throttle RE-ARM

- **6361** (`0749a6404` ✅) Paper full-exit qty preservation + CanonicalLearningContract end-to-end wire-up

## Full V5.0.6350 → V5.0.6364 stack

- **6350-6353** Paper close retry-loop drain, readiness fix, cycle evict, log aggregation
- **6354→6355** Scanner router wire-up (compile red → fix)
- **6356** LiveProbabilityEngine forensic rate-limit
- **6357** LaneBucketPivot whole-lane fallback removal (WR -20% root cause)
- **6358** StrategyTruthLedger TTL cache + rate-limit
- **6359** Foundation Policy live-wire
- **6360** Paper close force-RESET (not terminal)
- **6361** Paper full-exit qty preservation + Learning Contract E2E
- **6362** Locale-free formatter + Supervisor throttle RE-ARM
- **6363** Scanner circuit breaker + brain-mult floor + throttle observability
- **6364** SOURCE-OF-CREATION: probation zero-liq HELD + cycle-time arm removed

## V5.0.6363 → V5.0.6364 root causes fixed

| Symptom | Real source | Fix |
|---|---|---|
| Cycles 87s-171s, sells starving | PROBATION zero-liq churn: 1278 dead tokens force-promoted through intake every 5min | Widen noPairCold HELD guard to cover all dead-signal entries |
| 2099 emergency-throttle arms/96min → permanent clamp | Cycle-time arm was self-reinforcing (arm→clamp→starve exits→slower cycle→arm) | Remove cycle-time trigger; keep worker-timeout arm |
| ANR maxFrameGapMs=22245 back | Locale.clone from remaining ~700 String.format sites | (Deferred — extended locale-free migration is separate P0 workstream) |
| WR 80% → 32% | V5.0.6363 brain floor is band-aid; underlying is dust-crush from small-sample tuples | (Deferred — brain-floor kept for now, needs deeper audit) |

## Backlog (priority-ordered)

### 🔴 P0 — Deferred from V5.0.6364 (still needed but at the RIGHT layer)
- **Locale-free logging at the source** (not per-callsite): move formatting INSIDE `ForensicLogger.lifecycle/exec/etc` so all 700 sites are safe by default. One fix, not 700 migrations.
- **CanonicalLearningContract close-hook audit**: V5.0.6361 wired assess() into every close; verify it's on Dispatchers.IO not the main loop.
- **Sell-path dispatcher isolation**: verify sells actually run on their own dispatcher (V5.0.6362 comment claims this but the code path isn't visibly enforced).

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD.
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode (`PerpsLaneGate.kt`)

### 🟢 P3+ — Off-thread rendering, neural bridge, LLM Lab

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- CanonicalPnLAuthority6343 sole legal realized-SOL calculator
- FillLotLedger6344 sole legal cost-basis source
- ScannerHydrationQueues6347 router of record
- **V5.0.6364: processProbation HOLDS any zero-signal entry; never force-promotes dead tokens through intake**
- **V5.0.6364: supervisor emergency throttle arms ONLY on real worker timeouts, never on cycle time**
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

V5.0.6344 → V5.0.6364 all ✅ SUCCESS on GH Actions.

- **6323-6341** Foundation: canonical registry, WADDLE decimal repair, brain consensus, safety-hold demotion, loop stall fixes
- **6342** (`7a6e23639` ✅) **Lane Entry Contract** — governor HOLD veto + BLUECHIP/QUALITY identity
- **6343** (`dd4f2d0a2` ✅) **Canonical PnL Authority** — single source of realized-SOL truth
- **6344** (`1f85cb2f9` ✅) **Immutable FillLotLedger + strong unit types + Canonical PnL Conduit**
- **6345** (`45f6665bf` ✅) **Foundation Policy (PRE_ENTRY_DECISION_RECORD) + Executable-Price Stop Preflight**
- **6346** (`9ac9ec6bb` ✅) **Canonical Learning Contract** — P0-4
- **6347** (`4907b225c` ✅) **Scanner/Hydration Queue Separation** — P1-1
- **6348** (`61585afef` ✅) **FIRST-TRADE READINESS health block + priority ranking** — P1-2
- **6349** (`dfc1faa2a` ✅) **Golden-tape guard for 6344→6348**
- **6350** (`49daf75f9` ✅) **Paper close retry-loop drain**
- **6351** (`9dd292ecd` ✅) **FIRST_TRADE_READINESS false-alarm fix**
- **6352** (`7ac752277` ✅) **Loop-cycle emergency evict**
- **6353** (`d69ad98e5` ✅) **Rebalance held-block log aggregation**
- **6354** (`04dd83524` ❌) → **6355** (`7f7ea7ed3` ✅) Wire scanner emit + executor lane contract into router
- **6356** (`f9c3abdf8` ✅) **Rate-limit LiveProbabilityEngine ForensicLogger spam**
- **6357** (`4039664ba` ✅) **LaneBucketPivot: remove whole-lane fallback (WR -20% root cause)**
- **6358** (`46d5a037a` ✅) **StrategyTruthLedger TTL cache + rate-limit QUALITY_BOOST / DUST_STACK**
- **6359** (`cebc20c9d` ✅) **Foundation Policy live-wire (PreEntryDecisionRecord6345.emit on every LaneEntryContract pass)**
- **6360** (`029d677b4` ✅) **Paper close force-RESET (not terminal)**
- **6361** (`0749a6404` ✅) **Paper full-exit qty preservation + CanonicalLearningContract end-to-end**
- **6362** (`b52f383dc` ✅) **Locale-free formatter (ANR cure) + Supervisor emergency throttle RE-ARM**

## Emergency triage delivered from V5.0.6361 pipeline snapshot

| Symptom | Fix | Version |
|---|---|---|
| Max frame gap 25610ms (main-thread ANR stalls) | Locale-free integer-arithmetic formatter kills `Locale.clone` path | V5.0.6362 |
| ForensicLogger post-per-event took MessageQueue lock on main thread | Batched drain: one Runnable per ~128 events | V5.0.6362 |
| 300+ worker timeouts/10min while `SUPERVISOR_EMERGENCY_THROTTLE_OBSERVED_DISARMED` just logged | Emergency throttle re-armed: 5-min clamp window, cap→16 while active | V5.0.6362 |

## Backlog (priority-ordered)

### 🔴 P0 — Full locale-free migration (deferred from V5.0.6362)
- 702 remaining `String.format`/`"%.Nf".format(x)` sites across the engine. Requires golden-tape re-baseline (numeric strings identical but reached via new path). Do lane-by-lane with per-directory tests.

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD (block sell path when authority quarantines).
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block on the buy ticket.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode
- Resume `PerpsLaneGate.kt` now that the pipeline is stable.

### 🟢 P3+ — Off-thread rendering (chart + trade-list diffing), neural bridge, LLM Lab

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- TacticSwitcher persists per-bucket state to LearningPersistence
- LaneEdgeConcentrator amplifies per-bucket (lane × scoreBand) by expectancy
- LaneEntryContract6342 hard-vetoes on governor HOLD + enforces lane identity
- CanonicalPnLAuthority6343 is the sole legal realized-SOL calculator
- FillLotLedger6344 is the sole legal cost-basis source (immutable, first-write-wins)
- CanonicalLearningContract6346 is the sole legal canonical-eligibility gate
- ScannerHydrationQueues6347 is the router of record; drained on cycle overrun via LoopCycleEmergencyEvict6352
- FirstTradeReadiness6348 has advisory-vs-hard pillar distinction so the tile does not lie
- PaperPositionCloseAuthority force-RESETS (not terminals) after 3 stuck retries via V5.0.6360
- **V5.0.6362: SupervisorEmergencyThrottle re-armed — cap actually clamps under sustained overload**
- **V5.0.6362: All main-thread decimal formatting on hot paths is locale-free (no `Locale.clone` stall)**
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

All V5.0.6344 → V5.0.6362 ✅ SUCCESS on GH Actions.
V5.0.6362 required a one-commit follow-up (`6362a`) to restore a golden-tape literal after refactoring the tier logic into a pure helper.

