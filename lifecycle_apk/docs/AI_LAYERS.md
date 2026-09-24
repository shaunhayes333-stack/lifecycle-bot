# AATE Intelligence Stack — Layers

**AATE — Autonomous Algorithmic Trading Engine** · 5.0.7288

This page lists the intelligence components that exist in the code at 5.0.7288, in the order a candidate meets them. Every file named here exists. Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.

A note on words. Several source files carry headers written in the operator's voice ("sentient", "Harvard-trained", "hive mind"). Those are file-header names, not capability claims. Every layer below is a statistical or rule-based component. Most of them learn online from the trade journal. None is self-aware.

```
L0  Ingestion & discovery ─ scanners, WS fast lane, smart-money discovery, copy trading
L1  Safety                ─ HardRugPreFilter, TokenSafetyChecker, rug ledger, rug detector AI
L2  Lane scoring AIs      ─ v3/scoring per-lane traders + UnifiedScorer inner layers
L3  Fusion & governance   ─ SpecialistMoEGate, MetaCognitionAI, FluidLearningAI, FinalDecisionGate, BrainConsensusGate
L4  Learned predictors    ─ UnifiedPolicyHead, AutonomousMetaPolicy, ForwardOutcomeModel, expectancy trackers
L5  Oracle                ─ PredictiveEntryOracle6915 + OracleTradeHistory7287 + OracleEdgeProof7263
L6  Admission             ─ LearnedAdmissionAuthority6846 → ExecutableEntryAuthority6450
L7  Exit intelligence     ─ UnifiedExitPolicyHead, LaneExitTuner, PeakDrawdownLock, RunnerExitProfile7277
L8  LLM council           ─ GeminiCopilot (+ keyless), sentiment, tuner, SSI council, personas
L9  On-device ML          ─ OnDeviceMLEngine
L10 Collective learning   ─ CollectiveLearning over Turso/libSQL
L11 LLM Lab               ─ LLM-invented strategies on a synthetic paper bankroll
```

---

## L0 — Discovery

**Scanners** (`engine/SolanaMarketScanner.kt`, `engine/ModeSpecificScanners.kt`). These pull candidates from DexScreener, Birdeye, GeckoTerminal, CoinGecko trending and other sources. The PumpPortal WebSocket (`network/PumpFunWS.kt`) feeds the launch fast lane. `engine/ScannerDiversityBandit.kt` orders source families by realised performance and never blocks a source. `engine/ScannerSourceBrain.kt` tracks source quality.

**Smart-money discovery** (`engine/SmartMoneyDiscovery7277.kt`). This builds a proprietary wallet list from runners the bot itself has watched go 3×, 5× or 10×. For each one it walks the earliest on-chain buyers (Helius signatures back to genesis, parsed first-block swaps) and keeps wallets that were early on two or more independent runners. The list feeds `engine/CopyTradeEngine.kt`, which copies tracked-wallet buys pushed over the Helius WebSocket with Solscan polling as fallback. `engine/InsiderCopyEngine.kt` and `engine/WhaleWalletTracker.kt` turn wallet activity into watchlist signals.

## L1 — Safety

**HardRugPreFilter** (`engine/HardRugPreFilter.kt`) removes obvious RugCheck failures, missing liquidity floors, junk holder structure and suspicious launch profiles before any scoring runs.

**TokenSafetyChecker** (`engine/TokenSafetyChecker.kt`) assigns a `SafetyTier`: SAFE, CAUTION or HARD_BLOCK. Recorded facts are enforced by `engine/RugCheckPolicy.kt`, `engine/RugMintBlacklist.kt` and `engine/truth/FreezeAuthorityHardBlock7238.kt`. The oracle also refuses serial-rugger creators, based on the rug ledger in `engine/TradingMemory.kt`.

**UltraFastRugDetectorAI** (`v3/scoring/UltraFastRugDetectorAI.kt`) runs continuously on held positions to detect a rug as it starts.

## L2 — Per-lane scoring AIs (`v3/scoring/`)

