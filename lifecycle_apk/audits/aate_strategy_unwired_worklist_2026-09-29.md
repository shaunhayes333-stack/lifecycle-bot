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
