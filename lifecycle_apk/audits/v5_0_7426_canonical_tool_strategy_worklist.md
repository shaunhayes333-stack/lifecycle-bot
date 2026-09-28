# AATE 5.0.7426 — Canonical Tool + Strategy Worklist

## Objective

Make canonical PAPER choose deployment-quality, high-EV trades, then prove LIVE executes the same sealed decisions faithfully.

This file is the single backlog for:
- the **113 high-value unwired functions** already identified in `ci/UNWIRED_LEDGER.tsv`;
- the full strategy / setup / tactic / trade-type taxonomy;
- causal strategy identity repairs needed so exact strategies can actually learn from their own outcomes.

A primitive is not considered implemented merely because an enum/class/function exists.

## P0 — Strategy identity and causal credit

- [ ] Seal exact `tradeType`, `toolkitSetup`, `agenticStyle`, `tactic`, `strategyVariantId`, and applied exit profile in `EntryStrategySnapshot6450`.
- [ ] Carry those fields through `CanonicalFinalizedTradeBus6464.Envelope`.
- [ ] Stop collapsing all exact strategies into only lane + coarse `TacticSwitcher.Tactic`.
- [ ] Expand `StrategyHypothesisEngine` beyond only `lane|scoreBand|regime`, using hierarchical shrinkage so exact strategy identity is retained without sparse-cell blindness.
- [ ] Stamp the exact `StrategyVariantStore` variant ID at entry and credit the terminal outcome to that stamped ID, not the currently-active variant at close.
- [ ] Add exact-strategy PAPER/LIVE/SHADOW EV, WR, PF, MFE, MAE, hold-time and exit-reason telemetry.
- [ ] Preserve parent hierarchy: signal → setup → strategy/style → specialist/lane → tactic → sizing/management/exit → outcome.

## P0 — 113 high-value unwired functions

