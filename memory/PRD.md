# AATE PRD — V5.0.6401d (Intake Fallback + Async Cold-Start Loads)

## Session shipping stack (6388 → 6401d, all CI GREEN)

- **6401d** (`6f052a763` ✅ Build) INTAKE NO-PAIR FALLBACK + ASYNC
  ANR-LOAD.
  * 6401c snapshot showed 70/70 INTAKE events blocked by
    `NO_PAIR_NO_FALLBACK` while DexScreener was degraded, plus
    12.8% main-thread stall time with `ScannerHardRejectStore.load
    × 3` and `DynamicAltTokenRegistry.restoreFromDisk × 1` in the
    top ANR sites.
  * `BotService.processTokenCycle` no-pair branch now:
    - Fast-seeds `ts.lastPrice = ts.lastMcap / 1_000_000_000` for
      pump.fun-native sources (bonding curve has KNOWN 1e9 supply)
      so `synthesizeFallbackPair` fires BEFORE the 15-20s oracle
      chain. Emits `INTAKE_PUMPFUN_SOURCE_NATIVE_SEED_6401`.
    - Records canonical `PairHydrationState6398` snapshot (PENDING
      / SOURCE_NATIVE / HARD_UNAVAILABLE) into the
      `NO_PAIR_NO_FALLBACK` forensic row so operators can see
      whether it's still hot or actually exhausted.
  * `ScannerHardRejectStore.init` and
    `DynamicAltTokenRegistry.init` dispatch the disk load to a
    daemon background thread — no more synchronous
    SharedPreferences / JSON parse on the caller thread.
  * Bundle6401IntakeHydrationWireTest — 5 invariants covering
    pump.fun bonding curve as SOURCE_NATIVE, fresh intake as
    PENDING, aged as HARD_UNAVAILABLE, Raydium scanner pool
    survival, and DexScreener-degraded → non-erasure of
    source-native.
- **6401c** (`4ca7c6bcf` ✅) ANR-safe full-report share +
  `SellIntentQuantityAuthority6401`.
- **6401b** (`ade11f566` ✅) — compile fix for Bundle6399 testF.
- **6401a §4** (`2c1e66d9f` ✅) STARTUP EXIT-ONLY LATCH wired.
- **6401 P1** (`755075b2d` ✅) FDG parity terminal wire.
- **6400a** (`158c364fb` ✅) HARD SCORE FLOOR ERADICATED.
- **6399** (`9ce60f077` ✅) ENTRY AUTHORITY ORDERING + SPLIT-BRAIN.
- **6398a** (`1576f1153` ✅) CANONICAL FLUID ENTRY AUTHORITY REPAIR.
- **6397a/b** (`61d1d2c85` ✅) ADAPTIVE FLOOR BRAIN — fluid [12, 22].
- **6396** (`b43f74167` ✅) LIVE SCORE-SCALE REALIGNMENT.
- **6395a** (`9fc222307` ✅) EXECUTABLE RUNNER + POSITION IDENTITY.
- **6394c** (`b3efddd20` ✅) EarlyLaunchBypass wired into FDG.

## Snapshot expectations for the V5.0.6401d APK

1. `INTAKE/NO_PAIR_NO_FALLBACK` counter drops sharply for
   pump.fun-native sources (7 sources in the last snapshot).
2. `INTAKE_PUMPFUN_SOURCE_NATIVE_SEED_6401` counter fires for
   every pump.fun mint that used to blow through to the oracle
   chain.
3. `NO_PAIR_NO_FALLBACK` forensic rows now carry
   `hydrationState=PAIR_PENDING_HYDRATION | PAIR_SOURCE_NATIVE |
   PAIR_HARD_UNAVAILABLE`.
4. `ScannerHardRejectStore.load` and
   `DynamicAltTokenRegistry.restoreFromDisk` no longer appear in
   top ANR blocking sites.
5. All V5.0.6401a-c counters (BUY_DEFERRED_STARTUP_6401,
   PIPELINE_REPORT_FILE_WRITTEN_6401,
   QTY_DECIMAL_SKEW_6401_LIKELY_10X_DECIMALS,
   FDG_* terminals) continue as prior.

## Next backlog

### 🔴 P0 — V5.0.6401 remaining sections
- **Bot cycle max 148s / avg 11.4s** — 6401c snapshot showed
  extreme cycle latency; primary suspect is the 15-20s oracle
  fallback chain that we bypass for pump.fun in 6401d but still
  fires for other sources. Consider moving the whole
  `tryFallbackPriceData` chain off the per-token synchronous
  path.
- **§7/§8 Sell path migration** — wire
  `SellIntentQuantityAuthority6401.validateSellIntent` into every
  Executor sell callsite (substrate is landed).
- **§3 Legacy bypass authorities** — record
  `LANE_QUARANTINED_BLOCKED_ENTRY_6002` +
  `EXPRESS_LANE_PAUSED_EARLY_GATE_4594` via
  `CounterParityLedger6399.recordTerminal` so parity ledger
  tracks them.