Each trading lane has its own scorer with its own thesis, entry logic and exit profile:

| Lane | File | Thesis |
|---|---|---|
| ShitCoin | `v3/scoring/ShitCoinTraderAI.kt` | Lowest-cap memecoins, pump.fun launches |
| ShitCoin Express | `v3/scoring/ShitCoinExpress.kt` | Quick momentum rides |
| Moonshot | `v3/scoring/MoonshotTraderAI.kt` | 10× and above tail hunting (runner exit profile) |
| BlueChip | `v3/scoring/BlueChipTraderAI.kt` | Established tokens above $1M market cap |
| Quality | `v3/scoring/QualityTraderAI.kt` | Established non-meme Solana tokens |
| Project Sniper | `v3/scoring/ProjectSniperAI.kt` | Fresh project launches before the first pump fades |
| Treasury / CashGen | `v3/scoring/CashGenerationAI.kt` | Conservative scalping for steady cash flow |
| Dip Hunter | `v3/scoring/DipHunterAI.kt` | Dip buying on otherwise healthy tokens |
| Manipulated | `v3/scoring/ManipulatedTraderAI.kt` | Rides the first leg of a detected manipulation pump |

Non-meme traders live in `perps/`: `perps/CryptoAltTrader.kt` with `perps/CryptoAltScannerAI.kt` for sector heat and alt season, `perps/TokenizedStockTrader.kt`, `perps/CommoditiesTrader.kt`, `perps/MetalsTrader.kt`, `perps/ForexTrader.kt` and `perps/PerpsTraderAI.kt`. Perps learn in paper; live perps are not executing.

**UnifiedScorer** (`v3/scoring/UnifiedScorer.kt`, with `v3/scoring/ScoringModules.kt` and `v3/scoring/ScoreCard.kt`) runs the shared inner layers. Among them: `VolatilityRegimeAI`, `OrderFlowImbalanceAI`, `SmartMoneyDivergenceAI`, `LiquidityCycleAI`, `MEVDetectionAI`, `SocialVelocityAI`, `MemeNarrativeAI`, `InsiderTrackerAI`, `TokenDNAClusteringAI`, `FearGreedAI`, `NewsShockAI`, `RegimeTransitionAI` and `ExecutionCostPredictorAI`, all in `v3/scoring/`. Cross-market context comes from `v4/meta/` (`v4/meta/CrossMarketRegimeAI.kt`, `v4/meta/CrossAssetLeadLagAI.kt`, `v4/meta/NarrativeFlowAI.kt`, `v4/meta/PortfolioHeatAI.kt`, `v4/meta/LiquidityFragilityAI.kt`).

## L3 — Fusion and governance

**SpecialistMoEGate** (`v3/scoring/SpecialistMoEGate.kt`) is a bounded mixture-of-experts read-weighting over the inner layers. Each specialist still votes, and its vote is scaled by learned evidence from `EducationSubLayerAI` and `MetaCognitionAI`.

**MetaCognitionAI** (`v3/scoring/MetaCognitionAI.kt`) tracks which layers actually help. It down-weights layers that are noisy, over-confident or fragile to regime changes, and it learns consensus signatures from real outcomes.

**FluidLearningAI** (`v3/scoring/FluidLearningAI.kt`) is the single source of adaptive thresholds, which start loose and tighten with evidence. It also supplies lane-learned exit-band multipliers.

**BehaviorAI** (`v3/scoring/BehaviorAI.kt`) recognises the bot's own behavioural patterns, such as loss-streak overtrading and big-win milestones.

**FinalDecisionGate** (`engine/FinalDecisionGate.kt`) is the per-lane decision gate. It does no provider I/O. In live mode it reads the cached LLM narrative and scam verdict.

**BrainConsensusGate** (`engine/BrainConsensusGate.kt`) is the fusion point where lane rules propose and the learned stack predicts edge.

## L4 — Learned predictors (read by the oracle)

