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


## V5.0.7515 — wire Crypto Universe discovery/reaper to canonical runtime plan

Runtime evidence from installed 5.0.7501:
- Crypto Universe identities=4774 but networks observed / DEXes observed were blank.
- CrossAsset CRYPTO_ALT started=1 scanTick=1, then no continuing discovery/candidate flow.
- evaluation inflight=1 with oldestQueueAgeMs≈356872, beyond the 5-minute adaptive ceiling.
- DynamicAltTokenRegistry.startBackgroundDiscovery() had no production caller.

Repair:
- [x] Startup uses TraderRuntimePlan6526.cryptoUniverseOn to start/stop DynamicAltTokenRegistry discovery.
- [x] Runtime settings reapply uses the same authority.
- [x] Bot shutdown stops the discovery scheduler alongside CryptoAltTrader.
- [x] startBackgroundDiscovery remains idempotent.
- [x] Existing 15s/60s/5m discovery cadence and silent-lease reaper are unchanged.
- [x] No crypto scoring, route, sizing, entry or exit threshold is changed.


## V5.0.7516 — stop re-stamping already durable cross-asset history events

Runtime evidence from 5.0.7501: CANONICAL_EVENT_STORE_DUP_COMMIT_6635=27594, with POSITION/LEDGER/TERMINAL_EXEC/FILL_LOT each ~6156.

- [x] repairCryptoHistory6659 checks TradeHistoryStore.isDurableEconomicEvent7371(eventId) before openEvent, fill-lot projection and store receipt stamping.
- [x] Durable historical events are reused instead of reconstructing the same volatile receipt chain every reconciliation cadence.
- [x] Non-durable events still run the complete repair path.
- [x] No event is skipped before durable journal proof exists.


## V5.0.7517 — wire canonical lifecycle projection authority

Runtime evidence from installed 5.0.7501: canonical open=134, registry parity=134/134, but PositionStateLedger6454 positions=0 and root cause included LIFECYCLE_PROJECTION_DIVERGED_6470.

- [x] Canonical bootstrap calls CanonicalLifecycleAuthority6470.audit() after restored inventory reconstruction.
- [x] PositionStateLedger6454 and SellQtyBoundaryClamp6427 therefore project from canonical 6441 before normal trading resumes.
- [x] The same authority runs on the existing 12-loop integrity cadence under MaintenanceWorker6448.
- [x] CanonicalPositionAuthority6441 remains the sole lifecycle truth.
- [x] No position, quantity, exit, admission, sizing or learning policy is changed.


## V5.0.7518 — canonical-open short circuit for paper ghost reconciliation

Runtime evidence from 5.0.7501: PAPER_GHOST_PURGE_REFUSED_CANONICAL_OPEN_7351=11448 while canonical inventory was internally consistent.

- [x] Canonical PAPER OPEN now short-circuits the legacy missing-BUY-row ghost heuristic.
- [x] The journal BUY-row orphan test still runs for rows not present in canonical open inventory.
- [x] Unknown canonical state remains fail-safe KEEP.
- [x] True non-canonical/no-BUY ghosts still follow the existing purge path.
- [x] No canonical position can be made purgeable by this change.
- [x] Removes repeated refusal logging and stale-journal work against known-good canonical inventory.


## V5.0.7547 — native specialist decision correctness

Audit continuation after 7542 made all native specialist opinions authoritative.

- [x] TREASURY can no longer qualify from the neutral/default TokenMeta baseline merely because TreasuryBrain starts at score 50.
- [x] TREASURY proves momentum, buy-pressure, continuation and liquid-pond evidence before its score can become an executable native hypothesis.
- [x] Exhaustion/spike state is both penalized and excluded from setup confirmation; the scalp brain no longer treats a top signal as a probe.
- [x] CYCLIC negative-expectancy / losing-pattern memory now soft-shapes the existing score floor instead of permanently tombstoning that score band.
- [x] CYCLIC retains sellability, blacklist, position, fresh-price, cold-ring, loss-streak and downstream FDG/safety authorities unchanged.
- [x] SpecialistBrainBridge cache identity now includes the momentum, volatility, holder, Treasury-meta, safety/bundle, sentiment, tool-affinity and venue evidence consumed by native brains.
- [x] A fast evidence change can therefore invalidate an authoritative native ALLOW/VETO without waiting only for cache TTL expiry.
- [x] No scanner source, specialist lane, native brain, learning path or execution safety authority was removed.


## V5.0.7548 — PAPER/LIVE canonical decision parity

- [x] PAPER probes/exploration no longer spend canonical capital.
- [x] Only PAPER_BENCHMARK decisions that pass LIVE-equivalent rules can open canonical paper positions.
- [x] Proven consensus evidence is mode-neutral.
- [x] ExecutableOpenGate accepts BUY only as economic finality.


## V5.0.7549 — early-launch evidence is risk shaping only

- [x] Smart-money early-launch evidence cannot rescue an EDGE_SKIP into funded capital.
- [x] EarlyLaunchBypass6396 is explicitly risk shaping on an already-admitted candidate.
- [x] Legacy fresh-launch scanner remains unwired instead of duplicating LaunchPhase/ModeRouter.


## V5.0.7550 — native specialist PAPER/LIVE parity

- [x] QUALITY uses the same age floor in PAPER and LIVE at the same learning maturity.
- [x] QUALITY sizing passes the actual runtime mode into TraderSizingBridge6444 instead of hardcoding paperMode=true.
- [x] CASHGEN/TREASURY uses the same $10k native liquidity pond in PAPER and LIVE.
- [x] MOONSHOT treats pending RC=1 identically in both modes and rejects confirmed RC 2..14 in both.
- [x] MOONSHOT uses one native score-floor schedule (30/38/48/60) in both modes.
- [x] DIP_HUNTER uses the same 2-hour re-entry cooldown and the same lane-loss recovery shaping in both modes.
- [x] Existing hard safety, canonical FDG, sizing authority, exit logic and execution adapters remain intact.


## V5.0.7551 — native specialist momentum unit normalization

- [x] Confirmed DataOrchestrator stores TokenState.momentum as a centered 0..100 score where 50 means flat.
- [x] Confirmed BLUECHIP, SHITCOIN, EXPRESS, MANIPULATED and CASHGEN native APIs consume momentum as a signed percentage move.
- [x] SpecialistBrainBridge no longer feeds TokenState.momentum into signed-% APIs.
- [x] Native specialist momentum now comes from lastPriceChange5m when populated, otherwise a bounded recent-history signed percentage calculation.
- [x] A flat token can no longer appear as +50% momentum to authoritative native brains.
- [x] The native-brain cache fingerprint tracks the actual signed momentum evidence used by those brains.
- [x] TokenState.momentum itself is left unchanged for score-scale consumers that legitimately expect the 0..100 representation.
- [x] Volatility semantics are unchanged in this bundle because producer and current native thresholds both use score-like 0..100 bands.


## V5.0.7552 — native specialist unknown-evidence neutrality

- [x] Unresolved holder concentration no longer defaults to 0%, which several native brains interpreted as best-in-class distribution.
- [x] SpecialistBrainBridge uses TokenState.holderDataResolved to distinguish genuinely measured holder concentration from pending data.
- [x] Pending holder concentration is represented neutrally as 20%, matching the existing V3 neutral fallback rather than adding bullish points.
- [x] Unresolved RugCheck no longer fabricates score 3.
- [x] Unresolved RugCheck maps to score 1, the codebase's documented PENDING value, so Moonshot does not misclassify missing evidence as confirmed weak safety.
- [x] Confirmed rug scores and resolved holder concentration remain untouched.
- [x] No hard safety rule, lane threshold, or execution authority is weakened.


## V5.0.7553 — canonicalize native specialist occupancy authority

- [x] QUALITY local position/cap state is telemetry only during native entry evaluation.
- [x] BLUECHIP local position/cap state is telemetry only during native entry evaluation.
- [x] SHITCOIN local position/cap state is telemetry only during native entry evaluation.
- [x] EXPRESS local ride/cap state is telemetry only during native entry evaluation.
- [x] PROJECT_SNIPER local mission/cap state is telemetry only during native entry evaluation.
- [x] DIP_HUNTER local dip/cap state is telemetry only during native entry evaluation.
- [x] MANIPULATED local position state is telemetry only during native entry evaluation.
- [x] CASHGEN local position/cap state is telemetry only during native entry evaluation.
- [x] CanonicalPositionAuthority / HeldPositionSupervisor / execution slot authorities remain the sole duplicate and capacity truth.
- [x] Local maps remain intact for exits, PnL, UI projection, partials and lifecycle bookkeeping.


## V5.0.7554 — DipHunter unresolved-holder neutrality

- [x] DipHunter holderChange24h is nullable; null means unresolved/unknown.
- [x] Unknown holder change contributes zero quality points instead of being treated as stable/growing.
- [x] Holder-exodus danger only evaluates when real holder-change evidence exists.
- [x] SpecialistBrainBridge no longer fabricates holderChange24h=0.
- [x] CryptoAltTrader no longer fabricates holderChange24h=0 for dynamic DipHunter evaluation.
- [x] Positive/negative resolved holder-change semantics are unchanged.


## V5.0.7555 — Moonshot lifecycle + Express directional fidelity

- [x] MOONSHOT local position-count and same-mint maps are telemetry only during native scoring; canonical occupancy/slot authority remains the execution truth.
- [x] MOONSHOT phase scoring consumes the canonical LaunchPhaseAuthority7401 vocabulary: PRE_IGNITION, IGNITION, EXPANDING, POST_PUMP_FADE, MATURE_OR_UNKNOWN, METADATA_HYDRATING.
- [x] PRE_IGNITION/IGNITION receive the strongest lifecycle conviction; EXPANDING is weaker continuation; POST_PUMP_FADE is explicitly negative; unknown/hydrating receive zero rather than free bullish points.
- [x] EXPRESS native momentum receives TokenState.lastPriceChange1h, with recent-history signed-percent fallback when the 1h feed is unavailable.
- [x] EXPRESS keeps lastPriceChange5m as the separate acute continuation/chase signal, so one 5m print no longer earns both momentum and price-change score buckets.
- [x] Other native lanes keep the V5.0.7551 signed momentum input; this bundle changes only EXPRESS where the API explicitly has two directional horizons.
- [x] No score thresholds, safety gates, position sizing caps, or execution authorities were tuned in this repair.


## V5.0.7556 — native ShitCoin social evidence wiring

- [x] Authoritative SHITCOIN native evaluation now receives cached website, Twitter and Telegram presence from BirdeyeMetaDataProvider.peekCached().
- [x] The bridge adds no network/provider I/O; it consumes only the pre-existing metadata cache.
- [x] GitHub presence remains false because the canonical metadata source does not expose a GitHub field; Discord is not misrepresented as GitHub.
- [x] Native SHITCOIN's existing 0–15 social feature is therefore live again instead of every authoritative call silently receiving all-false presence flags.
- [x] Social metadata presence bits are included in SpecialistBrainBridge cache identity so newly hydrated metadata invalidates a stale opinion immediately.
- [x] Raw social URLs are not stored in the fingerprint or telemetry.
- [x] Existing sentiment, DEX visibility, graduation, bundle and hard-safety semantics remain unchanged.


## V5.0.7560 — exit-intelligence ledger truth + duplicate-authority guard

This bundle continues the original-113 audit by reconciling C_EXIT/A_PREDICT false positives against the current canonical held-position stack. It intentionally does **not** add another exit manager beside HoldingLogicLayer / AdvancedExitManager / Executor sell authority.

- [x] `HoldingLogicLayer.getHoldParams` — **CLOSED_LOOP / LEDGER_STALE (7455)**. `evaluatePosition` reads it as the single mode-parameter surface.
- [x] `LiveStrategyTuner.tpMultiplier` — **CLOSED_LOOP / LEDGER_STALE (7455)**. TP, hold and partial multipliers are consumed through one cached `LiveStrategyTuner.adjustment(mode)` in canonical held management.
- [x] `FluidLearningAI.getMarketsUncappedTpPct` — **CLOSED_LOOP / LEDGER_STALE**. Forex, Metals and Commodities consume it to preserve a stronger signal-derived TP instead of capping to the fluid default.
- [x] `ExitManager.shouldPartialSell` — **LEGACY_DEAD / DO_NOT_WIRE**. `ExitManager` is explicitly retired and has no production authority; canonical partial/full exits already route through held management and Executor sell authority.
- [x] `TrailingStopManager.getRecommendedStopType` — **LEGACY_DEAD_COMPANION / DO_NOT_WIRE**. The only production-looking adaptive-stop call remains inside retired `ExitManager`; canonical held management already owns hard stop, trailing, time and AEM decisions. Wiring this getter would create a second stop classifier.
- [x] `ExitIntelligence.getLearnedMaxHoldMinutes` — **REPORT_ONLY**. Current production use is operator status/telemetry; learned hold policy that can actuate positions is already represented through `LiveStrategyTuner` + FluidLearning hold clocks in `HoldingLogicLayer`.
- [x] `FluidLearningAI.getLayerHoldParams` — **ALIAS_WRAPPER**. Canonical held management consumes the constituent min/max/urgency getters directly; adding the aggregate wrapper would create two read surfaces for the same policy.
- [x] `FluidLearningAI.getMarketsTakeProfitPct` — **ALIAS_REDUNDANT**. Markets production paths use the explicit spot/leveraged TP getters; the generic getter is not a missing strategy.
- [x] `FluidLearning.getSimulatedPeak` — **REPORT/STATE ACCESSOR**. It exposes account-simulation state, not an independent exit decision primitive.
- [x] `ShadowLearningEngine.getPerformanceByMode` — **REPORT/ANALYTICS API**. It aggregates completed shadow outcomes and must not become a synchronous held-position gate.
- [x] `SmartExitOptimizer.getExitPressure` — **ALIAS_REDUNDANT_FOR_MEME_HOLD** remains closed as classified in 7455. It re-runs symbolic exit assessment and is deliberately absent from the meme held hot path.
- [x] Regression coverage: `Aate7560ExitUnwiredLedgerTruthTest` proves the canonical held stack is present, duplicate legacy classifiers stay out, and the stale-ledger markets TP accessor is genuinely called in production.


## V5.0.7561 — predictive sidecar/background contract bundle

This bundle continues the original-113 A_PREDICT audit. These accessors are useful intelligence surfaces, but their implementation shape makes them unsuitable for synchronous scanner / V3 / FDG use. The repair is classification + regression fencing so they are prefetched, cached, sidecar/report-only, or deliberately left local rather than accidentally becoming hot-path I/O.

- [x] `CoinGeckoTrending.getSolanaEcosystemMomentum` — **BACKGROUND_CACHE_REQUIRED**. It calls `getTrending()`, which refreshes CoinGecko over HTTP when cache TTL expires. Never call synchronously from scanner/V3/FDG.
- [x] `CollectiveLearning.getNetworkBoostForMint` — **BACKGROUND_CACHE_REQUIRED**. It is `suspend` and explicitly switches to `Dispatchers.IO`; hive/database evidence must be prefetched before admission.
- [x] `CorrelationScanner.getActionableSignals` — **PERPS_SIDECAR / BACKGROUND_SCAN**. It is suspend and invokes a full correlation scan; current runtime classification already treats CorrelationScanner as a perps sidecar/report surface.
- [x] `DataOrchestrator.scoreSentimentWithLlm` — **BACKGROUND_LLM_SIDECAR**. It invokes LLM sentiment and must never become a blocking prerequisite for scanner/FDG.
- [x] `EducationSubLayerAI.getEdgeLedger` — **BACKGROUND_REPORT / STRATEGY_RESEARCH**. It ranks aggregate learned reason statistics; useful for periodic strategy selection, not per-candidate admission.
- [x] `ShadowLearningEngine.getPerformanceByConfidence` — **REPORT/ANALYTICS**. It aggregates completed shadow outcomes; it is not an independent predictive veto/allow authority.
- [x] `TursoClient.getMarketsAssetRankings` — **BACKGROUND_DB_REQUIRED**. It is suspend and executes a remote SQL ranking query; never await it in a trading hot path.
- [x] `InsiderTrackerAI.getSignalsByWallet` — **LOCAL_QUERY_HELPER**. It filters an already-cached signal map by wallet; the actionable insider/copy routes are separate production surfaces, so this accessor is not itself a missing strategy.
- [x] `QualityTraderAI.getRecommendedLeverage` — **ASSET/LEVERAGE_HELPER**. It reads learned leverage preference state; it is not meme entry intelligence and should be audited with leveraged/perps execution rather than wired into meme FDG.
- [x] No scanner source, V3 score, FDG threshold, safety rule, sizing rule or execution authority is changed in this bundle.
- [x] Regression coverage: `Aate7561PredictiveSidecarContractTest` proves network/DB/suspend sidecars stay out of the canonical meme hot path.


## V5.0.7562 — B_RISK ledger truth + hot-path safety fencing

This bundle reconciles the original-113 B_RISK entries against current source. It does not add new hot-path provider/LLM/database dependencies and does not weaken deterministic safety.

- [x] `CanonicalEconomicIdentity6470.breachCount` — **TEST/DIAGNOSTIC COUNTER**. It reports invariant breaches; it is not an independent admission authority.
- [x] `EvidenceEpochFilter6388.canPassForensicRegressionGuard` — **FORENSIC_ACCEPTANCE_HELPER / TEST-ONLY BY DESIGN**. It validates export/recovery evidence completeness and must not become a trade gate.
- [x] `ExitCoordinatorHeartbeat.duplicateSweepsSuppressedCount` — **CLOSED_LOOP REPORT COUNTER**. `HealthSnapshot6324` already consumes it.
- [x] `ExternalAlphaFeeds.enrichSafety` — **OPTIONAL BACKGROUND SIDECAR / DO_NOT HOTPATH WIRE**. It performs synchronous DexScreener + RugCheck HTTP calls and its own source comment explicitly requires off-hot-path use. Deterministic canonical safety remains elsewhere.
- [x] `GeminiCopilot.assessRisk` — **LLM SIDECAR / DO_NOT HOTPATH WIRE**. No production caller; it can invoke external model providers and must not become a blocking prerequisite for deterministic safety/FDG.
- [x] `MemeExecutionRouteStack.stackExhausted` — **TELEMETRY WRAPPER**. It only emits the existing `EXEC_STACK_EXHAUSTED` lifecycle event; it is not missing execution/risk logic.
- [x] `ReentryGuard.manualBlock` — **MANUAL/OPERATOR HOOK**. It wraps the existing re-entry lockout mechanism for explicit human/scam actions; not a predictive risk primitive.
- [x] `SameMintCandidateEpoch6402.totalSuppressed` — **TEST/REPORT COUNTER**. The suppression mechanism itself is separate; this accessor only exposes count state.
- [x] `SellOnlySafeMode.blockedBuyCount` — **REPORT COUNTER**. Buy blocking authority lives in SellOnlySafeMode's active/update/check paths, not this getter.
- [x] `SoftScoreShaping6400.recordMechanicalMinBlock` — **OBSERVABILITY HELPER**. It records a distinct mechanical-minimum event and is not a score-floor/risk authority.
- [x] `TradeDatabase.getSuppressionStrength` — **BACKGROUND DB CACHE REQUIRED** if promoted. It executes a SQLite query per feature key and must not be called synchronously from scanner/V3/FDG.
- [x] `TradeLifecycle.forceExpireBlocked` — **DEBUG/TEST-ONLY BY DESIGN**. Source explicitly describes it as testing/debugging; production retry uses normal lifecycle expiry/reset paths.
- [x] Regression coverage: `Aate7562RiskLedgerTruthTest` locks these classifications and forbids synchronous ExternalAlpha/Gemini/TradeDatabase B_RISK calls from the canonical meme hot path.