### A_PREDICT — 47
- [ ] `BlueChipTraderAI.getStockTrustScore` — `v3/scoring/BlueChipTraderAI.kt`
- [ ] `BotBrain.getBlendedWinRate` — `engine/BotBrain.kt`
- [ ] `CoinGeckoTrending.getSolanaEcosystemMomentum` — `network/CoinGeckoTrending.kt`
- [ ] `CollectiveLearning.getNetworkBoostForMint` — `collective/CollectiveLearning.kt`
- [ ] `CorrelationScanner.getActionableSignals` — `perps/CorrelationScanner.kt`
- [ ] `CrossAssetLeadLagAI.getRotationProbability` — `v4/meta/CrossAssetLeadLagAI.kt`
- [ ] `DataOrchestrator.scoreSentimentWithLlm` — `engine/DataOrchestrator.kt`
- [ ] `EdgeOptimizer.calculateWeightedScores` — `engine/EdgeOptimizer.kt`
- [ ] `EducationSubLayerAI.getEdgeLedger` — `v3/scoring/EducationSubLayerAI.kt`
- [ ] `ExecutableEntryAuthority6450.scoreFloorDelta6487` — `engine/truth/ExecutableEntryAuthority6450.kt`
- [ ] `ExplorationBudget.allowShadowSignal` — `engine/learning/ExplorationBudget.kt`
- [ ] `FluidLearning.getExitTagWinRate` — `engine/FluidLearning.kt`
- [ ] `FluidLearningAI.getHeuristicSignal` — `v3/scoring/FluidLearningAI.kt`
- [ ] `ForensicEventEnvelope6430.setLedgerEpoch` — `engine/truth/ForensicEventEnvelope6430.kt`
- [ ] `HistoricalChartScanner.getBestModeForConditions` — `engine/HistoricalChartScanner.kt`
- [ ] `InsiderTrackerAI.getSignalsByWallet` — `v3/scoring/InsiderTrackerAI.kt`
- [ ] `MomentumPredictorAI.getStrongMomentumTokens` — `engine/MomentumPredictorAI.kt`
- [ ] `OrthogonalSignals.calculateAgePatternScore` — `engine/OrthogonalSignals.kt`
- [ ] `PatternBacktester.getConfidenceAdjustments` — `engine/PatternBacktester.kt`
- [ ] `PerpsAdvancedAI.getHourlyWinRate` — `perps/PerpsAdvancedAI.kt`
- [ ] `PerpsAutoReplayLearner.getLosingPatterns` — `perps/PerpsAutoReplayLearner.kt`
- [ ] `PerpsAutoReplayLearner.getWinningPatterns` — `perps/PerpsAutoReplayLearner.kt`
- [ ] `PerpsDirection.getSignalStrength` — `perps/PerpsModels.kt`
- [ ] `PerpsDirection.isHighConfidence` — `perps/PerpsModels.kt`
- [ ] `PerpsLearningBridge.getStockLayerRecommendations` — `perps/PerpsLearningBridge.kt`
- [ ] `PerpsNotificationManager.notifyPatternDiscovered` — `perps/PerpsNotificationManager.kt`
- [ ] `PerpsNotificationManager.notifyStrongSignal` — `perps/PerpsNotificationManager.kt`
- [ ] `PerpsTradeHeatmap.getAIRecommendation` — `perps/PerpsTradeHeatmap.kt`
- [ ] `PerpsTraderAI.getLifetimeWinRatePct` — `perps/PerpsTraderAI.kt`
- [ ] `QualityTraderAI.getRecommendedLeverage` — `v3/scoring/QualityTraderAI.kt`
- [ ] `QuantMetrics.calculateWinRateStats` — `engine/quant/QuantMetrics.kt`
- [ ] `RuntimeTune6833.isHighEdge` — `engine/truth/RuntimeTune6833.kt`
- [ ] `ScoreComponent.sourceScore` — `v3/scoring/ScoreCard.kt`
- [ ] `ScoreDistributionHistogram6396.recommendAdaptiveBaseline` — `engine/truth/ScoreDistributionHistogram6396.kt`
- [ ] `ShadowLearningEngine.getPerformanceByConfidence` — `v3/learning/ShadowLearningEngine.kt`
- [ ] `SmartExitOptimizer.getMinConfidenceAdvisory` — `engine/SmartExitOptimizer.kt`
- [ ] `SourceTimingRegistry.isLateSignal` — `v3/arb/SourceTimingRegistry.kt`
- [ ] `SymbolicContext.getAllSignals` — `engine/SymbolicContext.kt`
- [ ] `TacticSwitcher.posteriorLossProbAboveForTest` — `engine/learning/TacticSwitcher.kt`
- [ ] `TradeDatabase.getSignalWinRate` — `engine/TradeDatabase.kt`
- [ ] `TradeLessonRecorder.getWinRateForLane` — `v4/meta/TradeLessonRecorder.kt`
- [ ] `TradeLifecycle.noSignal` — `engine/TradeLifecycle.kt`
- [ ] `TradingCopilot.convictionBoost` — `engine/TradingCopilot.kt`
- [ ] `TradingMemory.getPatternWinRate` — `engine/TradingMemory.kt`
- [ ] `TrailingStopManager.getRecommendedStopType` — `engine/TrailingStopManager.kt`
- [ ] `TursoClient.getMarketsAssetRankings` — `collective/TursoClient.kt`
- [ ] `UnifiedPolicyHead.brierScore` — `engine/UnifiedPolicyHead.kt`

### B_RISK — 12
- [ ] `CanonicalEconomicIdentity6470.breachCount` — `engine/truth/CanonicalEconomicIdentity6470.kt`
- [ ] `EvidenceEpochFilter6388.canPassForensicRegressionGuard` — `engine/truth/GovernorRecoverySubstrate6388.kt`
- [ ] `ExitCoordinatorHeartbeat.duplicateSweepsSuppressedCount` — `engine/ExitCoordinatorHeartbeat.kt`
- [ ] `ExternalAlphaFeeds.enrichSafety` — `v4/meta/ExternalAlphaFeeds.kt`
- [ ] `GeminiCopilot.assessRisk` — `engine/GeminiCopilot.kt`
- [ ] `MemeExecutionRouteStack.stackExhausted` — `engine/execution/MemeExecutionRouteStack.kt`
- [ ] `ReentryGuard.manualBlock` — `engine/ReentryGuard.kt`
- [ ] `SameMintCandidateEpoch6402.totalSuppressed` — `engine/truth/SameMintCandidateEpoch6402.kt`
- [ ] `SellOnlySafeMode.blockedBuyCount` — `engine/sell/SellOnlySafeMode.kt`
- [ ] `SoftScoreShaping6400.recordMechanicalMinBlock` — `engine/truth/SoftScoreShaping6400.kt`
- [ ] `TradeDatabase.getSuppressionStrength` — `engine/TradeDatabase.kt`
- [ ] `TradeLifecycle.forceExpireBlocked` — `engine/TradeLifecycle.kt`