### 🟠 P1 — verification + monitoring
- **Live Session Validation** — run V5.0.6401d APK. Confirm:
  * INTAKE allow > 0 (was 0/70 in the last snapshot).
  * Max cycle ms drops from 148s to < 30s.
  * ANR stall % drops well below 12.8%.
  * `PIPELINE_REPORT_FILE_WRITTEN_6401` fires on every operator
    Copy tap; Share dialog exposes the full 60k+ line file.
- **Governor Soft-Only Verification** — audit every governor
  state mapping.

### 🟢 P2 — Phase 1 SOL Perps/Leverage
- Gated on live WR + 2×–5× daily benchmark hit.




# (Legacy) AATE PRD — V5.0.6393

## Session shipping stack (6388 → 6399, all CI GREEN)

- **6399** (`9ce60f077` ✅ Build) ENTRY AUTHORITY ORDERING + SPLIT-BRAIN
  REMOVAL. Root-cause repair for 6398 signature (blocked/shadow
  candidates still reaching MEME_LIVE_EXEC_ENTRY / EXEC_TICKET_CREATED
  alongside BUY_FAILED + ENTRY_REJECTED_SCORE_FLOOR).
  * RouteMode6399 (LIVE / SHADOW_READ_ONLY / DEFERRED / BLOCKED)
    resolved BEFORE ticket creation.
  * FdgTerminalOutcome6399 — 6 canonical terminals; exactly one per evalId.
  * AuthorityInvariants6399 — hard-check assertAllowLiveBeforeTicket
    (outcome=ALLOW_LIVE && score>=floor && route=LIVE && !denylist &&
    !shadow). Violations throw + emit AUTHORITY_INVARIANT_FAILURE_6399
    / SHADOW_ENTERED_LIVE_PATH_6399 / DENYLISTED_ENTERED_LIVE_PATH_6399.
  * CounterParityLedger6399 — funnel counters DERIVED from canonical
    terminals; parity guards enforce live_tickets==FDG_ALLOW_LIVE,
    exec<=tickets, BUY_ATTEMPT<=exec, BUY_FAILED<=BUY_ATTEMPT.
  * RuntimeDoctor6399 — authority-aware diagnosis; SPLIT_BRAIN /
    TICKET_BEFORE_ALLOW / PARITY / POLICY_MISFILE / LANE_DISABLED /
    RECONCILER_NOT_RUNNING all OVERRIDE HEALTHY.
  * CanonicalEntryPipeline6398.issueAndRegister now enforces the
    invariants before minting; shadow / denylisted / non-live-route
    callers return null. Every live ticket increments the parity
    ledger so drift is auditable in real time.
  * BotService.processTokenCycle calls
    ScannerHeatPublisher6398.onHydratedCandidate at cycle entry —
    fluid floor now sees real-time market temperature.
  * Bundle6399EntryAuthorityOrderingTest covers all 12 regression
    tests A..L + parity fail-loud + healthy diagnosis.

- **6398a** (`1576f1153` ✅) CANONICAL FLUID ENTRY AUTHORITY REPAIR —
  immutable envelopes, pipeline, gate-block cache, pair hydration
  states, scanner-heat + cognitive-advisory + lane-performance
  publishers.
- **6397a/b** (`61d1d2c85` ✅) ADAPTIVE FLOOR BRAIN — fluid [12, 22].
- **6396** (`b43f74167` ✅) LIVE SCORE-SCALE REALIGNMENT (55/56 → 15/17/20).
- **6395a** (`9fc222307` ✅) EXECUTABLE RUNNER + POSITION IDENTITY REPAIR.
- **6394c** (`b3efddd20` ✅) EarlyLaunchBypass wired into FDG.
- **6394b** (`358316d9a` ✅) 25%-divergence quarantine test fix.

## Next backlog

- **P1 LiveEntrySafetyHold_6312 migration** — remove residual score-
  floor logic and switch to
  CanonicalEntryPipeline6398.validateTicket() only. The substrate is
  fully in place; the callsite refactor lands next.
- **P1 Buy-lease acquisition migration** — same treatment: ticket-in,
  ticket-validate-out, no independent score/floor recompute.
- **P1 FDG terminal-outcome emission** — wire real FDG to
  CounterParityLedger6399.recordTerminal so parity is auditable
  end-to-end.
- **P1 Journal correction pass** — historical LIVE_BUY_FAIL_TELEMETRY
  rows reclassified as ENTRY_REJECTED_SCORE_FLOOR (write-only, never
  mutate original evidence).
- **P2 ANR Killer**, **P2 Fill Ledger LiveExecutor extension**,
  **P3 SOL Perps/Leverage (Phase 1)**.

# (Legacy) AATE PRD — V5.0.6393

## Session shipping stack (6388 → 6398a, all CI GREEN)