## V5.0.7563 — C_EXIT helper/report ledger cleanup

This bundle removes another false-positive cluster from the original-113 C_EXIT list. Every item below is a snapshot/getter/analytics/helper surface; none is an independent sell authority.

- [x] `HistoricalChartScanner.getProgress` — **DIAGNOSTIC COUNTER ACCESSOR**. Returns scan progress only.
- [x] `GlobalTradeRegistry.getProbationStats` — **REPORT STRING**. Summarizes probation occupancy/promotions/rejections; does not gate exits.
- [x] `GovernorRecovery6388.lastPromotionReason` — **REPORT ACCESSOR**. Exposes the reason already written by the governor state machine.
- [x] `PerpsMarketDataFetcher.getPriceSource` — **PROVENANCE GETTER**. Reads cached source attribution; not an exit verdict.
- [x] `PriceAggregator.getPrunedSymbols` — **DIAGNOSTIC SNAPSHOT**. Returns active provider-symbol cooldowns for UI/diagnostics.
- [x] `QuantMetrics.calculateProfitFactor` — **ANALYTICS API**. Computes historical performance under lock; not a held-position decision primitive.
- [x] `TacticBleedPivot.getLastPivot` — **STATE/REPORT ACCESSOR**. Returns the last recorded pivot decision; the pivot engine's active decision path is separate.
- [x] `TreasuryOpportunityEngine.getPendingOpportunities` — **QUEUE SNAPSHOT**. Returns sorted pending opportunities; not a sell/exit actuator.
- [x] `SolanaWallet.getPublicKeyOnly` — **SECURE IDENTITY GETTER**. Returns public key text only; not exit intelligence.
- [x] `EducationSubLayerAI.getCurriculumHoldStats` — **DIAGNOSTIC LEARNING SNAPSHOT**. Source explicitly describes it as a diagnostic hold-bucket snapshot.
- [x] No sell threshold, stop, partial ladder, hold clock, route, finality or execution authority changes in this bundle.
- [x] Regression coverage: `Aate7563ExitHelperLedgerTruthTest`.


## V5.0.7564 — C_EXIT recovery/compatibility authority classification

This bundle continues the C_EXIT audit by separating recovery/test/compatibility surfaces from production sell authority. No new seller, stop engine or finality path is introduced.

- [x] `EvidenceEpochFilter6388.buildFullExitPlan` — **RECOVERY_SUBSTRATE / TESTED HELPER**. It constructs a full-exit chunk plan with one exitIntentId; current evidence shows direct test coverage rather than a missing production seller.
- [x] `EvidenceEpochFilter6388.p1FaultLiveReconcilerMissingWithHoldings` — **RECOVERY_INVARIANT HELPER**. It detects a missing live reconciler under holdings; not an exit actuator.
- [x] `EvidenceEpochFilter6388.requiresFullExit` — **RECOVERY CLASSIFIER / TESTED HELPER**. It classifies hard-stop reasons that require full liquidation; not a second sell path.
- [x] `LiveExecutionGate.sellCompleted` — **FORWARD-COMPAT NO-OP**. Source explicitly states pending counts are tracker-derived.
- [x] `LiveExecutionGate.trySell` — **LEGACY/COMPATIBILITY GATE**. It enforces pending-sell concurrency but is not the canonical sell executor; do not promote it into a parallel sell authority.
- [x] `LiveExitOnlyMode6387.classifyForStop` — **PRICE-INTEGRITY CLASSIFIER / TESTED HELPER**. It maps zero/stale prices to observation/refresh states and prevents fake -100% exits; it does not dispatch sells.
- [x] `PortfolioInvariants6405.verifyWalletParity` — **INVARIANT TEST/DIAGNOSTIC HELPER**. Existing use is acceptance testing; not a runtime exit decision primitive.
- [x] `SellIntentQuantityAuthority6401.validateSellIntentFromUi` — **UI/BOUNDARY VALIDATION HELPER**. Existing evidence is unit-test coverage for quantity/decimals; not a missing autonomous exit strategy.
- [x] `TerminalFinalityAuthority6405.allowExit` — **TERMINAL-IDEMPOTENCY HELPER / TESTED AUTHORITY**. It guards duplicate terminal exits; current evidence is explicit acceptance tests, not an unwired strategy.
- [x] `PositionIdentity6395.activeExitIntent` — **STATE ACCESSOR**. Reads the currently active exit-intent id; mutation/open/close authority lives in the same position-identity subsystem.
- [x] No exit thresholds, partial fractions, finality rules, quantity clamps or sell dispatch behavior changed.
- [x] Regression coverage: `Aate7564ExitRecoveryAuthorityClassificationTest`.


## V5.0.7565 — C_EXIT stale-ledger + classifier/idempotency cleanup

This bundle closes another C_EXIT cluster by distinguishing already-wired production hooks from helper/counter/classifier surfaces.

- [x] `PositionStateLedger6454.onPartial` — **CLOSED_LOOP / LEDGER_STALE (7457)**. CanonicalPositionAuthority6441.partialSell() invokes it after PARTIALLY_CLOSED commits.
- [x] `GlobalCapitalArbitration6617.recordSpecialistProposal6617` — **CLOSED_LOOP / LEDGER_STALE (7458)**. CanonicalSizingBridge6532 records SOLANA specialist proposals by mint+lane before canonical entry sealing.
- [x] `ExecutionCounterContract.recordJournalSellWrite` — **COUNTER/OBSERVABILITY HELPER**. It increments journal-sell parity telemetry; not a sell decision.
- [x] `FinalizedSellProof6386.classifyPartial` — **FINALITY CLASSIFIER / TESTED HELPER**. It maps partial-proof state to pending/finalized classification; it does not execute the sell.
- [x] `IdempotencyKeyStore6437.sellKey` — **IDEMPOTENCY KEY HELPER**. Generates canonical SELL keys; not an exit strategy or actuator.
- [x] `LearnerRuntimeBudgetGuard6441.shouldStop` — **MAINTENANCE BUDGET HELPER**. Stops learner maintenance slices when their wall-clock budget is hit; unrelated to position stop-loss/exit.
- [x] `LiveStrategyTuner.tpMultiplier` — **ALIAS / LEDGER_STALE (7455)**. Canonical held management consumes one cached `LiveStrategyTuner.adjustment(mode)` object, including tp/hold/partial multipliers.
- [x] No sell dispatch, quantity, finality, partial, stop or learning thresholds changed.
- [x] Regression coverage: `Aate7565ExitStaleLedgerAndHelperTruthTest`.


## V5.0.7566 — C_EXIT perps/market helper + operator-control classification

This bundle reconciles the remaining perps/market helper cluster without creating duplicate exit authority.

- [x] `PerpsAdvancedAI.shouldPartialExit` — **PERPS HELPER / DECLARATION-ONLY**. It computes a partial-exit percentage from an existing plan; current source has no production caller. Do not wire it into meme held management.
- [x] `PerpsTrailingStop.getTrailStop` — **STATE ACCESSOR**. Reads the already-computed trailing-stop state for a position.
- [x] `JupiterPerps.getPoolInfo` — **BACKGROUND NETWORK HELPER**. It is suspend, runs on Dispatchers.IO and performs a Jupiter perps HTTP request; never await it from scanner/FDG/held hot paths.
- [x] `ForexStrategy.tpSlPrices` — **PURE STRATEGY HELPER / DECLARATION-ONLY**. Computes direction-aware TP/SL levels from a setup; current source has no external caller.
- [x] `RouteValidator.validateFinalOutput` — **POST-EXECUTION VALIDATOR / DECLARATION-ONLY**. Validates terminal route output; not an exit strategy or seller.
- [x] `RuntimeRepairState.requestPaperMode` — **OPERATOR-AUTHORITY NO-OP**. Source explicitly logs the request as ignored because mode authority is operator-controlled.
- [x] `ScannerHeatPublisher6398.currentPct01` — **STATE/TELEMETRY ACCESSOR**. Computes current scanner heat from its timestamp window; not an exit decision.
- [x] `ToxicModeCircuitBreaker.activateEmergencyStop` / `deactivateEmergencyStop` — **EXPLICIT OPERATOR/EMERGENCY CONTROLS**. These are manual global entry controls, not adaptive exit intelligence.
- [x] `SmartExitOptimizer.getExitPressure` remains **ALIAS_REDUNDANT_FOR_MEME_HOLD** from 7455/7560 and stays out of canonical meme held management.
- [x] No entry/exit threshold, TP/SL, route, partial, finality or operator-authority behavior changed.
- [x] Regression coverage: `Aate7566PerpsExitHelperContractTest`.


## V5.0.7567 — final C_EXIT tail classification

This bundle closes the remaining C_EXIT entries from the original-113 ledger. None of these rows justifies a new canonical sell authority.

- [x] `BirdeyeApi.getHolderDistribution` — **NETWORK DATA HELPER / BACKGROUND ONLY**. It performs Birdeye HTTP I/O and should be prefetched/cached, not called synchronously from held/exit hot paths.
- [x] `BotBrain.resetThresholds` — **EXPLICIT LEARNING RESET CONTROL**. Source documents it as a manual recovery action for poisoned learned thresholds; not adaptive exit intelligence.
- [x] `BotRuntimeController.runtimeJobActiveButUiStopped` — **TEST/REGRESSION HELPER**. Existing evidence is RuntimePipelineGatesTest; it detects UI/runtime divergence, not position exit conditions.
- [x] `CanonicalPositionAuthority6441.activeMintProjections6489` — **COMPATIBILITY ALIAS**. Delegates directly to `activeMintProjections6490()`.
- [x] `CounterParityLedger6399.recordSellExecutorInvocation` — **COUNTER/OBSERVABILITY HOOK**. Tracks sell-executor parity only.
- [x] `EarlyEntryScout6390.trackedPeakCount6948` — **REPORT/LEAK-CANARY ACCESSOR**. Source explicitly calls it a leak canary and OperatorAuxiliaryStatusDigest already consumes it.
- [x] `FluidLearningAI.getBreakoutThreshold` — **THRESHOLD ACCESSOR / NOT EXIT AUTHORITY**. Returns the fluid breakout threshold used by strategy/scanner logic; its C_EXIT ledger placement is taxonomy noise.
- [x] `TelegramNotifier.partialMsg` — **FORMATTER**. Produces notification text only.
- [x] `TradeIdentityManager.auditTrail` — **REPORT FORMATTER**. Renders trade identity history and has no mutation/execution authority.
- [x] With 7560–7567, every C_EXIT row has now been either proven closed-loop, explicitly classified as helper/report/test/background/alias, or fenced from duplicate sell authority.
- [x] Regression coverage: `Aate7567FinalCExitTailClassificationTest`.


## V5.0.7568 — A_PREDICT stale-ledger + calibration/helper cleanup

This bundle continues the A_PREDICT audit without changing score floors or adding duplicate prediction authority.

- [x] `ExplorationBudget.allowShadowSignal` — **CLOSED_LOOP / LEDGER_STALE (7537)**. Executor consumes it for paper exploration shadow budgeting.
- [x] `ExecutableEntryAuthority6450.scoreFloorDelta6487` — **INTENTIONALLY NEUTRALIZED COMPAT ACCESSOR**. Current implementation returns 0; entry authority shapes via canonical gate/size state rather than hidden floor inflation.
- [x] `FluidLearning.getExitTagWinRate` — **REPORT/LEARNING TABLE ACCESSOR**. The regime-tag table is observable, but current design deliberately avoids making it a second live exit authority.
- [x] `FluidLearningAI.getHeuristicSignal` — **DECLARATION-ONLY FALLBACK HELPER**. No production caller; do not stack it blindly on top of UnifiedScorer/V3/FDG.
- [x] `ForensicEventEnvelope6430.setLedgerEpoch` — **STATE/FORENSIC SETTER**. Not predictive alpha.
- [x] `PerpsTradeHeatmap.getAIRecommendation` — **REPORT/RECOMMENDATION STRING**. Aggregates heatmap best setup/time for display/advice, not canonical entry authority.
- [x] `PerpsTraderAI.getLifetimeWinRatePct` — **PERFORMANCE METRIC ACCESSOR**. Historical WR reporting, not a candidate signal.
- [x] `ScoreDistributionHistogram6396.recommendAdaptiveBaseline` — **THRESHOLD CALIBRATION HELPER / TESTED**. It recommends a bounded baseline from score distribution after minimum samples; route only through explicit threshold authority, never per-candidate oracle voting.
- [x] `SmartExitOptimizer.getMinConfidenceAdvisory` — **ADVISORY HELPER**. Returns a confidence suggestion from accuracy; not a canonical prediction or safety gate.
- [x] No score floor, V3 score, FDG threshold, sizing, exploration capital or execution behavior changed.
- [x] Regression coverage: `Aate7568PredictiveHelperLedgerTruthTest`.


## V5.0.7569 — original-113 A_PREDICT perps/markets block (items 20–30)

This bundle reconciles original-113 A_PREDICT items 20–30 as one family. These are perps/markets learning, model, notification, or leverage helpers; none belongs in the canonical meme entry hot path by default.

- [x] `PerpsAdvancedAI.getHourlyWinRate` — **PERPS PERFORMANCE ACCESSOR**. Returns learned hourly WR; not a candidate-level meme predictor.
- [x] `PerpsAutoReplayLearner.getLosingPatterns` — **PERPS LEARNING SNAPSHOT**. Returns cached losing-pattern rows for analysis/strategy adaptation.
- [x] `PerpsAutoReplayLearner.getWinningPatterns` — **PERPS LEARNING SNAPSHOT**. Returns cached winning-pattern rows.
- [x] `PerpsDirection.getSignalStrength` — **MODEL PRESENTATION HELPER**. Maps already-computed score to STRONG/MODERATE/WEAK/NONE.
- [x] `PerpsDirection.isHighConfidence` — **MODEL CONVENIENCE HELPER**. Boolean wrapper over confidence>=80; not distinct alpha.
- [x] `PerpsLearningBridge.getStockLayerRecommendations` — **STOCK/PERPS LEARNING HELPER**. Reads stock-lane layer recommendations; audit with tokenized-stock/perps execution, not meme FDG.
- [x] `PerpsNotificationManager.notifyPatternDiscovered` — **NOTIFICATION SIDE EFFECT**. Does not change trading authority.
- [x] `PerpsNotificationManager.notifyStrongSignal` — **NOTIFICATION SIDE EFFECT**. Does not change trading authority.
- [x] `PerpsTradeHeatmap.getAIRecommendation` — **REPORT/RECOMMENDATION STRING**. Already classified in 7568; included here to close original-113 item 28.
- [x] `PerpsTraderAI.getLifetimeWinRatePct` — **PERFORMANCE METRIC ACCESSOR**. Already classified in 7568; included here to close original-113 item 29.
- [x] `QualityTraderAI.getRecommendedLeverage` — **ASSET/LEVERAGE HELPER**. Reads learned leverage preference; not meme entry intelligence.
- [x] Original-113 A_PREDICT items 20–30 are now classified/closed as a contiguous block.
- [x] Regression coverage: `Aate7569Original113PerpsPredictiveBlockTest`.


## V5.0.7570 — original-113 A_PREDICT tail (items 31–47)

This bundle closes original-113 A_PREDICT items 31–47 as a contiguous block using current production-source evidence.

- [x] `QuantMetrics.calculateWinRateStats` — **REPORT/ANALYTICS API**.
- [x] `RuntimeTune6833.isHighEdge` — **CLOSED_LOOP / LEDGER_STALE** via InventoryPressureGovernor6829 / order-size admission.
- [x] `ScoreComponent.sourceScore` — **ALIAS_REDUNDANT**; production scoring uses richer timing-aware source evidence and duplicate voting is forbidden.
- [x] `ScoreDistributionHistogram6396.recommendAdaptiveBaseline` — **THRESHOLD CALIBRATION HELPER / TESTED**, not per-candidate alpha.
- [x] `ShadowLearningEngine.getPerformanceByConfidence` — **REPORT/ANALYTICS**.
- [x] `SmartExitOptimizer.getMinConfidenceAdvisory` — **ADVISORY HELPER**.
- [x] `SourceTimingRegistry.isLateSignal` — **ALIAS_REDUNDANT**; ScoreCard already consumes `getSourceTimingPenalty`, with regression tests proving single-vote timing.
- [x] `SymbolicContext.getAllSignals` — **UTILITY_ALIAS**; individual/composite symbolic signals already have consumers.
- [x] `TacticSwitcher.posteriorLossProbAboveForTest` — **TEST_ONLY_BY_DESIGN** and used by TacticSwitcherBayesTest.
- [x] `TradeDatabase.getSignalWinRate` — **BACKGROUND_DB_REQUIRED**. It executes SQLite rawQuery and must be cached/off-hot-path before any future use.
- [x] `TradeLessonRecorder.getWinRateForLane` — **INTERNAL_ANALYTICS_HELPER**.
- [x] `TradeLifecycle.noSignal` — **LIFECYCLE STATE HELPER**, not predictive alpha; clears proposal tracking after strategy returns no BUY.
- [x] `TradingCopilot.convictionBoost` — **CLOSED_LOOP (7430/7432)** through PredictiveEntryOracle6915 bounded evidence.
- [x] `TradingMemory.getPatternWinRate` — **CLOSED_LOOP (7427)** through PredictiveEntryOracle6915 exact-context evidence.
- [x] `TrailingStopManager.getRecommendedStopType` — **LEGACY_DEAD_COMPANION / DO_NOT WIRE**, already fenced in 7560.
- [x] `TursoClient.getMarketsAssetRankings` — **BACKGROUND_DB_REQUIRED**, already fenced in 7561.
- [x] `UnifiedPolicyHead.brierScore` — **CLOSED_LOOP / LEDGER_STALE**; FinalDecisionGate reads it for authoritative lane-head diagnostics/calibration.
- [x] Original-113 A_PREDICT items 31–47 are now classified/closed.
- [x] Regression coverage: `Aate7570Original113PredictiveTailTest`.


## V5.0.7571 — original-113 A_PREDICT front (items 1–19) + 113 reconciliation complete

This bundle closes original-113 A_PREDICT items 1–19 against current source and completes classification of all 113 high-value ledger rows.