### C_EXIT — 54
- [ ] `BirdeyeApi.getHolderDistribution` — `network/BirdeyeApi.kt`
- [ ] `BotBrain.resetThresholds` — `engine/BotBrain.kt`
- [ ] `BotRuntimeController.runtimeJobActiveButUiStopped` — `engine/BotRuntimeController.kt`
- [ ] `CanonicalPositionAuthority6441.activeMintProjections6489` — `engine/truth/CanonicalPositionAuthority6441.kt`
- [ ] `CounterParityLedger6399.recordSellExecutorInvocation` — `engine/truth/CounterParityLedger6399.kt`
- [ ] `EarlyEntryScout6390.trackedPeakCount6948` — `engine/truth/EarlyEntryAndPeakCapture6390.kt`
- [ ] `EducationSubLayerAI.getCurriculumHoldStats` — `v3/scoring/EducationSubLayerAI.kt`
- [ ] `EvidenceEpochFilter6388.buildFullExitPlan` — `engine/truth/GovernorRecoverySubstrate6388.kt`
- [ ] `EvidenceEpochFilter6388.p1FaultLiveReconcilerMissingWithHoldings` — `engine/truth/GovernorRecoverySubstrate6388.kt`
- [ ] `EvidenceEpochFilter6388.requiresFullExit` — `engine/truth/GovernorRecoverySubstrate6388.kt`
- [ ] `ExecutionCounterContract.recordJournalSellWrite` — `engine/runtime/ExecutionCounterContract.kt`
- [ ] `ExitIntelligence.getLearnedMaxHoldMinutes` — `engine/ExitIntelligence.kt`
- [ ] `ExitManager.shouldPartialSell` — `engine/ExitManager.kt`
- [ ] `FinalizedSellProof6386.classifyPartial` — `engine/truth/FinalizedSellProof6386.kt`
- [ ] `FluidLearning.getSimulatedPeak` — `engine/FluidLearning.kt`
- [ ] `FluidLearningAI.getBreakoutThreshold` — `v3/scoring/FluidLearningAI.kt`
- [ ] `FluidLearningAI.getLayerHoldParams` — `v3/scoring/FluidLearningAI.kt`
- [ ] `FluidLearningAI.getMarketsTakeProfitPct` — `v3/scoring/FluidLearningAI.kt`
- [ ] `FluidLearningAI.getMarketsUncappedTpPct` — `v3/scoring/FluidLearningAI.kt`
- [ ] `ForexStrategy.tpSlPrices` — `perps/strategy/ForexStrategy.kt`
- [ ] `GlobalCapitalArbitration6617.recordSpecialistProposal6617` — `engine/truth/GlobalCapitalArbitration6617.kt`
- [ ] `GlobalTradeRegistry.getProbationStats` — `engine/GlobalTradeRegistry.kt`
- [ ] `GovernorRecovery6388.lastPromotionReason` — `engine/truth/GovernorRecovery6388.kt`
- [ ] `HistoricalChartScanner.getProgress` — `engine/HistoricalChartScanner.kt`
- [ ] `HoldingLogicLayer.getHoldParams` — `engine/HoldingLogicLayer.kt`
- [ ] `IdempotencyKeyStore6437.sellKey` — `engine/truth/IdempotencyKeyStore6437.kt`
- [ ] `JupiterPerps.getPoolInfo` — `perps/JupiterPerps.kt`
- [ ] `LearnerRuntimeBudgetGuard6441.shouldStop` — `engine/truth/LearnerRuntimeBudgetGuard6441.kt`
- [ ] `LiveExecutionGate.sellCompleted` — `engine/LiveExecutionGate.kt`
- [ ] `LiveExecutionGate.trySell` — `engine/LiveExecutionGate.kt`
- [ ] `LiveExitOnlyMode6387.classifyForStop` — `engine/truth/LiveTruthExitAuthority6387.kt`
- [ ] `LiveStrategyTuner.tpMultiplier` — `engine/LiveStrategyTuner.kt`
- [ ] `PerpsAdvancedAI.shouldPartialExit` — `perps/PerpsAdvancedAI.kt`
- [ ] `PerpsMarketDataFetcher.getPriceSource` — `perps/PerpsMarketDataFetcher.kt`
- [ ] `PerpsTrailingStop.getTrailStop` — `perps/PerpsTrailingStop.kt`
- [ ] `PortfolioInvariants6405.verifyWalletParity` — `engine/truth/PortfolioInvariants6405.kt`
- [ ] `PositionIdentity6395.activeExitIntent` — `engine/truth/PositionIdentity6395.kt`
- [ ] `PositionStateLedger6454.onPartial` — `engine/truth/PositionStateLedger6454.kt`
- [ ] `PriceAggregator.getPrunedSymbols` — `perps/PriceAggregator.kt`
- [ ] `QuantMetrics.calculateProfitFactor` — `engine/quant/QuantMetrics.kt`
- [ ] `RouteValidator.validateFinalOutput` — `engine/execution/RouteValidator.kt`
- [ ] `RuntimeRepairState.requestPaperMode` — `engine/RuntimeRepairState.kt`
- [ ] `ScannerHeatPublisher6398.currentPct01` — `engine/truth/ScannerHeatPublisher6398.kt`
- [ ] `SellIntentQuantityAuthority6401.validateSellIntentFromUi` — `engine/truth/SellIntentQuantityAuthority6401.kt`
- [ ] `ShadowLearningEngine.getPerformanceByMode` — `v3/learning/ShadowLearningEngine.kt`
- [ ] `SmartExitOptimizer.getExitPressure` — `engine/SmartExitOptimizer.kt`
- [ ] `SolanaWallet.getPublicKeyOnly` — `network/SolanaWallet.kt`
- [ ] `TacticBleedPivot.getLastPivot` — `engine/TacticBleedPivot.kt`
- [ ] `TelegramNotifier.partialMsg` — `engine/TelegramNotifier.kt`
- [ ] `TerminalFinalityAuthority6405.allowExit` — `engine/truth/TerminalFinalityAuthority6405.kt`
- [ ] `ToxicModeCircuitBreaker.activateEmergencyStop` — `engine/ToxicModeCircuitBreaker.kt`
- [ ] `ToxicModeCircuitBreaker.deactivateEmergencyStop` — `engine/ToxicModeCircuitBreaker.kt`
- [ ] `TradeIdentityManager.auditTrail` — `engine/TradeIdentity.kt`
- [ ] `TreasuryOpportunityEngine.getPendingOpportunities` — `engine/TreasuryOpportunityEngine.kt`

