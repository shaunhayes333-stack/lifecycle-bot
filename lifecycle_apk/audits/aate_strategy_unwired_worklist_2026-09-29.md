# AATE Strategy + Unwired Intelligence Worklist — 2026-09-29

This is the canonical worklist for the current strategy/tooling sweep.

## Objective

Make PAPER a deployment-quality rehearsal for LIVE while preserving strategy diversity, then prove that LIVE executes the same sealed decisions faithfully.

The work is split into two coupled backlogs:

1. the **113 high-value unwired functions** already present in `ci/UNWIRED_LEDGER.tsv`;
2. the **strategy / setup / tactic / trade-type taxonomy audit**, where many named mechanisms exist but identity is lost, duplicated, partially wired, display-only, or never reaches causal learning.

Do not wire every module directly into FDG. Decision inputs must converge through bounded canonical evidence surfaces so hot-path latency and duplicated authority do not grow.

## V5.0.7427 completed — exact strategy identity / causal credit

- [x] `entryTactic` is again strictly the coarse `TacticSwitcher.Tactic` namespace; Toolkit free-form entry styles no longer overwrite it.
- [x] `EntryStrategySnapshot6450` persists `entryTradeType`, `entrySetup`, `entryStyle`, `entryEntryStyle`, `entryExitStyle`, and `entryStrategyVariantId`.
- [x] PAPER and LIVE policy snapshots derive the same canonical `ModeRouter → ToolkitSignalSheet → AgenticStyleRouter` identity before the execution adapter split.
- [x] `CanonicalFinalizedTradeBus6464.Envelope` carries exact strategy identity to terminal learning.
- [x] `StrategyHypothesisEngine` stamps the exact `StrategyVariantStore` variant used at entry and credits that exact ID at close.
- [x] `MemeCausalLearning6568` reports exact trade type / setup / style / variant alongside lane and tactic.
- [x] Regression coverage added in `Aate7427ExactStrategyIdentityTest`.
- [x] `TradingMemory.getPatternWinRate` wired as bounded exact-context predictive evidence (`TRADING_MEMORY_PATTERN_READ_7427`).
- [ ] Next: expand hypothesis contexts from coarse `lane|scoreBand|regime` toward hierarchical exact-strategy context without sparse-cell deadlock.
- [ ] Next: consume exact style/setup outcome statistics in entry selection and strategy promotion.

### First A_PREDICT classifications

- `SourceTimingRegistry.isLateSignal` — **ALIAS_REDUNDANT**: wraps `getSourceTimingPenalty`; the latter is already consumed by `ScoreCard`. Do not add a second late-signal penalty.
- `MomentumPredictorAI.getStrongMomentumTokens` — **CLOSED_LOOP / LEDGER STALE**: current source has a production caller in `LaneHunter7297`; the unwired ledger needs reclassification rather than another wire.
- `MomentumPredictorAI.getMomentumScore` — **CLOSED_LOOP** into `PredictiveEntryOracle6915`.
- `MomentumPredictorAI.getEntryScoreAdjustment` — **CLOSED_LOOP** into `LifecycleStrategy`.
- `TradingMemory.getPatternWinRate` — **CLOSED_LOOP (7427)**: exact phase + EMA + source are now carried into `PredictiveEntryOracle6915`; the read is a bounded local prior and never a standalone veto.
- `EducationSubLayerAI.getEdgeLedger` — **BACKGROUND/REPORT CANDIDATE**: aggregate ranking/sorting over learned maps; useful for periodic strategy selection/research, explicitly not per-candidate hot-path invocation.
- `CollectiveLearning.getNetworkBoostForMint` — **BACKGROUND-ONLY CANDIDATE**: suspend + database I/O; must be prefetched/cached before admission, never awaited on the scanner/FDG hot path.
- `HistoricalChartScanner.getBestModeForConditions` — **ALIAS/COARSE WRAPPER**: local and cheap, but it discards gain-stage/context already available in `getHistoricalRecommendation`; use the richer recommendation if this family is wired, not this wrapper.
- `TradingCopilot.convictionBoost` — **PARTIAL/REDUNDANT READ API**: Copilot state is real, but this isolated boost has no consumer; do not stack it blindly on top of existing Copilot sizing/confidence machinery until overlap is audited.
- `TradeDatabase.getSignalWinRate` — **BACKGROUND CACHE CANDIDATE**: SQLite query per feature key; useful evidence, but must be prefetched/cached before admission rather than queried on scanner/FDG threads.
- `PatternBacktester.getConfidenceAdjustments` — **BACKGROUND/LAB ONLY CANDIDATE**: transforms a completed backtest report; not an entry-time primitive and should feed periodic strategy research.
- `OrthogonalSignals.calculateAgePatternScore` — **ALIAS/OVERLAP RISK**: pure/local, but age is already authoritative through `LaunchPhaseAuthority7401`; wiring another independent age score risks double-counting unless it becomes part of the orthogonal composite.
- `ScoreComponent.sourceScore` — **ALIAS_REDUNDANT**: the richer `sourceScoreWithTiming` is the production shape and already consumes `SourceTimingRegistry.getSourceTimingPenalty`.
- `RuntimeTune6833.isHighEdge` — **CLOSED_LOOP / LEDGER STALE**: production caller exists in `InventoryPressureGovernor6829`.
- `ScoreDistributionHistogram6396.recommendAdaptiveBaseline` — **PARTIAL/ADVISORY CANDIDATE**: live score histograms are recorded, but this recommendation is test-only today; any use must remain bounded and avoid becoming another one-way floor ratchet.

## V5.0.7430 completed — exact strategy hypothesis attribution

- [x] FDG now constructs exact strategy identity as `tradeType > setup > style > tactic`.
- [x] `StrategyHypothesisEngine` uses exact strategy contexts instead of pooling every playbook into only `lane|scoreBand|regime`.
- [x] Exact contexts inherit promoted parent baselines so sparse strategy cells do not cold-start blind.
- [x] FDG mint/version/lane hypothesis stamps bind to the canonical opened `positionId`.
- [x] `StrategyVariantStore` exact variant ID is sealed at entry.
- [x] Canonical finalization now settles `StrategyHypothesisEngine.recordOutcomeForPosition7428(positionId,...)`; the old mutable mint-only terminal path is no longer canonical.
- [x] Exact strategy/variant terminal credit is deduplicated by position.
- [x] Regression coverage: `Aate7430ExactStrategyCausalLoopTest`.

### A_PREDICT ledger corrections confirmed from current source

- `SourceTimingRegistry.isLateSignal` — **ALIAS_REDUNDANT**. `ScoreCard.sourceScoreWithTiming` already consumes the richer `getSourceTimingPenalty`; do not apply the same late-signal evidence twice.
- `MomentumPredictorAI.getStrongMomentumTokens` — **CLOSED_LOOP / LEDGER_STALE**. `LaneHunter7297.claimMomentum7298` consumes it as a discovery source.
- `MomentumPredictorAI.getMomentumScore` — **CLOSED_LOOP** through `PredictiveEntryOracle6915`.
- `MomentumPredictorAI.getEntryScoreAdjustment` — **CLOSED_LOOP** through `LifecycleStrategy`.
- `TradingMemory.getPatternWinRate` — **CLOSED_LOOP (7427)** as bounded exact-context oracle evidence.
- `RuntimeTune6833.isHighEdge` — **CLOSED_LOOP / LEDGER_STALE** via `InventoryPressureGovernor6829`.
- `UnifiedPolicyHead.brierScore` — **CLOSED_LOOP / LEDGER_STALE**; FDG reads it when applying the authoritative per-lane policy head.
- `ScoreComponent.sourceScore` — **ALIAS_REDUNDANT**; richer timed source scoring is already the production path.
- `HistoricalChartScanner.getBestModeForConditions` — **ALIAS_COARSE_WRAPPER**; if this family is promoted into decision authority use `getHistoricalRecommendation`, which preserves sample size and expected gain/loss.
- `EducationSubLayerAI.getEdgeLedger` — **BACKGROUND_REPORT** candidate, not hot-path; it ranks aggregate learned reasons and should feed periodic strategy research/selection.
- `CollectiveLearning.getNetworkBoostForMint` — **BACKGROUND_CACHE_REQUIRED**; suspend/database work must be prefetched, never awaited by scanner/FDG.
- `TradeDatabase.getSignalWinRate` — **BACKGROUND_CACHE_REQUIRED** for the same reason.
- `PatternBacktester.getConfidenceAdjustments` — **LAB_BACKGROUND**; consumes completed backtests, not a per-candidate hot-path primitive.
- `TacticSwitcher.posteriorLossProbAboveForTest` — **TEST_ONLY_BY_DESIGN**; do not turn a test helper into production authority.
- `TradingCopilot.convictionBoost` — **PARTIAL/OVERLAP_REVIEW**; Copilot sizing/confidence state already exists, so this isolated positive boost must not be stacked until its overlap is proven non-duplicative.
- `OrthogonalSignals.calculateAgePatternScore` — **OVERLAP_REVIEW**; launch age is already canonical under `LaunchPhaseAuthority7401`, so a second independent age score risks double-counting.

## V5.0.7431 completed — exact playbook EV closes the selection loop

- [x] `ExactStrategyPerformance7429` maintains a variant-agnostic exact-playbook EV index keyed by mode/lane/tradeType/setup/style/tactic.
- [x] Admission lookup is O(1); no strategy-table scan, DB read, provider call, or LLM call is added to the hot path.
- [x] Exact playbook evidence is sample-gated (PAPER n>=3, LIVE n>=3; LIVE PAPER-seed n>=5).
- [x] LIVE prefers its own exact playbook outcomes; PAPER seed is capped to a small effective sample so simulated history cannot overwhelm real-money evidence.
- [x] Exact playbook EV participates as a hierarchical `PredictiveEntryOracle6915.Level`, not as a new hard gate.
- [x] `ExecutableOpenGate` carries the same canonical ModeRouter/Toolkit/AgenticStyle taxonomy into learned admission.
- [x] Exact playbook EV index persists across process restarts, allowing deployment-quality PAPER evidence to survive into a later LIVE run.
- [x] Duplicate SourceTiming oracle vote removed: timing remains represented once through ScoreCard's production timing-aware source score.
- [x] Regression coverage: `Aate7431ExactStrategyEvAdmissionTest`.

### Additional A_PREDICT classifications

