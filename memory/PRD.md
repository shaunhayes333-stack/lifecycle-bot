# AATE PRD — V5.0.6374

## Current build stack

- **6374** (pending push) **Scanner Fanout Throttle + Aggregate-Bad-Band Rotation + Heatmap ANR Fix** — single atomic P0 bundle addressing the three operator directives from the final V5.0.6373g snapshot:
  1. `ScannerFanoutDedupe6374` — per-(source, mint) 60s TTL dedupe upstream of `admitProtectedMemeIntake` in `BotService.wireExternalStreams`. Collapses PUMP_PORTAL_WS re-emit burst (snapshot: 788 intake events → 184s cycles). Fluid/tunable via `setTtlMs()`, bounded 4096 entries.
  2. `TacticSwitcher` aggregate-bad-band gate — `AGG_BAD_BAND_MIN_SAMPLES=50`, `AGG_BAD_BAND_MIN_LOSS_RATE=0.70` (WR<30%). Fires in `onTradeClosed` (since-rotation) AND `maybeRotateFromMemory` (lifetime). Kills MOONSHOT|S41-60 REACCUMULATION n=67 W/L=15/52 bleeder. Rotation only, never disable.
  3. `HeatmapRenderCache6374` + `MainActivity.renderWrRecoveryHeatmap` — heatmap SpannableString compute moved to `Dispatchers.Default` with a 15s coalesced background refresh. Eliminates the top blocking main-thread call site captured immediately before the 5-hour ANR lockup.
  - Bundle6374InvariantsTest.kt (11 assertions) covers all three fixes.

- **6373g** (`406bd6809` ✅) Fix compile: TokenState.mcap field removal from log line.
- **6373f** (`b93130fc6` ✅) Hard-block PRESALE_SNIPE/RESALE_SNIPE/FRESH_LAUNCH at paperBuy source.
- **6373e** (`aa94bb744` ✅) Fix V5.0.6373d compile (Trade field name reconciliation).
- **6373d** (`0b2f7d5e0` ✅) Wide bundle A+B+C: phantom pnl% recompute + phantom-retry dedupe + Trade.pnlPct mutable.
- **6373c** (`71c768be4` ✅) **Ghost Paper Purge NEUTRALIZED — primary regression fix.** Operator's V5.0.6364 baseline showed everything working (display, volume, WR/EV). V5.0.6366 F3 ghost purge whitelisted only 5 V3 sub-traders (ShitCoin/Moonshot/BlueChip/Quality/CashGen) → paper buys under WHALE_FOLLOW / COPYTRADE / PRESALE_SNIPE / MICRO_CAP / TREASURY / CYCLIC / MOMENTUM_SWING / LAB got mis-classified as ghosts and got `ts.position = Position()` reset every reconcile tick. Ghost predicate replaced with positive-existence check against TradeHistoryStore latest-buy. V5.0.6372 universal 2×–5× daily wallet-growth compound target KEPT per operator ("thats aate policy!").

- **6373b** (`81788486d` ✅) Canonical Position Sentinel at paperSell entry (source-of-creation P0-1+P0-2+P0-3 minimum). Blocks phantom sells when `ts.position` disagrees with `TradeHistoryStore` latest-buy by >2× cost/qty or `pos.costSol < 0.05 && buy.entryCostSol >= 0.05`. Emits `SELL_BLOCKED_NO_CANONICAL_POSITION_6373` and returns `FAILED_RETRYABLE` without touching real position or journaling.
- **6373a** (`f93efe655` ✅) Compile fix for V5.0.6373's V3 pre-empt if/else structure.
- **6373** (`94a84c8b1` ✅) V3 execute route same-mint pre-empt + trade-1 catastrophic rotation (≥90%) + skew-taint learning quarantine + CryptoAlt content-diff render skip.
- **6372a** (`596d9054f` ✅) Fix stale 6371 Golden Tape order assertion
- **6372** (`0a1eb8cfc` ✅) UNIVERSAL 2×–5× daily compound target (AATE core doctrine — KEPT)
- **6371** (`af01b13c4` ✅) OpenGate same-mint cooldown + ghost-zero family unlock
- **6370b** (`48c730a68` ✅) Golden Tape 6369 accepts renamed PAPER_BUY_OPENED_6370 label
- **6370a** (`199080580` ✅) Fix paper alias guard telemetry compile
- **6370** (`a5cfae95d` ✅) Global same-mint paper open guard
- **6369** (`e0a485ccb` ✅) ExecutionAttemptLease race-claim on paperBuy
- **6368a** (`dde34de6d` ✅) Fix Bundle6368 test imports
- **6368** (`7cd19c16f` ✅) Magnitude Downstream + ForensicLogger locale-free/zero-liq quarantine + 20s report watchdog
- **6367a** (`825f9d460` ✅) Restrict magnitude trigger to initial MOMENTUM only (later overridden by V5.0.6373 trade-1 catastrophic path)
- **6367** (`da24c9694` ✅) Self-learning from trade 1
- **6366c** (`84adffdf5` ✅) Golden-tape refresh for 6366 F4
- **6366b** (`f721b8113` ✅) STALE_FLAT_CULL_6366
- **6366** (`d17e05e55` ⚠️ *F3 neutralized in 6373c*) Worker-timeout raise + ghost paper purge (F3 replaced) + learning-ceiling raise
- **6365** (`362ecd6a4` ✅) REVERT V5.0.6361 canonical learning shim
- **6364** (`fd8331cf0` ✅) Operator's known-good baseline before regression