- **6398a** (`1576f1153` ✅ Build) CANONICAL FLUID ENTRY AUTHORITY REPAIR.
  Single live-entry pipeline (SCANNER → INTAKE → HYDRATION →
  LANE_EVAL → SCORE_ENVELOPE → FLOOR_ENVELOPE → FDG → TICKET →
  LEASE → EXECUTOR). Every stage carries the SAME evaluationId.
  Legacy 55/56 mechanically unreachable at every layer.
  * `EntryAuthorityModels6398` — 4 immutable data classes
    (EntryScoreEnvelope, DynamicFloorEnvelope, EntryAuthorityDecision,
    EntryAuthorityTicket) + enums (TraderLane, TraderId, EntryTactic,
    LifecycleStage, DiscoverySource, EntryOutcome).
  * `CanonicalEntryPipeline6398` — buildScoreEnvelope,
    buildFloorEnvelope, decide, mintTicket, issueAndRegister,
    validateTicket. Bayesian shrinkage caps: <8=±2, 8..24=±5, >=25=±8.
  * `EntryGateBlockCache6398` — fingerprint dedupe
    (mint+lane+trader+tactic+modelVers+roundedFloor+dataVersion).
    Reeval TTL 30s or +2pt material score change. Duplicates emit
    only ENTRY_GATE_BLOCK_DUPLICATE_SUPPRESSED — never BUY_FAILED
    and never an executor call.
  * `PairHydrationState6398` — replaces NO_PAIR_NO_FALLBACK with
    PAIR_CONFIRMED / PAIR_SOURCE_NATIVE / ROUTE_CONFIRMED_WITHOUT_PAIR
    / PAIR_PENDING_HYDRATION / PAIR_HARD_UNAVAILABLE. Preferred order:
    Helius > DexScreener > Birdeye > PumpFun > Raydium scanner
    pool > watchlist > Jupiter route. DexScreener degradation never
    erases source-native pairs.
  * `ScannerHeatPublisher6398` — rolling 30s hydrated candidates/sec
    → 0..1 percentile → AdaptiveFloorBrain6397.postScannerHeat.
  * `CognitiveAdvisoryBridge6398` — postSuperAgi/postSsi/postLlm
    accept conviction in [-1, +1] → signed advisory delta (±3 per
    channel, combined cap ±5).
  * `LanePerformancePublisher6398` — ingest(rowId, lane, wasWin,
    pnlSol) guards through CanonicalPerformanceFilter6395 (rejects
    all 6395 quarantine reasons) → AdaptiveFloorBrain6397.postLaneStat.
  Tests: Bundle6398 covers A..J regression suite + all 3 wire-ups
  + legacy-anchor unreachability + Bayesian shrinkage caps.

- **6397a/b** (`61d1d2c85` ✅) ADAPTIVE FLOOR BRAIN — [12, 22] envelope
  is fluid inside itself. Scanner heat / dist p50 nudge / lane
  learning / advisory stack.
- **6396** (`b43f74167` ✅) LIVE SCORE-SCALE REALIGNMENT (55/56 → 15/17/20).
- **6395a** (`9fc222307` ✅) EXECUTABLE RUNNER + POSITION IDENTITY REPAIR.
- **6394c** (`b3efddd20` ✅) EarlyLaunchBypass wired into FDG.
- **6394b** (`358316d9a` ✅) 25%-divergence quarantine test fix.

## Next backlog

- **P1 Executor migration** — remove residual score/floor calculations
  inside LiveEntrySafetyHold_6312 and the buy-lease path; make
  them ticket-validation only (CanonicalEntryPipeline6398.validateTicket).
- **P1 Scanner intake hook** — call ScannerHeatPublisher6398.onHydratedCandidate
  from Scanner intake.
- **P1 Journal correction pass** — historical LIVE_BUY_FAIL_TELEMETRY rows
  with reason=SCORE_BELOW_LIVE_FLOOR reclassified as
  ENTRY_REJECTED_SCORE_FLOOR via EntryRejectionTelemetry6396.
  recordForensicCorrection.
- **P2 ANR Killer** — TradeHistoryStore + MainActivity.onCreate → IO.
- **P2 Fill Ledger** — extend BuyFillLedger6388 / SellFillLedger6388
  persistence to LiveExecutor paths (V3JournalRecorder already covered).
- **P3 SOL Perps/Leverage (Phase 1)** — gated on live WR + 2..5× daily.

# (Legacy) AATE PRD — V5.0.6393

## Session shipping stack (6388 → 6393, all CI GREEN)

- **6393** (`d49a61d20` ✅) FORENSIC FINALITY + TRADE-1 TUNER + WEEKLY GROWTH.
  Trade1AdaptiveTuner6393 (real n=1 shaping), CanonicalFill6393 (BigDecimal
  round-trip), PositionStateMachine6393 (exactly-once CLOSED_SETTLED),
  WalletBalanceProof6393 (HELD/ZERO/UNKNOWN + zero-close policy),
  ManagedVsRecoveredCounters6393 (21/21 regression), ExecutionTelemetrySemantics6393
  (SCORE_FLOOR ≠ BUY_FAIL), AsymmetricExitStructure6393, WeeklyGrowthMode6393,
  PositionSizing6393, GovernorEpoch6393 (STRATEGY_EPOCH_6393).