- `BotBrain.getBlendedWinRate` — **UNWIRED_INSTANCE_API / DESIGN_REVIEW**. `BotBrain` is an instantiated engine; there is no canonical singleton read for the static oracle. Do not create a second brain instance just to consume this accessor.
- `CoinGeckoTrending.getSolanaEcosystemMomentum` — **BACKGROUND_CACHE_REQUIRED**. Calling it can refresh CoinGecko over HTTP; never invoke it synchronously from admission.
- `CrossAssetLeadLagAI.getRotationProbability` — **ALIAS_REDUNDANT_FOR_MEME_ENTRY**. Lead/lag already reaches the meme stack through CrossTalk/SymbolicContext and is consumed in exit reasoning; adding this accessor to the oracle would double-count the same rotation state.
- `EdgeOptimizer.calculateWeightedScores` — **LEGACY/OVERLAP_REVIEW**. It is a second weighted entry/exit score formulation beside the canonical V3/UnifiedScorer/FDG stack. Do not wire it as an additional score until one authority is selected.
- `SymbolicContext.getAllSignals` — **UTILITY_ALIAS**. Individual symbolic signals and composite risk/confidence/health/edge are already consumed; the bulk-map getter is not an independent intelligence source.
- `QuantMetrics.calculateWinRateStats` — **REPORT/ANALYTICS API**. The same locked calculation is already consumed by `generateReport`; not an entry-time primitive.
- `TradeLessonRecorder.getWinRateForLane` — **INTERNAL_ANALYTICS_HELPER**. The lesson corpus and StrategyTrust/CrossTalk paths already consume trade lessons; this generic map accessor is not itself a missing strategy.
- `BlueChipTraderAI.getStockTrustScore` — **ASSET-SPECIFIC PARTIAL**. It is trained by stock/perps learning but not relevant to meme admission; audit with tokenized-stock strategy, not the meme hot path.

## V5.0.7431 progress — early-pump smart-money chain corrected

- [x] `SmartMoneyDiscovery7277.start()` reclassified **CLOSED_LOOP / STALE_AUDIT**: current `BotService` starts it under the Helius push stack and updates `HeliusEnhancedWS` as wallets are promoted.
- [x] `InsiderCopyEngine.copyBuyFromSmartMoney7277()` reclassified **CLOSED_LOOP / STALE_AUDIT**: current copy-signal callback routes it into the canonical candidate/fast-lane path.
- [x] Helius tracked-wallet BUY → `CopyTradeEngine.onSwapDetected` is production wired.
- [x] Real validated copy BUY events now write `SmartMoneyFeed6394.onWhaleBuy`; its previous production-writer gap is closed.
- [x] `SmartMoneyFeed6394.smartMoneyBuysLast60s` now counts **distinct wallets**, not raw transactions. One whale buying twice cannot impersonate a 2-wallet cluster.
- [x] `EarlyLaunchBypass6396` is a real FDG consumer in current source; the September directionality audit entry saying it was unwired is stale.
- [ ] Review/rename `EarlyLaunchBypass6396` semantics against the new canonical no-exploration-PROBE doctrine. Smart-money early entry may remain reduced-size risk shaping, but it must not be an unrelated exploration escape hatch.
- [ ] PAPER/LIVE parity defect: FDG PAPER edge handling still has separate `PAPER BOOTSTRAP PROBE` and soft-bypass behavior while LIVE uses different evidence. Converge to one canonical pre-execution edge decision.
- [ ] `EarlyEntryScout6390.evaluate()` remains unconsumed. Wire only if its distinct-buyer/flow/authority inputs can be sourced from existing canonical caches without adding hot-path I/O.
- [ ] `ModeSpecificScanners.scanFreshLaunch()` remains legacy/unwired; do not resurrect if PumpPortal/ModeRouter/LaunchPhase already cover the same source/setup.

## Priority findings from the strategy audit

### P0 — Preserve exact strategy identity through the full trade lifecycle

The current entry snapshot preserves lane + coarse tactic but not the exact strategy stack. Add immutable entry identity for:

- market situation / trade type
- setup
- agentic style / execution strategy
- tactic
- strategy variant ID
- entry tactic
- sizing tactic(s)
- management tactic(s)
- exit profile / exit strategy
- specialist owner and contributors
- signal provenance / evidence family

Carry the same identity through `CanonicalFinalizedTradeBus6464.Envelope` and all canonical learning consumers.

### P0 — Confirmed PAPER + LIVE tactic-attribution defect

Both canonical meme entry reducers currently write the Toolkit desk's free-form `entryStyle` into the field named `entryTactic` whenever a desk hypothesis exists. The LIVE reducer currently writes:

`entryTactic = liveDeskHypothesis6599?.entryStyle ?: TacticSwitcher.currentTactic(...).name`

The PAPER reducer has the same shape: `entryTactic = entryDeskHypothesis6599?.entryStyle ?: TacticSwitcher.currentTactic(...).name`.

A Toolkit desk `entryStyle` is a free-form execution string such as
`degen_snipe_fast_confirm`, `breakout_confirmation`,
`dip_reclaim_confirmation`, etc. It is **not** a
`TacticSwitcher.Tactic` enum value.

`TacticSwitcher.onCanonicalTradeClosed6486` parses `entryTactic` by enum
name. When parsing fails it emits `TACTIC_ENTRY_ATTRIBUTION_INVALID_6568`
and falls back to `onTradeClosed(lane, band, pnl)`, crediting the currently
active coarse tactic instead of the actual entry strategy.

Repair requirement:

- keep `entryTactic` strictly the elected coarse tactic enum;
- add separate immutable fields for `entryTradeType`, `entrySetup`,
  `entryStyle`, `entryEntryStyle`, `entryExitStyle`,
  `entryStrategyVariantId`, and related strategy identity;
- never overload one field with two different namespaces;
- terminal learners must use sealed entry identity, not current tactic state.

### P0 — PAPER/LIVE style identity is not currently symmetric

The policy snapshot is built differently by mode:

- PAPER calls `buildTradePolicySnapshot(... style = finalMode ...)`, which normally records the lane/mode name as `style`.
- LIVE calls `buildTradePolicySnapshot(... style = routedStyleTag ...)`, which can carry the actual routed style.

Therefore PAPER and LIVE can make the same economic decision while recording different strategy identities. That prevents a clean proof that LIVE executed the exact strategy PAPER rehearsed.

Repair requirement: derive one immutable canonical strategy identity **before** the mode split and pass the same identity object into PAPER and LIVE adapters.

### P0 — Stop strategy-credit collapse

Current examples:

- `StrategyHypothesisEngine.ctxKey` is only `lane|scoreBand|regime`, so materially different styles are blended.
- `StrategyVariantStore` selects/credits by lane. The exact variant applied at entry is not sealed into the position; the active variant at close can receive the outcome instead.
- `TacticSwitcher` learns only five coarse tactics while `AgenticStyleRouter` exposes many materially different execution styles.
- `MemeCausalLearning6568` sees lane + coarse tactic, not exact setup/style/trade type.

Repair attribution before using strategy-level performance to promote, retire, resize, or alter exits.

### P1 — Separate real strategy surfaces from aliases / compatibility layers

Known overlap currently includes:

- `ModeRouter.TradeType`
- `ToolkitSignalSheet.Setup`
- `AgenticStyleRouter.Style`
- `TacticSwitcher.Tactic`
- `CryptoTacticSwitcher.Tactic`
- `UnifiedModeOrchestrator.ExtendedMode`
- `ModeSpecificGates.TradingModeTag`
- `ModeSpecificScanners.ScannerType`
- `AdvancedExitManager.ExitProfile`
- `SellOptimizationAI.ExitStrategy`
- LAB / generated strategy variants
- perps / crypto strategy families

Every item must be classified as:
`ACTIVE_CLOSED_LOOP`, `PARTIAL`, `ALIAS_REDUNDANT`, `SHADOW_LAB_ONLY`, `UNWIRED`, or `MISSING`.

### P1 — Canonical taxonomy

Use the operator's master taxonomy as the canonical vocabulary:

`signal → setup → strategy → specialist → entry tactic → sizing tactic → management tactic → exit tactic → outcome/learning`

The ~200 concepts are primitives, not 200 independent traders.

## Master strategy-taxonomy coverage work

Audit all 24 families against code and runtime:

1. Momentum / trend
2. Early-pump / launch
3. Mean reversion
4. Dip-buying
5. Reversal
6. Range / market making
7. Scalping
8. Swing trading
9. Position / investment-style
10. Arbitrage
11. Relative value / pairs
12. Volatility
13. Volume / flow
14. Liquidity / microstructure
15. On-chain
16. Meme-coin specific
17. Narrative / event
18. Market regime
19. Entry tactics
20. Position sizing tactics
21. Exit tactics
22. Trade-management tactics
23. Portfolio tactics
24. Learning / adaptive strategies

For every primitive record:

- implementation owner / function
- production caller
- canonical input source
- whether it changes entry / size / hold / exit
- whether exact identity is sealed at entry
- whether terminal outcome is attributed to it
- whether it can adapt from that outcome
- runtime counter proving use
- classification
- duplicate/alias target if applicable

## 113 high-value unwired functions

Source: current `ci/UNWIRED_LEDGER.tsv`.

## A_PREDICT — predictive / entry intelligence (47)

1. `BlueChipTraderAI.getStockTrustScore` — `v3/scoring/BlueChipTraderAI.kt`
2. `BotBrain.getBlendedWinRate` — `engine/BotBrain.kt`
3. `CoinGeckoTrending.getSolanaEcosystemMomentum` — `network/CoinGeckoTrending.kt`
4. `CollectiveLearning.getNetworkBoostForMint` — `collective/CollectiveLearning.kt`
5. `CorrelationScanner.getActionableSignals` — `perps/CorrelationScanner.kt`
6. `CrossAssetLeadLagAI.getRotationProbability` — `v4/meta/CrossAssetLeadLagAI.kt`
7. `DataOrchestrator.scoreSentimentWithLlm` — `engine/DataOrchestrator.kt`
8. `EdgeOptimizer.calculateWeightedScores` — `engine/EdgeOptimizer.kt`
9. `EducationSubLayerAI.getEdgeLedger` — `v3/scoring/EducationSubLayerAI.kt`
10. `ExecutableEntryAuthority6450.scoreFloorDelta6487` — `engine/truth/ExecutableEntryAuthority6450.kt`
11. `ExplorationBudget.allowShadowSignal` — `engine/learning/ExplorationBudget.kt`
12. `FluidLearning.getExitTagWinRate` — `engine/FluidLearning.kt`
13. `FluidLearningAI.getHeuristicSignal` — `v3/scoring/FluidLearningAI.kt`
14. `ForensicEventEnvelope6430.setLedgerEpoch` — `engine/truth/ForensicEventEnvelope6430.kt`
15. `HistoricalChartScanner.getBestModeForConditions` — `engine/HistoricalChartScanner.kt`
16. `InsiderTrackerAI.getSignalsByWallet` — `v3/scoring/InsiderTrackerAI.kt`
17. `MomentumPredictorAI.getStrongMomentumTokens` — `engine/MomentumPredictorAI.kt`
18. `OrthogonalSignals.calculateAgePatternScore` — `engine/OrthogonalSignals.kt`
19. `PatternBacktester.getConfidenceAdjustments` — `engine/PatternBacktester.kt`
20. `PerpsAdvancedAI.getHourlyWinRate` — `perps/PerpsAdvancedAI.kt`
21. `PerpsAutoReplayLearner.getLosingPatterns` — `perps/PerpsAutoReplayLearner.kt`
22. `PerpsAutoReplayLearner.getWinningPatterns` — `perps/PerpsAutoReplayLearner.kt`
23. `PerpsDirection.getSignalStrength` — `perps/PerpsModels.kt`
24. `PerpsDirection.isHighConfidence` — `perps/PerpsModels.kt`
25. `PerpsLearningBridge.getStockLayerRecommendations` — `perps/PerpsLearningBridge.kt`
26. `PerpsNotificationManager.notifyPatternDiscovered` — `perps/PerpsNotificationManager.kt`
27. `PerpsNotificationManager.notifyStrongSignal` — `perps/PerpsNotificationManager.kt`
28. `PerpsTradeHeatmap.getAIRecommendation` — `perps/PerpsTradeHeatmap.kt`
29. `PerpsTraderAI.getLifetimeWinRatePct` — `perps/PerpsTraderAI.kt`
30. `QualityTraderAI.getRecommendedLeverage` — `v3/scoring/QualityTraderAI.kt`
31. `QuantMetrics.calculateWinRateStats` — `engine/quant/QuantMetrics.kt`
32. `RuntimeTune6833.isHighEdge` — `engine/truth/RuntimeTune6833.kt`
33. `ScoreComponent.sourceScore` — `v3/scoring/ScoreCard.kt`
34. `ScoreDistributionHistogram6396.recommendAdaptiveBaseline` — `engine/truth/ScoreDistributionHistogram6396.kt`
35. `ShadowLearningEngine.getPerformanceByConfidence` — `v3/learning/ShadowLearningEngine.kt`
36. `SmartExitOptimizer.getMinConfidenceAdvisory` — `engine/SmartExitOptimizer.kt`
37. `SourceTimingRegistry.isLateSignal` — `v3/arb/SourceTimingRegistry.kt`
38. `SymbolicContext.getAllSignals` — `engine/SymbolicContext.kt`
39. `TacticSwitcher.posteriorLossProbAboveForTest` — `engine/learning/TacticSwitcher.kt`
40. `TradeDatabase.getSignalWinRate` — `engine/TradeDatabase.kt`
41. `TradeLessonRecorder.getWinRateForLane` — `v4/meta/TradeLessonRecorder.kt`
42. `TradeLifecycle.noSignal` — `engine/TradeLifecycle.kt`
43. `TradingCopilot.convictionBoost` — `engine/TradingCopilot.kt`
44. `TradingMemory.getPatternWinRate` — `engine/TradingMemory.kt`
45. `TrailingStopManager.getRecommendedStopType` — `engine/TrailingStopManager.kt`
46. `TursoClient.getMarketsAssetRankings` — `collective/TursoClient.kt`
47. `UnifiedPolicyHead.brierScore` — `engine/UnifiedPolicyHead.kt`