- [x] `BlueChipTraderAI.getStockTrustScore` — **ASSET-SPECIFIC STOCK HELPER**. Learned stock trust belongs to tokenized-stock/perps policy, not meme admission.
- [x] `BotBrain.getBlendedWinRate` — **UNWIRED INSTANCE API / DESIGN-REVIEW CLOSED**. BotBrain is instantiated; no canonical singleton exists. Creating another instance just to consume this getter would fork brain state.
- [x] `CoinGeckoTrending.getSolanaEcosystemMomentum` — **BACKGROUND_CACHE_REQUIRED**. It can refresh CoinGecko over HTTP.
- [x] `CollectiveLearning.getNetworkBoostForMint` — **BACKGROUND_CACHE_REQUIRED**. Suspend + Dispatchers.IO / remote-hive work.
- [x] `CorrelationScanner.getActionableSignals` — **PERPS_SIDECAR / BACKGROUND_SCAN**. Suspend full correlation scan.
- [x] `CrossAssetLeadLagAI.getRotationProbability` — **ALIAS/OVERLAP FOR MEME ENTRY**. Cross-asset lead/lag already reaches canonical symbolic/crosstalk surfaces; do not add a second correlated oracle vote.
- [x] `DataOrchestrator.scoreSentimentWithLlm` — **BACKGROUND_LLM_SIDECAR**. Never blocking scanner/V3/FDG.
- [x] `EdgeOptimizer.calculateWeightedScores` — **LEGACY PARALLEL SCORE STACK / DO NOT LAYER**. A second entry/exit weighting authority beside V3/UnifiedScorer/FDG would double-score the same evidence.
- [x] `EducationSubLayerAI.getEdgeLedger` — **BACKGROUND_REPORT / STRATEGY_RESEARCH**. Aggregate reason-performance ledger; no candidate-keyed synchronous gate.
- [x] `ExecutableEntryAuthority6450.scoreFloorDelta6487` — **INTENTIONALLY NEUTRALIZED COMPAT ACCESSOR**; returns 0.
- [x] `ExplorationBudget.allowShadowSignal` — **CLOSED_LOOP / LEDGER_STALE (7537)** in Executor paper-exploration routing.
- [x] `FluidLearning.getExitTagWinRate` — **REPORT/LEARNING TABLE ACCESSOR**, deliberately not a second exit authority.
- [x] `FluidLearningAI.getHeuristicSignal` — **DECLARATION-ONLY FALLBACK HELPER**; do not stack beside canonical V3/Unified scoring without authority consolidation.
- [x] `ForensicEventEnvelope6430.setLedgerEpoch` — **FORENSIC STATE SETTER**, not prediction.
- [x] `HistoricalChartScanner.getBestModeForConditions` — **ALIAS_COARSE_WRAPPER** over richer `getHistoricalRecommendation`, which is already consumed by PredictiveEntryOracle.
- [x] `InsiderTrackerAI.getSignalsByWallet` — **LOCAL QUERY HELPER** over cached signals; actionable smart-money/copy paths are separate closed-loop producers.
- [x] `MomentumPredictorAI.getStrongMomentumTokens` — **CLOSED_LOOP / LEDGER_STALE** via LaneHunter7297.
- [x] `OrthogonalSignals.calculateAgePatternScore` — **CLOSED_LOOP (7430)** through PredictiveEntryOracle6915 with bounded contribution and regression coverage.
- [x] `PatternBacktester.getConfidenceAdjustments` — **LAB_BACKGROUND**. Consumes completed backtest reports; do not run synchronous backtests/adaptation in admission.
- [x] Original-113 A_PREDICT items 1–19 are now classified/closed.
- [x] **ORIGINAL 113 COMPLETE:** A_PREDICT 47/47, B_RISK 12/12, C_EXIT 54/54 have each been source-reconciled as CLOSED_LOOP, LEDGER_STALE, ALIAS_REDUNDANT, BACKGROUND/SIDECAR, REPORT/TEST/HELPER, OPERATOR CONTROL, LEGACY/DO_NOT-WIRE, or genuine design-review surface. No row remains an unexplained “unwired function”.
- [x] Regression coverage: `Aate7571Original113FrontAndCompletionTest`.


## V5.0.7572 — wider-ledger D_DISPLAY demotion (151 rows)

After completing the original 113, the remaining UNWIRED ledger was re-counted by tier. The largest non-dead false-positive family is `D_DISPLAY`: **151 rows**.

Classification rule for this bundle:

- [x] All 151 `D_DISPLAY` rows are **DISPLAY/REPORT/DIAGNOSTIC SURFACES**, not missing predictive/risk/exit authority merely because no external production caller exists.
- [x] These rows include UI builders, report formatters, status snapshots, diagnostic exporters, display-only summaries, and read-only health cards.
- [x] A D_DISPLAY function may still be useful product surface, but it is **not counted as an unwired trading primitive** unless a separate source audit proves it was intended to change entry, sizing, hold, exit, safety or finality.
- [x] Do not wire D_DISPLAY functions into scanner/V3/FDG/Executor/held-management merely to reduce a static unused-function count.
- [x] D_DISPLAY remains eligible for UI/product cleanup, but is removed from the strategy-correctness backlog.
- [x] Wider-ledger reconciliation state after this bundle: original 113 complete + D_DISPLAY 151/151 classified.
- [x] Regression coverage: `Aate7572DisplayTierClassificationTest`.


## V5.0.7573 — wider-ledger E_INFILE local-helper classification (100 rows)

The current UNWIRED ledger contains **100 `E_INFILE` rows**. By construction, these functions have references inside their own implementation file but no external-file caller.

- [x] All 100 `E_INFILE` rows are classified **LOCAL_IMPLEMENTATION_HELPER / NOT EXTERNALLY UNWIRED**.
- [x] Same-file use is a valid production consumer for private/local orchestration, reducers, validation helpers, formatting, canonical substeps, and internal state transitions.
- [x] An E_INFILE row is not promoted to a cross-module API merely to satisfy static unused-function heuristics.
- [x] If an E_INFILE helper belongs to a broken subsystem, that subsystem is repaired through its actual public/canonical entry point rather than by manufacturing an external caller for the helper.
- [x] Representative owners include BotService, FinalDecisionGate, Executor/MainActivity helper surfaces, and ScannerLearning internals; their local call graph remains authoritative.
- [x] E_INFILE is removed from the strategy-correctness “unwired” count and retained only as local-callgraph audit metadata.
- [x] Wider-ledger reconciliation state: original 113 complete + D_DISPLAY 151/151 + E_INFILE 100/100 classified.
- [x] Regression coverage: `Aate7573InFileHelperClassificationTest`.


## V5.0.7574 — wider-ledger pre-triaged disposition tiers (182 rows)

The current UNWIRED ledger contains **182 rows** outside A/B/C, D_DISPLAY, E_INFILE and F_DEAD whose tier name already records an audited disposition.

These rows are now removed from the unresolved strategy-correctness backlog as follows:

- [x] `INTERNAL_VERIFIED_7079` (4) + `INTERNAL_VERIFIED_7094` (80) — **verified internal consumers/behaviour**.
- [x] `LOCAL_FUN_7081` (5) — **local implementation functions**, not missing external APIs.
- [x] `REDUNDANT_*` tiers (17 total) — **verified duplicate/advisory/relay/TTL surfaces**; do not re-wire duplicates.
- [x] `RETIRED_*` tiers (66 total) — **intentionally dark/disabled/duplicate/superseded/doc-marker/new-veto surfaces**; retirement is the resolution.
- [x] Operator/product markers: `CONSTRAINED_OPERATOR_RULE_7095` (1), `HUMAN_OVERRIDE_BY_DESIGN_7095` (1), `NEEDS_PRODUCT_DECISION_7095` (1), `COSMETIC_7095` (1) — not silent missing strategy wiring.
- [x] Starvation markers: `STARVED_LANE_IDLE_7095` (2), `STARVED_NO_INPUT_7094` (3), `STARVED_UPSTREAM_IDLE_7095` (1) — **upstream/input liveness diagnoses**, not evidence that their functions need arbitrary callers.
- [x] `REDUNDANT_ADVISORY_7095`, `REDUNDANT_RELAY_7095`, `REDUNDANT_TTL_SELFCLEARS_7095` remain explicitly redundant and must not be resurrected merely to shrink static counts.
- [x] Wider-ledger reconciliation state: original 113 complete + D_DISPLAY 151 + E_INFILE 100 + pre-triaged dispositions 182 classified.
- [x] Remaining large raw tier for real triage: **F_DEAD = 1,458 declarations**.
- [x] Regression coverage: `Aate7574PreTriagedTierDispositionTest`.


## V5.0.7575 — F_DEAD safe tranche: UI + voice product surface (40 rows)

F_DEAD triage begins with the lowest-risk homogeneous tranche: every dead declaration under `ui/` and `engine/voice/`.

- [x] **40 F_DEAD rows** classified **PRODUCT_UI_VOICE_DEAD / NOT TRADING AUTHORITY**.
- [x] Scope includes AateComponents6994, AateLoopAnim7027, BrainNetworkView, CollectiveBrainActivity, CryptoAltActivity view helpers, ErrorLogActivity, HeatmapRenderCache6374, JournalActivity, PersonaStudioActivity, BotViewModel UI toggle helper, UniverseHealthActivity, WalletActivity UI helpers, ElevenLabsApi unused helpers, PersonalityVoiceRegistry unused setters, VoiceDiagnostics and VoiceSfxLibrary accessors.
- [x] These functions may be removed or revived only as product/UI work; they are excluded from strategy, scanner, risk, sizing, execution, held-management, finality and learning correctness counts.
- [x] No canonical engine caller is manufactured for a dead UI/voice helper merely to make static analysis green.
- [x] F_DEAD reconciliation progress: **40 / 1,458** classified; **1,418** remain for subsystem-aware triage.
- [x] Regression coverage: `Aate7575DeadUiVoiceTrancheTest`.


## V5.0.7576 — F_DEAD explicit test-hook tranche (22 rows)

F_DEAD triage continues with functions whose names explicitly mark them as test/reset/testing-only hooks.

- [x] **22 F_DEAD rows** classified **TEST_HOOK_ONLY / NOT PRODUCTION-WIRING GAP**.
- [x] Includes `resetForTest*`, `resetAllForTest`, `setForTest`, `aliasesForTest`, `economicInvalidReasonForTest*`, `clearForTesting`, `lanesCompatibleForTests`, `setTestMemoryMode*`, `evaluateForTest*`, `replayForTest`, `forceTripForTests`, `forceOpenLockForTests`, and `forceAgeOpenLockForTests`.
- [x] These hooks remain available for unit/invariant tests but are not candidates for production caller fabrication.
- [x] No test-only reset/probe hook is promoted into scanner, FDG, execution, wallet, settlement, risk or learning authority.
- [x] F_DEAD reconciliation progress: prior 40 UI/voice + 22 explicit test hooks = **62 / 1,458** classified; **1,396** remain.
- [x] Regression coverage: `Aate7576DeadExplicitTestHookTrancheTest`.


## V5.0.7577 — F_DEAD engine/truth counter-readback tranche (12 rows)

F_DEAD triage continues with pure readback/counter accessors in canonical truth subsystems. These functions expose already-maintained state and do not mutate trading behavior.

- [x] `CanonicalCloseFinality6389.auditCount` — **COUNTER/INVARIANT READBACK**; test-covered.
- [x] `CanonicalCloseFinality6389.learningExcludedCount` — **COUNTER/INVARIANT READBACK**; test-covered.
- [x] `CanonicalLedgerParityHold6387.cleanCycleCount` — **STATE READBACK**; consumed by ExecutableOpenGate diagnostics and tests.
- [x] `CanonicalOutcomeClassifier6576.divergenceCount` — **COUNTER READBACK**; test-covered.
- [x] `CapitalConservationTracer6469.violationCount` — **COUNTER READBACK**; test-covered.
- [x] `CounterParityLedger6399.fdgCount` — **COUNTER READBACK**; used in parity/ordering tests.
- [x] `IntentSide.outstandingCount` — **STATE SIZE READBACK** over current intent map.
- [x] `OwnershipClassification6391.openMintCount` — **STATE SIZE READBACK** over recovered ownership map.
- [x] `ReconciliationCoordinator6387.activeJobsCount` — **STATE READBACK**; test-covered.
- [x] `RootCauseFreshnessAuthority6496.lifetimeCount` — **FORENSIC COUNTER READBACK** over PipelineHealthCollector.
- [x] `SameMintCandidateEpoch6402.trackedMintCount` — **STATE SIZE READBACK**.
- [x] `SpecialistContributorMerge6612.mergeCount` — **TELEMETRY COUNTER READBACK**.
- [x] None of these rows requires a fabricated production caller. They are retained as observability/test/readback surfaces.
- [x] F_DEAD reconciliation progress: prior 62 + 12 = **74 / 1,458** classified; **1,384 remain**.
- [x] Regression coverage: `Aate7577TruthCounterReadbackTrancheTest`.


## V5.0.7578 — F_DEAD engine diagnostic counter-readback tranche (12 rows)

F_DEAD triage continues with non-authoritative engine diagnostics and telemetry counters. These functions expose already-maintained state; they do not create trade intent, alter score/size, authorize execution, or schedule exits.

- [x] `CatastrophicExitLatency.activeTraceCount` — **TRACE STATE READBACK** over active catastrophic-exit traces.
- [x] `CatastrophicExitLatency.emittedTraceCount` — **TRACE COUNTER READBACK** over completed latency traces.
- [x] `ExitCoordinatorHeartbeat.falseResetsPreventedCount` — **DIAGNOSTIC COUNTER READBACK**.
- [x] `ExitCoordinatorHeartbeat.justifiedResetCount` — **DIAGNOSTIC COUNTER READBACK**.
- [x] `ExitCoordinatorHeartbeat.staleResetCount` — **DIAGNOSTIC COUNTER READBACK**.
- [x] `ForensicReconciler6377.lifetimeMismatchCount` — **FORENSIC COUNTER READBACK**.
- [x] `ForensicReconciler6377.lifetimePassCount` — **FORENSIC COUNTER READBACK**.
- [x] `LoopCycleEmergencyEvict6352.totalShedCount` — **RUNTIME SHED COUNTER READBACK**.
- [x] `RejectionTelemetry.totalSessionCount` — **SESSION TELEMETRY READBACK**.
- [x] `RuntimeRepairState.staleLocksClearedCount` — **REPAIR TELEMETRY READBACK**.
- [x] `ScannerFanoutDedupe6374.admitCount` — **DEDUPE COUNTER READBACK**.
- [x] `ScannerFanoutDedupe6374.skipCount` — **DEDUPE COUNTER READBACK**.
- [x] None of these rows justifies a fabricated production caller; their underlying mutating/decision paths are audited separately.
- [x] F_DEAD reconciliation progress: prior 74 + 12 = **86 / 1,458** classified; **1,372 remain**.
- [x] Regression coverage: `Aate7578EngineDiagnosticReadbackTrancheTest`.


## V5.0.7579 — F_DEAD perps state/readback tranche (11 rows)

This tranche classifies read-only perps state, registry, scanner and execution diagnostics. None creates trade intent or alters perps admission/execution.

- [x] `CorrelationScanner.getDataPointCounts` — **STATE/DIAGNOSTIC READBACK** over existing price-history buffers.
- [x] `DynamicAltTokenRegistry.getStaticCount` — **REGISTRY COUNT READBACK**.
- [x] `DynamicAltTokenRegistry.getDynamicCount` — **REGISTRY COUNT READBACK**.
- [x] `JupiterPerps.getActiveOrderCount` — **ACTIVE-ORDER STATE READBACK**.
- [x] `MarketsScanner.getCategoryStats` — **SCANNER CATALOG REPORT**.
- [x] `MarketsScanner.getTotalAssetsCount` — **STATIC MARKET-CATALOG COUNT**.
- [x] `PerpsAutoReplayLearner.getTradeHistorySize` — **LEARNING BUFFER SIZE READBACK**.
- [x] `PerpsExecutionEngine.getExecutionCount` — **EXECUTION TELEMETRY READBACK**.
- [x] `PerpsMarketScanners.getScannerStats` — **REPORT/STUB DIAGNOSTIC**; returns per-scanner display counts and is not signal authority.
- [x] `PerpsUnifiedScorerBridge.openEntryCount` — **BRIDGE STATE READBACK**.
- [x] `PriceAggregator.getSourceStats` — **PROVIDER SUCCESS/FAIL REPORT**.
- [x] F_DEAD reconciliation progress: prior 86 + 11 = **97 / 1,458** classified; **1,361 remain**.
- [x] Regression coverage: `Aate7579PerpsReadbackTrancheTest`.


## V5.0.7580 — F_DEAD learning/report readback tranche (10 rows)

This tranche classifies cached learning counters, analytics snapshots and report-only aggregate reads. No entry score, size, execution or exit authority is added.

- [x] `BehaviorLearning.getWinLossCount` — **LEARNING COUNTER READBACK**.
- [x] `CloudLearningSync.getCommunityStats` — **CACHED COMMUNITY REPORT FORMATTER**.
- [x] `FluidLearningAI.getSessionPnlStats` — **SESSION ANALYTICS SNAPSHOT**.
- [x] `FluidLearningAI.getSubTraderTradeCount` — **SESSION COUNTER READBACK**.
- [x] `FluidLearningAI.getSubTraderWinCount` — **SESSION COUNTER READBACK**.
- [x] `FluidLearningAI.getMarketsTradeCount` — **MARKETS LEARNING COUNTER READBACK**.
- [x] `FluidLearningAI.getAltsTradeCount` — **ALTS LEARNING COUNTER READBACK**.
- [x] `PerformanceAnalytics.lifetimeClosedCount` — **ANALYTICS STATE READBACK**.
- [x] `ShadowLearningEngine.getTrackedTradesCount` — **SHADOW REPORT COUNT**.
- [x] `ShadowLearningEngine.getVolatilityPlayStats` — **COMPLETED-SHADOW ANALYTICS REPORT**.
- [x] F_DEAD reconciliation progress: prior 97 + 10 = **107 / 1,458** classified; **1,351 remain**.
- [x] Regression coverage: `Aate7580LearningReportReadbackTrancheTest`.


## V5.0.7581 — F_DEAD crypto/perps learning-state tranche (10 rows)

This tranche closes simple crypto/perps learning-state accessors and same-record statistical helpers. They expose already-maintained learning state and do not constitute missing trade authority.

- [x] `CryptoFluidLearning.paperTradeCount` — **LEARNING COUNTER READBACK**.
- [x] `CryptoFluidLearning.liveTradeCount` — **LEARNING COUNTER READBACK**.
- [x] `CryptoFluidLearning.winCount` — **LEARNING COUNTER READBACK**.
- [x] `CryptoFluidLearning.lossCount` — **LEARNING COUNTER READBACK**.
- [x] `CryptoFluidLearning.paperPnlEma` — **LEARNED STATE READBACK**.
- [x] `CryptoAltTrader.getLossCount` — **TRADER PERFORMANCE COUNTER READBACK**.
- [x] `PerpsTraderAI.getMaxWinStreak` — **PERFORMANCE COUNTER READBACK**.
- [x] `PerpsTraderAI.getMaxLossStreak` — **PERFORMANCE COUNTER READBACK**.
- [x] `CryptoLosingPatternMemory.Bucket.lossRate` — **LOCAL BUCKET STATISTIC HELPER** used to derive danger state.
- [x] `CryptoLosingPatternMemory.Bucket.meanPnl` — **LOCAL BUCKET STATISTIC HELPER** used to derive danger state.
- [x] F_DEAD reconciliation progress: prior 107 + 10 = **117 / 1,458** classified; **1,341 remain**.
- [x] Regression coverage: `Aate7581CryptoLearningStateTrancheTest`.


