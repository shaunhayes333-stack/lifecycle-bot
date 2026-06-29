# Full Learning System Audit — 2026-06-30

Purpose: identify engines/brains/memory/tuners that are terminal-only, thinly wired, not persisted, or not exposed to enough lifecycle data.

Build context: 4530/4531 made MathematicalEdgeEngine a shared integration surface; 4532 starts moving from terminal-only results to lifecycle event exposure.

## Initial candidate count

- Learning/engine-like candidates found: **193**
- Thin/dormant callsite candidates (<=3 references): **57**
- Have record/learn/stamp/update APIs: **112**
- Persistence-looking engines: **55**

## Highest-risk thinly wired candidates (first pass)

| calls | engine | learn APIs | decision APIs | persisted | async | path |
|---:|---|---|---|---|---|---|
| -1 | `ConfidenceEngine` | `recordExecute, recordNonExecute` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/decision/DecisionEngine.kt` |
| -1 | `EntryAI` | `-` | `score, score, score, score, score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/ScoringModules.kt` |
| -1 | `RugModel` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/risk/FatalRiskChecker.kt` |
| -1 | `SentimentEngine` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/SentimentEngine.kt` |
| -1 | `SherpaTtsBridge` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/SherpaTtsBridge.kt` |
| 0 | `LearningStore` | `recordShadowBlock, recordShadowPass, record` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/learning/LearningStore.kt` |
| 0 | `ExitIntentClassifier` | `-` | `shouldSkipPumpPortalForPartialProfit` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/sell/ExitIntentClassifier.kt` |
| 0 | `FearGreedAI` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/FearGreedAI.kt` |
| 0 | `LaneStrategyEvaluator` | `-` | `evaluateAll` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LaneStrategyEvaluator.kt` |
| 0 | `OperatorChokeButterflyAuditLedger` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorChokeButterflyAuditLedger.kt` |
| 0 | `OperatorChokeSourceContractSentinel` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorChokeSourceContractSentinel.kt` |
| 0 | `OperatorEngineStatusDigest` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorEngineStatusDigest.kt` |
| 0 | `OperatorFinalResidualSourceContractSweep` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorFinalResidualSourceContractSweep.kt` |
| 0 | `OperatorLearningControlDigest` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorLearningControlDigest.kt` |
| 0 | `OperatorSourceContractCloseoutManifest` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorSourceContractCloseoutManifest.kt` |
| 0 | `OperatorSourceMarkerTriageDigest` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorSourceMarkerTriageDigest.kt` |
| 0 | `OperatorWalletPipelineGuardrailContractDigest` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/OperatorWalletPipelineGuardrailContractDigest.kt` |
| 0 | `PatchWriterAI` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/PatchWriterAI.kt` |
| 0 | `SlippageGuard` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/SlippageGuard.kt` |
| 0 | `SmartExitOptimizer` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/SmartExitOptimizer.kt` |
| 1 | `LearningRejectLabelSentinel` | `-` | `-` | False | True | `app/src/main/kotlin/com/lifecyclebot/engine/LearningRejectLabelSentinel.kt` |
| 1 | `LiquidityBucketRouter` | `-` | `getSizeMultiplier` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LiquidityBucketRouter.kt` |
| 1 | `LlmSentimentEngine` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LlmSentimentEngine.kt` |
| 1 | `MemeCrossTalkEntryBridge` | `-` | `shapeLaneEntry` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/MemeCrossTalkEntryBridge.kt` |
| 1 | `PaperExitSweepBudget` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/PaperExitSweepBudget.kt` |
| 1 | `ScannerDiversityBandit` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/ScannerDiversityBandit.kt` |
| 1 | `SocialVelocityAI` | `-` | `score` | False | True | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/SocialVelocityAI.kt` |
| 1 | `TokenSocialScorer` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/TokenSocialScorer.kt` |
| 1 | `UniversalRouteEngine` | `-` | `buildBuyRoute, pickRoute` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/execution/UniversalRouteEngine.kt` |
| 2 | `ArbLearning` | `recordOutcome, learnFromOutcome, loadFromJson` | `-` | True | False | `app/src/main/kotlin/com/lifecyclebot/v3/arb/ArbLearning.kt` |
| 2 | `InsiderCopyEngine` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/InsiderCopyEngine.kt` |
| 2 | `LearningFanoutMuxSentinel` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LearningFanoutMuxSentinel.kt` |
| 2 | `MarketStructureRouter` | `-` | `fluidScoreBonus, scoreMemeModes, scoreMajorModes, scoreMidCapModes, scorePerpModes` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/modes/MarketStructureRouter.kt` |
| 2 | `MetaCognitionExecutorBridge` | `-` | `sizeMultiplierForLane` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/MetaCognitionExecutorBridge.kt` |
| 2 | `PaperLiveIntelligenceBridge` | `-` | `liveSizeMultiplier` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/PaperLiveIntelligenceBridge.kt` |
| 2 | `SecondScorer` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/SecondScorer.kt` |
| 2 | `UnifiedNarrativeAI` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/UnifiedNarrativeAI.kt` |
| 3 | `CopyTradeEngine` | `recordResult` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/CopyTradeEngine.kt` |
| 3 | `ExecutionRouteReliabilityMemory` | `recordFailure` | `sizeMultiplierForSource` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/ExecutionRouteReliabilityMemory.kt` |
| 3 | `RunnerRetentionOptimizer` | `recordTerminalExit` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/RunnerRetentionOptimizer.kt` |
| 3 | `SecurityGuard` | `recordTrade` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/SecurityGuard.kt` |
| 3 | `StrategyTrainingGate` | `shouldTrainStrategy, shouldTrainRouteLayer` | `shouldTrainStrategy, shouldTrainRouteLayer` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/execution/StrategyTrainingGate.kt` |
| 3 | `CatastrophicPaperBleedGuard` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/CatastrophicPaperBleedGuard.kt` |
| 3 | `ExitManager` | `-` | `evaluate, shouldPartialSell` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/ExitManager.kt` |
| 3 | `FlowImbalanceModel` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/arb/FlowImbalanceModel.kt` |
| 3 | `FreeDataSourceRegistry` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/FreeDataSourceRegistry.kt` |
| 3 | `LifecycleStrategy` | `-` | `evaluate, evaluateWithDecision, applyEstablishedTokenScore, volumeScore, pressureScore` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LifecycleStrategy.kt` |
| 3 | `LiquidityExitPathAI` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/LiquidityExitPathAI.kt` |
| 3 | `LiveBreakEvenGuard` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LiveBreakEvenGuard.kt` |
| 3 | `LlmParameterTuner` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LlmParameterTuner.kt` |
| 3 | `MEVDetectionAI` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/MEVDetectionAI.kt` |
| 3 | `PanicReversionModel` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/arb/PanicReversionModel.kt` |
| 3 | `PatternBacktester` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/PatternBacktester.kt` |
| 3 | `RegimeVolatilityExecutorBridge` | `-` | `sizeShape` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/RegimeVolatilityExecutorBridge.kt` |
| 3 | `TreasurySourceRouter` | `-` | `bias` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/TreasurySourceRouter.kt` |
| 3 | `UniversalBridgeEngine` | `-` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/UniversalBridgeEngine.kt` |
| 3 | `VenueLagModel` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/arb/VenueLagModel.kt` |
| 4 | `AIStartupCoordinator` | `initShadowLearning` | `initModeRouter, initMarketStructureRouter` | False | True | `app/src/main/kotlin/com/lifecyclebot/v3/core/AIStartupCoordinator.kt` |
| 4 | `BaseQuoteMintGuard` | `-` | `shouldQuarantine` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/guard/BaseQuoteMintGuard.kt` |
| 4 | `LaneExpectancyDamper` | `-` | `sizeMultiplier` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt` |
| 4 | `LiveStylePivotRouter` | `-` | `route, qualityReleaseMultiplier, scoreBand` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt` |
| 4 | `ModeSpecificExits` | `-` | `shouldEmitFreshTimeoutTelemetry, getExitRecommendation, evaluateFreshLaunchExit, evaluateBreakoutExit, evaluateReversalExit` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/ModeSpecificExits.kt` |
| 4 | `OrderbookImbalancePulseAI` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/OrderbookImbalancePulseAI.kt` |
| 4 | `RuntimeRegressionGuards` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/RuntimeRegressionGuards.kt` |
| 4 | `TokenRefreshPolicy` | `-` | `shouldRefreshDynamic, shouldRefreshDynamic` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/TokenRefreshPolicy.kt` |
| 5 | `CapitalEfficiencyAI` | `recordOutcome` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/CapitalEfficiencyAI.kt` |
| 5 | `MemeLossStreakGuard` | `recordOutcome` | `-` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/MemeLossStreakGuard.kt` |
| 5 | `MultiplierAttributionLedger` | `recordEntry, exportState, importState` | `-` | True | False | `app/src/main/kotlin/com/lifecyclebot/engine/MultiplierAttributionLedger.kt` |
| 5 | `NewsShockAI` | `updateFromPoll` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/NewsShockAI.kt` |
| 5 | `ReflectiveOptimizerGEPA` | `exportState, importState` | `-` | True | False | `app/src/main/kotlin/com/lifecyclebot/engine/ReflectiveOptimizerGEPA.kt` |
| 5 | `RunnerExitShadowLedger` | `recordTerminalExit, exportState, importState` | `-` | True | False | `app/src/main/kotlin/com/lifecyclebot/engine/RunnerExitShadowLedger.kt` |
| 5 | `StablecoinFlowAI` | `updateFromPoll` | `getRegimeBias, score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/StablecoinFlowAI.kt` |
| 5 | `StrategyVariantStore` | `recordOutcome` | `-` | True | False | `app/src/main/kotlin/com/lifecyclebot/engine/learning/StrategyVariantStore.kt` |
| 5 | `TokenDNAClusteringAI` | `recordOutcome` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/TokenDNAClusteringAI.kt` |
| 5 | `CorrelationHedgeAI` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/CorrelationHedgeAI.kt` |
| 5 | `EntryWaitOverrideGate` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/EntryWaitOverrideGate.kt` |
| 5 | `MemeEdgeAI` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/MemeEdgeAI.kt` |
| 5 | `MemeUnifiedScorerBridge` | `-` | `scoreForEntry, computeTechnicalScore` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/MemeUnifiedScorerBridge.kt` |
| 5 | `PeerAlphaVerificationAI` | `-` | `score` | False | False | `app/src/main/kotlin/com/lifecyclebot/v3/scoring/PeerAlphaVerificationAI.kt` |
| 5 | `RugCheckPolicy` | `-` | `evaluate` | False | False | `app/src/main/kotlin/com/lifecyclebot/engine/RugCheckPolicy.kt` |

## Audit categories to complete

1. **Data starvation** — engine only sees terminal results or isolated callsites; needs candidate/reject/size/fill/hold/defer/terminal exposure.
2. **Dead advisory** — engine computes bias/score but no executor/FDG/sizing/exit consumer uses it.
3. **Amnesia** — engine has learned state but no LearningPersistence/PersistentLearning wiring.
4. **Hot-path risk** — engine calls APIs/LLM/heavy scans synchronously from scanner/FDG/executor.
5. **Mux blindness** — engine lacks live/paper, lane, source, regime, proofState, positionId, build tag.
6. **Result-only learner** — no pre-terminal decision labels; cannot learn from missed/blocked/deferred decisions.