## B_RISK — risk / safety intelligence (12)

1. `CanonicalEconomicIdentity6470.breachCount` — `engine/truth/CanonicalEconomicIdentity6470.kt`
2. `EvidenceEpochFilter6388.canPassForensicRegressionGuard` — `engine/truth/GovernorRecoverySubstrate6388.kt`
3. `ExitCoordinatorHeartbeat.duplicateSweepsSuppressedCount` — `engine/ExitCoordinatorHeartbeat.kt`
4. `ExternalAlphaFeeds.enrichSafety` — `v4/meta/ExternalAlphaFeeds.kt`
5. `GeminiCopilot.assessRisk` — `engine/GeminiCopilot.kt`
6. `MemeExecutionRouteStack.stackExhausted` — `engine/execution/MemeExecutionRouteStack.kt`
7. `ReentryGuard.manualBlock` — `engine/ReentryGuard.kt`
8. `SameMintCandidateEpoch6402.totalSuppressed` — `engine/truth/SameMintCandidateEpoch6402.kt`
9. `SellOnlySafeMode.blockedBuyCount` — `engine/sell/SellOnlySafeMode.kt`
10. `SoftScoreShaping6400.recordMechanicalMinBlock` — `engine/truth/SoftScoreShaping6400.kt`
11. `TradeDatabase.getSuppressionStrength` — `engine/TradeDatabase.kt`
12. `TradeLifecycle.forceExpireBlocked` — `engine/TradeLifecycle.kt`

## C_EXIT — exit / position-management intelligence (54)

1. `BirdeyeApi.getHolderDistribution` — `network/BirdeyeApi.kt`
2. `BotBrain.resetThresholds` — `engine/BotBrain.kt`
3. `BotRuntimeController.runtimeJobActiveButUiStopped` — `engine/BotRuntimeController.kt`
4. `CanonicalPositionAuthority6441.activeMintProjections6489` — `engine/truth/CanonicalPositionAuthority6441.kt`
5. `CounterParityLedger6399.recordSellExecutorInvocation` — `engine/truth/CounterParityLedger6399.kt`
6. `EarlyEntryScout6390.trackedPeakCount6948` — `engine/truth/EarlyEntryAndPeakCapture6390.kt`
7. `EducationSubLayerAI.getCurriculumHoldStats` — `v3/scoring/EducationSubLayerAI.kt`
8. `EvidenceEpochFilter6388.buildFullExitPlan` — `engine/truth/GovernorRecoverySubstrate6388.kt`
9. `EvidenceEpochFilter6388.p1FaultLiveReconcilerMissingWithHoldings` — `engine/truth/GovernorRecoverySubstrate6388.kt`
10. `EvidenceEpochFilter6388.requiresFullExit` — `engine/truth/GovernorRecoverySubstrate6388.kt`
11. `ExecutionCounterContract.recordJournalSellWrite` — `engine/runtime/ExecutionCounterContract.kt`
12. `ExitIntelligence.getLearnedMaxHoldMinutes` — `engine/ExitIntelligence.kt`
13. `ExitManager.shouldPartialSell` — `engine/ExitManager.kt`
14. `FinalizedSellProof6386.classifyPartial` — `engine/truth/FinalizedSellProof6386.kt`
15. `FluidLearning.getSimulatedPeak` — `engine/FluidLearning.kt`
16. `FluidLearningAI.getBreakoutThreshold` — `v3/scoring/FluidLearningAI.kt`
17. `FluidLearningAI.getLayerHoldParams` — `v3/scoring/FluidLearningAI.kt`
18. `FluidLearningAI.getMarketsTakeProfitPct` — `v3/scoring/FluidLearningAI.kt`
19. `FluidLearningAI.getMarketsUncappedTpPct` — `v3/scoring/FluidLearningAI.kt`
20. `ForexStrategy.tpSlPrices` — `perps/strategy/ForexStrategy.kt`
21. `GlobalCapitalArbitration6617.recordSpecialistProposal6617` — `engine/truth/GlobalCapitalArbitration6617.kt`
22. `GlobalTradeRegistry.getProbationStats` — `engine/GlobalTradeRegistry.kt`
23. `GovernorRecovery6388.lastPromotionReason` — `engine/truth/GovernorRecovery6388.kt`
24. `HistoricalChartScanner.getProgress` — `engine/HistoricalChartScanner.kt`
25. `HoldingLogicLayer.getHoldParams` — `engine/HoldingLogicLayer.kt`
26. `IdempotencyKeyStore6437.sellKey` — `engine/truth/IdempotencyKeyStore6437.kt`
27. `JupiterPerps.getPoolInfo` — `perps/JupiterPerps.kt`
28. `LearnerRuntimeBudgetGuard6441.shouldStop` — `engine/truth/LearnerRuntimeBudgetGuard6441.kt`
29. `LiveExecutionGate.sellCompleted` — `engine/LiveExecutionGate.kt`
30. `LiveExecutionGate.trySell` — `engine/LiveExecutionGate.kt`
31. `LiveExitOnlyMode6387.classifyForStop` — `engine/truth/LiveTruthExitAuthority6387.kt`
32. `LiveStrategyTuner.tpMultiplier` — `engine/LiveStrategyTuner.kt`
33. `PerpsAdvancedAI.shouldPartialExit` — `perps/PerpsAdvancedAI.kt`
34. `PerpsMarketDataFetcher.getPriceSource` — `perps/PerpsMarketDataFetcher.kt`
35. `PerpsTrailingStop.getTrailStop` — `perps/PerpsTrailingStop.kt`
36. `PortfolioInvariants6405.verifyWalletParity` — `engine/truth/PortfolioInvariants6405.kt`
37. `PositionIdentity6395.activeExitIntent` — `engine/truth/PositionIdentity6395.kt`
38. `PositionStateLedger6454.onPartial` — `engine/truth/PositionStateLedger6454.kt`
39. `PriceAggregator.getPrunedSymbols` — `perps/PriceAggregator.kt`
40. `QuantMetrics.calculateProfitFactor` — `engine/quant/QuantMetrics.kt`
41. `RouteValidator.validateFinalOutput` — `engine/execution/RouteValidator.kt`
42. `RuntimeRepairState.requestPaperMode` — `engine/RuntimeRepairState.kt`
43. `ScannerHeatPublisher6398.currentPct01` — `engine/truth/ScannerHeatPublisher6398.kt`
44. `SellIntentQuantityAuthority6401.validateSellIntentFromUi` — `engine/truth/SellIntentQuantityAuthority6401.kt`
45. `ShadowLearningEngine.getPerformanceByMode` — `v3/learning/ShadowLearningEngine.kt`
46. `SmartExitOptimizer.getExitPressure` — `engine/SmartExitOptimizer.kt`
47. `SolanaWallet.getPublicKeyOnly` — `network/SolanaWallet.kt`
48. `TacticBleedPivot.getLastPivot` — `engine/TacticBleedPivot.kt`
49. `TelegramNotifier.partialMsg` — `engine/TelegramNotifier.kt`
50. `TerminalFinalityAuthority6405.allowExit` — `engine/truth/TerminalFinalityAuthority6405.kt`
51. `ToxicModeCircuitBreaker.activateEmergencyStop` — `engine/ToxicModeCircuitBreaker.kt`
52. `ToxicModeCircuitBreaker.deactivateEmergencyStop` — `engine/ToxicModeCircuitBreaker.kt`
53. `TradeIdentityManager.auditTrail` — `engine/TradeIdentity.kt`
54. `TreasuryOpportunityEngine.getPendingOpportunities` — `engine/TreasuryOpportunityEngine.kt`


## Completed in 5.0.7429

- [x] Exact strategy identity survives canonical entry snapshot → finalized bus.
- [x] StrategyHypothesisEngine terminal credit is position-bound, not mint-current.
- [x] Exact `StrategyVariantStore` ID is stamped to the opened position and terminal credit follows that stamped ID.
- [x] Added `ExactStrategyPerformance7429` scoreboard keyed by mode/lane/tradeType/setup/style/tactic/variant.
- [x] Added runtime complete-vs-incomplete attribution counters so missing strategy identity is visible.
- [x] Wired `SourceTimingRegistry.isLateSignal` into predictive admission as bounded negative evidence for late/trending-only discovery.

## V5.0.7430 progress — exact strategy EV + predictive tool triage