## V5.0.7582 — F_DEAD provider-health/readback helper tranche (14 rows)

These rows are provider-health state queries, local health predicates, cooldown/readback helpers, or cached configuration state. They are not missing independent trading authorities.

- [x] `DexScreenerWebSocket.getSubscribedCount` — **STATUS COUNT READBACK**.
- [x] `LiveProviderQuorum.hostHealthy` — **LOCAL QUORUM HELPER** used inside provider evaluation.
- [x] `LiveProviderQuorum.hostDegraded` — **LOCAL QUORUM HELPER** used inside provider evaluation.
- [x] `ProviderAuthority.isDegraded` — **PROVIDER STATE READBACK**.
- [x] `ProviderAuthority.allowedRoles` — **AUTHORITY POLICY QUERY HELPER**.
- [x] `ProviderAuthority.canFulfill` — **AUTHORITY POLICY QUERY HELPER**.
- [x] `ProviderAuthority.deviationPct` — **PURE CROSS-PROVIDER MATH HELPER**.
- [x] `ExitProviderHealth.jupiterProbeReady` — **CIRCUIT STATE QUERY**.
- [x] `ExitProviderHealth.jupiterCooldownRemainingMs` — **CIRCUIT STATE READBACK**.
- [x] `ExitProviderHealth.pumpRouteInvalidatedRecently` — **ROUTE-CACHE STATE QUERY**.
- [x] `ProviderCircuitBreaker6402.totalSkipEvents` — **TELEMETRY COUNTER READBACK**.
- [x] `NetworkSignalAutoBuyer.getDailyRemaining` — **BUDGET STATE READBACK**; not an authorization bypass.
- [x] `PythOracle.isPriceFeedHealthy` — **CACHE HEALTH QUERY**.
- [x] `CloudLearningSync.isOptedIn` + `isUsingCommunityWeights` are configuration readbacks; counted as one paired configuration surface for this tranche.
- [x] F_DEAD reconciliation progress: prior 117 + 14 = **131 / 1,458** classified; **1,327 remain**.
- [x] Regression coverage: `Aate7582ProviderHealthReadbackTrancheTest`.


## V5.0.7583 — F_DEAD dashboard/cache projection tranche (7 rows)

These rows expose already-maintained projections or cached collective state for dashboards/diagnostics. They are not missing scanner, FDG, execution or exit authority.

- [x] `DashboardDataProvider.lastCanonicalFinalized6485` — **LAST-FINALIZED PROJECTION READBACK**.
- [x] `DashboardDataProvider.getIntelligenceDashboard` — **DASHBOARD AGGREGATOR**.
- [x] `DashboardDataProvider.getTreasuryDashboard` — **TREASURY DISPLAY PROJECTION**.
- [x] `CollectiveIntelligenceAI.getMintMemory` — **CACHE LOOKUP**.
- [x] `CollectiveIntelligenceAI.getEndpointHealthRecords` — **CACHE SNAPSHOT READBACK**.
- [x] `CollectiveLearning.lastSyncAgeMs6943` — **SYNC-AGE DIAGNOSTIC**.
- [x] `CollectiveLearning.getWhaleEffectiveness` — **CACHED HASHED-WALLET LOOKUP**.
- [x] F_DEAD reconciliation progress: prior 131 + 7 = **138 / 1,458** classified; **1,320 remain**.
- [x] Regression coverage: `Aate7583DashboardCacheProjectionTrancheTest`.


## V5.0.7584 — F_DEAD background/network I-O tranche (11 rows)

These functions perform HTTP/database/background retrieval or explicit connectivity work. Their absence from a direct hot-path caller is not evidence that scanner/FDG should invoke them synchronously.

- [x] `BirdeyeApi.getTokenPrice` — **NETWORK PRICE ENDPOINT**; use only through bounded provider/cache authority.
- [x] `BirdeyeApi.getTokenSecurity` — **NETWORK SECURITY ENDPOINT**.
- [x] `BirdeyeApi.getAllTimeStats` — **NETWORK HISTORICAL-STATS ENDPOINT**.
- [x] `BirdeyeApi.getTraderGainersLosers` — **NETWORK RANKING ENDPOINT**.
- [x] `TursoClient.testConnection` — **CONNECTIVITY/DIAGNOSTIC I-O**.
- [x] `TursoClient.getMarketsTradesForReplay` — **BACKGROUND DATABASE REPLAY QUERY**.
- [x] `TelegramScraper.scrapePublicChannel` — **EXTERNAL WEB SCRAPE**; never synchronous candidate authority.
- [x] `XScraper.checkSolanaAccounts` — **EXTERNAL NETWORK SCRAPE** with deliberate pacing.
- [x] `CollectiveLearning.getActiveUsersCount` — **BACKGROUND DATABASE AGGREGATE**.
- [x] `CollectiveLearning.getCollectiveTradeCount` — **BACKGROUND/CACHED AGGREGATE**.
- [x] `CollectiveLearning.getLegalAgreementCount` — **BACKGROUND DATABASE AGGREGATE**.
- [x] These remain eligible for prefetched/cached sidecars where product value exists; no hot-path caller is fabricated to satisfy static analysis.
- [x] F_DEAD reconciliation progress: prior 138 + 11 = **149 / 1,458** classified; **1,309 remain**.
- [x] Regression coverage: `Aate7584BackgroundNetworkIoTrancheTest`.


## V5.0.7585 — count reconciliation + operator/config control tranche (14 rows)

Count reconciliation: V5.0.7582 classified **15** ledger rows, not 14, because `CloudLearningSync.isOptedIn` and `isUsingCommunityWeights` are distinct F_DEAD rows. Therefore the correct pre-7585 state is **150 / 1,458 classified; 1,308 remain**.

This tranche classifies explicit operator, UI and persisted configuration controls. They can change configured behaviour when deliberately invoked, but they are not missing autonomous strategy primitives and must not be auto-wired merely to satisfy unused-function analysis.

- [x] `CloudLearningSync.setOptIn` — **OPERATOR PRIVACY/SHARING CONTROL**.
- [x] `CloudLearningSync.setUseCommunityWeights` — **OPERATOR LEARNING CONFIG CONTROL**.
- [x] `PerpsNotificationManager.setSoundEnabled` — **PRODUCT NOTIFICATION CONTROL**.
- [x] `PerpsNotificationManager.setVibrationEnabled` — **PRODUCT NOTIFICATION CONTROL**.
- [x] `TimeModeScheduler.setAutoSwitchEnabled` — **USER SCHEDULER CONTROL**.
- [x] `TimeModeScheduler.setScheduleOverride` — **USER SCHEDULE OVERRIDE**.
- [x] `TimeModeScheduler.clearSchedule` — **USER SCHEDULE RESET**.
- [x] `V3ConfidenceConfig.setMode` — **EXPLICIT UI CONFIG CONTROL**.
- [x] `V3ConfidenceConfig.setCustomOverrides` — **EXPLICIT UI CONFIG CONTROL**.
- [x] `V3ConfidenceConfig.clearCustomOverrides` — **EXPLICIT UI CONFIG RESET**.
- [x] `FreeRangeMode.forceOff` — **OPERATOR OVERRIDE CONTROL**.
- [x] `FreeRangeMode.forceOn` — **OPERATOR OVERRIDE CONTROL**.
- [x] `FreeRangeMode.clearOverride` — **OPERATOR OVERRIDE RESET**.
- [x] `LeveragePreference.setLeveragePreferred` — **PERSISTED USER ASSET-CLASS PREFERENCE**.
- [x] F_DEAD reconciliation progress: corrected prior 150 + 14 = **164 / 1,458** classified; **1,294 remain**.
- [x] Regression coverage: `Aate7585OperatorConfigControlTrancheTest`.


## V5.0.7586 — F_DEAD maintenance/callback control tranche (10 rows)

These rows are callback registration, explicit unwatch/migration controls, cache maintenance, or UI retry/reset surfaces. They are not autonomous trading decisions.

- [x] `WatchlistEngine.setAlertCallback` — **CALLBACK REGISTRATION**.
- [x] `WhaleWalletTracker.setOnMovementCallback` — **CALLBACK REGISTRATION**.
- [x] `WhaleWalletTracker.unwatchWhale` — **EXPLICIT USER/MAINTENANCE CONTROL**.
- [x] `HeliusWebSocket.unwatchWallet` — **SUBSCRIPTION MAINTENANCE CONTROL**.
- [x] `AutoEndpointMigrator.forceMigrate` — **OPERATOR ENDPOINT RECOVERY CONTROL**.
- [x] `AutoEndpointMigrator.clearMigration` — **OPERATOR ENDPOINT RECOVERY RESET**.
- [x] `GeminiCopilot.clearCaches` — **CACHE MAINTENANCE**.
- [x] `SmartChartScanner.clearAllCaches` — **CACHE MAINTENANCE**.
- [x] `PriceAggregator.clearPrune` — **MANUAL PROVIDER RETRY/PRUNE RESET**.
- [x] `PerpsTradeVisualizer.clearAllHistory` — **PRODUCT VISUALIZATION CACHE RESET**.
- [x] F_DEAD reconciliation progress: prior 164 + 10 = **174 / 1,458** classified; **1,284 remain**.
- [x] Regression coverage: `Aate7586MaintenanceCallbackControlTrancheTest`.


## V5.0.7587 — F_DEAD analytics/advisory tranche (21 rows)

These rows are performance snapshots, rankings, heatmap summaries, curriculum progress or advisory calculations. They may inform UI/research/explicit policy consumers, but they are not independent execution authorities and must not be wired directly into scanner/FDG merely to eliminate dead-code counts.

- [x] `ArbLearning.getTypeStats` — **ARBITRAGE LEARNING SNAPSHOT**.
- [x] `EducationSubLayerAI.getLayerLevel` — **CURRICULUM PROGRESS READBACK**.
- [x] `EducationSubLayerAI.getLayerLevelProgress` — **CURRICULUM PROGRESS READBACK**.
- [x] `EducationSubLayerAI.getStockLearningStats` — **STOCK LEARNING REPORT**.
- [x] `EducationSubLayerAI.getTopWinningReasons` — **LEARNING RANKING REPORT**.
- [x] `EducationSubLayerAI.getTopLosingReasons` — **LEARNING RANKING REPORT**.
- [x] `PerpsPerformanceAttribution.getLayerStats` — **ATTRIBUTION SNAPSHOT**.
- [x] `PerpsPerformanceAttribution.getTopLayers` — **RANKING REPORT**.
- [x] `PerpsPerformanceAttribution.getBottomLayers` — **RANKING REPORT**.
- [x] `PerpsPerformanceAttribution.getLayersNeedingAttention` — **ANALYTICS REPORT**.
- [x] `PerpsPerformanceAttribution.getSuggestedWeightAdjustments` — **ADVISORY OUTPUT**, not direct weight mutation.
- [x] `PerpsPositionSizer.getMarketStats` — **MARKET PERFORMANCE SNAPSHOT**.
- [x] `PerpsTradeHeatmap.getAvgPnl` — **CELL STATISTIC HELPER**.
- [x] `PerpsTradeHeatmap.getHeatmapData` — **UI/ANALYTICS AGGREGATE**.
- [x] `PerpsTradeHeatmap.getBestSetup` — **HEATMAP ADVISORY**.
- [x] `PerpsTradeHeatmap.getBestTierForMarket` — **HEATMAP ADVISORY**.
- [x] `PerpsTradeHeatmap.getBestTimeSlot` — **HEATMAP ADVISORY**.
- [x] `QuantMindV2.getOverallGrade` — **QUANT STATE READBACK**.
- [x] `QuantMindV2.getRegimePerformance` — **QUANT ANALYTICS SNAPSHOT**.
- [x] `QuantMindV2.getStrategyMetrics` — **QUANT ANALYTICS SNAPSHOT**.
- [x] `QuantMindV2.getRegimeSharpe` — **QUANT METRIC READBACK**.
- [x] F_DEAD reconciliation progress: prior 174 + 21 = **195 / 1,458** classified; **1,263 remain**.
- [x] Regression coverage: `Aate7587AnalyticsAdvisoryTrancheTest`.


## V5.0.7588 — F_DEAD pure helper/state-accessor tranche (12 rows)

This tranche classifies deterministic validation helpers, immutable/current-state accessors and local record predicates. None performs I/O or creates a new trading decision.

- [x] `TursoDefaults.validOrDefaultUrl` — **CONFIG SANITIZER**.
- [x] `TursoDefaults.validOrDefaultToken` — **CONFIG SANITIZER**.
- [x] Two ledger rows for `SwitchboardOracle.isSupported` in `AlternativeOracles.kt` — **STATIC FEED-CATALOG LOOKUPS** for separate oracle/token maps.
- [x] `QuantMindV2.getKellyFraction` — **CURRENT QUANT STATE READBACK**.
- [x] `QuantMindV2.getOptimalLeverage` — **CURRENT QUANT STATE READBACK**.
- [x] `QuantMindV2.getMomentumState` — **CURRENT QUANT STATE READBACK**.
- [x] `QuantMindV2.getEdgeDecay` — **CURRENT QUANT STATE READBACK**.
- [x] `QuantMindV2.getRecommendation` — **CURRENT QUANT ADVISORY STRING**.
- [x] `BirdeyeCreationInfoProvider.Info.isFreshDeploy` — **LOCAL AGE PREDICATE**.
- [x] `BirdeyeCreationInfoProvider.Info.isYoungToken` — **LOCAL AGE PREDICATE**.
- [x] `BirdeyeWhaleFeeder.isKnownWhale` — **LOCAL CACHE MEMBERSHIP QUERY**.
- [x] F_DEAD reconciliation progress: prior 195 + 12 = **207 / 1,458** classified; **1,251 remain**.
- [x] Regression coverage: `Aate7588PureHelperStateAccessorTrancheTest`.


## V5.0.7589 — F_DEAD telemetry/report/notification tranche (22 rows)

This tranche classifies report builders, telemetry state, analytics aggregators, notification emitters, and presentation helpers. They may be useful observability/product surfaces but are not missing entry/size/exit authority.

- [x] `BundleDetector.getCacheStats` — **CACHE STATUS FORMATTER**.
- [x] `CanonicalEntryAuthority6540.assetClassStats6567` — **ENTRY FUNNEL TELEMETRY SNAPSHOT**.
- [x] `DiscordNotifier.bigWinMsg` — **NOTIFICATION FORMATTER**.
- [x] `JournalReceiptAccounting6663.getLiveStats` — **JOURNAL ANALYTICS REPORT**.
- [x] `LockDiagnosticsTracker.inFlight` — **LOCK DIAGNOSTIC READBACK**.
- [x] `MarketsScanner.getTopGainers` — **SCANNER CONVENIENCE/REPORT WRAPPER**.
- [x] `MarketsScanner.getTopLosers` — **SCANNER CONVENIENCE/REPORT WRAPPER**.
- [x] `PerpsNotificationManager.notifyLearningMilestone` — **PRODUCT NOTIFICATION SIDE EFFECT**.
- [x] `PerpsNotificationManager.notifyLiquidationWarning` — **PRODUCT NOTIFICATION SIDE EFFECT**; not liquidation authority.
- [x] `PerpsNotificationManager.notifyMTFAlignment` — **PRODUCT NOTIFICATION SIDE EFFECT**.
- [x] `PortfolioAnalytics.calculateCorrelations` — **PORTFOLIO ANALYTICS COMPUTATION**.
- [x] `PortfolioAnalytics.generateHeatMap` — **PORTFOLIO ANALYTICS REPORT**.
- [x] `RejectionTelemetry.topWindow` — **ROLLING TELEMETRY REPORT**.
- [x] `RejectionTelemetry.topSession` — **SESSION TELEMETRY REPORT**.
- [x] `ReportingHub.addBoundedSection` — **LOCAL REPORT COMPOSITION HELPER**.
- [x] `RootCauseTelemetry6441.attribute` — **TELEMETRY ATTRIBUTION MUTATOR**, not trading authority.
- [x] `RootCauseTelemetry6441.subsystemBreakdown` — **TELEMETRY REPORT FORMATTER**.
- [x] `RunTracker30D.getFilterStats` — **UI/REPORT FORMATTER**.
- [x] `StrategyTelemetry.computePaperTerminalLeaderboard` — **PAPER ANALYTICS REPORT**.
- [x] `StrategyTelemetry.bleeders` — **ANALYTICS RANKING REPORT**.
- [x] `StrategyTelemetry.getDisabled` — **COMPATIBILITY/STATE READBACK**; current doctrine returns empty.
- [x] `UniversalBridgeEngine.getBridgeStats` — **BRIDGE TELEMETRY REPORT**.
- [x] F_DEAD reconciliation progress: prior 207 + 22 = **229 / 1,458** classified; **1,229 remain**.
- [x] Regression coverage: `Aate7589TelemetryReportNotificationTrancheTest`.


## V5.0.7590 — F_DEAD cached/state/report accessor tranche (24 rows)

These rows are cached-state lookups, persisted preference reads, scanner parameter accessors, schedule analytics, trace formatters, or registry views. They do not independently authorize a trade.

- [x] `BalanceProofWaitState.getWaiting` — **WAIT-STATE LOOKUP**.
- [x] `CanonicalIdentityModel6464.getIdentity` — **CANONICAL IDENTITY LOOKUP**.
- [x] `InsiderTrackerAI.getAllWallets` — **TRACKED-WALLET SNAPSHOT**.
- [x] `InsiderTrackerAI.getWalletActivity` — **CACHED WALLET-ACTIVITY LOOKUP**.
- [x] `LeveragePreference.isLeveragePreferred` — **PERSISTED USER PREFERENCE READ**.
- [x] `ModeSpecificScanners.getCached` — **RECENT RESULT CACHE LOOKUP**.
- [x] `ModeSpecificScanners.getMinBuyPressure` — **CONFIG/FLUID THRESHOLD READBACK**.
- [x] `ModeSpecificScanners.getMinDipDepth` — **CONFIG/FLUID THRESHOLD READBACK**.
- [x] `ModeSpecificScanners.getMinImpulse` — **CONFIG/FLUID THRESHOLD READBACK**.
- [x] `RunTracker30D.getTradeTrace` — **TRACE FORMATTER**.
- [x] `RunTracker30D.isRunComplete` — **RUN-STATE PREDICATE**.
- [x] `SourceTimingRegistry.getFirstSeen` — **TIMING CACHE LOOKUP**.
- [x] `SourceTimingRegistry.getLatestSeen` — **TIMING CACHE LOOKUP**.
- [x] `SourceTimingRegistry.getSourceCount` — **TIMING CACHE AGGREGATE**.
- [x] `SourceTimingRegistry.getVenueLagMs` — **TIMING CACHE DERIVED METRIC**.
- [x] `TimeModeScheduler.getSchedule` — **USER SCHEDULE SNAPSHOT**.
- [x] `TimeModeScheduler.getBestHoursForMode` — **SCHEDULE ANALYTICS REPORT**.
- [x] `TimeModeScheduler.getWorstHoursForMode` — **SCHEDULE ANALYTICS REPORT**.
- [x] `TokenizedAssetRegistry.knownSymbols` — **STATIC/USER REGISTRY VIEW**.
- [x] `V3ConfidenceConfig.getAllModes` — **UI CONFIG CATALOG**.
- [x] `V3ConfidenceConfig.getModeDescription` — **UI CONFIG DESCRIPTION**.
- [x] `V3ConfidenceConfig.hasCustomOverrides` — **CONFIG STATE READBACK**.
- [x] `WhaleWalletTracker.getWatchedWhales` — **TRACKER SNAPSHOT**.
- [x] `WhaleWalletTracker.getWatchedWhaleMovements` — **TRACKER SNAPSHOT**.
- [x] F_DEAD reconciliation progress: prior 229 + 24 = **253 / 1,458** classified; **1,205 remain**.
- [x] Regression coverage: `Aate7590CachedStateAccessorTrancheTest`.