## V5.0.6364 → V5.0.6373c regression audit

| Version | Change | Verdict at V5.0.6373c |
|---|---|---|
| **6366 F3** | **Ghost paper purge whitelist** | **NEUTRALIZED in 6373c — root cause of held-tokens-invisible + phantom sells** |
| 6366 F1a | Worker timeout 9s → 15s | KEEP — external-API tolerance |
| 6366 F4 | Learning-eligibility ceiling → 20 SOL | KEEP — learning-only ceiling |
| 6366b F5 | STALE_FLAT_CULL_6366 | KEEP — real exit path, not corruption |
| 6367 | Trade-1 magnitude + LanePolicy early demote | KEEP |
| 6367a | Restricted magnitude to MOMENTUM only | KEEP but superseded by 6373 trade-1 catastrophic (any tactic ≥90%) |
| 6368 | Locale-free helpers + zero-liq quarantine + magnitude downstream | KEEP |
| 6369 | ExecutionAttemptLease race-claim | KEEP |
| 6370 | GLOBAL SAME_MINT paper open guard | KEEP |
| 6371 | OpenGate same-mint cooldown | KEEP |
| **6372** | **UNIVERSAL 2×–5× compound target** | **KEEP — AATE core doctrine, not a regression** |
| 6373 | V3 pre-empt + trade-1 catastrophic + skew-taint + content-diff skip | KEEP (my push) |
| 6373b | Canonical Position Sentinel at paperSell | KEEP (my push) |
| 6373c | Ghost purge positive-existence predicate | KEEP (my push) |

## What operator should see after V5.0.6373c lands

1. **Held tokens visible again**: paper positions under WHALE_FOLLOW / COPYTRADE / PRESALE_SNIPE / MICRO_CAP / TREASURY / CYCLIC / MOMENTUM_SWING / LAB no longer vanish between reconcile ticks.
2. **Phantom sells STOP**: any sell path that would read a default `ts.position` gets blocked by V5.0.6373b canonical sentinel; the ghost-purge that CREATED those defaults is neutralized in 6373c.
3. **Volume returns**: EmergentGuardrails position registry stays populated, so `V3_EXEC_SAME_MINT_PREEMPT_6373` fires only for genuine duplicates instead of ghost-purged holes.
4. **Universal 2×–5× compound target intact**: sizing still pushes all lanes to hit the daily growth doctrine.

## Backlog

### 🟠 P0 — Deferred forensic-repair spec items
- P0-4: Immutable price identity hash + PRICE_IDENTITY_CONFLICT
- P0-5: tokenDecimals nullable + decimalsKnown boolean
- P0-6: performanceDomain enum (LIVE_CANONICAL / PAPER_PARITY / SHADOW_RESEARCH)
- P0-7: PAPER_PARITY score-floor parity with LIVE governor
- P0-8: LaneEligibilityContract
- P0-10: Quarantine corrupted history + rebuild tactic stats
- P0-11: Full forensic journal fields + CANONICAL_INTEGRITY_STATUS section
- **Empty UI panels**: ALL TRADERS, 30-DAY PROOF RUN, lane cards show 0 when top card shows 181 trades. Investigate `journalParityStatsSnapshot6085()` null bailout at MainActivity:2765.

### 🔵 P1 — After stabilization
- Phase 1: SOL Perps/Leverage mode → `PerpsLaneGate.kt`
- Move chart rendering + trade-list diffing off main thread

### 🟣 P2/P3
- Phase 2: Neural bridge (perps↔stocks cross-learning)
- Phase 3: LLM Lab sandbox


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