## P1 — Master strategy / setup / tactic taxonomy

Status values: **CLOSED_LOOP / PARTIAL / ALIAS / SHADOW_ONLY / UNWIRED / MISSING**.

### 1. Momentum / trend
- [ ] Breakout — status: TBD
- [ ] Range breakout — status: TBD
- [ ] High-of-day breakout — status: TBD
- [ ] All-time-high breakout — status: TBD
- [ ] Momentum continuation — status: TBD
- [ ] Trend following — status: TBD
- [ ] Trend pullback — status: TBD
- [ ] Trend resumption — status: TBD
- [ ] EMA trend — status: TBD
- [ ] Moving-average crossover — status: TBD
- [ ] ADX trend — status: TBD
- [ ] Supertrend — status: TBD
- [ ] Parabolic acceleration — status: TBD
- [ ] Relative-strength momentum — status: TBD
- [ ] Cross-sectional momentum — status: TBD
- [ ] Time-series momentum — status: TBD
- [ ] Volume-confirmed momentum — status: TBD
- [ ] Price/volume expansion — status: TBD
- [ ] Multi-timeframe trend alignment — status: TBD

### 2. Early-pump / launch
- [ ] Fresh-pair launch — status: TBD
- [ ] Token-launch sniper — status: TBD
- [ ] First-liquidity entry — status: TBD
- [ ] Initial volume burst — status: TBD
- [ ] First buyer-wave detection — status: TBD
- [ ] Early velocity breakout — status: TBD
- [ ] Liquidity-addition reaction — status: TBD
- [ ] Dev-wallet activity signal — status: TBD
- [ ] Bundle activity detection — status: TBD
- [ ] Coordinated-wallet acceleration — status: TBD
- [ ] Smart-money early entry — status: TBD
- [ ] Wallet-cluster accumulation — status: TBD
- [ ] Pre-breakout compression — status: TBD
- [ ] Pump ignition — status: TBD
- [ ] Pump continuation — status: TBD
- [ ] Second-wave pump — status: TBD
- [ ] Relaunch/revival trade — status: TBD
- [ ] Graduation/migration trade — status: TBD
- [ ] Bonding-curve momentum — status: TBD
- [ ] Post-graduation continuation — status: TBD

### 3. Mean reversion
- [ ] RSI oversold — status: TBD
- [ ] RSI overbought fade — status: TBD
- [ ] Bollinger-band reversion — status: TBD
- [ ] VWAP reversion — status: TBD
- [ ] Moving-average reversion — status: TBD
- [ ] Z-score reversion — status: TBD
- [ ] Extreme-deviation fade — status: TBD
- [ ] Intraday mean reversion — status: TBD
- [ ] Post-spike retracement — status: TBD
- [ ] Exhaustion fade — status: TBD
- [ ] Capitulation bounce — status: TBD
- [ ] Oversold bounce — status: TBD
- [ ] Overextension short/fade — status: TBD
- [ ] Statistical reversion — status: TBD