## V5.0.7591 — F_DEAD explicit maintenance/recovery control tranche (16 rows)

These APIs are deliberate maintenance, recovery, UI/operator configuration, cache cleanup, or queue housekeeping. Some mutate state when explicitly invoked, but none is a missing autonomous strategy edge and none should be auto-wired into the trading hot path.

- [x] `AutoModeEngine.clearCopy` — **COPY-MODE CLEANUP**.
- [x] `BirdeyeBudgetGate.setDailyCap` — **EXPLICIT BUDGET CONFIG CONTROL**.
- [x] `DataOrchestrator.setDevWallet` — **METADATA/TRACKING REGISTRATION**.
- [x] `EfficiencyLayer.removeCandidate` — **QUEUE MAINTENANCE**.
- [x] `EmergentGuardrails.enableConfigChanges` — **EXPLICIT RUN/CONFIG CONTROL**.
- [x] `FluidLearningAI.setCrossLearningEnabled` — **EXPLICIT LEARNING CONFIG CONTROL**.
- [x] `GlobalTradeRegistry.pruneDormant` — **INTAKE MAINTENANCE NO-OP BY CURRENT DOCTRINE**.
- [x] `InsiderTrackerAI.removeCustomWallet` — **USER TRACKER MAINTENANCE**.
- [x] `PerpsTrailingStop.setGlobalConfig` — **EXPLICIT TRAILING CONFIG CONTROL**.
- [x] `ReentryGuard.clearLockout` — **MANUAL RECOVERY CONTROL**.
- [x] `RemoteKillSwitch.clearLocalKill` — **LOCAL OPERATOR RECOVERY CONTROL**.
- [x] `SelfHealingDiagnostics.clearPoisonedMemory` — **DESTRUCTIVE EXPLICIT REPAIR ACTION**, never autonomous hot-path wiring.
- [x] `SelfHealingDiagnostics.manualClearMemory` — **UI/MANUAL REPAIR WRAPPER**.
- [x] `TradeHistoryStore.invalidateStatsCache` — **CACHE MAINTENANCE**.
- [x] `TokenMetaCache.pruneStale` — **BOUNDED METADATA CACHE MAINTENANCE**.
- [x] `WhaleDetector.clearToken` — **TRACKER CACHE MAINTENANCE**.
- [x] F_DEAD reconciliation progress: prior 253 + 16 = **269 / 1,458** classified; **1,189 remain**.
- [x] Regression coverage: `Aate7591MaintenanceRecoveryControlTrancheTest`.


## V5.0.7592 — F_DEAD RuntimeRepairState explicit-control tranche (8 rows)

`RuntimeRepairState` is explicitly documented as bounded live repair state. These remaining rows are operator/recovery controls or state readbacks; they are not missing autonomous alpha or execution logic. `staleLocksClearedCount` was already classified in 7578 and is not double-counted.

- [x] `RuntimeRepairState.clearPaperModeRequest` — **REPAIR STATE RESET**.
- [x] `RuntimeRepairState.enableLane` — **EXPLICIT LANE RECOVERY CONTROL**.
- [x] `RuntimeRepairState.enableScannerSource` — **EXPLICIT SOURCE RECOVERY CONTROL**.
- [x] `RuntimeRepairState.resumeTrading` — **EXPLICIT RUNTIME RECOVERY CONTROL**.
- [x] `RuntimeRepairState.scannerCap` — **REPAIR CONFIG READBACK**.
- [x] `RuntimeRepairState.setScannerUserDisabled` — **EXPLICIT OPERATOR SCANNER CONTROL**.
- [x] `RuntimeRepairState.shouldForcePaper` — **NEUTRALIZED COMPATIBILITY READBACK**; current implementation returns false.
- [x] `RuntimeRepairState.uiRebindGeneration` — **UI REPAIR GENERATION READBACK**.
- [x] F_DEAD reconciliation progress: prior 269 + 8 = **277 / 1,458** classified; **1,181 remain**.
- [x] Regression coverage: `Aate7592RuntimeRepairStateTrancheTest`.


## V5.0.7593 — F_DEAD truth readback/predicate tranche (37 rows)

These rows are truth-layer counters, immutable/current-state accessors, classification predicates, and forensic snapshots. They expose or classify state already owned elsewhere; they do not create a second execution authority.

- [x] `AntiRewardHackingGuard6439.currentHigh` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `BackgroundTradingAuthority6469.currentJobId` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.currentRunId` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.currentStartMs` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.eligibleForFreshMetrics` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.isAmbiguous` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.isForbiddenSource` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.isLegalSource` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalCloseFinality6389.stallCountAbove700ms` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalExecutionReceipt6394.currentExecutionId` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalExecutionReceipt6394.isConsumed` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalExecutionReceipt6394.isTagged` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.canonicalLearningEligible` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.countHeld` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.countZero` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.eligibleForGovernorInfluence` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.eligibleForZeroClose` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.isValidTransition` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFill6393.isWalletBalanceAuthority` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalFinalizedTradeBus6464.isExcluded` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalOutcomeClassifier6576.counts` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalPositionAuthority6441.openCountForValuation` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalTokenMetricsSnapshot6725.isDyingToken` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalTokenMetricsSnapshot6725.isHealthyRunner` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `CanonicalTradeStream6501.isEligible` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `PaperCapitalAuthority6577.accountId` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `PaperCapitalAuthority6577.invariantCounts` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `ProtectiveExitScheduler6450.armedCount7027` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `ProtectiveExitScheduler6450.noMarkCount7027` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `RiskExitPriorityDomain6461.laneStatsAgeMs` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `RootCauseClassifier6471.lastResult` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `RuntimeTune6833.accountingBoundaryViolations6833` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `RuntimeTune6833.markContractHonoredCount6833` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `RuntimeTune6833.phantomSizedRejectedCount6833` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `TokenMapVersionGuard6411.currentLaneRoutingVersion` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `TokenMapVersionGuard6411.currentMappingVersion` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] `UniversalSlLeaseRegistry6402.oldestLeaseAgeMs` — **TRUTH STATE/PREDICATE/READBACK**.
- [x] F_DEAD reconciliation progress: prior 277 + 37 = **314 / 1,458** classified; **1,144 remain**.
- [x] Regression coverage: `Aate7593TruthReadbackPredicateTrancheTest`.


## V5.0.7594 — F_DEAD truth eligibility/classification helper tranche (38 rows)

These are bounded truth-layer predicates and lookups over already-authoritative state. Their job is classification/eligibility/query semantics; lack of an external caller does not justify inventing parallel authority.

- [x] `BleederLaneProbation6747.isOnProbation` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `CanaryReleaseGate6386.canAcceptBuy` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `CanonicalPositionAuthority6441.canAffordPaperBuy` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `CapitalPreservationCreed6439.isAlignedWithDailyTarget` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `DataProviderFaultCircuits6468.isNetworkAllowed` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `EarlyEntryScout6390.isAlarm` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `EvidenceEpochFilter6388.isHeld` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `EvidenceEpochFilter6388.isHistoricalAudit` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `EvidenceEpochFilter6388.isRecoveryEligible` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `FdgFanoutControl6396.canShadowLaneExecute` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `FillLotLedger6504.canonicalQtyOf` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `GovernorRecovery6388.lastDemotionReason` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `LaneCapitalFairness6732.hasHeadroom` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `LiveContinuityPolicy6392.isBluechip` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `LiveExitOnlyMode6387.currentIndex` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `LiveExitOnlyMode6387.isConfirmedZero` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `LiveExitOnlyMode6387.isJobActive` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `OwnershipClassification6391.hasAnyProof` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `OwnershipClassification6391.isValidEvent` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PaperAccountLedger6430.canAffordBuy` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PaperAccountLedger6430.hasPersistentState6487` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PaperCatastrophicCloseIdempotency6497.isClaimed` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PerpsSandbox6463.leverageOf` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PositionIdentity6395.canonicalId` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PositionLifecycleFormalization6617.lastDeltas` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PositionViewModelStore6395.canShowLockedPercent` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PositionViewModelStore6395.getByMint` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `PreSupervisorBudgetGuard6437.canRun` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `RootCauseFreshnessAuthority6496.isHistoricalOnly` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `SentienceLabRewardBridge6444.canonicalWLTrio` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `SoftScoreShaping6400.lastShaping` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `SpecialistProposalArbiter6629.currentDecision6629` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `StartupReconciliation6635.isQuarantined6635` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `V3VerdictContract6622.isFatal6622` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `WalletAssetClass6387.countsAsFreeEntrySlot` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `WalletAssetClass6387.isDeletedMint` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `WalletAssetClass6387.isLearningEligible` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] `WorkerPoolDomainRegistry6411.canDegradedEntry` — **TRUTH ELIGIBILITY/CLASSIFICATION HELPER**.
- [x] F_DEAD reconciliation progress: prior 314 + 38 = **352 / 1,458** classified; **1,106 remain**.
- [x] Regression coverage: `Aate7594TruthEligibilityHelperTrancheTest`.


## V5.0.7595 — F_DEAD diagnostics/storage/status tranche (28 rows)

These rows expose logs, counters, persisted paths/preferences, cache/status snapshots, or diagnostics. They do not create trade authority.

- [x] `ErrorLogger.getCrashesOnly` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ErrorLogger.getCurrentSessionLogs` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ErrorLogger.getErrorsAndCrashes` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ErrorLogger.getLogsByComponent` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `PatternAutoTuner.getLastUpdateTs` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `PatternAutoTuner.getTradesAnalyzed` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `RateLimiter.getAllUsage` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `RateLimiter.getEffectiveLimit` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `RateLimiter.getRetryAfterMs` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `RateLimiter.getUsage` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ReentryGuard.getLockoutInfo` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ReentryGuard.getRemainingMinutes` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ReentryGuard.isSecondMoonHot` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `RuggedContracts.getCount` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `SafetyTier.getWhitelistedMints` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `SafetyTier.isWhitelisted` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `ScannerFanoutDedupe6374.currentTtlMs` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `WalletPositionLock.getBreakdown` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `WalletPositionLock.getExposurePct` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `WalletPositionLock.getTotalDeployed` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `WalletTokenMemory.getAllEntries` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `WalletTokenMemory.isKnownOpenPosition` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `WalletConnectionState.getCurrentRpcUrl` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `PipelineTracer.getLoopAggression` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `PersonalityMemoryStore.getBio` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `PersistentLearning.getStoragePath` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `TradeLifecycle.getCompleted` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] `TradeState.getCooldownRemaining` — **DIAGNOSTIC/STATUS/STORAGE READBACK**.
- [x] F_DEAD reconciliation progress: prior 352 + 28 = **380 / 1,458** classified; **1,078 remain**.
- [x] Regression coverage: `Aate7595DiagnosticsStorageStatusTrancheTest`.


## V5.0.7596 — F_DEAD classification/validation helper tranche (29 rows)

These are deterministic classification, validation, forensic-list, or state-predicate helpers. They may support an owning authority when called, but are not themselves evidence that a second decision path must be manufactured.

- [x] `AssetClass.isRealLearningSize` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BannedTokens.getBanTimestamp` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BannedTokens.getBannedList` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BannedTokens.getDetailedBannedList` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BannedTokens.getReason` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BehaviorLearning.getBroadSignature` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BehaviorLearning.getFineSignature` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BehaviorLearning.getRichSignature` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BehaviorLearning.getSignature` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `BirdeyeBudgetGate.isLockedDown` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `CloseOutcomeLabelSanitizer.isDirtyForTraining` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `DeadAILayerFilter.isNotApplicable` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `ExecutionHealthGuard.isEmergencyReason` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `HardRugPreFilter.isViable` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `HotfixRules.isSignatureValid` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `LaneTag.isMeme` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `LearningPnlSanitizer.isTrainablePct` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `LearningPnlSanitizer.isTrainableTrade` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `LiquidityClassifier.isBondingCurveQuote` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `LivePositionCloseAuthority.isUntrustedRpc` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `MintIntegrityGate.isSilentStablePark` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `PendingSellQueue.isTemporary` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `SellIntentSeverity.isEmergencyBand` (ledger declaration 22) — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `SellIntentSeverity.isEmergencyBand` (ledger declaration 23) — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `SellReconciler.isLiveAlive` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `SolanaBlueChipWatchlist.isBlueChip` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `UniversalRouteEngine.isPumpFamilyMint` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `WalletReconciler.isHeldOrOpen` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] `WalletReconciler.knownMintContains` — **CLASSIFICATION/VALIDATION HELPER**.
- [x] F_DEAD reconciliation progress: prior 380 + 29 = **409 / 1,458** classified; **1,049 remain**.
- [x] Regression coverage: `Aate7596ClassificationValidationHelperTrancheTest`.


## V5.0.7597 — F_DEAD V3 state/accessor/predicate tranche (43 rows)

These V3 rows expose learned state, configuration, dashboard projections, threshold reads, or convenience predicates. Classification here does not declare the owning subsystem dead; it prevents unused-accessor noise from being mistaken for missing independent strategy authority.

- [x] `AIStartupCoordinator.isInitialized` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `BehaviorAI.getAggressionName` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `BootstrapAdaptiveEngine.getMultiplier` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `CashGenerationAI.getCurrentTreasuryBalance` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `ConfidenceBreakdown.getStarvationRelief` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `DipHunterAI.getFluidRecoveryTarget` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `EducationSubLayerAI.getBootstrapRelaxation` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `EligibilityResult.isCoolingDown` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `EligibilityResult.isGlobalExposureMaxed` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `EligibilityResult.isTokenAlreadyOpen` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FluidLearningAI.getAltsLearningProgress` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FluidLearningAI.getBehaviorModifier` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FluidLearningAI.getFreshTokenAgeMinutes` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FluidLearningAI.getLearningWeights` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FluidLearningAI.getMinHistoryCandles` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FluidLearningAI.getScannerMinLiquidity` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `FundingRateAwarenessAI.getFundingApr` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `LayerTransitionManager.getCurrentLayer` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `LayerTransitionManager.getLayerState` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `LiquidityCycleAI.isOutflowing` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `ManipulatedTraderAI.getFluidMaxAge` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `ManipulatedTraderAI.getFluidPositionSize` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `MarketStructureRouter.getAIWeights` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `MarketStructureRouter.getRawPositionParams` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `MarketStructureRouter.isSuitableForLiquidity` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `MetaCognitionAI.getLayerDashboard` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `MoonshotTraderAI.getDailyHundredX` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `MoonshotTraderAI.getDailyTenX` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `NewsShockAI.getSlope` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `OrderFlowImbalanceAI.getCumulativeDelta` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `OrderFlowImbalanceAI.isAbsorbing` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `ProjectSniperAI.getActiveMissionCount` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `ScoreComponent.hasFatal` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `ShadowOutcome.isTracked` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `SmartMoneyDivergenceAI.hasBearishDivergence` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `SmartMoneyDivergenceAI.hasBullishDivergence` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `StablecoinFlowAI.getRegimeBias` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `UltraFastRugDetectorAI.getMonitoredCount` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `V3Adapter.getLearningStore` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `V3Adapter.getOrchestrator` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `V3EngineManager.getMode` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `V3EngineManager.isSuccess` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] `V3EngineManager.shouldExecute` — **V3 STATE/ACCESSOR/PREDICATE SURFACE**.
- [x] F_DEAD reconciliation progress: prior 409 + 43 = **452 / 1,458** classified; **1006 remain**.
- [x] Regression coverage: `Aate7597V3AccessorPredicateTrancheTest`.


## V5.0.7598 — F_DEAD perps/markets accessor and analytics tranche (47 rows)

These rows expose perps/markets state, catalog scans, correlation/learning analytics, cached mark predicates, or reporting conveniences. They do not justify a second execution path simply because the accessor itself has no external caller.