- [x] Exact playbook identity now participates in `StrategyHypothesisEngine` context (`tradeType > setup > style > tactic`) with parent baseline fallback.
- [x] Exact strategy causal rows retain realized return and expose sample / WR / mean EV / PF.
- [x] New exact contexts are seeded once from realized exact-strategy EV; the prior is bounded and cannot hard-veto.
- [x] `TradingCopilot.convictionBoost/sizingMultiplier` now contribute bounded predictive evidence.
- [x] `HistoricalChartScanner.getHistoricalRecommendation` now contributes only when real liquidity + real volume and >=5 historical samples exist.
- [x] `OrthogonalSignals.calculateAgePatternScore` now contributes from authoritative launch age + canonical graduation state.
- [x] `MomentumPredictorAI.getStrongMomentumTokens` reclassified from UNWIRED: already consumed by `LaneHunter7297.claimMomentum7298`.
- [x] `SourceTimingRegistry.isLateSignal` reclassified ALIAS_REDUNDANT: `ScoreCard` already consumes the underlying `getSourceTimingPenalty`; wiring both would double-count timing.
- [ ] `CrossAssetLeadLagAI.getRotationProbability` remains blocked on producer-key mismatch: crypto feeds mint IDs while model pairs are symbol/sector keys (BTC/SOL/MEME_SECTOR/etc). Repair producer identity before consuming it.
- [ ] `PatternBacktester.getConfidenceAdjustments` requires a cached/background backtest report; do not call synchronously from admission.
- [ ] `EducationSubLayerAI.getEdgeLedger` is aggregate reason-ledger output and needs candidate-key mapping before it can safely affect admission.
- [ ] `TradeDatabase.getSignalWinRate` overlaps source-performance evidence; classify per-key before adding to avoid correlated double-counting.
- [ ] `ScoreDistributionHistogram6396.recommendAdaptiveBaseline` is threshold calibration, not candidate alpha; route to threshold authority rather than oracle.

## 5.0.7431 runtime reclassification

- [x] SourceTimingRegistry.isLateSignal — ALIAS_REDUNDANT. ScoreCard already consumes getSourceTimingPenalty; do not double-count.
- [x] MomentumPredictorAI.getStrongMomentumTokens — CLOSED_LOOP / ledger stale; production caller exists in LaneHunter7297.
- [x] TradingMemory.getPatternWinRate — PARTIAL_CLOSED_LOOP / cold evidence. Oracle caller exists; runtime useful reads=0.
- [x] TradingCopilot.convictionBoost — PARTIAL_CLOSED_LOOP / neutral state. Oracle caller exists; runtime useful reads=0.
- [x] HistoricalChartScanner coarse mode wrapper — ALIAS_COARSE; richer historical recommendation is consumed. Runtime predictive reads=337.
- [x] OrthogonalSignals.calculateAgePatternScore — CLOSED_LOOP. Runtime reads=3508.
- [x] Cross-asset symbol feed — CLOSED_LOOP input feed. Runtime reads=2539.

Strategy identity runtime proof:
- tactic attribution invalid=0
- hypothesis decision stamped=545
- hypothesis position bound=2, bind missing=0
- position-bound outcome=1, missing=0
- exact variant stamped=2, exact variant outcome=1
- FDG exact strategy identity=13
- exact hypothesis contexts=13

Historical note: exact-strategy complete=1 and incomplete=506 is primarily pre-7427 historical data with no sealed tradeType/setup/style. Treat those rows as LEGACY_UNSTAMPED; never infer missing historical identity.

Runtime correctness proof:
- executable mark block=0
- valid-source missing executable mark=0
- held stale/missing=0
- fast-lane saturation=12/195
- owner-lane rewrites=0
- sealed-intent provenance misses=0

## Execution order

### Phase 1 — causal identity and attribution
1. Add exact strategy/setup/style/variant identity to immutable entry snapshots.
2. Carry it through canonical finalization and persistence.
3. Make strategy learners consume the exact sealed identity rather than recomputing current state at close.
4. Add attribution-missing / attribution-mismatch counters.

### Phase 2 — high-value unwired predictive inputs
Prioritize entry-time information that can improve early lifecycle selection without synchronous provider latency:
- source timing
- momentum prediction
- smart-money / network corroboration
- historical setup quality
- Harvard / education edge memory
- pattern win-rate evidence
- cross-asset / rotation evidence where relevant

Route these into one bounded predictive evidence envelope rather than independent vetoes.

### Phase 3 — risk and lifecycle evidence
Wire useful B_RISK items only where they add distinct facts. Avoid duplicating hard-safety authority.

### Phase 4 — exit intelligence
Wire C_EXIT items into the canonical held-position supervisor / exit policy rather than letting modules dispatch independent sells.

### Phase 5 — taxonomy completion
Implement genuinely missing primitives only after aliases and partial implementations are identified, so new code does not duplicate existing mechanisms.

### Phase 6 — paper/live parity proof
For each canonical PAPER entry, persist the sealed decision identity and the exact LIVE-equivalent decision.
For LIVE, prove the same decision reached execution unchanged except for venue/fill/finality mechanics.

## Acceptance conditions

- No canonical trade lacks a sealed strategy identity.
- No terminal outcome is credited by looking up "whatever strategy is active now".
- No strategy promotion/demotion uses mixed identities.
- PAPER and LIVE use the same canonical decision spine.
- Shadow/LAB exploration stays separate from deployment-quality PAPER.
- Every retained high-value tool has a real consumer and a runtime proof counter.
- Dead/duplicate surfaces are retired or explicitly documented instead of inflating the apparent strategy count.


## 7431 runtime evidence / 7432 follow-up

7431 confirmed the new strategy-identity path is live for fresh trades:

- `HYPOTHESIS_DECISION_STAMPED_7428=545`
- `HYPOTHESIS_POSITION_BOUND_7428=2`
- `HYPOTHESIS_POSITION_OUTCOME_7428=1`
- `STRATEGY_VARIANT_EXACT_STAMPED_7428=2`
- `STRATEGY_VARIANT_EXACT_OUTCOME_7428=1`
- `TACTIC_ENTRY_ATTRIBUTION_INVALID_6568=0`

The exact-strategy table showed `complete=1/507`; the 506 incomplete rows are predominantly historical terminals created before exact strategy fields existed. They remain useful only at coarser lane/tactic levels and must not be fabricated into exact identities.

7432 diagnostic correctness work:

- [x] Finalized-learning reconciliation distinguishes a durable full-terminal `BUS_PUBLISH_FAILED` from `HISTORICAL_NO_DURABLE_FINALITY`.
- [x] No historical terminal is replayed or assigned invented economics.
- [x] TradingMemory telemetry separates `CONSULTED`, `NEUTRAL`, and actual contributed reads.
- [x] TradingCopilot telemetry separates `CONSULTED`, `NEUTRAL`, and actual contributed reads.
- [x] Exact-strategy EV prior telemetry reports `CONSULTED`, `NO_EVIDENCE`, `IMMATURE`, `NEUTRAL`, positive and negative states.
- [x] `SourceTimingRegistry.isLateSignal` remains deliberately unwired as an alias; `ScoreCard` already consumes the underlying source-timing penalty and a second vote would double-count the same fact.

7431 also confirmed:
- executable-mark choke repaired: `EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613=0`;
- held live mark freshness healthy: stale/noMark = 0/0;
- fast-lane saturation reduced to 12/195 (~6%);
- current finalized consumer delivery has zero refusals;
- current exact strategy binding is position-based rather than mint-current.


## V5.0.7455 — held-management causal loop

- [x] `HoldingLogicLayer.evaluatePosition` now consumes `getHoldParams` as the single mode-parameter read surface.
- [x] `LiveStrategyTuner.tpMultiplier`, `holdMultiplier`, and `partialTriggerMultiplier` are consumed through one cached adjustment in canonical held-position management.
- [x] The learned hold multiplier reaches both the legacy max-hold clock and the earlier FluidLearning max-hold clock, so the fluid branch can no longer make learned hold policy inert.
- [x] Learned TP/hold/partial shaping is advisory only; hard stop loss and AEM critical safety remain unconditional.
- [x] `SmartExitOptimizer.getExitPressure` classified **ALIAS_REDUNDANT_FOR_MEME_HOLD**: it simply invokes `SymbolicExitReasoner.assess`, while the canonical meme hold/exit stack already evaluates symbolic/AEM evidence. Do not add a second hot-path symbolic pass just to clear the unwired ledger.
- [x] Runtime proof counters: `HOLD_PARAMS_CANONICAL_READ_7455`, `HOLD_EXIT_TUNER_CONSUMED_7455` (+ mode suffix).

## V5.0.7457 — canonical partial lifecycle projection

- [x] `PositionStateLedger6454.onPartial()` moved from dead API to a causal consumer at `CanonicalPositionAuthority6441.partialSell()`.
- [x] Partial lifecycle projection runs only after the canonical mutation has committed `PARTIALLY_CLOSED`.
- [x] An unregistered LIVE ledger row may seed to PARTIAL only when the canonical row proves `PARTIALLY_CLOSED` with remaining quantity.
- [x] CLOSING/CLOSED ledger states are never overwritten by a partial callback.
- [x] Runtime proof counters: `POSITION_STATE_PARTIAL_PROJECTED_7457`, `POSITION_STATE_PARTIAL_APPLIED_7457`, `POSITION_STATE_PARTIAL_REFUSED_NO_CANONICAL_PROOF_7457`, `POSITION_STATE_PARTIAL_REFUSED_STATE_7457`.

## V5.0.7458 — specialist capital proposal continuity

- [x] `GlobalCapitalArbitration6617.recordSpecialistProposal6617()` moved from dead/test-only API into the canonical SOLANA specialist sizing bridge.
- [x] Proposals are keyed by `mint + lane`, so multiple specialist desks can independently propose the same token without overwriting each other.
- [x] `CanonicalEntryAuthority6551.submit()` verifies that the specialist lane being sealed into the execution intent has a proposal for that same asset.
- [x] Verification is telemetry-only to expose silent lane swaps without creating a new throughput choke during convergence.
- [x] Runtime proof: `SPECIALIST_PROPOSAL_RECORDED_7458`, `SPECIALIST_PROPOSAL_DISPATCH_MATCH_7458`, `SPECIALIST_PROPOSAL_MISSING_AT_DISPATCH_7458`, `SPECIALIST_PROPOSAL_DISPATCH_MISMATCH_7458`.

## V5.0.7459 — real specialist sized→ticket→exec handoff telemetry

- [x] `RuntimeTune6833.recordSized6833`, `recordTicket6833`, and `recordExec6833` moved from zero-caller helpers into the deduped `ToolkitSignalSheet.recordDeskStage` authority.
- [x] The counters now consume the same causal stage stream as `SpecialistCausalFunnel6625`, avoiding a parallel observer or duplicate execution path.
- [x] SHITCOIN handoff choke evaluation runs only on real SIZE/TICKET/EXEC stage transitions and remains advisory.
- [x] No scoring, sizing, ticket creation, or execution policy changed.
- [x] Runtime proof: `RuntimeTune6833.statusLine6833()` now reflects real stage counts and can emit `SHITCOIN_HANDOFF_STALLED_6833` from actual production evidence.


# P0 runtime-derived repair batch queue — baseline 5.0.7456

Reference snapshot:
- Build/tag: `5.0.7456`
- Uptime: 471s
- Canonical open: 65 PAPER / 0 LIVE
- Held supervisor: held=65 fresh=63 staleRefresh=0 missing=0 discoveryResident=0
- Paper capital conservation: OK, delta ~= 0
- Canonical/registry parity: 68/68, qtyMismatch=0
- Avg/max bot-loop cycle: 6566ms / 38723ms
- Acceptance audit: failures=9
- Finalized-learning gap: closed=1430 published=1084 missing=373 BUS_PUBLISH_FAILED

These are P0 correctness/causality repairs. Do not treat them as threshold tuning and do not disable LIVE to hide them.