### 4. Dip-buying
- [ ] First pullback — status: TBD
- [ ] Shallow pullback — status: TBD
- [ ] Deep pullback — status: TBD
- [ ] Buy-the-dip — status: TBD
- [ ] Support retest — status: TBD
- [ ] Breakout retest — status: TBD
- [ ] VWAP reclaim — status: TBD
- [ ] EMA reclaim — status: TBD
- [ ] Prior-high retest — status: TBD
- [ ] Liquidity-sweep reclaim — status: TBD
- [ ] Flush-and-recover — status: TBD
- [ ] Panic-sell recovery — status: TBD
- [ ] Post-rug survivor bounce — status: TBD
- [ ] Dip after first pump — status: TBD
- [ ] Dip after liquidity expansion — status: TBD

### 5. Reversal
- [ ] V-bottom — status: TBD
- [ ] Rounded-bottom — status: TBD
- [ ] Double-bottom — status: TBD
- [ ] Triple-bottom — status: TBD
- [ ] Failed breakdown — status: TBD
- [ ] Failed breakout reversal — status: TBD
- [ ] Bullish divergence — status: TBD
- [ ] Bearish divergence — status: TBD
- [ ] Momentum divergence — status: TBD
- [ ] Volume divergence — status: TBD
- [ ] Selling-exhaustion reversal — status: TBD
- [ ] Buying-exhaustion reversal — status: TBD
- [ ] Market-structure shift — status: TBD
- [ ] Change-of-character — status: TBD
- [ ] Reclaim reversal — status: TBD
- [ ] Sweep-and-reverse — status: TBD

### 6. Range / market making
- [ ] Range low buy — status: TBD
- [ ] Range high sell — status: TBD
- [ ] Grid trading — status: TBD
- [ ] Dynamic grid — status: TBD
- [ ] Mean-range rotation — status: TBD
- [ ] VWAP range trading — status: TBD
- [ ] Channel trading — status: TBD
- [ ] Support/resistance rotation — status: TBD
- [ ] Inventory-based market making — status: TBD
- [ ] Spread capture — status: TBD
- [ ] Passive maker — status: TBD
- [ ] Rebate capture — status: TBD
- [ ] Volatility-adjusted quoting — status: TBD

### 7. Scalping
- [ ] Micro-breakout scalp — status: TBD
- [ ] Momentum scalp — status: TBD
- [ ] Tape/flow scalp — status: TBD
- [ ] Order-flow scalp — status: TBD
- [ ] Spread scalp — status: TBD
- [ ] VWAP scalp — status: TBD
- [ ] EMA scalp — status: TBD
- [ ] Liquidity-grab scalp — status: TBD
- [ ] Quick-flip scalp — status: TBD
- [ ] 1-minute reversal — status: TBD
- [ ] 5-minute momentum — status: TBD
- [ ] Burst-volume scalp — status: TBD
- [ ] News scalp — status: TBD
- [ ] Launch scalp — status: TBD

### 8. Swing trading
- [ ] Multi-day momentum — status: TBD
- [ ] Trend swing — status: TBD
- [ ] Support swing — status: TBD
- [ ] Breakout swing — status: TBD
- [ ] Pullback swing — status: TBD
- [ ] Narrative swing — status: TBD
- [ ] Sector rotation — status: TBD
- [ ] Relative-strength swing — status: TBD
- [ ] Accumulation swing — status: TBD
- [ ] Position-building swing — status: TBD

### 9. Position / investment-style
- [ ] DCA — status: TBD
- [ ] Value accumulation — status: TBD
- [ ] Fundamental accumulation — status: TBD
- [ ] Long-term trend — status: TBD
- [ ] Cycle positioning — status: TBD
- [ ] Halving-cycle positioning — status: TBD
- [ ] Risk-on/risk-off allocation — status: TBD
- [ ] Market-cap rotation — status: TBD
- [ ] BTC dominance rotation — status: TBD
- [ ] ETH/BTC relative-value positioning — status: TBD
- [ ] Treasury allocation — status: TBD