- **6392** (`a8ab1a76` ✅) LIVE CONTINUITY: CONTINUE_CLEAN_TRADING + PER_MINT_QUARANTINE.
  CanonicalWalletPosition6392 (one per wallet+mint), MintDecimalsAuthority6392,
  SafeSellCalculator6392, ExitMutex6392, BroadcastLiability6392, CanonicalWalletParity6392,
  VerifiedBluechipIdentity6392, ExternalRugClassification6392, EntrySizingProgression6392.
- **6391** (`028a46b0` ✅) UNBLOCK sell-only hold. OwnershipClassification6391 (6-class),
  SellOnlyHold6391 (release-first), EffectiveLiveAuthority6391, ExecutionCircuitBreakers6391
  (8-namespace), ExitRoutePlan6391, PumpRescueUnifiedBuilder6391.
- **6390** (`eaf4d492` ✅) EARLY-ENTRY + PEAK CAPTURE. EarlyEntryScout6390 (CHEEMS-class
  46.5K MC detector), PeakAdaptiveTrail6390, PeakSlipExit6390, WinnerLadderExit6390
  (25/25/25/25), PeakCaptureAuthority6390.
- **6389** (`104743a7` ✅) CANONICAL SETTLEMENT + COHORT + ANR HARDENING.
  CanonicalCloseFinality6389 (3 legal sources + unique key), AuthoritativePnl6389,
  HardSettlementInvariants6389 (8-clause quarantine), KnownMintHistoricalRepair6389
  (BELKA/MICHI/Cygnets), JournalCohort6389, CanonicalUnitTypes6389,
  PreExecPolicyRedirectTaxonomy6389, UnknownBroadcastPolling6389,
  MainThreadHardening6389, SellOnlyForensicHold6389 (default null).
- **6388** (`af4595a1` + `104743a7` ✅) GOVERNOR RECOVERY STATE MACHINE.
  BLOCKED_INFRASTRUCTURE → HOLD_PROBATION → SOFT_TIGHT → BASELINE → EXPANSION.
  Auto promotion/demotion driven by PostFixEvidenceCollector6388.

## Wire-sites live in production paths

- `LaneEntryContract6342`: ALLOW_LIVE_PROBATION verdict; PolicyBlockDedup6388 on HOLD.
- `Executor.kt`: SellOnlyHold6391 gate; probation size clamp; PRE_EXEC_POLICY_REDIRECT
  taxonomy replaces LIVE_LANE_CONTRACT_6383 BUY_FAIL inflation.
- `sell/SellReconciler.tickOnce()`: GovernorRecovery6388.onReconcilerTick per pass.
- `V3JournalRecorder.recordClose()`: PostFixEvidenceCollector6388 + auto
  promote/demote inline on every canonical close.
- `TradeHistoryStore.clearAllTrades()`: JournalCohort6389.beginNewCohort().

## Next backlog (post-CI validation)

- **Live Session Validation**: Run V5.0.6393 APK; confirm Trade1AdaptiveTuner picks up
  first clean canonical close (Pigeooon-class) and tuner leaves neutral n=0 state.
- **Fill Ledger Wire-Up**: Persist BuyFillLedger6388 + SellFillLedger6388 records at
  every finalized fill on the live BUY / SELL executor paths.
- **Peak Capture Wire-Up**: Have every open position poll PeakCaptureAuthority6390.decide
  each hot-exit tick so the CHEEMS 26x scenario banks 75% via ladder + trailing 25%.
- **ANR Killer**: Move TradeHistoryStore + MainActivity.onCreate DB reads to
  Dispatchers.IO to kill the 50-second UI freeze.

# (Legacy) AATE PRD — V5.0.6388

## CURRENT MAJOR PROGRAM

**LIVE EXECUTION TRUTH AND COMPOUNDING FOUNDATION** — 13-section repair directive shipped in Bundles 6385 + 6386 + 6387 + 6388. Full text preserved in the operator message log; core acceptance criterion:
```
SUM(finalized sell lamport deltas) − SUM(finalized buy lamport deltas) == observed wallet change adjusted for deposits, withdrawals, fees.
```

## Current build stack