## P0-0 — restore a green installable build before further functional batches
- [ ] 5.0.7456 remains the last confirmed green baseline.
- [ ] Do not stack new functional audit changes on a red head.
- [ ] For every red build: pull the exact CI failure, repair that blocker in one coherent recovery batch, and re-run until green.
- [ ] No dead-code exemptions or failure masking when the new declaration can be wired, made private, or removed.

## P0-1 — specialist causal predecessor / identity repair
Baseline evidence:
- PROJECT_SNIPER: discovered=0/qualified=0 but rawSized=5 rawTicket=4 rawExec=4 rawOpen=4; phantomMissing=NO_DISCOVER=5,NO_INTENT=5.
- DIP_HUNTER: discovered=0/qualified=0 but rawSized=2 rawTicket=1 rawExec=1 rawOpen=1; phantomMissing=NO_DISCOVER=2,NO_INTENT=2.
- CASHGEN: discovered=0/qualified=0 but rawSized=2 rawTicket=1 rawExec=1 rawOpen=1; phantomMissing=NO_DISCOVER=2,NO_INTENT=2.
- CYCLIC: rawSized=1 rawTicket=1 rawExec=1 rawOpen=1 while validated downstream counts are zero; suppressedStages=SIZE,TICKET,EXEC,OPEN.
- Funnel suppression counter: `FUNNEL_STAGE_COUNT_SUPPRESSED_7214=18`.
Required repair:
- [ ] Preserve the same immutable candidate/attempt identity from discovery -> qualification -> owner selection -> intent -> FDG -> mark -> size -> ticket -> exec -> open.
- [ ] Never backfill a predecessor from a later stage unless same-record proof is complete.
- [ ] Raw stage counts and validated counts must converge for fresh post-fix attempts.
- [ ] Keep `PROJECT_SNIPER_NON_SNIPER_ADMISSION=0`.

## P0-2 — executable mark -> sizing continuity
Baseline evidence:
- `missingExecutableMarkWithValidSource=164`.
- `EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613=164`.
- MARK health: broken=277, suppressed=277.
- Valid source/no executable mark telemetry: 194.
Required repair:
- [ ] Trace exact source-valid -> canonical mark -> mark identity -> sizing edge.
- [ ] A valid source must end in either MARK_READY or an explicit terminal MARK_REJECT reason.
- [ ] No candidate may reach executable sizing without an immutable canonical mark.
- [ ] Eliminate phantom size records caused by missing mark predecessors.

## P0-3 — BLUECHIP sizing-path break
Baseline evidence:
- candidateN=1208 qualifiedN=1208 ownerSelectedN=16 buyIntentN=18 fdgN=78 markN=7.
- sizedN=0 ticketN=0 execN=0 positionOpenedN=0.
- Capital is available: sharedCash=59.4863, enforcedHeadroom=true, capitalStarved=false.
Required repair:
- [ ] Trace BLUECHIP mark -> canonical sizing bridge -> sealed size -> ticket.
- [ ] Identify exact suppressor rather than loosening score/floor policy.
- [ ] Prove one causal post-fix BLUECHIP attempt can either size or terminate with a named reason.

## P0-4 — CORE ensemble sizing continuity
Baseline evidence:
- candidateN=380 qualifiedN=380 ownerSelectedN=18 buyIntentN=41 fdgN=22 markN=1.
- sizedN=0 ticketN=0 execN=0.
- status=SIZING_CHOKED with shared capital available.
Required repair:
- [ ] Preserve CORE as ensemble/coordinator for opportunities not cleanly owned by a specialist.
- [ ] Trace owner-selected/intent/FDG/mark -> size without collapsing CORE into a generic tag.
- [ ] Ensure contributors can influence but cannot create duplicate economic execution.

## P0-5 — EXPRESS ownership / intent continuity
Baseline evidence:
- candidateN=322 qualifiedN=322 fdgN=53.
- ownerSelectedN=0 buyIntentN=0 markN=0 sizedN=0 ticketN=0 execN=0.
- status=INTENT_CHOKED.
Required repair:
- [ ] Find why EXPRESS can reach FDG accounting with no owner/intent lineage.
- [ ] EXPRESS remains the intentionally reactive chase/scalp desk; do not convert it into launch/sniper ownership.
- [ ] Require same-lane owner/intent proof before executable downstream stages.

## P0-6 — Crypto Universe discovery -> CryptoBrain -> V3/FDG handoff
Baseline evidence:
- unique chain+token identities=4591.
- fresh pools discovered=23; fresh reaching CryptoBrain=33; fresh reaching V3/FDG=0.
- live-routable candidates=28.
- terminal reasons: `CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244=1067` / 1110 terminal.
- Cross-asset producer liveness: only CRYPTO_ALT started; STOCK/FOREX/COMMODITY/METAL/PERPS all zero.
Required repair:
- [ ] Audit CryptoBrain actionable-signal logic and handoff contract before changing thresholds.
- [ ] Preserve static-vs-dynamic candidate identity.
- [ ] Prove fresh/routable candidates can reach canonical V3/FDG when strategy evidence is actionable.
- [ ] Restore configured non-crypto cross-asset producer liveness where the product configuration expects those traders to run.

## P0-7 — finalized-learning population completeness
Baseline evidence:
- canonical closed=1430.
- finalized published=1084.
- missing=373, all classified `BUS_PUBLISH_FAILED`.
- acceptance failure includes reward population mismatch.
Required repair:
- [ ] Repair only provable durable finalized-bus publication gaps.
- [ ] Require CLOSED canonical position + durable full SELL + immutable entry identity + trustworthy economics.
- [ ] Never synthesize economics for historical rows lacking durable proof.
- [ ] Fresh terminal close must publish once and reach all intended consumers once.

## P0-8 — strategy / hypothesis attribution completeness
Baseline evidence:
- `HYPOTHESIS_POSITION_BOUND_7428=2`, bind missing=20.
- hypothesis outcomes=0, outcome missing=22.
- exact strategy complete=13/13 for fresh complete rows but legacy incomplete=9.
- `TACTIC_ENTRY_ATTRIBUTION_INVALID_6568=9`.
- UnifiedPolicy pendingPositions=671.
Required repair:
- [ ] Exact entry identity must bind at open and survive to terminal outcome.
- [ ] Invalid/legacy tactic identity remains forensic-only; never credit current close-time tactic.
- [ ] Reduce bind/outcome misses for fresh post-fix positions to zero.
- [ ] Keep restored/replayed/administrative rows out of strategy learning.

## P0-9 — provider/data-waste reduction
Baseline evidence:
- token metric observations=50936, supplyCaptured=169, identityBroken=1475, unverifiable=25552.
- token birth resolved: TOKEN_META_CREATION=24075, FIRST_POOL_CREATION=13460.
- `TOKEN_METRICS_UNVERIFIABLE_CAP_STALE_7268=4926+`.
- keyless OHLCV fetches=969 served=0; localSkips=945.
- Pump token-trade subscription skipped no key >7k.
- dead/degraded providers include Birdeye auth failure, Dexpaprika disabled, GeckoTerminal poor health, several LLM endpoints terminal/quota-limited.
Required repair:
- [ ] Remove repeated known-dead provider work from hot paths.
- [ ] Coalesce repeated token birth/metric hydration by canonical identity and freshness epoch.
- [ ] Cache negative/no-capability results with bounded TTL.
- [ ] Keep provider degradation fail-open where safe, but never retry dead capabilities every candidate/cycle.

## P0-10 — bot-loop / worker latency and exit-service stability
Baseline evidence:
- bot-loop avg=6566ms, max=38723ms; cycles >30s observed.
- supervisor workerTimeout=36, expiredLeases=9.
- risk-clock budget overruns=34.
- exit coordinator stale resets=2.
- main-thread ANR hints=0, stall=0%; therefore primary fault is pipeline/worker/provider workload, not UI ANR.
Required repair:
- [ ] Attribute >5s cycle time by phase and provider/worker wait.
- [ ] Keep UI/report and learner maintenance off the trading hot path.
- [ ] Bound provider calls and fanout per cycle.
- [ ] Eliminate stale coordinator resets and worker timeout storms without weakening exit safety.

## P0-11 — preserve good 7456 invariants while repairing
Must remain true:
- [ ] held supervisor `discoveryResident=0`.
- [ ] held missing=0 and staleRefresh approximately 0 in steady state.
- [ ] canonical/registry parity exact.
- [ ] quantity mismatch=0 and oversell prevented.
- [ ] paper capital conservation delta approximately 0.
- [ ] LIVE remains enabled/capable; do not convert architecture faults into global HOLD/WAIT.
- [ ] no duplicate canonical execution per mint/version.
- [ ] no owner-lane rewrite after selection.
- [ ] no cross-lane execution rewrite.

## P0 acceptance bar against 5.0.7456
A repair series is not complete until a fresh runtime snapshot shows:
- build green and installable;
- causal funnel raw/validated stage counts no longer hiding real opens behind missing predecessors;
- valid-source missing executable mark materially reduced from 164 with named terminal outcomes for the remainder;
- BLUECHIP/CORE no longer silently die between mark and size;
- EXPRESS either creates same-lane intent or terminates before FDG/execution accounting;
- fresh Crypto Universe candidates can reach V3/FDG when actionable;
- durable finalized-bus gaps trend toward zero without synthetic history;
- fresh strategy identity bind/outcome misses trend to zero;
- cycle latency and worker timeout/stale-reset counts materially improve;
- all preserved invariants above remain intact.


## V5.0.7464 — P0-1 specialist causal predecessor repair (runtime proof pending)

Baseline 5.0.7456 showed PROJECT_SNIPER / DIP_HUNTER / CASHGEN / CYCLIC reaching raw SIZE/TICKET/EXEC/OPEN while validated lineage dropped them for missing DISCOVER and/or INTENT.

- [x] Added provenance-gated DISCOVER recovery from existing `GlobalTradeRegistry` lane affinity on the exact immutable causal key.
- [x] QUALIFY can be recovered only when that same key has already reached OWNER or a later executable stage.
- [x] No INTENT/FDG/MARK/SIZE stage is fabricated by this repair.
- [x] Existing 7418 same-record executable-lineage rule remains the only INTENT backfill path.
- [x] Missing affinity proof remains visible via `SPECIALIST_AFFINITY_LINEAGE_NO_PROOF_7464_<LANE>`.
- [ ] Runtime acceptance: fresh post-7464 PROJECT_SNIPER/DIP_HUNTER/CASHGEN/CYCLIC raw and validated downstream counts converge without increasing cross-lane rewrites.


## V5.0.7465 — P0-2 canonical mark -> entry continuity (runtime proof pending)

5.0.7456 baseline: `missingExecutableMarkWithValidSource=164`, `EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613=164`, broken/suppressed marks=277.