### 10. Arbitrage
- [ ] Cross-exchange arbitrage — status: TBD
- [ ] Triangular arbitrage — status: TBD
- [ ] DEX/CEX arbitrage — status: TBD
- [ ] DEX/DEX arbitrage — status: TBD
- [ ] Stablecoin arbitrage — status: TBD
- [ ] Spot/futures arbitrage — status: TBD
- [ ] Cash-and-carry — status: TBD
- [ ] Funding-rate arbitrage — status: TBD
- [ ] Basis arbitrage — status: TBD
- [ ] Perpetual basis — status: TBD
- [ ] Cross-chain arbitrage — status: TBD
- [ ] Bridge-price arbitrage — status: TBD
- [ ] Pool imbalance arbitrage — status: TBD
- [ ] AMM arbitrage — status: TBD
- [ ] Latency arbitrage — status: TBD

### 11. Relative value / pairs
- [ ] Pairs trading — status: TBD
- [ ] Cointegration trade — status: TBD
- [ ] Beta-neutral trade — status: TBD
- [ ] Sector-relative value — status: TBD
- [ ] Leader/laggard — status: TBD
- [ ] BTC-alt divergence — status: TBD
- [ ] ETH-alt divergence — status: TBD
- [ ] Stablecoin relative-value — status: TBD
- [ ] Ecosystem relative strength — status: TBD
- [ ] Market-neutral spread — status: TBD

### 12. Volatility
- [ ] Volatility breakout — status: TBD
- [ ] Volatility contraction — status: TBD
- [ ] Squeeze — status: TBD
- [ ] Bollinger squeeze — status: TBD
- [ ] ATR expansion — status: TBD
- [ ] Volatility regime shift — status: TBD
- [ ] High-vol momentum — status: TBD
- [ ] Low-vol accumulation — status: TBD
- [ ] Realized-vol breakout — status: TBD
- [ ] Volatility mean reversion — status: TBD

### 13. Volume / flow
- [ ] Volume spike — status: TBD
- [ ] Relative-volume breakout — status: TBD
- [ ] Buy/sell imbalance — status: TBD
- [ ] CVD divergence — status: TBD
- [ ] Aggressive-buy flow — status: TBD
- [ ] Aggressive-sell flow — status: TBD
- [ ] Absorption — status: TBD
- [ ] Volume climax — status: TBD
- [ ] Accumulation detection — status: TBD
- [ ] Distribution detection — status: TBD
- [ ] Whale accumulation — status: TBD
- [ ] Whale distribution — status: TBD
- [ ] Smart-wallet following — status: TBD
- [ ] Insider-wallet monitoring — status: TBD
- [ ] Exchange inflow/outflow reaction — status: TBD

### 14. Liquidity / microstructure
- [ ] Liquidity sweep — status: TBD
- [ ] Stop hunt reclaim — status: TBD
- [ ] Order-book imbalance — status: TBD
- [ ] Thin-book breakout — status: TBD
- [ ] Liquidity vacuum — status: TBD
- [ ] Liquidity wall reaction — status: TBD
- [ ] Bid-wall support — status: TBD
- [ ] Ask-wall breakout — status: TBD
- [ ] Slippage opportunity — status: TBD
- [ ] Spread compression — status: TBD
- [ ] Spread expansion — status: TBD
- [ ] Pool-depth acceleration — status: TBD
- [ ] Liquidity migration — status: TBD
- [ ] LP-addition trade — status: TBD
- [ ] LP-removal defence/exit — status: TBD

### 15. On-chain
- [ ] Whale-wallet tracking — status: TBD
- [ ] Smart-money tracking — status: TBD
- [ ] New-wallet clustering — status: TBD
- [ ] Holder growth — status: TBD
- [ ] Holder concentration change — status: TBD
- [ ] Transaction-growth momentum — status: TBD
- [ ] Active-address momentum — status: TBD
- [ ] Large-transfer reaction — status: TBD
- [ ] Exchange-deposit signal — status: TBD
- [ ] Exchange-withdrawal signal — status: TBD
- [ ] Token-burn reaction — status: TBD
- [ ] Mint-authority event — status: TBD
- [ ] Liquidity-lock signal — status: TBD
- [ ] Contract-change event — status: TBD
- [ ] Treasury-wallet activity — status: TBD