- **6388** (`104743a7b` ✅ CI green) **GOVERNOR RECOVERY STATE MACHINE + FULL 27-SECTION DIRECTIVE**
  - Substrate: `GovernorRecovery6388` (state machine) + `GovernorRecoverySubstrate6388.kt` (12 modules covering S12-S26).
  - States: BLOCKED_INFRASTRUCTURE → EXIT_ONLY → HOLD_PROBATION → SOFT_TIGHT → BASELINE → EXPANSION.
  - Auto promotion driven by `PostFixEvidenceCollector6388` (5/10/20 rolling windows, evidence-epoch=6388).
  - Auto demotion on infra faults (SellReconciler stopped/stale) or performance floor breach.
  - **S5** ProbationEntryLimiter6388: 1 open, 3/hr, 180s spacing, size clamp 0.005–0.010 SOL / 10% normal.
  - **S13** PolicyBlockDedup6388: governor HOLD emits `LIVE_ENTRY_POLICY_BLOCKED_6388` with 60s TTL — no more BUY_FAIL inflation.
  - **S14** ReconcilerLivenessAuthority6388: mandatory `isStarted && totalTicks>0 && tickAge<=configured`.
  - **S15** WalletPositionAuthority6388: 6-way (CANONICAL_OPEN, RESTORED_KNOWN_BASIS, RESTORED_UNKNOWN_BASIS, EXTERNAL, DUST, QUARANTINED) with balance-equation invariant.
  - **S16/17** BuyFillLedger6388 + SellFillLedger6388: immutable records keyed by signature, dedup rejected.
  - **S18** CanonicalTradeAggregator6388: many partial fills → one canonical lifecycle.
  - **S19** PartialExitStateMachineFull6388: 14-state ladder + 30s partial cooldown.
  - **S20** HardStopFullExit6388: HARD_STOP / RUG_CONFIRMED / etc → 100% liquidation (ROUTE_CHUNKED_FULL_EXIT preserves single exitIntentId).
  - **S21** SellLeaseIntegrity6388: one lease per (gen, positionId, exitIntentId); overlap rejected; premature release rejected.
  - **S22** ForensicExportMode6388: PRIMARY vs FALLBACK; fallback cannot pass forensic regression guard.
  - **S23** JournalEpoch6388: archive-not-delete; new epoch references previous checksum.
  - **S24** RecoveryHealthSnapshot6388: single dump authority (entry-authority, governor, probation, reconciler, position-authority, journal, export).
  - Wire-sites: LaneEntryContract6342 (ALLOW_LIVE_PROBATION verdict); Executor (probation size clamp inside sol-resolution + no-BUY_FAIL policy-block path); SellReconciler.tickOnce (onReconcilerTick every pass); V3JournalRecorder.recordClose (evidence collector + evaluatePromotion/Demotion inline).
  - Tests: Bundle6388GovernorRecoveryTest.kt (30+ invariants, every Section 25 named test + end-to-end HOLD→BASELINE→EXIT_ONLY→SOFT_TIGHT acceptance from Section 26).

- **6387b** (`3573a0490` ✅) **LIVE TRUTH, HOT EXIT AND NET-EDGE AUTHORITY (P0-P12 directive)** — LiveExitOnlyMode6387, ReconcilerZeroProof6387, PriceIntegrityAuthority6387, HotExitSupervisorContract6387, FeeAwareExecution6387, PartialExitStateMachine6387.
- **6387** (`2b8daf0a3` ✅) Canonical Trade Ledger + Price Identity.
- **6386b** (`e7a2809c0` ✅) Migrate legacy write/read sites onto truth substrate.
- **6386** (`ae60e6598` ✅) TRUTH REPAIR Bundle 2/2 (Sections 2/4/5/6/7/8/9/10/12/13).
- **6385** (`7e3a2b7b8` ✅) LIVE ACCOUNTING TRUTH REPAIR (Bundle 1/2).
- **6384** (`3b5ddbd6e` ✅) Governor "profitable low-WR" escape hatch.
- **6383** (`3f917e217` ✅) Live volume recovery + FLAT_EXIT protection.

## What operator will see on V5.0.6388 build

- `LIVE_LANE_CONTRACT_6383` counter DROPS SHARPLY — governor-HOLD rejections no longer inflate BUY_FAIL taxonomy.
- `LIVE_ENTRY_POLICY_BLOCKED_6388` NEW counter — policy blocks routed via dedup channel.
- `LANE_ENTRY_CONTRACT_ALLOW_PROBATION_6388` — first probation entries authorised while governor is HOLD.
- `LIVE_BUY_PROBATION_AUTHORIZED_6388` + `LIVE_BUY_PROBATION_SIZE_CLAMPED_6388` — probation trades executing at 0.005-0.010 SOL.
- `GOVERNOR_AUTO_PROMOTED_6388_HOLD_PROBATION_TO_SOFT_TIGHT` when 5 clean canonical closes prove healthy trading.
- `GOVERNOR_AUTO_PROMOTED_6388_SOFT_TIGHT_TO_BASELINE` when 10 clean closes maintain PF≥1.20 and expectancy>0.
- On reconciler stall: `GOVERNOR_AUTO_DEMOTED_6388_BASELINE_TO_EXIT_ONLY` — automatic capital protection.

## Next steps