- [x] Added one mode-aware entry-mark resolver in `CanonicalPriceMarkRegistry6522`.
- [x] LIVE remains strict: no OBSERVATION_SCORING fallback can authorize a live entry.
- [x] PAPER preserves the established 6579 doctrine: strict executable mark preferred, otherwise fresh authoritative observation mark.
- [x] Fresh FDG intent sealing, PAPER execution, and expired-ticket resealing now consume the same mark-selection authority.
- [x] Fresh intents now seal mark source/timestamp/price as well as mark id/version; mark provenance is no longer empty decoration.
- [x] `missingExecutableMarkWithValidSource` now means fresh named price evidence actually existed, rather than any stale/nonzero cache value.
- [x] Missing marks remain a hard refusal; no synthetic price/liquidity/route proof is created.
- [ ] Runtime acceptance: valid-source missing mark count materially below 164 and remaining refusals carry `ENTRY_MARK_MODE_RESOLVER_MISSING_REASON_7465_*`.


## V5.0.7466 — P0-3 BLUECHIP sealed-attempt sizing continuity (runtime proof pending)

5.0.7456 baseline: BLUECHIP candidate=1208 qualified=1208 ownerSelected=16 buyIntent=18 fdg=78 mark=7, but sized/ticket/exec/open all zero while shared capital had headroom.

- [x] Dedicated BLUECHIP sub-trader now reuses the active immutable FDG intent attemptId when entering `TradeAuthorizer`.
- [x] No BLUECHIP threshold, score floor, allocation target, or position size formula changed.
- [x] MARK_READY / SIZED_EXECUTABLE / TICKET are recorded only after `TradeAuthorizer` returns executable, i.e. after executable-open finality and minimum-size checks have actually passed.
- [x] Missing sealed intent remains observable via `BLUECHIP_SEALED_INTENT_MISSING_BEFORE_AUTH_7466`; the code does not fabricate one.
- [x] Successful same-attempt continuity is measured by `BLUECHIP_SEALED_INTENT_REUSED_FOR_AUTH_7466` and `BLUECHIP_POST_AUTH_CAUSAL_HANDOFF_7466`.
- [ ] Runtime acceptance: fresh BLUECHIP mark-ready attempts either reach sized/ticket or terminate with a named authorization/finality reason; no silent mark->size disappearance.


## V5.0.7467 — P0-4 CORE / primary-spine causal sizing continuity (runtime proof pending)

5.0.7456 baseline: CORE candidate=380 qualified=380 ownerSelected=18 buyIntent=41 fdg=22 mark=1, sized/ticket/exec all zero with shared capital available.

- [x] Generic primary/CORE spine now reuses a lane-matched immutable FDG intent attemptId when entering `TradeAuthorizer`.
- [x] A sealed intent from another specialist lane is never reused for CORE or another primary lane.
- [x] MARK_READY / SIZED_EXECUTABLE / TICKET are no longer backfilled merely because an `ExecutionIntent` object exists.
- [x] Post-FDG stages require executable authorization, the exact same attemptId, positive sealed size, and sealed mark provenance.
- [x] Contributor lanes remain contributors; no new economic execution path or duplicate mint/version execution was added.
- [x] No CORE score floor, sizing formula, or allocation target changed.
- [ ] Runtime acceptance: CORE mark-ready attempts either reach canonical size/ticket on the same attempt or terminate with a named auth/finality/proof reason.


## V5.0.7468 — grouped specialist split-attempt repair (P0 causal identity)

Shared fault class confirmed in 5 dedicated meme specialists: TREASURY, QUALITY, MOONSHOT, MANIPULATED, DIP_HUNTER ran FDG first, then entered `TradeAuthorizer.authorize()` without the sealed attemptId, creating a second causal record.

- [x] Added one shared `sealedSpecialistAttempt7468()` helper.
- [x] Reuse requires exact candidate-version + canonical-lane match.
- [x] TREASURY / QUALITY / MOONSHOT / MANIPULATED / DIP_HUNTER now carry the already-sealed FDG attempt into authorization.
- [x] Missing/mismatched sealed intent is observable and fail-visible; no intent is fabricated.
- [x] SHITCOIN / EXPRESS / PROJECT_SNIPER already carried explicit attempts and were not changed.
- [x] BLUECHIP remains on its stricter 7466 lane-specific repair.
- [x] Early direct CORE/V3 authorization is intentionally excluded: it authorizes before sealing FDG and is a different ordering defect.
- [x] No lane thresholds, scores, position sizes, capital allocation, or LIVE safety rules changed.
- [ ] Runtime acceptance: fresh validated BUY_INTENT/OWNER/FDG/MARK/SIZE/TICKET chains stop splitting across attempt IDs for these five lanes.


## V5.0.7469 — grouped post-FDG fresh-attempt repair (EXPRESS + SHITCOIN)

Fault class: both lanes called `ExecutableOpenGate.recordFdg()` and then immediately generated a brand-new `nextAttemptId()` before authorization. That defeats the sealed FDG causal identity even though an explicit attemptId parameter is present.

- [x] EXPRESS now reuses the lane/version-matched sealed FDG attempt when available.
- [x] SHITCOIN now reuses the lane/version-matched sealed FDG attempt when available.
- [x] If no executable sealed intent exists, both lanes retain the prior fresh-attempt fallback. This preserves paper bootstrap/probe paths that intentionally continue after a non-executable FDG opinion.
- [x] PROJECT_SNIPER is not changed: its standalone path does not seal FDG first, so generating its own attempt remains correct.
- [x] No entry thresholds, FDG decisions, paper-probe policy, position sizes, or LIVE safety rules changed.
- [ ] Runtime acceptance: EXPRESS ownerSelected/buyIntent become non-zero on real executable candidates and SHITCOIN phantom split-stage counts fall without changing execution volume policy.


## V5.0.7470 — grouped position-bound learner identity repair (P0-8)

5.0.7456 baseline: `HYPOTHESIS_POSITION_BOUND_7428=2`, bind missing=20, hypothesis outcome missing=22, while UnifiedPolicy held 671 pending positions.

Confirmed defect: `StrategyHypothesisEngine.bindExecutedPosition7428()` had zero production callers even though canonical terminal learning uses the position-bound outcome path.

- [x] StrategyHypothesis/variant binding now occurs at the same canonical OPEN attribution boundary as UnifiedPolicy.
- [x] Binding uses the immutable sealed AATE envelope's `candidateVersion` and `primaryStrategy`; no current-version/current-lane lookup is allowed.
- [x] Non-winning fanout lanes still cannot steal terminal credit because only the canonical opened position reaches `attachPosition()`.
- [x] Existing persisted hypothesis binding and terminal settle-once guards remain intact.
- [x] Invalid/legacy tactic identity remains forensic-only per 7456.
- [x] No strategy thresholds, arm promotion rules, sizing, or execution policy changed.
- [ ] Runtime acceptance: fresh opens materially increase `HYPOTHESIS_POSITION_BOUND_7428`; fresh terminal `HYPOTHESIS_POSITION_OUTCOME_MISSING_7428` trends to zero.


## V5.0.7471 — shared specialist canonical handoff bundle (P0 cross-stack)

- [x] All specialist downstream funnel stages prefer immutable sealed ExecutionIntent candidateVersion for the same mode+mint+canonical lane.
- [x] Callback/current candidateVersion is fallback only before a sealed intent exists.
- [x] No cross-lane intent reuse; canonical lane equality is required.
- [x] TokenMapAuthority exposes strict cached executable entry proof requiring route, expectedOut, real price, real liquidity and concrete venue identity.
- [x] CanonicalPriceMark entry resolution materialises that already-proven TokenMap evidence through existing integrity gates before declaring a mark missing.
- [x] LIVE strictness unchanged; no observation-only/stale/pending/synthetic evidence is promoted.
- [x] Shared scope: QUALITY/BLUECHIP/SHITCOIN/CYCLIC/EXPRESS/CORE/MOONSHOT/PROJECT_SNIPER/DIP_HUNTER/MANIPULATED/TREASURY/CASHGEN.
- [x] No score floors, strategy thresholds, sizing multipliers, allocation targets or exit policy changed.


## V5.0.7472 — Crypto Universe fresh-evidence warmup handoff (P0-6)

- [x] Fresh Crypto Universe candidates are no longer terminally classified NO_ACTIONABLE while the existing local tactic tape has fewer than four observations.
- [x] Samples 1..3 release the evaluation lease as CRYPTO_FRESH_TAPE_WARMUP_7472 so the next scan can add evidence.
- [x] At four or more observations the existing CryptoBrain/specialist decision remains authoritative; no score/confidence floor is lowered.
- [x] No synthetic BUY/PROBE signal is created and no route/sizing/live-safety rule is bypassed.
- [x] Runtime acceptance: fresh reaching V3/FDG becomes non-zero when existing specialist/brain evidence actually produces actionable candidates; warmup counters explain candidates still accumulating tape.


## V5.0.7473 — additive canonical finalized-learning projection recovery

- [x] Rich terminal persistence/subscribers remain exactly-once.
- [x] Duplicate terminal callbacks can re-drive only the missing canonical 6464 learning projection.
- [x] No cash, quantity, journal or position mutation is replayed.
- [x] Public duplicate semantics remain false; a recovery pass is not treated as a second terminal event.
- [x] Existing 7459 bounded durable reconciler remains as historical fallback.


## V5.0.7474 — additive exact-position paper open-cost parity

- [x] Typed paper replay now retains remaining cost basis by immutable positionId in addition to existing per-mint aggregates.
- [x] Current open-cost parity uses exact active positionIds when every current lot is represented in the typed event window.
- [x] Re-entry into a previously closed mint can no longer inherit old per-mint carry basis into current-open parity.
- [x] If exact position coverage is incomplete, the existing carry/mint reconciliation remains unchanged; no inferred allocation is created.
- [x] Divergence guard thresholds are unchanged. The repair makes evidence converge rather than weakening the guard.


## V5.0.7475 — additive intake/evidence fanout compression

Runtime evidence from 5.0.7466: 4,595 intake events / 639 unique symbols, PROBATION=3,377, WATCHLIST_AFFINITY=4,499, 6,984 shadow lane evaluations, max bot-loop 137s with wedge in GlobalTradeRegistry.mergeAffinity.

- [x] Affinity merge now returns whether genuinely new lane/tool evidence was added and avoids temporary uppercase collections.
- [x] Same-source probation repeats no longer masquerade as multi-scanner confirmation; only distinct source evidence promotes.
- [x] Per-mint dedupe refresh still propagates higher liquidity, higher mcap, confidence, new source, and new lane/tool evidence.
- [x] A duplicate callback that adds no information no longer re-runs addToWatchlist freshness mutation or LIVE_READY hydration enqueue.
- [x] No scanner source, lane, specialist, learning path, safety gate, score floor, or sizing authority is removed.
- [x] New evidence remains additive and immediately visible; only no-change repetition is coalesced.


## V5.0.7476 — additive unchanged-intelligence reuse

- [x] ToolkitSignalSheet no longer rebuilds every 2.5s solely because time passed.
- [x] Unchanged fingerprints may reuse the last full multi-desk sheet for 15s.
- [x] Any real fingerprint change still schedules an immediate refresh.
- [x] Timestamp-only quote updates no longer invalidate the sheet; price movement is represented by scale-independent 10bp log buckets.
- [x] History growth, V3 score/confidence, buy/sell pressure, liquidity, mcap, source, trade type and classification confidence remain fingerprint inputs.
- [x] No specialist hypotheses, lanes, tools, scores, execution paths or learning outputs are removed.


## V5.0.7477 — additive revision-gated maintenance