### 16. Meme-coin specific
- [ ] Fresh-launch sniper — status: TBD
- [ ] Narrative meme rotation — status: TBD
- [ ] Influencer mention reaction — status: TBD
- [ ] Social-velocity trade — status: TBD
- [ ] Telegram/Discord acceleration — status: TBD
- [ ] Holder-count acceleration — status: TBD
- [ ] Market-cap milestone breakout — status: TBD
- [ ] Bonding-curve acceleration — status: TBD
- [ ] Migration/graduation — status: TBD
- [ ] Dev-buy signal — status: TBD
- [ ] Dev-sell avoidance — status: TBD
- [ ] Bundle-entry detection — status: TBD
- [ ] Bundle-unwind detection — status: TBD
- [ ] Community takeover — status: TBD
- [ ] CTO revival — status: TBD
- [ ] Dead-token revival — status: TBD
- [ ] Second-pump — status: TBD
- [ ] Liquidity resurrection — status: TBD
- [ ] DexScreener trending — status: TBD
- [ ] Pump.fun trending — status: TBD

### 17. Narrative / event
- [ ] News momentum — status: TBD
- [ ] Listing trade — status: TBD
- [ ] Exchange-listing anticipation — status: TBD
- [ ] Post-listing continuation — status: TBD
- [ ] Airdrop catalyst — status: TBD
- [ ] Mainnet launch — status: TBD
- [ ] Token unlock — status: TBD
- [ ] Burn event — status: TBD
- [ ] Governance-event trade — status: TBD
- [ ] Partnership announcement — status: TBD
- [ ] Protocol upgrade — status: TBD
- [ ] ETF/news catalyst — status: TBD
- [ ] Regulatory-event reaction — status: TBD
- [ ] Macro-event reaction — status: TBD
- [ ] Narrative rotation — status: TBD
- [ ] Sector rotation — status: TBD

### 18. Market regime
- [ ] Bull-regime momentum — status: TBD
- [ ] Bear-regime short/fade — status: TBD
- [ ] Sideways-regime mean reversion — status: TBD
- [ ] High-volatility regime — status: TBD
- [ ] Low-volatility regime — status: TBD
- [ ] Risk-on regime — status: TBD
- [ ] Risk-off regime — status: TBD
- [ ] BTC-led market — status: TBD
- [ ] Altseason — status: TBD
- [ ] Meme-season — status: TBD
- [ ] DeFi rotation — status: TBD
- [ ] AI-token rotation — status: TBD
- [ ] L1/L2 rotation — status: TBD

### 19. Entry tactics
- [ ] Market entry — status: TBD
- [ ] Limit entry — status: TBD
- [ ] Maker-only entry — status: TBD
- [ ] Stop entry — status: TBD
- [ ] Breakout confirmation — status: TBD
- [ ] Anticipatory entry — status: TBD
- [ ] Scale-in — status: TBD
- [ ] Laddered entry — status: TBD
- [ ] Probe entry — status: TBD
- [ ] Starter position — status: TBD
- [ ] Add-on-strength — status: TBD
- [ ] Add-on-pullback — status: TBD
- [ ] Retest entry — status: TBD
- [ ] Sweep entry — status: TBD
- [ ] VWAP entry — status: TBD
- [ ] EMA entry — status: TBD
- [ ] Time-delayed confirmation — status: TBD
- [ ] Multi-signal confirmation — status: TBD

### 20. Position sizing tactics
- [ ] Fixed notional — status: TBD
- [ ] Fixed percentage — status: TBD
- [ ] Volatility-adjusted sizing — status: TBD
- [ ] ATR sizing — status: TBD
- [ ] Confidence sizing — status: TBD
- [ ] EV-weighted sizing — status: TBD
- [ ] Kelly fraction — status: TBD
- [ ] Fractional Kelly — status: TBD
- [ ] Liquidity-adjusted sizing — status: TBD
- [ ] Slippage-adjusted sizing — status: TBD
- [ ] Drawdown-adjusted sizing — status: TBD
- [ ] Regime-adjusted sizing — status: TBD
- [ ] Progressive sizing — status: TBD
- [ ] De-risked sizing after losses — status: TBD

### 21. Exit tactics
- [ ] Fixed take profit — status: TBD
- [ ] Fixed stop loss — status: TBD
- [ ] Risk/reward exit — status: TBD
- [ ] Trailing stop — status: TBD
- [ ] ATR trailing stop — status: TBD
- [ ] Chandelier exit — status: TBD
- [ ] Break-even stop — status: TBD
- [ ] Time stop — status: TBD
- [ ] Momentum-decay exit — status: TBD
- [ ] Volume-decay exit — status: TBD
- [ ] Trend-break exit — status: TBD
- [ ] Structure-break exit — status: TBD
- [ ] VWAP-loss exit — status: TBD
- [ ] EMA-loss exit — status: TBD
- [ ] Liquidity-loss exit — status: TBD
- [ ] Whale-sell exit — status: TBD
- [ ] Dev-sell exit — status: TBD
- [ ] LP-removal exit — status: TBD
- [ ] Partial take profit — status: TBD
- [ ] Multi-stage take profit — status: TBD
- [ ] Runner position — status: TBD
- [ ] Moonbag — status: TBD
- [ ] Scale-out — status: TBD
- [ ] Profit lock — status: TBD
- [ ] Parabolic trailing exit — status: TBD