- [x] `CorrelationScanner.getCorrelationStrength` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CorrelationScanner.hasEnoughData` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoAltScannerAI.getAltBeta` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoAltScannerAI.getTopSectors` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoAltTrader.getClosedPositions` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoAltTrader.hasPositionSymbol` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoAltTrader.hasTrustedMark` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoBrain.getLevConfFloor` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoBrain.getSlPct` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoBrain.shouldShadowOnly` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `CryptoWrappedAssetMapper.isNativeOnly` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `DynamicAltTokenRegistry.getNewTokens` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `DynamicAltTokenRegistry.getTokensBySector` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `JupiterPerps.getActiveOrders` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `JupiterPerps.getFailedOrders` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `JupiterPerps.getSuccessfulOrders` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `JupiterPerps.getTotalOrders` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsLiveExecutor.getSuccessRate` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsLiveExecutor.getTotalExecutions` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsLiveExecutor.getTotalFeesCollected` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsScanner.getChinaStocks` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsScanner.getEuropeStocks` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsScanner.getGoldMiners` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsScanner.getJapanStocks` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `MarketsScanner.getSilverMiners` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAdvancedAI.getBestTradingHours` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAdvancedAI.getCorrelatedPositions` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAdvancedAI.getSectorRotation` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAdvancedAI.isHighlyCorrelated` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAdvancedAI.isInHotSector` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAutoReplayLearner.getLayerAdjustments` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsAutoReplayLearner.isLearning` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsCorrelationMatrix.getCorrelationMatrix` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsCorrelationMatrix.getGroupMembers` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsCorrelationMatrix.getMarketGroup` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsDirection.isReadyForLive` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsLearningInsightsPanel.getActionableInsights` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsLearningInsightsPanel.getHighPriorityInsights` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsLearningInsightsPanel.getInsightsByType` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsLearningInsightsPanel.getMarketInsights` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsMarketDataFetcher.getAllMarketsData` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsMarketScanners.getLastScanTime` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsMarketScanners.getTotalScans` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PerpsTraderAI.getCurrentStreak` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `PythOracle.isTradable` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `TokenizedStockTrader.isRegularTradingHours` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] `WatchlistEngine.isOnWatchlist` — **PERPS/MARKETS ACCESSOR/ANALYTICS/PREDICATE**.
- [x] Network-backed market-data retrieval remains background/bounded; no synchronous meme hot-path wiring is introduced.
- [x] F_DEAD reconciliation progress: prior 452 + 47 = **499 / 1,458** classified; **959 remain**.
- [x] Regression coverage: `Aate7598PerpsMarketsAccessorTrancheTest`.


## V5.0.7599 — F_DEAD network/provider helper tranche (21 rows)

These remaining network rows are provider I/O wrappers, batching helpers, wallet serialization/confirmation utilities, cache/status counters, or transport classification helpers. Network-capable functions stay behind their owning provider/budget/background authority and are not promoted into synchronous decision voting.

- [x] `CoinGeckoTrending.getTrending` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `CoinGeckoTrending.getTrendingRank` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `HeliusEnhancedWS.updateWatchlist` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `HostCircuitInterceptor.totalNxBypassedRequests` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `HostCircuitInterceptor.totalServerBypassedRequests` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `JupiterStrictTokenList.getVerified` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `KeylessLlmProviders6999.chatWithModel` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `KeylessLlmProviders6999.models` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `KeylessPriceSources6996.defiLlamaBatch` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `KeylessPriceSources6996.jupiterBatch` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.applyRoundRobin` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.awaitConfirmation` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.compactU16` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.finalized` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.getTokenAccountsChecked` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.getTokenAccountsWithDecimals` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.getTokenAccountsWithDecimalsStrict` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.markEndpointUnhealthy` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolanaWallet.mergeFrom` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SolscanDevTracker.getRecentTransactions` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] `SwapQuote.isTransient` — **NETWORK/PROVIDER/TRANSPORT HELPER**.
- [x] F_DEAD reconciliation progress: prior 499 + 21 = **520 / 1,458** classified; **938 remain**.
- [x] Regression coverage: `Aate7599NetworkProviderHelperTrancheTest`.


## V5.0.7600 — F_DEAD lab/backtest-only tranche (7 rows)

These rows belong to explicit LAB/backtest/background research surfaces. They are intentionally outside canonical LIVE/PAPER execution authority unless a separately reviewed transfer contract promotes an artifact.

- [x] `AsyncStrategyLab.requestBackgroundProviderHypothesis` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] `BacktestEngine.assetClassBreakdown` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] `BacktestEngine.compareStrategies` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] `BacktestEngine.runAndLog` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] `LlmLabEngine.requestTransferToMainPaper` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] `LlmLabStore.adjustLiveBalance` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] `LlmLabStore.getLiveBalance` — **LAB/BACKTEST/BACKGROUND RESEARCH SURFACE**.
- [x] F_DEAD reconciliation progress: prior 520 + 7 = **527 / 1,458** classified; **931 remain**.
- [x] Regression coverage: `Aate7600LabBacktestTrancheTest`.


## V5.0.7601 — F_DEAD V4 meta-layer API tranche (19 rows)

All remaining V4 rows are meta-layer accessors, recorders, risk/advisory queries or explicit quarantine controls. They are reviewed as **META-LAYER UNUSED API / NO DIRECT HOT-PATH WIRING**: any future promotion must converge through canonical policy/oracle surfaces rather than creating another authority.

- [x] `CrossMarketRegimeAI.getCapitalBias` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `CrossMarketRegimeAI.getRegimeFitMultiplier` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `CrossMarketRegimeAI.lastAssessAgeMs` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `CrossMarketRegimeAI.trackedMarketCount` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `ExecutionPathAI.recordExecution` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `LeverageSurvivalAI.getAllowedLeverage` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `LiquidityFragilityAI.getMaxSafeSize` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `LiquidityFragilityAI.getMaxSafeSizeFor` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `LiquidityFragilityAI.isTradeAllowed` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `LiquidityFragilityAI.recordBreakout` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `LiquidityFragilityAI.recordWick` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `PortfolioHeatAI.excess` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `PortfolioHeatAI.isNewEntryAllowed` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `StrategyTrustAI.getQuarantineUntil` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `StrategyTrustAI.getTrustRecord` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `StrategyTrustAI.setQuarantine` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `TradeLessonRecorder.getLeverageLessons` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `TradeLessonRecorder.getNarrativeLessons` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] `TradeLessonRecorder.getRegimeLessons` — **V4 META API / NO DIRECT AUTHORITY**.
- [x] F_DEAD reconciliation progress: prior 520 + 19 = **539 / 1,458** classified; **919 remain**.
- [x] Regression coverage: `Aate7601V4MetaApiTrancheTest`.


## V5.0.7602 — F_DEAD collective/Turso remaining tranche (8 rows)

These are collective sync, legal/product state, orphan reconciliation, database maintenance, or background persistence surfaces. None belongs in scanner/V3/FDG as a synchronous vote.

- [x] `CollectiveLearning.downloadAll` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `CollectiveLearning.uploadModePerformance` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `LegalAgreementManager.getAcceptanceTimestamp` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `LegalAgreementManager.hasAcceptedAgreement` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `LegalAgreementManager.needsReacceptance` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `LocalOrphanStore.reconcileAll` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `TursoClient.nukeBadData` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] `TursoClient.saveLeadLagPair` — **COLLECTIVE/BACKGROUND/MAINTENANCE SURFACE**.
- [x] F_DEAD reconciliation progress: prior 539 + 8 = **547 / 1,458** classified; **911 remain**.
- [x] Regression coverage: `Aate7602CollectiveRemainingTrancheTest`.


## V5.0.7603 — count reconciliation + runtime/quant helper tranche (21 rows)

**Count correction:** V5.0.7600 ended at **527 / 1,458**. V5.0.7601 added 19 and V5.0.7602 added 8, so the correct pre-7603 state is **554 / 1,458 classified; 904 remain**. The earlier 7601/7602 running arithmetic understated classified rows by 7; no source disposition was lost.

This tranche classifies runtime counters/damper helpers, learning-vote maintenance, and standalone quant metric functions. These are utility/control/math surfaces rather than missing independent lane owners.

- [x] `ColdStreakDamper.currentLossStreak` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ColdStreakDamper.currentWinStreak` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ColdStreakDamper.effectiveLossStreak6991` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ColdStreakDamper.gateNormalEntry` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ColdStreakDamper.recordCall` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ColdStreakDamper.shouldCall` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `EVCalculator.getKellySize` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `EVCalculator.isPositiveEV` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ExecutionCounterContract.recordCloseAttempt` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ExecutionCounterContract.recordCloseSuccess` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ExecutionCounterContract.recordJournalBuyWrite` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ExecutionCounterContract.recordOpenAttempt` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `ExecutionCounterContract.recordOpenSuccess` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `LayerVoteStore.drainVotes` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `LayerVoteStore.purgeStale` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `QuantMetrics.calculateCVaR` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `QuantMetrics.calculateCalmarRatio` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `QuantMetrics.calculateSharpeRatio` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `QuantMetrics.calculateSortinoRatio` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `QuantMetrics.calculateVaR` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] `QuantMetrics.updateEquity` — **RUNTIME/QUANT/LEARNING UTILITY SURFACE**.
- [x] F_DEAD reconciliation progress: corrected prior 554 + 21 = **575 / 1,458** classified; **883 remain**.
- [x] Regression coverage: `Aate7603RuntimeQuantHelperTrancheTest`.


## V5.0.7604 — F_DEAD engine state/predicate/advisory tranche (53 rows)

These engine-level rows expose current state, cached analytics, policy status, diagnostics, compatibility views, or advisory predicates around an already-owned subsystem. They are **not independently promoted to execution authority** by virtue of being zero-caller declarations.

- [x] `AntiChokeManager.currentLevel` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `BotBrain.isRecentRegimeFavorable` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `EdgeLearning.getLiveTradesSeen` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `EfficiencyLayer.getTopCandidates` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `EmergentGuardrails.getFrozenAggression` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `EmergentGuardrails.getTradesLastMinute` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `EmergentGuardrails.isAggressionFrozen` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `ExitManager.hasCriticalCondition` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `FinalExecutionPermit.getRejectionReason` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `FluidLearning.getAvailableBalance` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `FluidLearning.getLearningScaleMultiplier` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `FluidLearning.isLearningAvailable` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `GeminiCopilot.getRateLimitRemainingMinutes` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `GlobalTradeRegistry.getTotalExposure` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `GlobalTradeRegistry.isInProbation` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `HoldingLogicLayer.getAllModeParams` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LaneAutoPauseGuard.isPausedLive` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiquidityBucketRouter.isModeAppropriate` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiquidityDepthAI.getEntryLiquidity` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiveEntrySafetyHold.currentFloorAdjustment` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiveProbeEntry.hasActiveProbe` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiveWalletReconciler.isStarted` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiveWalletReconciler.lastBuySignature` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `LiveWalletReconciler.lastRunMs` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `MarketRegimeAI.getRegimeDurationMinutes` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `MarketRegimeAI.getRegimeInfo` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `PerpsLaneGate.isShadowEnabled` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `ProfitabilityLayer.isEvicted` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `RemoteKillSwitch.getMaxPositionOverride` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `RemoteKillSwitch.isForcePaperMode` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `SentientPersonality.getLatestThought` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `SentimentEngine.getSentiment` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `ShadowLearningEngine.getBestVariant` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `SuperBrainEnhancements.getBreadthTrend` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `SymbolicContext.getAge` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `SymbolicContext.isFundingUnfavourable` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TimeOptimizationAI.getCurrentDayOfWeek` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TimeOptimizationAI.getCurrentSession` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TimeOptimizationAI.isWeekend` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `ToxicModeCircuitBreaker.getLiquidityFloor` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `ToxicModeCircuitBreaker.isModeDisabled` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeAuthorizer.getShadowTracking` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeAuthorizer.getTokenBooks` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeAuthorizer.getTokenState` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeAuthorizer.getTokensInBook` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeAuthorizer.hasOpenPositionInBook` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeDatabase.getTradesByPhase` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradeHistoryStore.getAllTradesIncludingInvalidForensics` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradingCopilot.getAssetWindow` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradingCopilot.isAggressiveHunt` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TradingCopilot.isEmergencyBrake` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `TreasuryOpportunityEngine.getActiveDeployments` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] `VoiceManager.getBackend` — **ENGINE STATE/PREDICATE/ADVISORY SURFACE**.
- [x] Database/provider-backed reads remain off synchronous hot paths unless separately cached/prefetched.
- [x] F_DEAD reconciliation progress: prior 575 + 53 = **628 / 1,458** classified; **830 remain**.
- [x] Regression coverage: `Aate7604EngineStatePredicateTrancheTest`.


## V5.0.7605 — complete F_DEAD disposition ledger (1,458 / 1,458)

The incremental tranche audit is now replaced by a complete machine-readable disposition ledger at `audits/f_dead_disposition_7605.tsv`.

Important semantic distinction: **0 unresolved F_DEAD rows does not mean 1,458 functions were wired.** It means every static zero-caller declaration now has an explicit disposition and basis, so no row remains an unexplained "maybe this should be wired" item.

Disposition policy is deliberately conservative:

- [x] UI/voice surfaces remain product/UI work, not trading authority.
- [x] Explicit test hooks remain test-only.
- [x] Lab/backtest functions remain research/background unless promoted through a reviewed canonical contract.
- [x] Network/database/provider helpers remain bounded/background or behind provider authority.
- [x] Explicit setters/resetters/recovery controls are not auto-called merely to remove dead-code warnings.
- [x] Readbacks/predicates/advisories are not promoted into independent votes.
- [x] Truth-layer zero-caller declarations remain internal/compatibility/legacy surfaces; fabricate no parallel authority.
- [x] Strategy/V3/V4/perps zero-caller internals remain owned by their canonical subsystem; direct wiring requires proof of distinct evidence and non-duplication.
- [x] Remaining legacy zero-caller declarations are **DO_NOT_AUTOWIRE**: retain for explicit redesign/removal or delete in a later cleanup pass.
- [x] **F_DEAD unresolved count: 0 / 1,458.**
- [x] Complete disposition count: **1,458 / 1,458 classified.**
- [x] Regression coverage: `Aate7605FDeadDispositionCompletenessTest`.

Disposition totals generated from the ledger:

- BACKGROUND_IO_PROVIDER_HELPER: 44
- EXPLICIT_CONTROL_MAINTENANCE: 105
- LAB_BACKTEST_BACKGROUND: 10
- LEGACY_ZERO_CALLER_DO_NOT_AUTOWIRE: 308
- PRODUCT_UI_VOICE_DEAD: 42
- READBACK_PREDICATE_ADVISORY: 523
- RUNTIME_QUANT_LEARNING_UTILITY: 17
- STRATEGY_INTERNAL_ZERO_CALLER: 94
- TEST_HOOK_ONLY: 21
- TRUTH_INTERNAL_ZERO_CALLER: 294


## V5.0.7606 — runtime forward-progress repair from 7586 field log

Field evidence from build 5.0.7586 showed two concrete worker-starvation mechanisms:

- held supervisor: `held=101 fresh=4 staleRefresh=1 missing=94`;
- held mark worker advanced only 2 marks while risk clock accumulated >23k no-mark reads;
- exit coordinator had stale resets and a >119s `locked_venue` in-flight phase;
- the captured worker stack ended in `BlockingCoroutine.joinBlocking -> runBlocking -> FluidLearning.recordPriceImpact`.

Repairs:

- [x] `HeldHotMarkAuthority7419` no longer uses a queued fixed 2-thread provider executor. It now uses a bounded zero-queue executor so timed-out network calls cannot permanently head-of-line block every later held-mark refresh pass.
- [x] Saturation is fail-fast and counted as `HELD_HOT_PROVIDER_POOL_SATURATED_7606`; the next 750ms pass retries instead of building an unbounded stale queue.
- [x] `FluidLearning.recordPriceImpact` no longer performs `runBlocking` / provider I/O. It consumes `WalletManager.lastKnownSolPrice` cache and fails soft to the existing simulation default.
- [x] No score floor, lane threshold, TP/SL, sizing doctrine, live enablement, or strategy authority changed.
- [x] Regression coverage: `Aate7606RuntimeForwardProgressRepairTest`.


## V5.0.7607 — 12-lane specialist parity + causal intent continuity

Field evidence from 5.0.7586 showed the configured 12-lane specialist set was not receiving equivalent routing opportunity:

- BLUECHIP/CORE/MOONSHOT/CASHGEN had meaningful candidate/FDG traffic.
- QUALITY and PROJECT_SNIPER had partial causal progress.
- SHITCOIN/CYCLIC/EXPRESS/MANIPULATED/TREASURY were effectively absent from qualified ownership flow.
- DIP_HUNTER showed only an orphan mark reject.
- Capital headroom was available across all lanes, so starvation was upstream of capital allocation.

Repairs:

- [x] `TokenMetricStageRouter.laneFit()` now reuses `isEstablished7306()` instead of the obsolete `ageMin >= 60` predicate. A token selected as established by source/scale can no longer be rejected one line later because it has not sat on the watchlist for an hour.
- [x] `ExecutableOpenGate.registerCanonicalIntent6554()` mirrors the immutable canonical `ExecutionIntent` into the specialist causal funnel as `BUY_INTENT`. This closes telemetry-only `NO_INTENT` phantoms for any of the 12 lanes without creating a new decision or execution authority.
- [x] The mirror is restricted to the canonical 12 meme specialists: QUALITY, BLUECHIP, SHITCOIN, CYCLIC, EXPRESS, CORE, MOONSHOT, PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, TREASURY, CASHGEN.
- [x] No lane threshold, score floor, TP/SL, sizing multiplier, allocation, FDG decision, or execution safety rule changed.
- [x] Regression coverage: `Aate7607TwelveLaneParityRepairTest`.


## V5.0.7608 — 12-lane native-brain liveness visibility

- [x] Each configured meme specialist now reports native brain called/allow/reject/error counts in the runtime liveness row.
- [x] Each row includes latest native eligible/score/confidence/reason.
- [x] This distinguishes native-strategy rejection from router/election/causal starvation without relaxing any thresholds.
- [x] No trading decision, size, TP/SL, allocation, FDG or execution authority changed.
- [x] Regression coverage: `Aate7608TwelveLaneNativeLivenessTelemetryTest`.


## V5.0.7609 — specialist architecture-state visibility

- [x] Role-liveness rows now distinguish native rejection from live quarantine, buyer disablement and ownership aliasing.
- [x] MANIPULATED reports its real buyer-enabled flag rather than merely runtimeAlive.
- [x] CASHGEN reports `ownershipModel=TREASURY_SHARED_EXEC_ALIAS` until an independent canonical execution path exists.
- [x] Live quarantine state is shown per lane from `LaneQuarantineController`.
- [x] Read-only diagnostics only; no entry permission, sizing, FDG, TP/SL or execution behaviour changed.
- [x] Regression coverage: `Aate7609SpecialistArchitectureStateTest`.


## V5.0.7610 — remove first-N specialist lane starvation

5.0.7607 field evidence: 12 native specialist brains were alive/qualifying, but active lane evaluation still showed only eight lanes and `FANOUT_LANE_EVAL_CAPPED_6835=147`.

Root cause: `IntakeFanoutGovernor6835` still enforced `LANE_EVAL_CAP=2` as **two distinct lanes total per candidate causal root**. The first two callers spent the entire lane budget. This is the same caller-order starvation class already repaired for FDG in 7265.

- [x] Lane fanout budget is now keyed by `mint + causalRoot + lane`.
- [x] Each lane may perform at most two active evaluations per burst, with the existing refill cadence.
- [x] One specialist can no longer consume another specialist's evaluation allowance.
- [x] Per-lane cap counters are emitted as `FANOUT_LANE_EVAL_CAPPED_6835_<LANE>`.
- [x] No score floor, strategy threshold, TP/SL, sizing multiplier, capital allocation, or safety gate changed.
- [x] Regression coverage: `Aate7610PerLaneFanoutFairnessTest`.


## V5.0.7611 — all-lane TokenState → canonical entry-mark continuity

5.0.7607 runtime evidence:
- `VALID_SOURCE_NO_EXECUTABLE_MARK=557`
- `EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613=540`
- specialist mark failures affected BLUECHIP, SHITCOIN, CYCLIC, CORE, MOONSHOT, PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, TREASURY and CASHGEN.
- held-position mark coverage was already healthy, proving this is specifically an ENTRY mark continuity defect.

Repair:
- [x] The final `canOpenExecutablePosition(TokenState,...)` boundary now republishes the already-observed TokenState price/pool/source/timestamp through `CanonicalPriceMarkRegistry6522.resolveBestSourceEvidence6734` when the canonical entry mark is absent.
- [x] Uses the original `lastPriceUpdate`; zero/stale timestamps are never rewritten to current time.
- [x] Uses canonical pool/source/quote/liquidity fields already present on TokenState/TokenMap.
- [x] Existing canonical mark integrity remains authoritative: identity, provider provenance, sentinel filtering, freshness, route and liquidity checks are unchanged.
- [x] PAPER retains observation-mark fallback; LIVE still requires strict `EXECUTABLE_ENTRY_QUOTE`.
- [x] No lane threshold, score floor, TP/SL, sizing multiplier or capital allocation changed.
- [x] Regression coverage: `Aate7611TokenStateEntryMarkContinuityTest`.


## V5.0.7612 — specialist intent ownership reclaim from trunk placeholders

5.0.7607 field evidence exposed an authority contradiction on the same candidate:
- FDG emitted `lane=TREASURY ... FDG_ALLOW`.
- The immediately reused immutable intent reported `lane=STANDARD`.
- Several specialists therefore showed BUY_INTENT activity while `ownerSelected=0`.