- [x] Position-registry parity reuses its exact previous result when canonical mutation count + registry authority epoch are unchanged.
- [x] Real canonical or registry mutation invalidates reuse immediately.
- [x] Scheduled parity no longer unconditionally rebuilds the legacy registry before auditing it.
- [x] Existing divergence-streak auto-heal remains intact.
- [x] Full forensic reconstruction reuses its previous result only when forensic-row revision + canonical mutation count are unchanged.
- [x] Forensic row snapshots are not copied when reconstruction is provably unchanged.
- [x] No audit, auto-heal, invariant, or reconciliation capability is removed.


## V5.0.7478 — additive strategy-history revision cache

Runtime evidence from 5.0.7466: STRATEGY_TERMINAL_FOLDED_PARTIALS_7333=154174 against roughly 2400 strategy rows.

- [x] StrategyTruthLedger clean-cache identity is keyed to TradeHistoryStore.journalRevision7343 plus exact row-count/limit/endpoints.
- [x] Any journal mutation invalidates immediately; unchanged historical corpora no longer expire merely because ten seconds passed.
- [x] Different list windows remain separated by limit, exact size and endpoint identity.
- [x] Folded-partial telemetry is lifetime-deduped by canonical terminal identity.
- [x] Partial-leg economics, learner populations, strategy rows and strategy decisions are unchanged.
- [x] Cache remains bounded to the existing 16 entries.


## V5.0.7479 — additive exact forensic-repeat coalescing

- [x] Exact repeated lifecycle text inside a 5s window is coalesced before disk/logcat queueing.
- [x] PipelineHealthCollector.onLifecycle still receives every occurrence, so counts/frequency remain exact.
- [x] Canonical-event bridge still evaluates every lifecycle occurrence; no execution/economic event is removed.
- [x] When a repeated row becomes emit-eligible again, one FORENSIC_REPEAT_SUMMARY_7479 row reports the suppressed repeat count before the fresh row.
- [x] Coalescer is bounded to 4096 exact fingerprints and self-prunes.
- [x] EXEC, gate, decision, phase and snapshot logging paths are unchanged.


## V5.0.7480 — additive specialist causal-funnel lane index

Runtime evidence from 5.0.7466: causal records=8075 and every specialist report scanned records.values independently.

- [x] Canonical causal records and all DISCOVER→LEARN stages remain unchanged.
- [x] A secondary lane→record-key index is populated whenever a causal record exists.
- [x] laneSnapshot6647 and stageCounts6625 visit only records for the requested lane.
- [x] latest candidate/key/open/finalized lane lookups use the same lane index.
- [x] TTL and over-cap eviction remove records from both canonical map and secondary index.
- [x] The index is diagnostic/read acceleration only; it has no admission/execution authority.
- [x] No specialist lane, causal predecessor, outcome, or forensic acceptance check is removed.


## V5.0.7481 — bounded specialist stage telemetry idempotency

- [x] Replaced unbounded deskStageOnce6599 lifetime set with timestamped deskStageOnce7481 cache.
- [x] Duplicate stage telemetry remains suppressed for the full 30-minute causal diagnostic horizon.
- [x] Cache prunes expired keys and binds to a 48,000-key soft cap.
- [x] Underlying SpecialistCausalFunnel, execution tickets, canonical intents, same-mint authority and economic idempotency remain unchanged.
- [x] Expiry can only permit a diagnostic stage to be observed again after the causal horizon; it cannot create an economic action.
- [x] Offered/deduped stage counters remain intact.


## V5.0.7482 — additive journal historical-anomaly telemetry dedupe

- [x] Replay arithmetic remains full and unchanged on every real journal revision.
- [x] Immutable terminal residual event log/counter emits only on first observation of that event+reason.
- [x] Residual basis/count still participates in every ReplayResult; only duplicate telemetry is suppressed.
- [x] Terminal residual aggregate summary emits again whenever count or basis changes.
- [x] Skipped-economics aggregate forensic summary emits only when its by-reason content changes.
- [x] Learning quarantine and invariant failure identity remain unchanged.
- [x] No journal rows, accounting legs, failure checks or reconciliation outputs are removed.


## V5.0.7483 — additive TokenMeta report revision cache

- [x] TokenMeta completeness scan is cached by monotonic metadata revision.
- [x] Creation, warm-start hydration, pair-address first capture, decimals first capture, first interaction, prune and soft eviction invalidate it.
- [x] Price/mcap/liquidity ticks do not force an O(all rows) completeness recount.
- [x] Read hits, read misses, total writes and dirty queue size remain live every report.
- [x] Hot cache values, SQLite persistence and provider behavior are unchanged.


## V5.0.7484 — additive same-key OHLCV request coalescing

Runtime evidence from 5.0.7466: OHLCV fetches=12518, poolResolves=11237, served=0, localSkips=12396.

- [x] Identical mint/timeframe/limit requests inside the existing 2.5s provider minimum interval are coalesced before pool/provider fallback work.
- [x] Positive candle cache and confirmed-negative cache remain higher-priority and unchanged.
- [x] The first request in every provider-eligible interval still traverses DexPaprika/Gecko resolution exactly as before.
- [x] Same-key map is bounded to 8000 entries and prunes against existing cache horizons.
- [x] No OHLCV provider, timeframe, strategy consumer, pattern engine or fallback is disabled.


## V5.0.7485 — additive unsupported Pump trade-demand coalescing

Runtime evidence from 5.0.7466: PUMP_TRADE_SUBSCRIBE_SKIPPED_NO_KEY_7284 exceeded 23k while token trade stream was already AUTH_DENIED.

- [x] Unsupported token-trade subscription demand is tracked as a current mint set instead of re-counted every sync tick.
- [x] Newly wanted held mints are still counted once and remain visible in status.
- [x] Removed held mints are removed from unsupported demand immediately.
- [x] If a valid/changed key restores capability, unsupported demand clears and the normal real subscription diff resumes.
- [x] Pump create stream, migration stream, curve RPC fallback and mark fallback are unchanged.
- [x] No provider or price source is disabled.


## V5.0.7486 — additive SymbolicContext single-flight refresh

Runtime evidence from 5.0.7466: symbolic_context_refresh reached 28.7s while SymbolicContext.refresh could be entered concurrently before lastRefresh was updated.

- [x] Full 24-channel SymbolicContext refresh is single-flight.
- [x] Concurrent callers reuse the currently published symbolic snapshot and continue immediately.
- [x] Existing 2-second freshness gate remains unchanged.
- [x] The successful refresh timestamps at actual completion, not pre-refresh start.
- [x] In-flight ownership is released in finally on both success and failure.
- [x] All symbolic channels, live gates, sizing reads, mood/edge state and persistence remain enabled.


## V5.0.7487 — additive canonical active-projection revision cache

- [x] All-mode active mint projection is cached against CanonicalPositionAuthority mutationCount.
- [x] Any canonical position mutation invalidates the cached revision automatically.
- [x] Mode-local readers filter the already aggregated active rows rather than rescanning historical positions.
- [x] A mutation racing a rebuild prevents that rebuild from being cached.
- [x] Position authority, lot aggregation, quantity, cost basis, asset class and mode+mint identity semantics are unchanged.


## V5.0.7488 — bounded expired-ticket reseal tombstones

- [x] Replaced lifetime resealedTickets6613 set with timestamped resealedTickets7488.
- [x] Same attempt cannot reseal twice inside a 20-minute guard horizon.
- [x] Guard prunes expired attempt IDs and is bounded to a 12,000-key soft cap.
- [x] Ticket provenance, feedback epoch, canonical occupancy, sealed size and executable-mark checks are unchanged.
- [x] Execution-ticket / same-mint / canonical-position idempotency authorities are unchanged.
- [x] No ticket is made more executable by this change; only dead tombstone retention is reduced.


## V5.0.7489 — release finalized-consumer pending identities at terminal exclusion

- [x] exactEventPendingLogged6699 remains active during the 120s exact-proof grace window.
- [x] Once CanonicalFinalizedTradeBus marks a consumer/trade terminally EXCLUDED as unprovable, its pending-log identity is removed immediately.
- [x] Exact proof success still removes the pending identity as before.
- [x] Consumer delivery, exclusion reason, learning eligibility and canonical ACK semantics are unchanged.
- [x] No learning consumer or retry opportunity is removed; only terminally dead retry-log state is released.


## V5.0.7490 — Crypto Universe canonical identity/index convergence

- [x] Stale dynamic-token eviction now removes the evicted canonical key from symbolCandidates6493 and symbolIndex.
- [x] Remaining same-symbol candidates can become the display preference without changing execution identity.
- [x] Disk restore now stores rows under restoredKey6544 (canonical chain+token identity), not bare mint.
- [x] Symbol indexing on restore uses the same canonical key.
- [x] Held-token preservation and discovery TTLs are unchanged.
- [x] Symbol ambiguity remains fail-closed for execution; no symbol-to-mint guessing was added.
- [x] No discovery source, chain, DEX or crypto strategy is removed.


## V5.0.7491 — Crypto Universe evaluation-state retirement on identity eviction

- [x] Genuine stale-token eviction now retires evaluationInflight / inflight-start / completed state for that canonical identity.
- [x] Terminal-generation and progress-generation keys for the evicted identity are removed.
- [x] Evidence-deadline progress stamps for the evicted identity are removed.
- [x] Held crypto identities are still never evicted and therefore retain all held-position supervision state.
- [x] A token rediscovered after the 7-day stale horizon is eligible for fresh evaluation instead of inheriting an ancient completed generation.
- [x] No active evaluation, held position, discovery source or terminal learning event is removed early.


## V5.0.7492 — Crypto Universe discovery-report memo

- [x] discoveryReport6544 text generation is memoized for 5 seconds.
- [x] Registry rows, evaluation state, routeability, pricing and trading consumers remain live and unchanged.
- [x] Expensive per-report list copy, chain grouping, cohort counting and label-prefix sorting are skipped on repeated report reads inside the memo window.
- [x] Memo applies only to diagnostics/report presentation; it has zero admission or execution authority.


## V5.0.7493 — exact finalized-reconciliation revision cache

- [x] CanonicalFinalizedTradeBus exposes a monotonic revision that increments only on unique canonical publish.
- [x] FinalizedLearningReconciler diagnostic snapshot is cached by canonical-position mutation + economic-event version + unique bus revision.
- [x] Duplicate bus redispatch does not invalidate the diagnostic snapshot.
- [x] A source mutation racing snapshot construction prevents that snapshot from being cached.
- [x] repairDurableBusPublishFailures7459 remains fully live and unchanged.
- [x] No learning, repair, exclusion or publication semantics are changed.


## V5.0.7494 — exact finalized-bus parity revision cache

- [x] Finalized bus parity report now has a consumer-state revision for ACK/exclusion changes.
- [x] Parity cache keys on unique canonical publish revision + consumer parity revision.
- [x] Duplicate/no-op ACK and exclusion calls do not force a full parity rebuild.
- [x] A concurrent canonical/consumer mutation prevents a computed parity snapshot from being cached.
- [x] Delivery, retry, ACK persistence, exclusion persistence and consumer semantics are unchanged.


## V5.0.7495 — canonical position mutation-revision completeness