### 22. Trade-management tactics
- [ ] Pyramid into winners — status: TBD
- [ ] Never-average-down — status: TBD
- [ ] Controlled averaging — status: TBD
- [ ] Re-entry after stop — status: TBD
- [ ] Re-entry after reclaim — status: TBD
- [ ] Cooldown after loss — status: TBD
- [ ] Cooldown after volatility spike — status: TBD
- [ ] Dynamic stop tightening — status: TBD
- [ ] Dynamic target extension — status: TBD
- [ ] Hold-time adaptation — status: TBD
- [ ] Signal-decay management — status: TBD
- [ ] Position-priority scheduling — status: TBD
- [ ] Capital recycling — status: TBD
- [ ] Opportunity-cost exit — status: TBD

### 23. Portfolio tactics
- [ ] Max concurrent positions — status: TBD
- [ ] Exposure caps — status: TBD
- [ ] Sector caps — status: TBD
- [ ] Correlation caps — status: TBD
- [ ] Token-level caps — status: TBD
- [ ] Wallet-risk caps — status: TBD
- [ ] Liquidity-tier allocation — status: TBD
- [ ] Strategy diversification — status: TBD
- [ ] Lane diversification — status: TBD
- [ ] Regime-based allocation — status: TBD
- [ ] Dynamic cash reserve — status: TBD
- [ ] Drawdown governor — status: TBD

### 24. Learning / adaptive strategies
- [ ] Online learning — status: TBD
- [ ] Contextual bandit — status: TBD
- [ ] Multi-armed bandit — status: TBD
- [ ] Bayesian strategy weighting — status: TBD
- [ ] Thompson sampling — status: TBD
- [ ] Reinforcement learning — status: TBD
- [ ] Regime-conditioned model — status: TBD
- [ ] EV ranking — status: TBD
- [ ] Profit-factor weighting — status: TBD
- [ ] Recency weighting — status: TBD
- [ ] Decay-weighted performance — status: TBD
- [ ] Strategy ensemble — status: TBD
- [ ] Specialist ensemble — status: TBD
- [ ] Meta-strategy selector — status: TBD
- [ ] Auto-disable negative-EV setup — status: TBD
- [ ] Auto-promote positive-EV setup — status: TBD

## Confirmed findings so far

- `AgenticStyleRouter` has 28 styles and is genuinely consumed.
- `ToolkitSignalSheet` has 18 setups including NONE and feeds style selection.
- `ModeRouter` has 10 trade types and is active.
- Meme `TacticSwitcher` has 5 tactics; Crypto has a separate 5-tactic switcher.
- `UnifiedModeOrchestrator` exposes 19 named modes, but present usage is substantially dashboard/orchestration/compatibility; these are not counted as 19 independent live strategies without execution proof.
- `ModeSpecificScanners` contains strategy-like scanners, but key helpers are listed as unwired/dead.
- Exact Agentic style / Toolkit setup / ModeRouter trade type are not preserved in the immutable entry snapshot, so terminal learning loses exact strategy identity.
- `StrategyHypothesisEngine` currently keys experiments by `lane|scoreBand|regime`, blending materially different execution strategies.
- `StrategyVariantStore` is lane-scoped and terminal credit currently resolves the active variant at close instead of the exact variant applied at entry.
- `AdvancedExitManager.evaluateExit` is called from `HoldingLogicLayer`, but its canonical role is advisory.
- `SellOptimizationAI` learns/persists exit outcomes, but its exit-strategy enum still needs a proven production decision consumer.

## Acceptance contract

Only mark a primitive CLOSED_LOOP when:
1. its input producer is real and fresh;
2. a production consumer reads it;
3. it materially changes entry, sizing, management, hold, or exit;
4. its exact applied identity is sealed at decision/entry time;
5. the exact terminal outcome is credited back once;
6. PAPER and LIVE use the same pre-execution logic unless the difference is execution-mechanical;
7. runtime telemetry proves selected → applied → outcome → learned;
8. SHADOW/LAB-only experimentation cannot contaminate canonical PAPER/LIVE economics.