### 🟠 P0 — Awaits live-run validation
- Live-run the V5.0.6388 APK. Verify the HOLD deadlock breaks and probation lifecycles begin.
- Confirm auto-promotion HOLD_PROBATION → SOFT_TIGHT → BASELINE within a live session.

### 🔵 P1 — Truth-substrate adoption (multi-bundle work behind the CI shield)
- Complete Executor's live BUY path to write `BuyFillLedger6388` records at each finalized fill.
- Complete Executor's live SELL path to write `SellFillLedger6388` records + call `CanonicalTradeAggregator6388.aggregate` at position close.
- Move `TradeHistoryStore.clearAllTrades/getTotalTradeCount` + `MainActivity.onCreate` DB reads to `Dispatchers.IO` (kills 50s ANR).
- Promote `CanaryReleaseGate6386` from LOCKED → CANARY once operator validates.

### 🟢 P2 — After 100 clean finalized trades
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`).
- Phase 2: Neural bridge (perps ↔ stocks cross-learning).
- Phase 3: LLM Lab sandbox.


  - **Section 2** ExecutionIntent6386: immutable intent + atomic registry (one outstanding BUY/mint/side; one mint cannot create competing lane tickets).
  - **Section 4** AmountTypes6386: `Lamports`/`RawTokenAmount` value classes on BigInteger; `MintDecimals` sealed (Known/Unknown) — never coerced to zero; `SolAmount`/`UiTokenAmount`/`UsdAmount`/`UsdPerToken`/`SolPerToken`.
  - **Section 5** FinalizedBuyProof6386.validate — quantity = post − pre (never ATA total).
  - **Section 6** FillLotLedger6386 — immutable lots keyed by (wallet, mint, buySig); re-entry = new lot; FIFO consumption.
  - **Section 7** FinalizedSellProof6386.validate — sold = pre − post; proceeds = lamport delta (no Jupiter/mark).
  - **Section 8** classifyPartial — no finalized proof ⇒ PendingReconciliation ⇒ no truth contribution.
  - **Section 9** ProofState6386 sealed with 8 states. `contributesToTruth()` true ONLY for FinalizedProofComplete.
  - **Section 10** HistoricalQuarantine6386.runOnce wired into BotService.onCreate (12 corruption criteria; emits _ROW_TAGGED / _TOTAL_ROWS / _REASON counters).
  - **Section 12** Bundle6386TruthRepairTest.kt — 21 invariants incl. decimals property tests (0/1/5/6/8/9) and 9 fixture assertions from the directive.
  - **Section 13** CanaryReleaseGate6386: LOCKED→CANARY→PROBATION→FULL, size 0.003–0.005 SOL, 20 clean RT to advance, invariant-failure reset. `promoteToCanary()` internal — awaiting operator green-light.

- **6385** (`7e3a2b7b8` ✅ CI green) **TRUTH REPAIR Bundle 1/2 — Sections 1, 3, 11**
  - **Section 1** LiveAccountingRepairMode6385 — hard-blocks all new live BUY signatures (LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385). Paper, shadow, monitoring, exits unaffected.
  - **Section 3** QUALITY_REJECTS_MINT_ROUTE_6342 removed; MINT_ROUTE now advisory (`LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385`).
  - **Section 11** EXEC_PROVIDER_TRY only fires for adapterWired && supported providers; unwired emit EXEC_PROVIDER_SKIPPED_6385.

- **6384** (`3b5ddbd6e` ✅) Governor "profitable low-WR" escape hatch (deprecated with substrate replacement in 6386).
- **6383** (`a4c2a3229` ✅) Live volume recovery (LIVE_MODE_DESYNC 795→0) + FLAT_EXIT winner protection.
- **6382** (`20c7fb7ec` ✅) Win-rate integrity + Wave Entry Quality Gate.
- **6381** (`556d3fa29…bfca89e0e` ✅) LIVE TRADER UNBLOCK — LIVE_MODE_DESYNC auto-promote.

## What operator will see on the V5.0.6386 build

- `LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385` — every live BUY attempt hard-blocked.
- `HISTORICAL_QUARANTINE_6386_TOTAL_ROWS_<N>` — count of legacy live rows tagged as excluded from truth stats on first boot.
- `HISTORICAL_QUARANTINE_6386_REASON_*` — per-criterion breakdown (LIVE_BROADCAST_WITHOUT_FINALIZATION, MISSING_TX_SIGNATURE, PNL_MISMATCH_GT_2PCT, POST_ATA_TOTAL_USED_AS_BUY_QUANTITY, RECOVERED_WALLET_UNKNOWN_BASIS, ALIAS_MERGE_OR_PHANTOM_HEAL, PARTIAL_WITHOUT_FINALIZED_PROOF, PHANTOM_SCRATCH_SELL_NO_DELTA).
- `EXEC_PROVIDER_TRY` counter drops sharply (only real, wired attempts now).
- `EXEC_PROVIDER_SKIPPED_6385` appears (unwired provider audit event, non-inflating).
- `LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385` replaces the old REJECTED counter.
- CanaryReleaseGate6386.snapshot(): `mode=LOCKED …` — awaits operator promote.

## Next steps

### 🟠 P0 — Awaits operator green-light
- **Promote to canary**: On the next live-connected build, once the operator verifies the substrate (`Bundle6386TruthRepairTest.kt` passing + HISTORICAL_QUARANTINE counters look reasonable + no false quarantines), we can flip `CanaryReleaseGate6386.promoteToCanary()`. Then LiveAccountingRepairMode6385.disable() ONLY through the canary orchestrator after 20 clean round trips.

### 🔵 P1 — Truth-substrate adoption (multi-bundle work behind the CI shield)
- Migrate Executor's live BUY path to open a FillLot6386 keyed by (wallet, mint, buySig) instead of writing via CanonicalBuyFillRegistry.
- Migrate live SELL path to compute realised proceeds from `FinalizedSellProof6386.validate()` only.
- Migrate partials to `FinalizedSellProof6386.classifyPartial` — no more phantom +50% sanitization.
- Route `EXEC_PROVIDER_TRY` gauge to a startup-once dump instead of every stack call.
- Replace `Double`-backed cost basis / proceeds in TradeHistoryStore with `Lamports` fields (schema migration).

### 🟢 P2 — Only after 100 clean finalized trades
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`).
- Phase 2: Neural bridge (perps ↔ stocks cross-learning).
- Phase 3: LLM Lab sandbox.