Audit found canonical position mutations that changed the authoritative map without advancing mutationCount7387, invalidating exact revision caches.

- [x] abortEntry6485 advances canonical mutation revision after removing a pending position.
- [x] quarantine advances revision after lifecycle/quarantine mutation.
- [x] rebuildPaperFromEvents6486 advances revision once after its atomic full paper-position reconstruction.
- [x] cancelStalePendingEntries6461 advances revision for every actual pending→quarantined mutation.
- [x] purgeZeroQtyLifecycleOpens6752 advances revision when at least one lifecycle is stamped CLOSED.
- [x] Existing open/promote/add/sell/recovery mutation bumps remain unchanged.
- [x] No economic calculation, lifecycle decision, quarantine rule, refund, replay rule or position content changed.


## V5.0.7496 — exact canonical position-view revision cache

- [x] openPositions, closedPositions and lifecycle classification share one snapshot keyed to canonical mutation revision.
- [x] A real canonical mutation invalidates the view immediately through mutationCount7387.
- [x] A mutation racing view construction prevents that view from being published for reuse.
- [x] Open-position lifecycle/quantity visibility semantics remain identical.
- [x] Closed-position membership and lifecycle sum invariant remain identical.
- [x] Existing strict valuation surface remains independent and unchanged.


## V5.0.7497 — finalized-bus canonical projection cache

- [x] canonicalPositionIds7018 and earliestCanonicalAtMs7433 share one projection keyed to unique canonical bus revision.
- [x] Duplicate bus redispatches do not invalidate the projection.
- [x] A unique canonical publish invalidates immediately via canonicalRevision7493.
- [x] A publish racing projection construction prevents that projection from being cached.
- [x] Bus publication, delivery, retry, ACK and exclusion behavior are unchanged.


## V5.0.7498 — canonical quarantine/pending read projection reuse

- [x] The 7496 canonical revision snapshot now carries pending entries and quarantined position IDs by mode.
- [x] quarantinedPositionIds6635 no longer rescans all historical positions.
- [x] pendingEntryPositions6461 reuses the exact mutation-revision snapshot.
- [x] Mode-specific quarantine identity semantics remain case-insensitive and unchanged.
- [x] Mutation rules, quarantine decisions and stale-pending cancellation remain unchanged.


## V5.0.7499 — exact durable-economic-event snapshot cache

- [x] EconomicEventSchema6464.snapshot reuses an immutable event list keyed to eventVersion.
- [x] Any appended/evicted event or replay-carry version change invalidates the cached version.
- [x] An event mutation racing list construction prevents that list from being cached.
- [x] Event retention CAP, durable keys, replay carry, arithmetic and persistence are unchanged.


## V5.0.7500 — version-cached durable terminal-sell index

- [x] EconomicEventSchema snapshot cache now derives full terminal SELLs by canonical positionId once per event version.
- [x] Finalized reconciliation consumes the cached terminal position-id set instead of refiltering the event corpus.
- [x] Durable bus repair consumes the same cached per-position SELL groups.
- [x] Racing event mutation falls back to a one-shot derived view and never caches against the wrong version.
- [x] SELL selection, latest-event choice, repair proof requirements and event persistence are unchanged.


## V5.0.7501 — exact precomputed forensic-reconcile reuse

- [x] ForensicReconciliation unchanged-state key now applies to both self-replay and caller-precomputed replay paths.
- [x] Same journal revision + canonical mutation revision + canonical cash cannot repeat the full quantity/set-diff pass.
- [x] A real journal, canonical-position, or cash mutation still triggers the complete forensic reconciliation immediately.
- [x] Replay arithmetic, tolerances, delta status and forensic failure rules are unchanged.


## V5.0.7502 — dormant token-birth hydration state retirement

- [x] Partial birth paging retains resumable progress for 24 hours.
- [x] Dormant non-inflight progress/cooldown entries are retired when support maps exceed the 8,000-key soft cap.
- [x] Inflight hydration state is never pruned.
- [x] Successful birth hydration still clears progress/cooldown immediately as before.
- [x] Rediscovered tokens after dormant retirement restart birth lookup from page one; no discovery source or route is disabled.


## V5.0.7503 — TokenMergeQueue no-change repeat coalescing

- [x] Same scanner + no stronger metrics + no new lane/tool affinity returns before confidence recompute and MERGED log emission.
- [x] total discovery count and per-entry discoveryCount still observe every callback.
- [x] New scanner evidence, new affinity, stronger mcap/liquidity/volume or better symbol still runs the full merge path immediately.
- [x] Merge-window timing, confidence formula, age priority, ranked selection and emit caps are unchanged.


## V5.0.7504 — lazy old-runtime decision snapshot retirement

- [x] ExecutionDecisionSnapshot now retires older runtime generations on reads as well as writes.
- [x] Current runtime-generation snapshots are never pruned by this path.
- [x] Mint/version/lane authority semantics are unchanged.
- [x] Runtime restart no longer requires a new decision record before old-generation memory is released.


## V5.0.7505 — bounded stale canonical TokenMap cache

- [x] canonicalResultByMint6492 gains pressure-based pruning only after 12,000 cached mints.
- [x] Only rows older than 24 hours are eligible for retirement.
- [x] Canonical held-position mints, current hydration target, and inflight hydration mints are protected.
- [x] Route TTL, route classification, executable proof and provider fallback are unchanged.
- [x] A pruned discovery mint simply rehydrates normally if rediscovered later.


## V5.0.7508 — P0 causal funnel lane-index correctness repair

Runtime evidence from 5.0.7501: records=1479 indexedLanes=0 while active lane evaluations continued and every specialist liveness row reported zero.

- [x] Fixed getOrCreateRecord7480 accidental self-recursion.
- [x] getOrCreateRecord7480 now creates the canonical record with records.computeIfAbsent and always indexes the key by canonical lane.
- [x] stamp6625 now uses getOrCreateRecord7480 instead of bypassing the index with a raw records.computeIfAbsent.
- [x] ensureAffinityLineage7464 and ordinary stage stamps therefore converge on the same indexed canonical record.
- [x] No causal stages, lane logic, entry criteria, sizing, execution authority or learning output changed.
- [x] Runtime acceptance: CAUSAL_FUNNEL indexedLanes>0 and specialist discovered/qualified/FDG/etc counters become non-zero when stages are actually stamped.


## V5.0.7509 — P0 remove nested runBlocking from held/entry Solana mark fallback

Runtime evidence from 5.0.7501: open-position tick inFlight ~365s at phase=locked_venue with stack ending in runBlocking -> BotService.tryFallbackPriceData; held=134 fresh=0 staleRefresh=72 missing=62 and riskClockStale=87153.

- [x] tryFallbackPriceData is now suspend instead of creating nested blocking coroutine bridges.
- [x] DexScreenerOracle retains the existing 2s withTimeoutOrNull deadline.
- [x] BirdeyeOracle retains the existing 2s withTimeoutOrNull deadline.
- [x] Canonical exit refresh already calls the fallback from Dispatchers.IO and remains asynchronous.
- [x] Entry hydration explicitly switches to Dispatchers.IO before running the same complete fallback chain.
- [x] Birdeye overview, DexScreener token, Birdeye Oracle and pump.fun fallback branches are all preserved.
- [x] No mark trust, stale threshold, exit threshold, provider ordering or safety rule is weakened.


## V5.0.7510 — P0 batch held Solana mark supervision

Runtime evidence from 5.0.7501: held=134 fresh=0 staleRefresh=72 missing=62, riskClockStale=87153. HeldHotMarkAuthority processed stale Solana positions one-by-one and wrapped LockedVenueMarks (internally bounded to 600ms/provider) in a shorter 450ms outer timeout.

- [x] Stale held Solana positions are resolved as one locked-venue book per pass.
- [x] Only unresolved Solana mints enter one existing ParallelMarkFanout batch.
- [x] Batch fanout has a bounded 2.5s deadline.
- [x] Per-position canonical mark publication and identity verification remain unchanged.
- [x] CRYPTO_ALT held marks retain the existing exact-identity DynamicAltTokenRegistry path.
- [x] Freshness bar, mark trust, exit thresholds, stop logic and provider set are unchanged.
- [x] No held position is removed or marked fresh unless a real canonical mark advances.


## V5.0.7511 — background split-runtime diagnostic correction

Runtime evidence from 5.0.7501: BG_SCAN_CB=0, but BG_FDG age ~4.6s and normal V3/FDG were active; intake was PumpPortal/probation/hot-warmup. The prior OR condition produced a false zombie alarm.

- [x] BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579 now requires fresh intake plus BOTH scan-callback and FDG staleness.
- [x] PumpPortal-only intake no longer implies scanner/runtime death.
- [x] A genuine split runtime with both downstream witnesses stale still alarms.
- [x] No scanner, intake, FDG, restart, trading or execution behavior changed.


## V5.0.7512 — recover finalized-bus gaps from rich durable finality without requiring duplicate entry cache

Runtime evidence from 5.0.7501: closed=1965, published=1503, missing=489 BUS_PUBLISH_FAILED; FINALIZED_BUS_REPAIR_ENTRY_SNAPSHOT_MISSING_7459=3172.

- [x] Durable rich 6450 finality is checked before EntryStrategySnapshot6450.
- [x] If rich durable finality exists, a missing entry-strategy snapshot no longer blocks 6464 projection repair.
- [x] This matches the normal CanonicalTradeFinalizedBus6450 publisher, which already publishes durable lane/tactic/economics with optional snapshot fields blank.
- [x] Typed-SELL-only reconstruction still requires EntryStrategySnapshot6450.
- [x] Missing optional exact strategy/source/regime fields remain blank, and entryScore remains neutral 0.
- [x] Existing economics/quarantine/replay/carry checks remain intact.


## V5.0.7513 — additive shadow evidence for 6663 canonical quality refusals

Runtime evidence from installed 5.0.7501:
- FDG allow=86, EXEC_GATE allow=19, PAPER BUY ok=0.
- A representative SHITCOIN passed FDG and EXEC authority, then PAPER_ENTRY_QUALITY_REJECTED_6663 rejected score=45 / liq=2847 against the legacy 66 / 3000 learning-quality floor.

Repair:
- [x] The 6663 canonical paper-capital hard block is preserved exactly.
- [x] Rejected candidates are offered to the existing always-on shadow book before canonical markPaperBuyNotOpened/return.
- [x] Shadow positions use observed prices, do not debit canonical paper capital, and close through the existing shadow learning path.
- [x] No score floor, liquidity floor, FDG decision, execution authority, live route, sizing authority or safety gate is relaxed.
- [x] Existing duplicate/no-price/full-book shadow protections remain authoritative.
- [x] New counters distinguish shadow diversion attempts from errors.


## V5.0.7514 — bounded finalized-bus repair throughput

- [x] Independent reconciliation may repair up to 32 proven bus gaps per 30-second full pass instead of 8.
- [x] Repair loop has a 2.5-second wall-clock budget and yields cleanly when exhausted.
- [x] The repair remains on IndependentReconcilerScheduler6431 Dispatchers.IO, never the bot loop.
- [x] No additional row becomes eligible: 7512 proof/quarantine/replay/economics rules are unchanged.
- [x] Faster draining reduces repeated scans over the same CLOSED positions while restoring learner population sooner.