**UnifiedPolicyHead** (`engine/UnifiedPolicyHead.kt`) is a per-lane online logistic head over committee signals. It is warm-started from a global head, and its per-lane Brier calibration sets an authority tier (BOOTSTRAP, ADVISORY, LEARNED or AUTHORITATIVE). When it is LEARNED or higher and predicts a loss, it vetoes an oracle ADMIT.

**AutonomousMetaPolicy** (`engine/AutonomousMetaPolicy.kt`) learns the true win probability of each decision context from settled PnL and supplies a conviction value.

**ForwardOutcomeModel** (`engine/ForwardOutcomeModel.kt`) predicts the outcome distribution before entry: P(win), expected PnL, P(rug) and dispersion. Its cohort evidence is conditioned on mode and regime.

**ScoreExpectancyTracker** (`engine/ScoreExpectancyTracker.kt`) gives the realised expectancy per lane and score bucket. This is the oracle's CELL level.

**LiveProbabilityEngine** (`engine/LiveProbabilityEngine.kt`) is a single probability facade that exposes lane win rate, EV and sample size.

**SemanticPatternGraph** (`engine/SemanticPatternGraph.kt`) is a local similarity memory of setup and outcome families.

**SourceFamilyOpportunityScorecard** (`engine/SourceFamilyOpportunityScorecard.kt`) records realised expectancy per discovery source.

**LaneExpectancyDamper** (`engine/LaneExpectancyDamper.kt`) returns a mode-matched size multiplier from per-lane expectancy. It never vetoes a candidate.

**LosingPatternMemory** (`engine/LosingPatternMemory.kt`) and **RegimeDetector** (`engine/RegimeDetector.kt`) remember losing feature patterns and classify the market regime into one of five states.

## L5 — The oracle

**PredictiveEntryOracle6915** (`engine/truth/PredictiveEntryOracle6915.kt`) is the forecaster. It blends expectancy and win probability across cell, lane and book using empirical-Bayes shrinkage (`w = n/(n+6)`). It adds bounded votes from L4 (±25 pp for the stack, ±18 pp for the brain-network tier) and returns a binary verdict: **ADMIT** when expected value net of cost is positive and not contradicted by a binding policy head, otherwise **REFUSE**. There are no probe trades. Recorded safety facts refuse unconditionally.

**OracleTradeHistory7287** (`engine/truth/OracleTradeHistory7287.kt`) gives the oracle the whole journal. It reads up to 5,000 terminal closes (paper and live) from the SQLite journal and reduces them to per-lane and whole-book net expectancy and win rate. The oracle's LANE and GLOBAL levels come from here whenever the journal holds more closes than the session learner.

**OracleEdgeProof7263** (`engine/truth/OracleEdgeProof7263.kt`) keeps the oracle honest. It grades every forecast against the close that follows it. The oracle stays ADVISORY until all of these hold: ≥ 20 ADMIT closes, ≥ 10 REFUSE closes, ADMIT mean return > 0 and ≥ REFUSE mean + 2 pp, ADMIT win rate ≥ REFUSE win rate, and ADMIT Brier ≤ 0.25. Then it becomes PROVEN, and it demotes itself the moment any condition fails. The proof persists across restarts.

## L6 — Admission

**LearnedAdmissionAuthority6846** (`engine/truth/LearnedAdmissionAuthority6846.kt`), fed by `engine/truth/LearnedAdmissionInputs6909.kt`, is the coordinator of learned evidence. It checks, in order: oracle hard-safety, policy-head HARD_BLOCK, a PROVEN oracle's verdict, then the evidence rules (a DUMP regime with a mature negative cohort, proven-dead cohorts, source-family suspicion, capital-target overshoot). Thin evidence never refuses.

**ExecutableEntryAuthority6450** (`engine/truth/ExecutableEntryAuthority6450.kt`) is the single gate before capital is reserved. It turns a learned DENY into a zero size and applies loss-streak and cooldown size shaping per mode and lane.

## L7 — Exit intelligence

**UnifiedExitPolicyHead** (`engine/UnifiedExitPolicyHead.kt`) is a per-lane "exit now or hold" head. Its features include current and peak PnL, normalised age, momentum and liquidity erosion, and it has Brier-calibrated authority tiers.