- **6383** (`a4c2a3229` ✅ CI green) **Live Volume Recovery + Live-Winner Protection**.
  Stale paper/shadow flag auto-clear in `Executor.kt` (recovers 92% of dying buys), lane-contract counter split onto `LIVE_LANE_CONTRACT_6383`, MoonshotTraderAI FLAT_EXIT protection for live positions with `peakPnlPct≥3%` OR `holdMinutes<15`.

- **6382** (`20c7fb7ec` ✅ CI green) Win-Rate Integrity + Wave Entry Quality Gate.

- **6381** (`556d3fa29…bfca89e0e` ✅) LIVE TRADER UNBLOCK — LIVE_MODE_DESYNC auto-promote + Rugcheck tier recalibration.

- **6380** (`09906d856` ✅) Paper wallet continuity hole #2 + Learning Trajectory Governor.

- **6377-6379x** (✅) Forensic Reconciler + Learned Toxic Lane Hard Veto + cache-bucketing.

- **6376** (`94ab099ef` ✅) Paper Wallet Continuity + Screen-Off Proof-of-Life.

- **6372** (`0a1eb8cfc` ✅) UNIVERSAL 2×–5× daily compound target (AATE core doctrine).

## Expected V5.0.6384 signature

1. **`LIVE_BUY_REDIRECTED_GOVERNOR_HOLD_6342` drops sharply** when live PF≥2.0 and expectancy is positive (which is what the current live journal shows: PF=7.58, exp=+0.0024).
2. **`LIVE_CONFIDENCE_GOVERNOR_BASELINE` counter increases** — governor stays in baseline for profitable strategies.
3. **`LANE_ENTRY_CONTRACT_ALLOWED_6342` counter increases** — more live buys reach FDG allow.
4. **`FIRST_TRADE_READINESS_6348.GOVERNOR_NOT_HOLD` pillar flips ✓** (was `P1` in V5.0.6383 dump).

## Backlog

### 🟠 P0 — Deferred forensic-repair spec items
- P0-4: Immutable price identity hash + PRICE_IDENTITY_CONFLICT
- P0-5: tokenDecimals nullable + decimalsKnown boolean
- P0-6: performanceDomain enum (LIVE_CANONICAL / PAPER_PARITY / SHADOW_RESEARCH)
- P0-7: PAPER_PARITY score-floor parity with LIVE governor
- P0-8: LaneEligibilityContract
- P0-10: Quarantine corrupted history + rebuild tactic stats
- P0-11: Full forensic journal fields + CANONICAL_INTEGRITY_STATUS section
- **Phantom REALIZED_SCRATCH_AFTER_RISK_EXIT_SIGNAL at sol=0 cost>0**: same-mint alias collision fires SELL rows that never actually broadcast realized SOL. Distorts WR.
- **Empty UI panels**: ALL TRADERS, 30-DAY PROOF RUN, lane cards show 0.