Repair:
- [x] A same-version live non-specialist placeholder may be replaced by a canonical specialist intent.
- [x] The 12 specialist owner lanes are QUALITY, BLUECHIP, SHITCOIN, CYCLIC, EXPRESS, CORE, MOONSHOT, PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, TREASURY and CASHGEN.
- [x] A real specialist can never replace another real specialist in this repair.
- [x] The superseded placeholder ticket is removed from the ticket map.
- [x] Canonical specialist intent registration mirrors `OWNER_SELECTED` before `BUY_INTENT`.
- [x] No score threshold, FDG decision, sizing, TP/SL, allocation, safety or route rule changed.
- [x] Regression coverage: `Aate7612SpecialistIntentOwnershipReclaimTest`.


## V5.0.7613 — specialist sealed-ticket stage continuity

5.0.7607 field evidence:
- global `EXEC_TICKET_CREATED=10`;
- every one of the 12 specialist rows reported `ticketN=0`;
- downstream raw EXEC/OPEN existed for PROJECT_SNIPER and DIP_HUNTER, proving the missing specialist ticket stage was instrumentation drift, not absence of tickets.

Repair:
- [x] The canonical `publishTicket()` authority now mirrors `TICKET` into the specialist causal funnel.
- [x] Only already-created immutable tickets are mirrored; this path cannot create or authorize a ticket.
- [x] Lane comes from canonical ticket ownership and is normalized through `CanonicalLaneIdentity6506`.
- [x] Restricted to the canonical 12 specialist lanes.
- [x] No FDG, mark, sizing, safety, route, TP/SL or allocation rule changed.
- [x] Regression coverage: `Aate7613SpecialistTicketContinuityTest`.


## V5.0.7614 — CASHGEN canonical execution identity restored

5.0.7607 proved CASHGEN was not a real independent lane despite its canonical contracts:
- CASHGEN qualified 329 candidates but printed `alias=TREASURY_CASHGEN_SHARED_EXEC no_fdg=true`.
- LaneHunter CASHGEN ownership was explicitly rewritten to TREASURY.
- Canonical lane identity, execution-book and learning contracts define CASHGEN as distinct.

Repair:
- [x] CASHGEN LaneHunter ownership is no longer rewritten to TREASURY.
- [x] `compounderLane7614` selects TREASURY or CASHGEN while reusing one cashflow implementation.
- [x] The selected lane flows through permit precheck, Toolkit bridge, FDG, TradeAuthorizer book, sealed attempt, final execution permit, failure release and position tradingMode.
- [x] CASHGEN therefore gains its own canonical owner → FDG → intent → ticket → executor attribution.
- [x] Treasury-style position mechanics remain shared; economic execution is not duplicated.
- [x] No threshold, TP/SL, sizing multiplier, capital allocation or hard safety changed.
- [x] Regression coverage: `Aate7614CashgenCanonicalExecutionIdentityTest`.


## V5.0.7615 — MANIPULATED paper execution restored, live safeguard retained

The all-12-lane directive supersedes the old assumption that MANIPULATED should be inert everywhere. Source audit showed the complete canonical MANIPULATED execution path still exists; one constant disabled it after its native brain evaluated.

- [x] PAPER MANIPULATED candidates may now proceed through their existing FDG → MANIPULATED TradeAuthorizer book → sealed attempt → executor → position → learning path.
- [x] LIVE manipulation buying remains disabled by the existing 7395 real-money safeguard.
- [x] The native ManipulatedTraderAI eligibility/scoring, safety, FDG, sizing and finality rules remain unchanged.
- [x] No generic candidate is relabelled MANIPULATED; its own brain must emit `shouldEnter`.
- [x] Regression coverage: `Aate7615ManipulatedPaperExecutionRestorationTest`.


## V5.0.7616 — explicit 12-lane execution parity contract

After the 7614 CASHGEN identity repair and 7615 MANIPULATED PAPER restoration, the static contracts are pinned to the runtime architecture:

- [x] CASHGEN role-liveness reports `ownershipModel=SELF`; the obsolete Treasury alias diagnostic is retired.
- [x] CYCLIC and CASHGEN are explicit in `LaneExecutionCoordinator.lanePriority` at the same value (50) they previously inherited from the unknown-lane default. This is behavior-preserving but removes hidden default dependency.
- [x] CI requires the canonical 12 to exist in SpecialistBrainBridge, MemeOwnershipInvariant, Toolkit specialist registry and TradeAuthorizer.
- [x] MANIPULATED remains PAPER-executable / LIVE-disabled under the 7615 safety contract.
- [x] No lane score threshold, FDG rule, mark rule, sizing multiplier, TP/SL, allocation or hard safety changed.
- [x] Regression coverage: `Aate7616TwelveLaneExecutionParityContractTest`.


## V5.0.7617 — finalized learning backlog catch-up

5.0.7607 runtime evidence:
- canonical CLOSED = 3,113;
- finalized bus published = 330;
- missing = 2,783, of which 2,782 were `BUS_PUBLISH_FAILED`;
- `FINALIZED_BUS_DURABLE_REPAIR_7459=128` after four full reconciler passes, exactly the prior 32-row batch ceiling.

Repair:
- [x] Background durable-bus repair batch ceiling increased from 32 to 128.
- [x] Existing `maxWorkMs7514=2,500ms` remains unchanged, so the repair cannot extend its wall-clock background slice.
- [x] Reconciliation remains on `IndependentReconcilerScheduler6431` / Dispatchers.IO, never the bot loop or exit worker.
- [x] No eligibility is fabricated: only rows already classified `BUS_PUBLISH_FAILED` with durable terminal economics can publish.
- [x] Non-trainable historical terminals stay non-trainable under existing 7521/7526 rules.
- [x] No entry, FDG, sizing, exit, lane or safety behavior changed.
- [x] Regression coverage: `Aate7617FinalizedLearningCatchupBudgetTest`.


## V5.0.7618 — CASHGEN/TREASURY structural-proof parity

- [x] A CASHGEN canonical primary now runs the same `cashGenProofOk()` structural proof as TREASURY.
- [x] Both require real route price evidence, no hard safety block, and scalp-executable liquidity before owner execution.
- [x] This closes the post-7614 asymmetry where CASHGEN gained self ownership but could skip the proof protecting the shared cashflow executor.
- [x] No threshold value changed.
- [x] Regression coverage: `Aate7618CashgenTreasuryProofParityTest`.


## V5.0.7619 — fresh specialist election uses learned/fair authority

Source audit after 5.0.7607 exposed a dormant authority path:

- `LaneExecutionCoordinator` already computes lane affinity, realised-expectancy priority and recent-win fairness.
- `pickFreshPrimary()` had **zero production callers**.
- Fresh `elect()` still selected `preferred ?: clean.firstOrNull()`, making caller/list order an undeclared owner-selection authority.

Repair:

- [x] Explicit valid `preferred` remains authoritative when deliberately supplied.
- [x] Otherwise, fresh specialist ownership now uses existing `pickFreshPrimary()` rather than list insertion order.
- [x] Secondary telemetry lane uses the same priority/fairness model over the remaining qualified lanes.
- [x] Sealed FDG ownership remains authoritative and is not re-elected.
- [x] No lane score threshold, FDG rule, mark rule, sizing multiplier, TP/SL, allocation or safety gate changed.
- [x] Regression coverage: `Aate7619LearnedFairFreshElectionTest`.


## V5.0.7620 — pre-FDG specialist contest uses qualified/fair election

7619 activated the dormant learned/fair selector in `elect()`, then call-site audit found two more dormant pieces:

- `qualifiedLanesFor()` had zero callers.
- `recordPrimaryWin()` had zero callers.
- `canRequestExecution()` pre-FDG compatibility still called `elect(listOf(laneUpper), preferred=laneUpper)`, so a single caller constructed a one-lane contest and necessarily won.

Repair:

- [x] Pre-FDG claims now build a contest from registered/registry affinity plus the requesting lane.
- [x] Observer/non-owner lanes are excluded from that contest.
- [x] No explicit preferred lane is fabricated pre-FDG; existing learned expectancy + affinity + fairness chooses among qualified specialists.
- [x] A newly sealed primary records an ownership win so the existing recent-win fairness decay finally has runtime state.
- [x] Sealed FDG ownership remains authoritative and still replaces any pre-seal owner.
- [x] No score floor, lane strategy threshold, FDG rule, mark rule, sizing multiplier, TP/SL, allocation or hard safety changed.
- [x] Regression coverage: `Aate7620QualifiedPreFdgElectionParityTest`.


## V5.0.7621 — candidate-specific native-qualified election set

Post-7620 source audit found one remaining ownership ambiguity: the fair pre-FDG contest was built from persistent scanner/registry affinity plus the requesting lane. Affinity is only a hint and can be stale/incomplete; it is not the same thing as the specialists whose native brains qualified this exact candidate.

- [x] `ToolkitSignalSheet` publishes the exact current `deskHypotheses.keys` set for `mint + candidateVersion`.
- [x] `LaneExecutionCoordinator` stores that set with candidate-TTL semantics and **replaces**, rather than accumulates, the set for the generation.
- [x] Pre-FDG election prefers this exact native-qualified set when available.
- [x] Scanner/source affinity remains a bootstrap fallback only when no candidate-qualified set has been published yet.
- [x] Observer/non-owner lanes remain excluded.
- [x] Sealed FDG ownership remains immutable authority.
- [x] No lane threshold, scoring rule, FDG verdict rule, mark rule, sizing multiplier, TP/SL, allocation or safety gate changed.
- [x] Regression coverage: `Aate7621CandidateQualifiedElectionSetTest`.


## V5.0.7622 — pin Toolkit qualification + causal funnel to one candidate generation

Post-7621 audit found a same-evaluation generation race: `ToolkitSignalSheet` called `candidateVersionFor(mint)` once when publishing the native-qualified specialist contest and again when building the causal funnel ID. A 30-second bucket rollover, or an FDG allow latch appearing between the reads, could therefore publish qualification under generation N while POOL/QUALIFIED stages were stamped under generation N+1.

- [x] Resolve `candidateVersionFor(ts.mint)` exactly once for the completed Toolkit desk evaluation.
- [x] Publish `registerQualifiedContest7621` with that pinned version.
- [x] Stamp `causalId6647` with the same pinned version.
- [x] No lane threshold, score, FDG verdict, sizing, TP/SL, allocation or hard-safety rule changed.
- [x] Regression coverage: `Aate7622PinnedToolkitCandidateVersionTest`.


## V5.0.7623 — pin one candidate generation through the full FDG evaluation

The 7622 follow-up sweep found seven independent `candidateVersionFor(ts.mint)` reads inside one FDG evaluation. Those reads fed the verdict cache, fanout root, primary/specialist FDG stamps, brain-contribution causal IDs, AATE strategy context candidateId, and context candidateVersion. A 30-second bucket rollover or an FDG allow latch appearing during the evaluation could therefore split one logical decision across generations.

- [x] Resolve `candidateVersion7623` once at `FinalDecisionGate.evaluate` entry.
- [x] Pass the pinned generation into the FDG cache key helper rather than letting the helper re-read authority.
- [x] Reuse the same generation for fanout, FDG stage stamps, desk contribution IDs and AATE strategy context.
- [x] Existing mutable score/safety/liquidity cache fingerprints remain unchanged.
- [x] No threshold, scoring, sizing, TP/SL, allocation, route or hard-safety policy changed.
- [x] Regression coverage: `Aate7623PinnedFdgCandidateVersionTest`.


## V5.0.7624 — preserve candidate generation through TradeAuthorizer handoff

The post-7623 sweep found a second generation split downstream of FDG. `TradeAuthorizer.authorize` independently resolved candidateVersion for its causal BUY_INTENT ID, then let `canRequestExecution` resolve again, and a blank caller attempt generated `nextAttemptId()` with yet another current-version read. A bucket rollover between those points could encode N+1 in the attempt ID while the immutable lane-election receipt remained on N.

- [x] Pin `candidateVersion7624` once at authorization entry.
- [x] Use it for fallback causal BUY_INTENT/AUTH_REJECT identity.
- [x] Pass it explicitly into `LaneExecutionCoordinator.canRequestExecution`.
- [x] Release fallback uses the same pinned generation.
- [x] `nextAttemptId` now accepts an explicit candidateVersion and TradeAuthorizer supplies the immutable election receipt generation.
- [x] Existing two-argument `nextAttemptId` callers remain source-compatible through the canonical default.
- [x] No threshold, sizing, FDG, exit, allocation, route or safety policy changed.
- [x] Regression coverage: `Aate7624AuthorizerCandidateContinuityTest`.


## V5.0.7625 — preserve candidate generation through FinalExecutionPermit

The execution-finality sweep found the same intra-call generation race in `FinalExecutionPermit.tryAcquireExecution`: release fallback, a generated attempt ID, and the immutable-ticket version comparison could each observe a different current generation.

- [x] Pin `candidateVersion7625` once at permit entry.
- [x] Generated attempt IDs use that pinned version.
- [x] Release fallback uses that pinned version when no ticket is available.
- [x] Immutable ticket validation compares against the generation captured at permit entry, not a later reread.
- [x] No execution eligibility, hard safety, finality, sizing, route or economic rule changed.
- [x] Regression coverage: `Aate7625PermitCandidateContinuityTest`.


## V5.0.7626 — one current-candidate observation per ExecutableOpenGate attempt

The open-gate sweep found three independent current-generation observations inside one `canOpenExecutablePositionInternal` call. The first could create a synthetic LIVE state under generation N, the second could select authority under N+1, and the third could then classify the same state as stale.

- [x] Capture `currentCandidateVersion7626` once at open-gate entry.
- [x] Synthetic LIVE state uses the pinned generation.
- [x] Authority fallback uses the same pinned generation.
- [x] The intentional stale-candidate comparison uses that same attempt-entry observation.
- [x] Immutable elected/ticket/snapshot generations still take precedence where present.
- [x] No FDG threshold, lane policy, sizing, route, exit or safety rule changed.
- [x] Regression coverage: `Aate7626OpenGateCandidateContinuityTest`.


## V5.0.7627 — bind strategy-hypothesis decision stamp to FDG generation

After execution-path generation continuity was repaired through 7626, the learning audit found `StrategyHypothesisEngine.getSizeBias` re-reading `candidateVersionFor(mint)` when storing `pendingByDecision7428`. That could stamp the exact strategy hypothesis under N+1 while the FDG decision and eventual position belonged to N.

- [x] `getSizeBias` accepts an explicit candidateVersion, preserving source compatibility with a canonical default.
- [x] FDG passes its already-pinned `candidateVersion7623`.
- [x] `pendingByDecision7428` is keyed by the exact FDG decision generation.
- [x] No hypothesis scoring/bias value, promotion logic, thresholds, sizing bounds or execution policy changed.
- [x] Regression coverage: `Aate7627HypothesisCandidateContinuityTest`.


## V5.0.7628 — final permit inherits sealed ticket generation

A remaining false-stale path survived 7625: when `attemptId` already identifies an immutable ticket on generation N, `FinalExecutionPermit.tryAcquireExecution` must not resample the current 30-second candidate clock and compare that ticket against N+1.

- [x] Existing attempt ticket generation is authoritative for permit finality.
- [x] Current candidate authority is consulted only when no immutable ticket generation exists.
- [x] Fallback attempt creation, release bookkeeping and ticket validation reuse the inherited generation.
- [x] No threshold, scoring, FDG, sizing, TP/SL, allocation, route or hard-safety policy changed.
- [x] Regression coverage: `Aate7628PermitTicketGenerationContinuityTest`.


## V5.0.7629 — restore ART-verifier-safe FDG structure

Runtime Smoke Test run 36841737881 proved the current debug APK compiled but crashed during MainActivity startup with `java.lang.VerifyError` while ART verified `FinalDecisionGate.evaluate` on the API-30 emulator. The later `btnToggle` miss and `NO_WINDOW_START` were downstream symptoms of the process death.

This is the same structural failure class previously repaired in 7417; the helper extraction had since regressed out while `evaluate` continued accumulating locals and branches.

- [x] Move the current 7232/7265 fanout-cap branch into `fanoutCapVerdict7629`.
- [x] Preserve the exact per-lane fanout key, counters and blocked verdict.
- [x] Reuse the pinned `candidateVersion7623`; no candidate-generation reread is introduced.
- [x] Remove the fanout locals/branch tree from the giant `evaluate` bytecode to reduce ART register pressure.
- [x] No threshold, scoring, FDG policy, sizing, TP/SL, allocation, route or safety behavior changed.
- [x] Regression coverage: `Aate7629FinalDecisionGateVerifierStructureTest`.


## V5.0.7630 — restore LiquidityFragilityAI production evidence feed

The F_DEAD strategy-internal re-audit found a source contradiction: `SymbolicExitReasoner` and `TradeLessonRecorder` actively consume `LiquidityFragilityAI`, and comments claimed it was fed at a BotService safety-commit site, but current source contains **zero production callers** of `LiquidityFragilityAI.analyze()`. Its report map therefore remained empty and readers mostly consumed the same default fragility for every meme token.

- [x] `ToolkitSignalSheet.build` now feeds the V4 fragility brain on its existing side-effect refresh, never on the scanner caller thread.
- [x] Evidence is cached/in-memory only: canonical liquidity, resolved holder concentration, canonical token age, latest real 24h candle volume and real OHLC upper-wick history.
- [x] Unknown spread, recent execution slippage, price impact, liquidation distance and failed-breakout evidence remain neutral/default; no values are fabricated.
- [x] Reports remain mint-first so duplicate meme tickers cannot cross-contaminate each other.
- [x] Existing consumers become data-bearing; no new independent hard veto or entry gate was added.
- [x] Runtime proof counters: `LIQUIDITY_FRAGILITY_CACHED_FEED_7630`, `LIQUIDITY_FRAGILITY_CACHED_FEED_FAILED_7630`.
- [x] Regression coverage: `Aate7630LiquidityFragilityProductionFeedTest`.


## V5.0.7631 — restore Moonshot 10x collective-winner feed

The strategy-internal zero-caller sweep found `MoonshotTraderAI.recordCollectiveWinner` dead while `scoreMoonshot` actively reads `collectiveWinners` for both the collective bonus and JUPITER mode. The hive already downloads peer `NetworkSignal` rows containing exact mint, sanitized raw PnL, confidence and broadcaster identity.

- [x] Existing off-thread `CollectiveIntelligenceAI.refreshNetworkSignals` feeds Moonshot collective memory only when raw peer `pnlPct >= 900%` (canonical 10x threshold).
- [x] The coarse `MEGA_WINNER` label is **not** used as the condition because it starts at only +50%.
- [x] `networkTraders=1` records only the guaranteed broadcaster; ack count is not misrepresented as unique traders.
- [x] Missing peer entry market cap remains explicit UNKNOWN (`0.0`) and is not used as scoring evidence.
- [x] For duplicate peer rows on one mint, the strongest raw PnL is retained instead of a later weaker row overwriting it.
- [x] Existing 24h Moonshot collective-memory cleanup now runs on the already-scheduled network refresh.
- [x] No new network call, hard veto, threshold reduction, sizing bypass or execution authority was added.
- [x] Runtime proof: `MOONSHOT_COLLECTIVE_10X_FEED_7631`.
- [x] Regression coverage: `Aate7631MoonshotCollectiveWinnerFeedTest`.