**LaneExitTuner** (`engine/learning/LaneExitTuner.kt`) tunes each lane's take-profit and stop-loss ladder from realised outcomes.

**PeakDrawdownLock** (`engine/PeakDrawdownLock.kt`) holds the sliding profit lock: allowed give-back shrinks from 40% of the peak (under +50%) to 8% (+3,000% and above).

**RunnerExitProfile7277** (`engine/RunnerExitProfile7277.kt`) inverts exits for tail-hunting lanes: locks arm only after a +50% peak, and a position at −20% within 2 minutes is cut on the first strike.

**TrailingStopManager** (`engine/TrailingStopManager.kt`) sets adaptive trailing stops.

## L8 — LLM council

**GeminiCopilot** (`engine/GeminiCopilot.kt`) is the council. Providers: Gemini (`gemini-2.5-flash`), Groq (`openai/gpt-oss-20b` primary, `engine/GroqRouteConfig6498.kt`), Cerebras (`openai/gpt-oss-120b`), Mistral, OpenRouter, OpenAI-compatible endpoints and an Emergent proxy. Keyless fallbacks are in `network/KeylessLlmClient.kt` and `network/KeylessLlmProviders6999.kt`. Each provider has its own call spacing, cache and backoff.

Roles:

- narrative and scam analysis, cached in the background (`engine/AsyncGeminiNarrativeCache6478.kt`); a cached scam verdict hard-blocks a **live** entry;
- exit advice (`engine/AsyncGeminiExitAdviceCache6479.kt`), which can trigger a **live** exit;
- sentiment (`engine/LlmSentimentEngine.kt`);
- bounded, whitelisted parameter nudges (`engine/LlmParameterTuner.kt`);
- trade scoring (`engine/LlmTradeScore.kt`);
- chat personas and voice (`engine/Personalities.kt`, `engine/voice/PersonaSpeaker.kt`).

**SsiPilotCouncil** (`engine/SsiPilotCouncil.kt`) fuses symbolic state, meta-cognition layer trust and live lane truth into a per-lane size view, which the oracle reads as a bounded vote.

## L9 — On-device ML

**OnDeviceMLEngine** (`ml/OnDeviceMLEngine.kt`) has three online-trained heads over 32 normalised trade features: P(win), P(rug) and P(should-exit). Each head is a linear logistic model trained by SGD on every recorded trade. From 120 samples, a 16-unit hidden-layer MLP is blended in at weight 0.45. Weights persist in SharedPreferences. It is pure Kotlin and runs in under 1 ms. The TensorFlow Lite dependency is declared in `app/build.gradle.kts`, but no `.tflite` model ships and no TFLite interpreter is called in 5.0.7288. The file says so itself.

## L10 — Collective learning

**CollectiveLearning** (`collective/CollectiveLearning.kt`) is the shared knowledge base over Turso/libSQL (`collective/TursoClient.kt`, `collective/CollectiveSchema.kt`). It holds hashed pattern aggregates, a shared token blacklist, mode performance, whale effectiveness, and instance heartbeats and messages. **CollectiveIntelligenceAI** (`v3/scoring/CollectiveIntelligenceAI.kt`) reads the collective back into scoring. In 5.0.7288 the collective is bound to the operator's own Turso instance.

## L11 — LLM Lab

**LLM Lab** (`engine/lab/LlmLabEngine.kt`, `engine/lab/LlmLabTrader.kt`, `engine/lab/LlmLabStore.kt`, `engine/lab/LlmLabModels.kt`, `engine/lab/LabPromotedFeed.kt`) is the LLM's own sandbox. The LLM invents strategies and paper-trades them on a synthetic 100 SOL bankroll with take-profit, stop-loss and timeout rules. Lab outcomes are reported to the rest of the engine. A strategy that earns promotion goes to an approval queue before any real money is involved. Screen: `ui/LabActivity.kt`.

---

> Trading crypto is high risk. Paper results are not live results. Not financial advice.