### 🔵 P1 — After live WR stabilization + 2x–5x growth benchmark hit
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`)
- Phase 2: Neural bridge (AI cross-learning perps↔stocks)
- Phase 3: LLM Lab sandbox

- **6382** (`20c7fb7ec` ✅ CI green) Win-Rate Integrity + Wave Entry Quality Gate.
  1. `TradeHistoryStore.isValidAccountingTrade` whitelists `EXTERNAL_RUG_CLOSE` rows.
  2. `StartupReconciler` synth rugSell inherits `buyRow.tradingMode`.
  3. `TacticSwitcher.rederiveFromRawJournal6382()` cold-boot repair of phantom μ drift.
  4. `WaveEntryQualityGate6382` rejects parabolic top-tick / ejection / extended chases.

- **6381** (`556d3fa29…bfca89e0e` ✅) LIVE_MODE_DESYNC auto-promote + Rugcheck tier recalibration + Paper size floor fix + Golden Tape refresh.

- **6380** (`09906d856` ✅) Paper wallet continuity hole #2 + Learning Trajectory Governor.

- **6379x** (`5c9d19554…2f504c4f7` ✅) Learned Toxic Lane Hard Veto + cache-bucketing.

- **6377** (`1575a03f1` ✅) Forensic Reconciler (11-item correctness spec).

- **6376** (`94ab099ef` ✅) Paper Wallet Continuity + Screen-Off Proof-of-Life.

- **6374-6375** (✅) Scanner Fanout Throttle + Heatmap ANR fix.

- **6373x** (✅) Trade-1 catastrophic rotation + phantom pnl% recompute + ghost purge neutralization.

- **6372** (`0a1eb8cfc` ✅) UNIVERSAL 2×–5× daily compound target (AATE core doctrine).

## What operator should see on V5.0.6383

1. **`LIVE_MODE_DESYNC` counter drops dramatically** — the 795 count from V5.0.6382 was almost entirely stale-flag garbage. Now only real desync (`runtimePaper=true` requesting a LIVE buy) fires this counter.
2. **`LIVE_MODE_STALE_FLAG_AUTO_CLEARED_6383` counter appears** — this is the recovered volume. Expect it to be similar in magnitude to the prior `LIVE_MODE_DESYNC` number.
3. **`LIVE_LANE_CONTRACT_6383` counter appears** — routing hygiene (BLUECHIP rejecting Pump.fun mints, etc.), working as intended.
4. **`LIVE_WINNER_PROTECT_FLAT_EXIT_SUPPRESSED_6383` counter appears** — every time a live moonshot position was saved from a hair-trigger flat exit.
5. **Live BUY ok/fail ratio significantly improved** — no longer 31/862 (3.5%).
6. **Live SELLs no longer showing pnl=+0 sol=0 patterns** repeated on the same mint.

## Backlog

### 🟠 P0 — Deferred forensic-repair spec items
- P0-4: Immutable price identity hash + PRICE_IDENTITY_CONFLICT
- P0-5: tokenDecimals nullable + decimalsKnown boolean
- P0-6: performanceDomain enum (LIVE_CANONICAL / PAPER_PARITY / SHADOW_RESEARCH)
- P0-7: PAPER_PARITY score-floor parity with LIVE governor
- P0-8: LaneEligibilityContract
- P0-10: Quarantine corrupted history + rebuild tactic stats
- P0-11: Full forensic journal fields + CANONICAL_INTEGRITY_STATUS section
- **Empty UI panels**: ALL TRADERS, 30-DAY PROOF RUN, lane cards show 0.

### 🔵 P1 — After live WR stabilization + 2x–5x growth benchmark hit
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`)
- Phase 2: Neural bridge (AI cross-learning perps↔stocks)
- Phase 3: LLM Lab sandbox

## Current build stack (old)

- **6377** (pending push) **Forensic Reconciler (11-item correctness spec)** — additive read-only module runs 11 named cross-domain reconciliation checks against the trade journal on every ~50th bot loop tick. Emits `FORENSIC_OK_6377|<CHECK>` / `FORENSIC_MISMATCH_6377|<CHECK>|<summary>` counters. Pipeline dump gains a dedicated FORENSIC RECONCILER section listing pass/fail + per-check summaries. TacticSwitcher exposes new read-only `dumpForensicSnapshot6377()` so persisted μ can be cross-checked against journal μ per lane.
  Checks: WALLET_VS_JOURNAL, JOURNAL_ROW_PARITY, BUY_SELL_QTY_SKEW, COST_BASIS, PNL_PCT_VS_SOL, SELL_REASON_PRESENCE, PRICE_IMMUTABILITY, TACTIC_MU_VS_JOURNAL, DUPLICATE_JOURNAL_ROWS, ORPHAN_SELL, CANONICAL_VS_REGISTRY.
  `Bundle6377ForensicReconcilerTest.kt` (14 assertions).

- **6376** (`94ab099ef` ✅) Paper Wallet Continuity + Screen-Off Proof-of-Life.

- **6375** (`e4bfc98f9` ✅) Shadow lane-read telemetry split from active fanout.
- **6374b** (`5e4dea126` ✅) Runtime test scenario reshaped to bypass healthy-window reset (agg-bad-band gate).
- **6374a** (`a3e7c850c` ✅) Test qualified `HeatmapRenderCache6374` package reference.
- **6374** (`22006c59c` ⚠ compile-fixed by 6374a) Scanner Fanout Throttle + Aggregate-Bad-Band Rotation + Heatmap ANR fix bundle.

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