## V5.0.7632 — Super SSI consensus/uncertainty fusion

The intelligence stack already had many useful bounded opinions, but the oracle still combined that tier primarily by arithmetic sum. Five brains agreeing +4 each and five brains split +20/-20 were both reduced to a number without explicitly representing epistemic disagreement.

- [x] Added `SuperSsiFusion7632`, a pure in-memory meta-intelligence transform over the **existing** brain-network reads.
- [x] It reports positive/negative evidence mass, breadth, directional agreement, disagreement/uncertainty and fused delta.
- [x] Strong directional agreement preserves nearly all existing bounded influence.
- [x] Contradictory brains are attenuated rather than blindly summed; fusion can never amplify the magnitude beyond the raw opinion sum.
- [x] Recorded creator-rug facts remain outside opinion attenuation and retain their existing safety treatment.
- [x] No provider I/O, LLM call, new hard veto, new execution authority, threshold reduction, sizing bypass or safety weakening was added.
- [x] Runtime proof: `SUPER_SSI_FUSION_7632`, `SUPER_SSI_CONSENSUS_7632`, `SUPER_SSI_CONFLICT_7632`.
- [x] Regression coverage: `Aate7632SuperSsiFusionTest`.


## V5.0.7633 — Super Intelligence counterfactual planner

The post-7632 architecture moves beyond additive scoring. AATE now has an explicit advisory planner that evaluates alternative actions against the already-fused predicted distribution rather than reducing intelligence to one scalar.

- [x] Added `SuperIntelligencePlanner7633` with a bounded action lattice: WAIT, ENTER_REDUCED, ENTER_BASE, ENTER_CONVICTION.
- [x] Each action receives risk-adjusted expected utility from predicted expectancy, win probability, confidence, policy-head agreement and epistemic disagreement.
- [x] High disagreement and low confidence increase uncertainty cost instead of pretending all models are equally certain.
- [x] Hard safety facts force the planner recommendation to WAIT but remain owned by existing safety authorities.
- [x] Planner output is appended to oracle forensic contributions and is **advisory-only**; it has no execution, capital, sizing or veto authority in this build.
- [x] No provider I/O, LLM call, hot-path network work, threshold reduction, safety weakening or execution bypass was added.
- [x] Runtime proof: `SUPER_INTELLIGENCE_PLAN_7633`.
- [x] Regression coverage: `Aate7633SuperIntelligencePlannerTest`.


## V5.0.7634 - hierarchical multi-horizon Super World Model

The Super-SSI stack now moves from scalar scoring to explicit trajectory reasoning.

- [x] Added SuperWorldModel7634 with IMPULSE (~30s), TACTICAL (~5m) and THESIS (~30m) forecasts.
- [x] Each horizon carries P(win), expected PnL, failure risk, rug risk, dispersion, epistemic uncertainty and risk-adjusted utility.
- [x] Existing learned evidence is ensembled from ForwardOutcomeModel, LiveProbabilityEngine and the oracle current-candidate estimate; no new provider or hot-path network dependency exists.
- [x] Lane doctrine is horizon-aware: runner lanes may preserve thesis upside while short-horizon scalp lanes naturally decay over long holds.
- [x] Regime context shapes horizon projections without inventing a new hard veto.
- [x] The world model derives a latent state: ACCELERATING, TRENDING, MEAN_REVERTING, DISTRIBUTING, FRAGILE or UNCERTAIN.
- [x] SuperIntelligencePlanner7633 now evaluates reduced/base/conviction actions against the appropriate horizon rather than one scalar expectation.
- [x] Conviction entry receives an explicit penalty when the modeled trajectory slopes down.
- [x] World-model output is forensic/advisory only; existing safety, oracle, admission, sizing and execution authorities remain canonical.
- [x] Regression coverage: Aate7634SuperWorldModelTest.


## V5.0.7635 - adversarial scenario critic

The Super Intelligence stack now red-teams its own world model before the planner trusts conviction.

- [x] Added SuperAdversarialCritic7635 with explicit BULL, BASE and BEAR scenarios.
- [x] The critic measures cross-horizon contradictions, thesis fragility, scenario spread, worst-case utility and epistemic risk.
- [x] High fragility produces a bounded conviction penalty rather than a new independent veto.
- [x] The planner applies the critic strongest to conviction entries, less to base entries, and only lightly to reduced entries.
- [x] Existing hard safety remains independent and authoritative.
- [x] No provider I/O, LLM call, new execution path, sizing bypass or hard gate was added.
- [x] Regression coverage: Aate7635SuperAdversarialCriticTest.


## V5.0.7636 - causal self-calibration for Super Intelligence

The world model, critic and planner are now graded against exact canonical terminal outcomes instead of remaining unvalidated advisory traces.

- [x] Decision-time world/critic/plan state is stamped by mint + canonical owner lane.
- [x] CanonicalPositionAuthority binds that exact decision snapshot to the immutable positionId at OPEN.
- [x] The canonical finalized trade bus delivers settled economics to SuperIntelligenceCalibration7636.
- [x] Terminal grading is position-bound; later same-mint evaluations cannot steal outcome credit.
- [x] The horizon nearest the actual holding duration is graded, so short trades test IMPULSE and longer holds test TACTICAL/THESIS.
- [x] Calibration records Brier error, expected-PnL absolute error, direction accuracy, realized return and latent-state performance.
- [x] Missing historical predictions ACK as no-op with explicit telemetry rather than causing infinite redelivery.
- [x] No execution authority, provider I/O, threshold reduction or safety weakening was added.
- [x] Regression coverage: Aate7636SuperIntelligenceCalibrationTest.


## V5.0.7637 - persistent adaptive trust for world-model horizons

Super Intelligence now learns which of its own horizon models deserve confidence.

- [x] Calibration statistics persist across process restarts through LearningPersistence.
- [x] IMPULSE, TACTICAL and THESIS each earn an independent bounded reliability multiplier.
- [x] Reliability remains neutral until at least 8 exact position-bound outcomes exist for that horizon.
- [x] Brier calibration and directional accuracy determine trust; poor calibration increases world-model uncertainty rather than silently rewriting expected returns.
- [x] A well-calibrated horizon may reduce uncertainty modestly, capped at 1.20x trust; a poor horizon may be damped to 0.60x.
- [x] This modifies model confidence only; it does not grant execution, hard-veto or safety authority.
- [x] Regression coverage: Aate7637AdaptiveModelTrustTest.


## V5.0.7638 - retrieval-augmented policy-tree search

Super Intelligence now reasons from retrieved experience and searches multi-step policies instead of only one-shot actions.

- [x] Added SuperEpisodicRetriever7638: exact playbook evidence is O(1), with bounded cached local semantic-memory fallback when exact history is absent.
- [x] Retrieval carries exact sample size, WR, EV, semantic size/score bias, confidence and a bounded utility prior.
- [x] Added SuperPolicyTree7638 with WAIT_REASSESS, REDUCED_THEN_SCALE, BASE_TACTICAL_HOLD, BASE_TACTICAL_BANK and CONVICTION_RUNNER branches.
- [x] Tree utility combines horizon-specific world forecasts, adversarial fragility, failure risk and retrieved episodic evidence.
- [x] The planner consumes only a bounded tree-search bias; the tree remains advisory and cannot execute or reserve capital.
- [x] No provider I/O, LLM call, new hard veto, safety weakening or duplicate execution authority was added.
- [x] Regression coverage: Aate7638RetrievalTreeSearchTest.


## V5.0.7639 - recursive reasoning arbitration

Super Intelligence now performs model-of-models arbitration instead of assigning fixed trust to every reasoning mechanism.

- [x] Added SuperReasoningArbiter7639 over world-model coherence, critic confidence/fragility, episodic-memory confidence, policy-tree confidence and learned horizon calibration.
- [x] The arbiter identifies which reasoning family is dominant for the current candidate and emits meta-confidence.
- [x] Critic and tree influence in the planner are dynamically weighted by arbitration rather than permanently fixed.
- [x] Horizon calibration from exact settled outcomes contributes to world-model trust.
- [x] Arbitration only reweights existing bounded reasoning; it owns no execution, capital, provider, safety or hard-veto authority.
- [x] Regression coverage: Aate7639RecursiveReasoningArbiterTest.


## V5.0.7640 - reasoning failure attribution

Super Intelligence now learns not only that a prediction was wrong, but which reasoning subsystem most likely failed.

- [x] Entry-time calibration snapshot now retains tree policy, tree confidence, arbiter dominant mechanism and arbiter meta-confidence alongside world/critic/plan state.
- [x] Exact position-bound terminal outcomes classify reasoning misses into latent-state, horizon-calibration, critic, tree-policy and model-overtrust failure families.
- [x] Examples include LATENT_STATE_OVERBULLISH, CRITIC_TOO_WEAK, TREE_CONVICTION_POLICY_WRONG, MEMORY_OVERTRUST and TREE_OVERTRUST.
- [x] Correct directional calls are explicitly labelled REASONING_OK.
- [x] Failure-mode counts surface in SuperIntelligenceCalibration status for later meta-learning.
- [x] No new execution authority, provider I/O, hard veto, sizing bypass or safety weakening was added.
- [x] Regression coverage: Aate7640ReasoningFailureAttributionTest.


## V5.0.7641 - contextual reasoning self-repair

Reasoning failure attribution now feeds back into lane-local model trust.

- [x] Each lane tracks exact settled reasoning outcomes and attributable failure families.
- [x] WORLD, CRITIC, MEMORY and TREE receive separate lane-local trust multipliers.
- [x] Trust remains neutral until at least 8 exact position-bound outcomes exist.
- [x] A repeated MEMORY_OVERTRUST pattern reduces only memory influence for that lane; critic/tree/world are unaffected unless their own failure modes accumulate.
- [x] Trust can recover automatically because miss rate is measured against the growing exact outcome denominator.
- [x] Reasoning failure/outcome memory persists across restart.
- [x] SuperReasoningArbiter7639 now consumes these learned trust multipliers before weighting its internal reasoners.
- [x] No independent execution, hard-veto, provider, capital or safety authority was added.
- [x] Regression coverage: Aate7641ReasoningSelfRepairTest.


## V5.0.7642 - autonomous reasoning reflection and hypothesis generation

Repeated reasoning failures now create their own bounded repair hypotheses for shadow validation.

- [x] SuperReflectionLoop7642 observes exact position-bound reasoning failure classifications from the canonical terminal calibration loop.
- [x] A failure family must repeat at least three times and passes a 30-minute per-lane/failure cooldown before proposing work.
- [x] MEMORY, TREE, CRITIC and WORLD/HORIZON failure families produce distinct bounded repair hypotheses.
- [x] Every proposal is dispatched off the hot path and must pass MultiAgentCriticStack skeptic + symbolic review before entering AsyncStrategyLab.
- [x] Proposals specify an expected metric and rollback condition.
- [x] Reflection cannot directly modify live execution, thresholds, capital, safety or trade authority.
- [x] Regression coverage: Aate7642AutonomousReflectionTest.


## V5.0.7643 - distributional imagination rollouts and CVaR policy search

Policy search now reasons over outcome distributions rather than one average future.

- [x] Added SuperImaginationRollout7643 with deterministic imagined trajectories per policy.
- [x] Rollouts derive from world-model dispersion, epistemic uncertainty, disagreement, failure risk, adversarial fragility, episodic prior and runner-tail opportunity.
- [x] Each policy receives mean utility, median utility, downside P10, downside CVaR, upside P90 and failure probability.
- [x] SuperPolicyTree7638 ranks branches on robust distributional utility, so a high average cannot hide a catastrophic lower tail.
- [x] WAIT remains zero-exposure baseline; no synthetic rollout can itself authorize execution.
- [x] No provider I/O, LLM call, random network dependency, hard-veto authority or safety weakening was added.
- [x] Runtime proof: SUPER_IMAGINATION_TREE_SEARCH_7643.
- [x] Regression coverage: Aate7643DistributionalImaginationTest.


## V5.0.7644 - contextual policy bandit over multi-step plans

The planner now learns which policy branch actually works in each lane + latent-state context.

- [x] Added SuperPolicyBandit7644 keyed by lane x world latent-state x tree policy.
- [x] Exact position-bound terminal outcomes train the selected policy once.
- [x] Policy priors blend realised mean return, win rate and a small uncertainty/exploration bonus.
- [x] Priors are neutral until at least 3 outcomes and are hard-clamped to +/-8 utility points.
- [x] SuperPolicyTree7638 adds the learned contextual prior before distributional imagination/CVaR ranking.
- [x] Policy learning persists across restart.
- [x] This learns planning strategy, not execution authority; safety and canonical execution remain unchanged.
- [x] Regression coverage: Aate7644ContextualPolicyBanditTest.


## V5.0.7645 - repair reflection loop CI expression-body false positive

- [x] `SuperReflectionLoop7642.repairShape` converted from expression-body `= when` to an explicit block-body function.
- [x] Behaviour is unchanged; this only prevents the repository expression-body return guard from misreading string literals containing the word `return`.
- [x] No intelligence, execution, safety, sizing, threshold or learning semantics changed.


## V5.0.7646 - adaptive deliberation and dynamic compute allocation

Super Intelligence now spends deeper local reasoning only when a candidate warrants it.

- [x] Added SuperDeliberationController7646 combining novelty, epistemic uncertainty, downside risk, critic fragility and upside optionality.
- [x] Deliberation depth ranges from 1 to 5 and deterministic imagination rollouts from 7 to 21.
- [x] Familiar low-conflict candidates remain shallow; novel/conflicted/high-risk/high-opportunity candidates receive deeper recursive lookahead.
- [x] SuperPolicyTree7638 adds discounted continuation value across the selected reasoning depth.
- [x] SuperImaginationRollout7643 accepts a bounded dynamic rollout budget rather than always running one fixed ensemble.
- [x] No network I/O, LLM call, execution authority, hard veto or safety weakening was added.
- [x] Regression coverage: Aate7646AdaptiveDeliberationTest.


## V5.0.7647 - propensity-aware causal policy evaluation

The planner now distinguishes raw policy performance from selection-biased estimated policy lift.

- [x] SuperPolicyTree7638 emits a bounded softmax selection propensity from robust branch utilities.
- [x] The selected propensity is frozen into the position-bound Super Intelligence decision stamp.
- [x] Exact canonical terminal outcomes feed SuperCausalPolicyEvaluator7647.
- [x] The evaluator uses clipped inverse-propensity weighting (max 5x) against the same lane + latent-state baseline.
- [x] Policy lift remains neutral until at least 5 policy outcomes and 10 context outcomes exist.
- [x] Estimated lift is reliability-shrunk and bounded to +/-6 utility points before entering tree search.
- [x] State persists across restart.
- [x] This is propensity-aware observational estimation, not a claim of randomized causal identification; it owns no execution or veto authority.
- [x] Regression coverage: Aate7647CausalPolicyEvaluationTest.


## V5.0.7648 - learned latent-state policy transition model

The planning stack now learns empirical state-transition dynamics from exact position-bound outcomes.

- [x] Added SuperLatentTransitionModel7648 keyed by lane x entry latent-state x selected multi-step policy.
- [x] Exact terminal outcomes classify into RUNNER, PROFIT, SCRATCH, LOSS or CATASTROPHIC and retain mean return/MFE/MAE/hold duration.
- [x] Transition priors are neutral until at least 3 exact outcomes and confidence shrinks by n/(n+10).
- [x] SuperPolicyTree7638 retrieves the transition prior separately for every candidate branch.
- [x] SuperImaginationRollout7643 blends learned loss/catastrophic probability into downside risk and learned runner probability into upside-tail imagination.
- [x] Transition state persists across restart.
- [x] No provider I/O, LLM call, independent execution authority, hard veto or safety weakening was added.
- [x] Regression coverage: Aate7648LatentTransitionModelTest.


## V5.0.7649 - multi-objective goal-conditioned planning

The shared Super Intelligence core now optimizes different economic objectives for different specialist lanes instead of forcing every strategy through one generic utility function.

- [x] Added lane goal profiles for capital preservation, fast turnover, early asymmetry, tail capture, recovery edge, quality compounding, asymmetric momentum and balanced edge.
- [x] Each branch is evaluated across expected return, downside CVaR, failure probability, upside tail, capital velocity and robustness.
- [x] The planner constructs a Pareto frontier so a branch dominated on every objective cannot win merely because one scalar happened to be large.
- [x] Lane-specific goal weights apply only a bounded +/-7 utility nudge on top of existing CVaR/world/critic/causal policy reasoning.
- [x] Learned transition hold duration supplies the capital-velocity objective when enough exact evidence exists.
- [x] No duplicate execution authority, provider I/O, LLM call, hard veto or safety weakening was added.
- [x] Regression coverage: Aate7649GoalConditionedPlanningTest.


## V5.0.7650 - integrate the intelligence AATE already had

The Super Intelligence layer now explicitly consumes existing AATE brains and learning systems instead of building parallel replacements.

- [x] SpecialistBrainBridge7542 exposes a read-only cached snapshot accessor; Super reasoning never re-runs specialist brains.
- [x] ExistingIntelligenceContext7650 consumes cached native specialist opinion, UltimateEdgeEngine, BrainConsensusBridge6329, StrategyHypothesisEngine, AsyncStrategyLab and CounterfactualReplayEngine MCTS.
- [x] The adapter creates no new market signal and performs no provider I/O.
- [x] UltimateEdge and old consensus are deliberately low-weight cross-checks because their underlying evidence overlaps other stack inputs; this prevents double counting.
- [x] Existing strategy hypotheses and symbolically reviewed Lab work contribute bounded policy priors.
- [x] Existing counterfactual replay/MCTS exit intelligence maps into tactical-bank, hold/runner, reduced/wait policy branches.
- [x] SuperPolicyTree7638 consumes the combined existing-stack prior capped to +/-6 utility points.
- [x] No execution, hard-veto, safety or capital authority moved.
- [x] Regression coverage: Aate7650ExistingIntelligenceIntegrationTest.


## V5.0.7651 - evidence ancestry graph and correlation-aware fusion

The integrated Super Intelligence stack now reasons about evidence provenance instead of assuming every adapter output is an independent vote.

- [x] Added `SuperEvidenceTopology7651`, a pure/local evidence ancestry fuser.
- [x] Native specialist opinion, aggregate cross-checks, strategy learning and counterfactual replay are represented as distinct ancestry families.
- [x] `UltimateEdgeEngine` score/size and legacy `BrainConsensusBridge6329` outputs collapse inside one aggregate family instead of triple-counting overlapping lower-level intelligence.
- [x] `StrategyHypothesisEngine` and symbolically reviewed `AsyncStrategyLab` evidence collapse inside one learned-strategy family instead of behaving like independent votes.
- [x] Independent native-specialist and counterfactual evidence can still corroborate policy conviction.
- [x] Cross-family disagreement explicitly attenuates utility; correlated repetition cannot manufacture confidence.
- [x] De-correlation can only preserve or reduce evidence magnitude and remains bounded to +/-6 planner utility points.
- [x] No provider I/O, LLM call, execution, capital, sizing, hard-veto, threshold or safety authority was added.
- [x] Regression coverage: `Aate7651EvidenceTopologyTest`.
