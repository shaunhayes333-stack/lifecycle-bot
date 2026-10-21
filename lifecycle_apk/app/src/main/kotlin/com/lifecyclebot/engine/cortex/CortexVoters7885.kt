package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState

/**
 * V5.0.7885 — Cortex §2.2/§2.3: the voter registry. Everything existing gets a
 * seat; seats are earned in [CortexLedger7885].
 *
 * A voter is a cheap, side-effect-free READ of an existing component's view of
 * this candidate (cached specialist opinions, V3 score, brains, forecasters,
 * memories, the plan, safety, market state). It returns a raw number on its
 * own scale, or null to abstain ("no opinion" is never a fake neutral). The
 * bin edges only decide how finely the ledger calibrates that scale; they do
 * not assert what is good. Evidence tags let fusion discount voters that read
 * the same evidence (v1 §2.4: "the same win rate voted 10x").
 *
 * Deliberately NOT voters: anything that fetches, writes, records a decision
 * or re-runs an evaluator (SpecialistBrainBridge7542.evaluate,
 * PredictiveEntryOracle6915.evaluate, TradePlan7739.liveBlockReason,
 * FieldManual7715.decide, HardRugPreFilter.filter).
 */
object CortexVoters7885 {
    class Voter(
        val id: String,
        val family: String,
        val edges: DoubleArray,
        val evidence: Set<String>,
        val read: (TokenState, String, Long) -> Double?,
    )

    const val FIRST_BLOCK_SUPPLY = "FIRST_BLOCK_SUPPLY"

    private fun e(vararg v: Double) = v
    private val SCORE = e(30.0, 45.0, 60.0, 75.0)
    private val CONF = e(30.0, 50.0, 70.0, 85.0)
    private val PROB = e(0.30, 0.45, 0.55, 0.70)
    private val PCT = e(-5.0, -2.0, 0.0, 2.0, 5.0)
    private val MULT = e(0.80, 0.95, 1.05, 1.20)
    private val FLAG = e(0.5)

    private fun spec(ts: TokenState) = com.lifecyclebot.engine.SpecialistBrainBridge7542.cachedSnapshot7650(ts.mint)
    private fun pos(v: Double?): Double? = v?.takeIf { it.isFinite() && it > 0.0 }
    private fun fin(v: Double?): Double? = v?.takeIf { it.isFinite() }

    val ALL: List<Voter> = listOf(
        // ── specialists (the 12 lanes' own read, cached by FDG for 5 s) ──
        Voter("SPEC_OWN_SCORE", "SPECIALIST", SCORE, setOf("lane_native")) { ts, lane, _ ->
            spec(ts)?.opinions?.get(lane)?.takeIf { it.gradeable }?.let { if (it.eligible) it.score.toDouble() else 0.0 }
        },
        Voter("SPEC_OWN_CONF", "SPECIALIST", CONF, setOf("lane_native")) { ts, lane, _ ->
            spec(ts)?.opinions?.get(lane)?.takeIf { it.gradeable && it.eligible }?.confidence?.toDouble()
        },
        Voter("SPEC_BREADTH", "SPECIALIST", e(1.0, 2.0, 3.0, 5.0), setOf("lane_native_all")) { ts, _, _ ->
            spec(ts)?.opinions?.values?.count { it.gradeable && it.eligible }?.toDouble()
        },
        Voter("SPEC_MEAN_SCORE", "SPECIALIST", SCORE, setOf("lane_native_all")) { ts, _, _ ->
            spec(ts)?.opinions?.values?.filter { it.gradeable && it.eligible }?.takeIf { it.isNotEmpty() }?.map { it.score }?.average()
        },
        // ── V5.0.8003 — modules that had no seat ──
        Voter("TREASURY_SCALP", "BRAIN", SCORE, setOf("treasury_scalp")) { ts, _, _ ->
            com.lifecyclebot.engine.TreasuryBrain.evaluate(ts).score
        },
        Voter("LANE_TRUST", "STRATEGY", e(0.2, 0.4, 0.6, 0.8), setOf("lane_trust")) { _, lane, _ ->
            com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustRecord(lane)?.trustScore
        },
        Voter("EXEC_CONFIDENCE", "MARKET", e(0.70, 0.85, 0.95), setOf("exec_path")) { _, _, _ ->
            com.lifecyclebot.v4.meta.ExecutionPathAI.takeIf { it.getVenueStats().isNotEmpty() }?.getExecutionConfidenceMultiplier()
        },
        Voter("CRYPTO_BEHAVIOR_ADJ", "BRAIN", e(-10.0, -3.0, 0.0, 3.0, 10.0), setOf("crypto_behavior")) { _, _, _ ->
            com.lifecyclebot.perps.crypto.brain.CryptoBrain.takeIf { it.isReady() }?.scoreAdjustment()?.toDouble()
        },
        // ── V3 committee ──
        Voter("V3_SCORE", "V3", SCORE, setOf("v3")) { ts, _, _ -> ts.lastV3Score?.toDouble() },
        Voter("V3_CONFIDENCE", "V3", CONF, setOf("v3")) { ts, _, _ -> ts.lastV3Confidence?.toDouble() },
        // ── strategy / flow ──
        Voter("ENTRY_SCORE", "STRATEGY", SCORE, setOf("strategy")) { ts, _, _ -> pos(ts.entryScore) },
        Voter("SETUP_QUALITY", "STRATEGY", e(1.5, 2.5, 3.5), setOf("strategy")) { ts, _, _ ->
            when (ts.meta.setupQuality.trim().uppercase()) { "A+" -> 4.0; "A" -> 3.0; "B" -> 2.0; "C" -> 1.0; else -> null }
        },
        Voter("MOMENTUM_SCORE", "FLOW", e(35.0, 50.0, 65.0, 80.0), setOf("flow")) { ts, _, _ -> fin(ts.meta.momScore) },
        Voter("PRESSURE_SCORE", "FLOW", e(35.0, 50.0, 65.0, 80.0), setOf("flow")) { ts, _, _ -> fin(ts.meta.pressScore) },
        Voter("VOLUME_SCORE", "FLOW", e(35.0, 50.0, 65.0, 80.0), setOf("flow")) { ts, _, _ -> fin(ts.meta.volScore) },
        Voter("RSI", "FLOW", e(30.0, 45.0, 55.0, 70.0), setOf("flow_rsi")) { ts, _, _ -> fin(ts.meta.rsi) },
        // ── brains ──
        Voter("CROSSTALK_BOOST", "BRAIN", e(-5.0, -1.0, 1.0, 5.0), setOf("brain_crosstalk")) { ts, _, _ ->
            com.lifecyclebot.engine.AICrossTalk.cachedSignal7654(ts.mint, ts.position.tradingMode)?.entryBoost
        },
        Voter("SUPERBRAIN_SIZE", "BRAIN", e(0.97, 0.995, 1.005, 1.03), setOf("brain_super")) { ts, _, _ ->
            fin(com.lifecyclebot.engine.SuperBrainEnhancements.entrySizeMultiplier(ts.mint))
        },
        Voter("CAPITAL_EFFICIENCY", "BRAIN", MULT, setOf("brain_capital")) { ts, lane, _ ->
            fin(com.lifecyclebot.engine.CapitalEfficiencyBrain.sizeMultiplier(lane, ts.source))
        },
        // ── forecasters ──
        Voter("FORWARD_MODEL_EV", "FORECASTER", PCT, setOf("fwd_labels")) { ts, lane, _ ->
            val f = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.signature8051(ts, lane).let { sig -> com.lifecyclebot.engine.ForwardOutcomeModel.forecast(lane, ts.entryScore.toInt(), sig.first, regime(), sig.second) }
            if (f.samples <= 0L) null else fin(f.expectedPnl)
        },
        Voter("FORWARD_MODEL_PWIN", "FORECASTER", PROB, setOf("fwd_labels")) { ts, lane, _ ->
            val f = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.signature8051(ts, lane).let { sig -> com.lifecyclebot.engine.ForwardOutcomeModel.forecast(lane, ts.entryScore.toInt(), sig.first, regime(), sig.second) }
            if (f.samples <= 0L) null else fin(f.pWin)
        },
        Voter("SCORE_EXPECTANCY", "FORECASTER", e(-10.0, -3.0, 0.0, 3.0, 10.0), setOf("closes")) { ts, lane, _ ->
            fin(com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean(lane, ts.entryScore.toInt()))
        },
        Voter("PATTERN_CLASSIFIER", "FORECASTER", PROB, setOf("pattern")) { ts, _, _ ->
            fin(com.lifecyclebot.engine.PatternClassifier.predictWinProb(com.lifecyclebot.engine.PatternClassifier.extract(ts)))
        },
        // ── memory / education ──
        Voter("TOKEN_WIN_MEMORY", "MEMORY", e(-10.0, -1.0, 1.0, 10.0), setOf("memory_token")) { ts, _, _ ->
            com.lifecyclebot.engine.TokenWinMemory.getMemoryScoreForMint(ts.mint).toDouble()
        },
        Voter("EXPERT_PRIOR", "MEMORY", e(-5.0, -1.0, 1.0, 5.0), setOf("expert")) { ts, lane, _ ->
            val p = com.lifecyclebot.engine.ExpertTraderKnowledge7813.peekPrior7813(ts.mint, lane)
            if (p.confidence <= 0.0) null else fin(p.deltaPct)
        },
        // ── field manual / plan ──
        Voter("PLAN_SETUP", "PLAN", FLAG, setOf("plan")) { ts, _, now ->
            if (com.lifecyclebot.engine.truth.TradePlan7739.readForEntry7837(ts, now).setup != null) 1.0 else 0.0
        },
        Voter("PLAN_REWARD_RISK", "PLAN", e(1.5, 2.5, 4.0), setOf("plan")) { ts, _, now ->
            com.lifecyclebot.engine.truth.TradePlan7739.readForEntry7837(ts, now).takeIf { it.setup != null }?.r
        },
        Voter("EDGE_GATE_CELL", "PLAN", PCT, setOf("fwd_labels")) { ts, lane, now ->
            fin(com.lifecyclebot.engine.truth.LiveEdgeGate7877.verdictFor(ts, lane, now).edgePct)
        },
        Voter("SHAPE_RULE", "PLAN", FLAG, setOf("fwd_labels_shape")) { ts, lane, _ ->
            if (com.lifecyclebot.engine.truth.TradeShapeLearner7883.shapeRefusal(ts, lane) != null) 1.0 else 0.0
        },
        Voter("LAUNCH_READ", "PLAN", e(1.5, 2.5, 3.5), setOf("launch")) { ts, _, now ->
            val v = com.lifecyclebot.engine.truth.FreshLaunchSelector7737.launchRead7742(ts, 4.0, now).verdict
            if (v == com.lifecyclebot.engine.truth.FreshLaunchSelector7737.LaunchVerdict.NOT_FRESH) null else v.ordinal.toDouble()
        },
        Voter("STAGE_FIT", "PLAN", FLAG, setOf("stage")) { ts, lane, _ ->
            if (com.lifecyclebot.engine.TokenMetricStageRouter.laneFit(ts, lane).allowed) 1.0 else 0.0
        },
        // ── safety ──
        Voter("RUGCHECK_SCORE", "SAFETY", e(20.0, 40.0, 60.0, 80.0), setOf("safety")) { ts, _, _ ->
            ts.safety.rugcheckScore.takeIf { it >= 0 }?.toDouble()
        },
        Voter("TOP_HOLDER_PCT", "SAFETY", e(10.0, 20.0, 30.0, 50.0), setOf("holders")) { ts, _, _ ->
            ts.safety.topHolderPct.takeIf { it >= 0.0 && it.isFinite() }
        },
        // V5.0.7885 — a paid feature (BundleDetector, 100 Helius credits): its seat
        // decides whether the fetch keeps being bought (Cortex7885.enrichmentWorth).
        Voter(FIRST_BLOCK_SUPPLY, "SAFETY", e(5.0, 15.0, 30.0, 50.0), setOf("bundle")) { ts, _, _ ->
            ts.safety.firstBlockSupplyPct.takeIf { it >= 0.0 && it.isFinite() }
        },
        Voter("SAFETY_PENALTY", "SAFETY", e(1.0, 10.0, 25.0), setOf("safety")) { ts, _, _ ->
            ts.safety.entryScorePenalty.toDouble()
        },
        // ── market state ──
        Voter("BUY_PRESSURE", "MARKET", e(45.0, 55.0, 65.0, 80.0), setOf("flow_market")) { ts, _, _ -> fin(ts.lastBuyPressurePct) },
        Voter("CHANGE_5M", "MARKET", e(-10.0, -2.0, 2.0, 10.0, 30.0), setOf("price_action")) { ts, _, _ -> fin(ts.lastPriceChange5m) },
        Voter("CHANGE_1H", "MARKET", e(-20.0, -5.0, 5.0, 30.0, 100.0), setOf("price_action")) { ts, _, _ -> fin(ts.lastPriceChange1h) },
        Voter("LIQUIDITY_USD", "MARKET", e(3_000.0, 10_000.0, 50_000.0, 250_000.0), setOf("liquidity")) { ts, _, _ -> pos(ts.lastLiquidityUsd) },
        Voter("MARKET_CAP_USD", "MARKET", e(10_000.0, 50_000.0, 250_000.0, 1_000_000.0, 5_000_000.0), setOf("mcap")) { ts, _, _ -> pos(ts.lastMcap) },
        // ── V5.0.7888 timing / tokenomics (the trade-shape features, as votes) ──
        Voter("AGE_MIN", "TIMING", e(3.0, 15.0, 60.0, 240.0, 1_440.0), setOf("timing_age")) { ts, _, now ->
            stage(ts, now)?.ageMin?.takeIf { it >= 0.0 && it.isFinite() }
        },
        Voter("RUNUP_PCT", "TIMING", e(25.0, 70.0, 150.0, 400.0), setOf("timing_runup")) { ts, _, now ->
            stage(ts, now)?.runupFromLocalLowPct?.takeIf { it >= 0.0 && it.isFinite() }
        },
        Voter("PEAK_POSITION", "TIMING", e(0.5, 0.7, 0.88), setOf("timing_peak")) { ts, _, now ->
            stage(ts, now)?.currentVsPeak?.takeIf { it > 0.0 && it.isFinite() }
        },
        Voter("DRAWDOWN_PCT", "TIMING", e(10.0, 25.0, 50.0), setOf("timing_peak")) { ts, _, now ->
            stage(ts, now)?.drawdownFromPeakPct?.takeIf { it >= 0.0 && it.isFinite() }
        },
        Voter("MCAP_TO_LIQ", "TOKENOMICS", e(3.0, 8.0, 25.0, 85.0), setOf("valuation")) { ts, _, now ->
            stage(ts, now)?.takeIf { it.liquidityUsd > 0.0 && it.marketCapUsd > 0.0 }?.mcapToLiq?.takeIf { it.isFinite() }
        },
        // ── collective (Turso hive): this mint's outcomes across instances ──
        Voter("COLLECTIVE_MINT_PNL", "COLLECTIVE", e(-20.0, -5.0, 0.0, 5.0, 20.0), setOf("collective")) { ts, _, _ ->
            com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getMintMemory(ts.mint)
                ?.takeIf { it.totalOutcomes >= 2 }?.avgPnlPct?.takeIf { it.isFinite() }
        },
        // ── V5.0.7895: built and collected, never used for entries ──
        // momentum predictor sub-scores (only its enum was used)
        Voter("MOMENTUM_ACCUMULATION", "UNUSED_TECH", e(20.0, 40.0, 60.0, 80.0), setOf("mom_predictor")) { ts, _, _ ->
            com.lifecyclebot.engine.MomentumPredictorAI.getMomentum(ts.mint)?.accumulationScore?.let { fin(it) }
        },
        Voter("MOMENTUM_COILING", "UNUSED_TECH", e(20.0, 40.0, 60.0, 80.0), setOf("mom_predictor")) { ts, _, _ ->
            com.lifecyclebot.engine.MomentumPredictorAI.getMomentum(ts.mint)?.coilingScore?.let { fin(it) }
        },
        Voter("MOMENTUM_VOLUME_ACCEL", "UNUSED_TECH", e(20.0, 40.0, 60.0, 80.0), setOf("mom_predictor")) { ts, _, _ ->
            com.lifecyclebot.engine.MomentumPredictorAI.getMomentum(ts.mint)?.volumeAccelerationScore?.let { fin(it) }
        },
        // liquidity depth trend (raw %; only a coarse signal was used)
        Voter("LIQUIDITY_TREND_PCT", "UNUSED_TECH", e(-10.0, -2.0, 2.0, 10.0, 20.0), setOf("liq_trend")) { ts, _, _ ->
            com.lifecyclebot.engine.LiquidityDepthAI.analyzeTrend(ts.mint)
                .takeIf { it.trend != com.lifecyclebot.engine.LiquidityDepthAI.Trend.UNKNOWN }?.changePercent?.let { fin(it) }
        },
        // volume profile (folded as a small score nudge only)
        Voter("VOLUME_POC_DISTANCE", "UNUSED_TECH", e(-20.0, -5.0, 5.0, 20.0, 60.0), setOf("volume_profile")) { ts, _, _ ->
            com.lifecyclebot.engine.VolumeProfileAnalyzer.analyze(ts)?.distanceFromPoc?.let { fin(it) }
        },
        Voter("VOLUME_SKEW", "UNUSED_TECH", e(-0.5, -0.1, 0.1, 0.5), setOf("volume_profile")) { ts, _, _ ->
            com.lifecyclebot.engine.VolumeProfileAnalyzer.analyze(ts)?.volumeSkew?.let { fin(it) }
        },
        // bundle analysis beyond the first-block field (paid for, never read)
        Voter("BUNDLE_LARGEST_PCT", "UNUSED_DATA", e(20.0, 35.0, 45.0, 70.0), setOf("bundle")) { ts, _, _ ->
            com.lifecyclebot.engine.BundleDetector.cachedFresh7763(ts.mint)
                ?.takeIf { it.bundleRisk != com.lifecyclebot.engine.BundleDetector.BundleRisk.UNKNOWN }?.largestBundlePct?.let { fin(it) }
        },
        Voter("BUNDLE_SOLD_FRACTION", "UNUSED_DATA", e(0.25, 0.5, 0.75), setOf("bundle")) { ts, _, _ ->
            com.lifecyclebot.engine.BundleDetector.cachedFresh7763(ts.mint)?.let { b ->
                val n = b.bundledWalletsSold + b.bundledWalletsHolding
                if (n > 0) b.bundledWalletsSold.toDouble() / n else null
            }
        },
        Voter("UNIQUE_WALLETS_FIRST10", "UNUSED_DATA", e(3.0, 5.0, 8.0), setOf("bundle")) { ts, _, _ ->
            com.lifecyclebot.engine.BundleDetector.cachedFresh7763(ts.mint)
                ?.takeIf { it.bundleRisk != com.lifecyclebot.engine.BundleDetector.BundleRisk.UNKNOWN }?.uniqueWalletsFirst10?.toDouble()
        },
        Voter("FIRST_BLOCK_BUYERS", "UNUSED_DATA", e(1.0, 3.0, 6.0, 10.0), setOf("bundle")) { ts, _, _ ->
            ts.safety.takeIf { it.bundleRisk != "UNKNOWN" }?.firstBlockBuyers?.takeIf { it >= 0 }?.toDouble()
        },
        Voter("LP_LOCK_PCT", "UNUSED_DATA", e(50.0, 90.0, 99.0), setOf("lp_lock")) { ts, _, _ ->
            ts.safety.lpLockPct.takeIf { it >= 0.0 && it.isFinite() }
        },
        // holders
        Voter("HOLDER_GROWTH_PCT", "UNUSED_DATA", e(-20.0, -5.0, 5.0, 15.0), setOf("holders_growth")) { ts, _, _ ->
            ts.holderGrowthRate.takeIf { ts.holderDataResolved && it != 0.0 && it.isFinite() }
        },
        Voter("HOLDER_COUNT", "UNUSED_DATA", e(50.0, 150.0, 500.0, 2_000.0), setOf("holders_count")) { ts, _, _ ->
            ts.history.lastOrNull()?.holderCount?.takeIf { it > 0 }?.toDouble()
        },
        Voter("VOLATILITY", "UNUSED_DATA", e(10.0, 25.0, 50.0, 75.0), setOf("volatility")) { ts, _, _ -> ts.volatility?.let { fin(it) } },
        Voter("TURNOVER_5M", "UNUSED_DATA", e(0.02, 0.1, 0.3, 1.0), setOf("turnover")) { ts, _, _ ->
            ts.tokenMap.volume5mUsd?.takeIf { it >= 0.0 && ts.lastLiquidityUsd > 0.0 }?.div(ts.lastLiquidityUsd)
        },
        Voter("MOVE_3_CANDLES", "UNUSED_DATA", e(-10.0, 0.0, 10.0, 40.0), setOf("price_action_short")) { ts, _, _ ->
            fin(ts.meta.move3Pct)?.takeIf { it != 0.0 }
        },
        Voter("MOVE_8_CANDLES", "UNUSED_DATA", e(-10.0, 0.0, 10.0, 40.0), setOf("price_action_short")) { ts, _, _ ->
            fin(ts.meta.move8Pct)?.takeIf { it != 0.0 }
        },
        Voter("LAST_EXIT_PNL", "UNUSED_DATA", e(-20.0, 0.0, 20.0, 100.0), setOf("reentry")) { ts, _, _ ->
            ts.lastExitPnlPct.takeIf { ts.lastExitTs > 0L && it.isFinite() }
        },
        // live alpha pipeline (fetched on the live path, never read)
        Voter("HOLDER_ACCELERATION", "UNUSED_DATA", e(-1.0, 0.0, 1.0, 5.0), setOf("alpha_pipeline")) { ts, _, _ ->
            com.lifecyclebot.engine.DataPipeline.cachedAlphaSignals6486(ts.mint)?.holderAcceleration?.let { fin(it) }
        },
        Voter("BUY_CLUSTERING", "UNUSED_DATA", e(40.0, 70.0, 85.0), setOf("alpha_pipeline")) { ts, _, _ ->
            com.lifecyclebot.engine.DataPipeline.cachedAlphaSignals6486(ts.mint)?.buyClusteringScore?.let { fin(it) }
        },
        Voter("TX_VELOCITY", "UNUSED_DATA", e(1.0, 3.0, 8.0), setOf("alpha_pipeline")) { ts, _, _ ->
            com.lifecyclebot.engine.DataPipeline.cachedAlphaSignals6486(ts.mint)?.txVelocity?.let { fin(it) }
        },
        // market sweep ranking (only a composite multiplier used)
        Voter("SWEEP_PERCENTILE", "UNUSED_DATA", e(0.5, 0.75, 0.9, 0.97), setOf("sweep")) { ts, _, _ ->
            com.lifecyclebot.engine.market.MarketSweep7297.opportunityFor7777(ts.mint)?.percentile?.let { fin(it) }
        },
        Voter("SWEEP_TX_ACCEL", "UNUSED_DATA", e(0.7, 1.0, 1.35, 2.0), setOf("sweep")) { ts, _, _ ->
            com.lifecyclebot.engine.market.MarketSweep7297.opportunityFor7777(ts.mint)?.txAcceleration?.let { fin(it) }
        },
        Voter("SWEEP_VOLUME_ACCEL", "UNUSED_DATA", e(0.7, 1.0, 1.35, 2.0), setOf("sweep")) { ts, _, _ ->
            com.lifecyclebot.engine.market.MarketSweep7297.opportunityFor7777(ts.mint)?.volumeAcceleration?.let { fin(it) }
        },
        Voter("SWEEP_LIQUIDITY_DELTA", "UNUSED_DATA", e(-10.0, 0.0, 12.0), setOf("sweep")) { ts, _, _ ->
            com.lifecyclebot.engine.market.MarketSweep7297.opportunityFor7777(ts.mint)?.liquidityDeltaPct?.let { fin(it) }
        },
        // dev / creator history (cached Helius, only a rugger blacklist used)
        Voter("DEV_TOKENS_CREATED", "UNUSED_DATA", e(1.0, 3.0, 10.0, 30.0), setOf("creator")) { ts, _, _ ->
            com.lifecyclebot.engine.OperatorRegistry.getDevWallet(ts.mint)
                ?.let { com.lifecyclebot.network.HeliusCreatorHistory.peek7895(it) }?.takeIf { it.tokensCreated > 0 }?.tokensCreated?.toDouble()
        },
        Voter("DEV_AVG_RUGCHECK", "UNUSED_DATA", e(30.0, 50.0, 70.0, 85.0), setOf("creator")) { ts, _, _ ->
            com.lifecyclebot.engine.OperatorRegistry.getDevWallet(ts.mint)
                ?.let { com.lifecyclebot.network.HeliusCreatorHistory.peek7895(it) }?.takeIf { it.tokensCreated > 0 }?.avgRugcheckScore?.let { fin(it) }
        },
        // DexScreener social (fetched every 90 s, dropped by the sentiment filter)
        Voter("DEX_BOOST", "UNUSED_DATA", e(1.0, 100.0, 500.0), setOf("dex_social")) { ts, _, _ ->
            com.lifecyclebot.network.DexScreenerSocialSource.peek7895(ts.mint)?.boostTotal?.let { fin(it) }
        },
        Voter("DEX_SOCIAL_LINKS", "UNUSED_DATA", e(1.0, 2.0, 4.0), setOf("dex_social")) { ts, _, _ ->
            com.lifecyclebot.network.DexScreenerSocialSource.peek7895(ts.mint)?.socialCount?.toDouble()
        },
        Voter("DEX_COMMUNITY_TAKEOVER", "UNUSED_DATA", FLAG, setOf("dex_social")) { ts, _, _ ->
            com.lifecyclebot.network.DexScreenerSocialSource.peek7895(ts.mint)?.let { if (it.communityTakeover) 1.0 else 0.0 }
        },
        // V3 modules with per-mint caches read only for exits / lessons
        Voter("REGIME_TRANSITION", "UNUSED_TECH", e(-3.0, -0.5, 0.5, 3.0), setOf("regime_transition")) { ts, _, _ ->
            com.lifecyclebot.v3.scoring.RegimeTransitionAI.getActiveTransitions().firstOrNull { it.first == ts.mint }?.second?.let { sig ->
                val bear = sig.type.name in setOf("RUG_FORMING", "TREND_EXHAUSTION", "DISTRIBUTION_PHASE", "LIQUIDITY_DRAIN")
                // Bearish types carry alphaPotential 0; score them by urgency instead.
                (if (bear) -sig.type.urgency.toDouble() else sig.type.alphaPotential.toDouble()) * sig.confidence / 100.0
            }
        },
        Voter("SCANNER_SOURCE_PNL", "UNUSED_TECH", e(-10.0, -3.0, 0.0, 3.0, 10.0), setOf("source_cohort")) { ts, _, _ ->
            com.lifecyclebot.engine.ScannerSourceBrain.sourceSnapshot7658(ts.source)?.takeIf { it.samples >= 20 }?.avgPnlPct?.let { fin(it) }
        },
        Voter("NARRATIVE_RECENT_PNL", "UNUSED_TECH", e(-10.0, 0.0, 10.0, 20.0), setOf("narrative")) { ts, _, _ ->
            com.lifecyclebot.engine.NarrativeDetectorAI.getNarrativeHeat(com.lifecyclebot.engine.NarrativeDetectorAI.detectNarrative(ts.symbol, ts.name))
                .takeIf { it.tradeCount >= 3 }?.recentAvgPnl?.let { fin(it) }
        },
        Voter("MEME_CLUSTER_CROWDING", "UNUSED_TECH", e(1.0, 2.0, 4.0, 8.0), setOf("meme_cluster")) { ts, _, _ ->
            com.lifecyclebot.v3.scoring.CultMomentumAI.countWithin(com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(ts.symbol, ts.name).cluster).toDouble()
        },
        Voter("INSIDER_SCORE", "UNUSED_TECH", e(10.0, 30.0, 60.0), setOf("insiders")) { ts, _, _ ->
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.getInsiderScore(ts.mint).takeIf { it > 0 }?.toDouble()
        },
        // ── V4 meta layer ──
        Voter("V4_LIQUIDITY_FRAGILITY", "V4", e(0.2, 0.4, 0.6, 0.8), setOf("v4_fragility")) { ts, _, _ ->
            com.lifecyclebot.v4.meta.LiquidityFragilityAI.getReportFor(ts.mint, ts.symbol)?.fragilityScore?.let { fin(it) }
        },
        Voter("V4_NARRATIVE_HEAT", "V4", e(0.2, 0.4, 0.6, 0.8), setOf("v4_narrative")) { ts, _, _ ->
            com.lifecyclebot.v4.meta.NarrativeFlowAI.getNarrativeForSymbol(ts.symbol)?.narrativeHeat?.let { fin(it) }
        },
        Voter("V4_NARRATIVE_EXHAUSTION", "V4", e(0.2, 0.4, 0.6, 0.8), setOf("v4_narrative")) { ts, _, _ ->
            com.lifecyclebot.v4.meta.NarrativeFlowAI.getNarrativeForSymbol(ts.symbol)?.themeExhaustion?.let { fin(it) }
        },
        Voter("V4_LEAD_LAG_ROTATION", "V4", e(0.2, 0.4, 0.6, 0.8), setOf("v4_leadlag")) { ts, _, _ ->
            com.lifecyclebot.v4.meta.CrossAssetLeadLagAI.getLeadSignalFor(ts.symbol)?.let { l ->
                (if (l.direction == "INVERSE") -1.0 else 1.0) * l.rotationProbability
            }
        },
        Voter("V4_CROSSTALK_DIRECTION", "V4", e(-0.5, -0.1, 0.1, 0.5), setOf("v4_crosstalk")) { ts, _, _ ->
            com.lifecyclebot.v4.meta.CrossTalkFusionEngine.getSignalsForSymbol(ts.symbol).filter { it.direction != null }
                .takeIf { it.isNotEmpty() }?.map { (if (it.direction == "SHORT") -1.0 else 1.0) * it.confidence }?.average()
        },
        Voter("V4_GLOBAL_RISK_MODE", "V4", e(0.5, 1.5, 2.5, 3.5, 4.5), setOf("v4_regime")) { _, _, _ ->
            com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime().ordinal.toDouble()
        },
        Voter("V4_PORTFOLIO_HEAT", "V4", e(0.2, 0.4, 0.6, 0.8), setOf("v4_portfolio")) { _, _, _ ->
            fin(com.lifecyclebot.v4.meta.PortfolioHeatAI.getPortfolioHeat())
        },
        // V5.0.7907 — which of the lane's playbook setups this candidate is (NO_TRIGGER = menu size).
        // V5.0.8076 — V2's fixed 21 bins merged every appended setup >=20. A new voter identity
        // learns the full menu from its first label; existing mixed V2 history is preserved separately.
        Voter("PLAYBOOK_SETUP_V3", "PLAYBOOK", LanePlaybook7907.setupBinEdges8076(), setOf("playbook")) { ts, lane, now ->
            LanePlaybook7907.setupIndex(ts, lane, now)
        },
        Voter("SENTIMENT", "MARKET", e(-0.3, -0.05, 0.05, 0.3), setOf("sentiment")) { ts, _, _ ->
            ts.sentiment.takeIf { it.confidence > 0.0 }?.score?.let { fin(it) }
        },
        // V5.0.7916 — Cortex v14: the Super/SSI planner stack and the LLM analysts
        // as voters (v1 §2.3). Their proposals were blended into the oracle with
        // hand weights; here each is graded on forward labels and earns authority
        // per lane, or none. Read-only peeks: no planner run, no LLM call.
        Voter("SUPER_PLAN_EXPOSURE", "SUPER", e(0.2, 0.7, 1.2), setOf("super_plan")) { ts, lane, now ->
            superStamp(ts, lane, now)?.planAction?.exposure
        },
        Voter("SUPER_WORLD_TACTICAL_EV", "SUPER", PCT, setOf("super_world")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.world?.forHorizon(com.lifecyclebot.engine.SuperWorldModel7634.Horizon.TACTICAL)?.expectedPnlPct)
        },
        Voter("SUPER_WORLD_THESIS_PWIN", "SUPER", PROB, setOf("super_world")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.world?.forHorizon(com.lifecyclebot.engine.SuperWorldModel7634.Horizon.THESIS)?.pWin)
        },
        Voter("SUPER_WORLD_TAIL", "SUPER", e(0.05, 0.2, 0.4, 0.6), setOf("super_world")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.world?.tailOpportunity)
        },
        Voter("SUPER_WORLD_FAILURE_RISK", "SUPER", e(0.2, 0.4, 0.6, 0.72), setOf("super_world")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.world?.failureRisk)
        },
        Voter("SUPER_LATENT_STATE", "SUPER", e(0.5, 1.5, 2.5, 3.5, 4.5), setOf("super_world")) { ts, lane, now ->
            superStamp(ts, lane, now)?.world?.latentState?.ordinal?.toDouble()
        },
        Voter("SUPER_CRITIC_FRAGILITY", "SUPER", e(0.2, 0.4, 0.6, 0.8), setOf("super_critic")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.criticFragility)
        },
        Voter("SUPER_TREE_CONFIDENCE", "SUPER", e(0.2, 0.4, 0.6, 0.8), setOf("super_tree")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.treeConfidence)
        },
        Voter("SUPER_ARBITER_META_CONF", "SUPER", e(0.2, 0.4, 0.6, 0.8), setOf("super_arbiter")) { ts, lane, now ->
            fin(superStamp(ts, lane, now)?.arbiterMetaConfidence)
        },
        Voter("LLM_VIRAL_POTENTIAL", "LLM", e(30.0, 50.0, 70.0, 85.0), setOf("llm_narrative")) { ts, _, _ ->
            fin(llmNarrative(ts)?.analysis?.viralPotential)
        },
        Voter("LLM_SCAM_CONFIDENCE", "LLM", e(10.0, 30.0, 60.0), setOf("llm_scam")) { ts, _, _ ->
            llmNarrative(ts)?.let { n -> if (n.quickScam == true) 100.0 else n.analysis?.let { a -> if (a.isScam) fin(a.scamConfidence) else 0.0 } }
        },
        Voter("LLM_RECOMMENDATION", "LLM", e(-0.5, 0.5), setOf("llm_narrative")) { ts, _, _ ->
            when (llmNarrative(ts)?.analysis?.recommendation) { "BUY" -> 1.0; "AVOID" -> -1.0; "WATCH" -> 0.0; else -> null }
        },
        // V5.0.7921 — Cortex v16: the launch tape since birth (LaunchTape7921).
        Voter("LAUNCH_CROWD_BUYERS", "LAUNCH", e(5.0, 15.0, 30.0, 60.0), setOf("launch_tape")) { ts, _, now ->
            launch(ts, now)?.crowdBuyers?.toDouble()
        },
        Voter("LAUNCH_NET_SOL", "LAUNCH", e(0.0, 1.0, 3.0, 8.0, 20.0), setOf("launch_tape")) { ts, _, now ->
            fin(launch(ts, now)?.crowdNetSol)
        },
        Voter("LAUNCH_BUYERS_PER_MIN", "LAUNCH", e(1.0, 3.0, 6.0, 12.0), setOf("launch_tape")) { ts, _, now ->
            fin(launch(ts, now)?.buyersPerMin)
        },
        Voter("LAUNCH_BUY_SHARE", "LAUNCH", e(45.0, 55.0, 65.0, 75.0), setOf("launch_tape")) { ts, _, now ->
            fin(launch(ts, now)?.buySharePct)
        },
        Voter("LAUNCH_LARGEST_BUYER", "LAUNCH", e(15.0, 30.0, 50.0), setOf("launch_tape")) { ts, _, now ->
            launch(ts, now)?.takeIf { it.crowdBuyers > 0 }?.largestBuyerPct
        },
        Voter("LAUNCH_DEV_SOLD", "LAUNCH", FLAG, setOf("launch_tape")) { ts, _, now ->
            launch(ts, now)?.let { if (it.devSold) 1.0 else 0.0 }
        },
        Voter("LAUNCH_FROM_FIRST_PCT", "LAUNCH", e(-30.0, 0.0, 50.0, 150.0), setOf("launch_tape")) { ts, _, now ->
            fin(launch(ts, now)?.fromFirstPct)
        },
        Voter("LAUNCH_HEAT", "LAUNCH", com.lifecyclebot.engine.market.LaunchTape7921.HEAT_EDGES, setOf("launch_tape")) { ts, _, now ->
            fin(launch(ts, now)?.heat)
        },
        // V5.0.7917 — Cortex v15: the legacy FDG size stack's composite shape, graded.
        Voter(Cortex7885.LEGACY_SIZE_SHAPE, "SIZING", e(0.5, 0.8, 1.0, 1.25), setOf("size_stack")) { ts, _, now ->
            Cortex7885.legacyShapeOf(ts.mint, now)
        },
    ) + CortexVotersWide8004.VOTERS  // V5.0.8004 — the wide estate

    private fun superStamp(ts: TokenState, lane: String, now: Long) =
        try { com.lifecyclebot.engine.SuperIntelligenceCalibration7636.peek7916(ts.mint, lane, now) } catch (_: Throwable) { null }

    private fun launch(ts: TokenState, now: Long) =
        try { com.lifecyclebot.engine.market.LaunchTape7921.features(ts.mint, now) } catch (_: Throwable) { null }

    private fun llmNarrative(ts: TokenState) =
        try { com.lifecyclebot.engine.AsyncGeminiNarrativeCache6478.peekExact7925(ts.symbol, ts.name) } catch (_: Throwable) { null }

    private val V3_MODULE_EDGES = e(-10.0, -3.0, 0.0, 3.0, 10.0)

    /**
     * V5.0.7895 — each V3 UnifiedScorer module (about 50 of them, previously only
     * summed into one score) as its own voter, from the per-mint component snapshot
     * the scorer records. Absent snapshot = no votes.
     */
    fun dynamicVotes(ts: TokenState): List<CortexLedger7885.Vote> {
        val lanes = laneBrainVotes8003(ts)
        val comps = com.lifecyclebot.v3.scoring.EducationSubLayerAI.peekEntryScores7895(ts.mint)
        val v3m = comps?.entries?.sortedBy { it.key }?.take(80)?.map { (name, v) ->
            val id = "V3M_" + name.uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(32)
            CortexLedger7885.Vote(id, V3_MODULE_EDGES, v.toDouble(), setOf("v3_module_$id"))
        }?.distinctBy { it.voterId }.orEmpty()
        lastLaneVotes8003 = lanes.size
        if (comps != null) lastV3Votes8003 = v3m.size
        return lanes + v3m
    }

    @Volatile private var lastLaneVotes8003 = 0
    @Volatile private var lastV3Votes8003 = 0

    /** Lane-brain voter id stem from a lane name: "LB_<LANE>". */
    internal fun laneBrainStem8003(lane: String): String =
        "LB_" + lane.uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(24)

    /**
     * V5.0.8003 — every one of the 12 lane brains votes on every candidate in every
     * lane's seat, not just its own lane (SPEC_OWN_*): score (0 when it refuses),
     * confidence, and conviction on its own pass bar. A MOONSHOT brain's read is
     * then graded as evidence for the SHITCOIN lane's decision too. Evidence is per
     * brain, so one brain's three numbers count once in fusion.
     */
    internal fun laneBrainVotes8003(ts: TokenState): List<CortexLedger7885.Vote> {
        val ops = spec(ts)?.opinions ?: return emptyList()
        return ops.values.filter { it.gradeable }.sortedBy { it.lane }.flatMap { o ->
            val stem = laneBrainStem8003(o.lane)
            val ev = setOf("lane_brain_$stem")
            listOfNotNull(
                CortexLedger7885.Vote("${stem}_S", SCORE, if (o.eligible) o.score.toDouble() else 0.0, ev),
                if (o.eligible) CortexLedger7885.Vote("${stem}_C", CONF, o.confidence.toDouble(), ev) else null,
                o.ownershipConviction7948?.takeIf { it.isFinite() }?.let { CortexLedger7885.Vote("${stem}_V", SCORE, it, ev) },
            )
        }.distinctBy { it.voterId }
    }

    /** Voter census for the diag: static + lane brains + V3 modules (last read). */
    fun votersLine8003(): String =
        "${ALL.size}static+${lastLaneVotes8003}laneBrain+${lastV3Votes8003}V3modules+crosses"

    val IDS: List<String> = ALL.map { it.id }
    val EDGES: List<DoubleArray> = ALL.map { it.edges }
    private val EDGES_BY_ID: Map<String, DoubleArray> by lazy { ALL.associate { it.id to it.edges } }

    /**
     * V5.0.7920 — a voter's bin edges from its id alone (static voter, V3 module,
     * or a pairwise cross), so a persisted decision can be graded after a restart.
     */
    fun edgesFor(id: String): DoubleArray? {
        EDGES_BY_ID[id]?.let { return it }
        if (id.startsWith("V3M_")) return V3_MODULE_EDGES
        if (id.startsWith("LB_")) return when {
            id.endsWith("_S") || id.endsWith("_V") -> SCORE
            id.endsWith("_C") -> CONF
            else -> null
        }
        if (id.startsWith("X_")) {
            val parts = id.removePrefix("X_").split("__")
            if (parts.size != 2) return null
            val a = EDGES_BY_ID[parts[0]] ?: return null
            val b = EDGES_BY_ID[parts[1]] ?: return null
            return DoubleArray((a.size + 1) * (b.size + 1) - 1) { k -> k + 0.5 }
        }
        return null
    }

    // One stage snapshot per (mint, read time) — five timing voters share it.
    @Volatile private var stageMemo: Triple<String, Long, com.lifecyclebot.engine.TokenMetricStageRouter.Snapshot>? = null

    private fun stage(ts: TokenState, now: Long): com.lifecyclebot.engine.TokenMetricStageRouter.Snapshot? {
        stageMemo?.let { (m, t, snap) -> if (m == ts.mint && t == now) return snap }
        val snap = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts) } catch (_: Throwable) { return null }
        stageMemo = Triple(ts.mint, now, snap)
        return snap
    }

    private fun regime(): String = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" }

    // V5.0.7909 — per-voter time budget (v1 §2.9). Each voter's read time is
    // tracked (EW mean); a voter averaging over SLOW_MS is read on a 10% sample
    // only, so one slow component cannot hold every assessment hostage.
    private const val SLOW_MS = 4.0
    private val readMs = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val slowSkips = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun slowLine(): String = readMs.entries.filter { it.value > SLOW_MS }.sortedByDescending { it.value }.take(6)
        .joinToString(",") { "${it.key}=${"%.1f".format(it.value)}ms/skip${slowSkips[it.key]?.get() ?: 0}" }.ifBlank { "none" }

    // ── V5.0.8187 §VOTER_TIME_BUDGET — the hot read never waits on a slow voter ──
    //
    // 5.0.8186: assess avgMs=1,799.9 (8175: 16.6), capture meanLagMs=14,878, lateVoterReads=172 (slow voters 58-160 ms each,
    // still read inline on a 10% sample, and the cold capture path assessed every coin in full). Now a voter averaging over
    // [CACHED_MS_8187] — or any voter once the read has spent [ASSESS_BUDGET_MS_8187] — is served from its own per-coin cache
    // (a value read at most [VOTER_CACHE_TTL_MS_8187] earlier, before this read) or abstains (NaN = unknown, which the ledger
    // already handles); a missing or stale entry is refreshed on a separate background worker, never on the decision path.
    private const val CACHED_MS_8187 = 2.0
    const val ASSESS_BUDGET_MS_8187 = 35.0
    const val VOTER_CACHE_TTL_MS_8187 = 45_000L
    private const val VOTER_CACHE_MAX_8187 = 30_000
    private class CachedRead8187(val v: Double, val atMs: Long)
    private val voterCache8187 = java.util.concurrent.ConcurrentHashMap<String, CachedRead8187>()
    private val cacheServed8187 = java.util.concurrent.atomic.AtomicLong(0)
    private val budgetAbstained8187 = java.util.concurrent.atomic.AtomicLong(0)
    private val refreshDropped8187 = java.util.concurrent.atomic.AtomicLong(0)
    private val refreshPool8187: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(512),
        { r -> Thread(r, "cortex-voter-refresh-8187").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        // V5.0.8291 — was DiscardPolicy: a discarded refresh never reached its finally, so its key stayed in the queued set
        // for good and that (voter, coin) was never refreshed again (abstained=79094 vs cacheServed=19418 in 5.0.8286).
        // A rejected job now releases its coin and is counted exactly.
        java.util.concurrent.RejectedExecutionHandler { r, _ -> (r as? RefreshTask8291)?.let { refreshJobs8291.remove(it.key) }; refreshDropped8187.incrementAndGet() },
    )

    /** Pure (V5.0.8187): is a voter served from its cache on this read (slow on average, or the read is over budget)? */
    fun servedFromCache8187(avgMs: Double, elapsedMs: Double): Boolean = avgMs > CACHED_MS_8187 || elapsedMs > ASSESS_BUDGET_MS_8187

    /** Pure (V5.0.8187): may a cached voter value read at [atMs] stand in for a read at [wallMs]? Never one from the future. */
    fun cacheFresh8187(atMs: Long, wallMs: Long): Boolean = atMs > 0L && wallMs - atMs in 0L..VOTER_CACHE_TTL_MS_8187

    private fun cacheKey8187(i: Int, mint: String, lane: String): String = if (laneFree8169[i]) "$i|$mint" else "$i|$mint|$lane"

    private fun timedRead8187(i: Int, ts: TokenState, lane: String, nowMs: Long,
                              failures: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>?): Double {
        val v = ALL[i]
        val t0 = System.nanoTime()
        val r = try { v.read(ts, lane, nowMs)?.takeIf { it.isFinite() } ?: Double.NaN } catch (_: Throwable) {
            failures?.computeIfAbsent(v.id) { java.util.concurrent.atomic.AtomicLong(0) }?.incrementAndGet()
            Double.NaN
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        readMs[v.id] = (readMs[v.id] ?: ms) * 0.9 + ms * 0.1
        return r
    }

    private fun scheduleRefresh8187(i: Int, key: String, ts: TokenState, lane: String) {
        // V5.0.8291 — one background job per coin x lane reads every voter of that coin the hot path asked for (it was one
        // job per voter: ~290 jobs a coin against a 512-slot queue, so most refreshes were dropped and the cache never filled).
        val now = System.currentTimeMillis()
        if (!refreshClaim8291(refreshQueued8291.putIfAbsent(key, now), now)) return
        refreshQueued8291[key] = now
        val jobKey = "${ts.mint}|$lane"
        var created = false
        val job = refreshJobs8291.computeIfAbsent(jobKey) { created = true; RefreshJob8291(ts, lane) }
        job.indices.add(i)
        if (!created) return
        try { refreshPool8187.execute(RefreshTask8291(jobKey)) } catch (_: Throwable) { refreshJobs8291.remove(jobKey); refreshDropped8187.incrementAndGet() }
    }

    // ── V5.0.8291 §REFRESH_FILLS_THE_CACHE ──
    /** A queued claim older than this is stale (its job was dropped or raced): the voter may be asked for again. */
    const val REFRESH_STALE_MS_8291 = 30_000L

    /** Pure (V5.0.8291): may a refresh be queued for a voter whose existing claim was made at [prevAtMs] (null: none)? */
    fun refreshClaim8291(prevAtMs: Long?, nowMs: Long): Boolean = prevAtMs == null || nowMs - prevAtMs !in 0L until REFRESH_STALE_MS_8291

    /** "voter|coin" -> when its refresh was queued. Was a plain set whose dropped entries were never released. */
    private val refreshQueued8291 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private class RefreshJob8291(val ts: TokenState, val lane: String) {
        val indices: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
    private class RefreshTask8291(val key: String) : Runnable {
        override fun run() { CortexVoters7885.runRefresh8291(key) }
    }
    private val refreshJobs8291 = java.util.concurrent.ConcurrentHashMap<String, RefreshJob8291>()
    private val refreshFilled8291 = java.util.concurrent.atomic.AtomicLong(0)

    private fun runRefresh8291(jobKey: String) {
        val job = refreshJobs8291.remove(jobKey) ?: return
        if (refreshQueued8291.size > VOTER_CACHE_MAX_8187) { val now = System.currentTimeMillis(); refreshQueued8291.entries.removeIf { now - it.value >= REFRESH_STALE_MS_8291 } }
        for (i in job.indices.toList()) {
            val key = cacheKey8187(i, job.ts.mint, job.lane)
            try {
                val wall = System.currentTimeMillis()
                val r = timedRead8187(i, job.ts, job.lane, wall, null)
                if (voterCache8187.size >= VOTER_CACHE_MAX_8187) voterCache8187.entries.removeIf { !cacheFresh8187(it.value.atMs, wall) }
                if (voterCache8187.size < VOTER_CACHE_MAX_8187) { voterCache8187[key] = CachedRead8187(r, wall); refreshFilled8291.incrementAndGet() }
            } catch (_: Throwable) {
            } finally { refreshQueued8291.remove(key) }
        }
    }

    // ── V5.0.8289 — memory census and pressure trim (MemoryGuard7977 registry) ──
    /** Cached voter reads (key "i|mint|lane" ~60 chars + [CachedRead8187] + node) and per-coin memos (each pins a TokenState). */
    fun cacheCount8289(): Int = voterCache8187.size + memo8169.size
    fun estBytes8289(): Long = voterCache8187.size * 150L + memo8169.size * (64L + ALL.size * 8L)

    /** Pressure trim: cached reads no longer fresh (never served) and per-coin memos past [MEMO_MS_8169] (never reused). */
    fun trim8289(nowMs: Long = System.currentTimeMillis()): Int {
        val before = cacheCount8289()
        voterCache8187.entries.removeIf { !cacheFresh8187(it.value.atMs, nowMs) }
        memo8169.entries.removeIf { nowMs - it.value.wallMs > MEMO_MS_8169 }
        return (before - cacheCount8289()).coerceAtLeast(0)
    }

    /** "budget8187[...]" for the compute line: cache-served reads, abstentions, dropped refreshes. */
    fun budgetLine8187(): String = "budget8187[cacheServed=${cacheServed8187.get()} abstained=${budgetAbstained8187.get()} " +
        "refreshQueued=${refreshPool8187.queue.size} refreshDropped=${refreshDropped8187.get()} cached=${voterCache8187.size} budgetMs=${ASSESS_BUDGET_MS_8187.toInt()}] " +
        coverageLine8291()

    // ── V5.0.8291 §VOTE_COVERAGE — how much of each assessment the voters actually answered ──
    //
    // 5.0.8286: abstained=79094 vs cacheServed=19418 — four in five budget-routed reads abstained, and nothing said how many
    // voters a single assessment lost. Now (1) the voters seated in the assessing lane (the ones fusion weighs: [core]) are
    // always read inline — never abstained by the budget; (2) the rest are read cheapest first, so the 35 ms budget is spent
    // on many fast voters and only the slow tail is routed to the cache; (3) each assessment's coverage is recorded.
    private val coverageRing8291 = DoubleArray(256)
    private val coverageN8291 = java.util.concurrent.atomic.AtomicLong(0)
    private val coreRead8291 = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var readOrderCache8291: IntArray? = null
    @Volatile private var readOrderAtMs8291 = 0L
    private const val READ_ORDER_REFRESH_MS_8291 = 10_000L

    private class ReadTally8291 { var abstained = 0; var core = 0 }

    /** Pure (V5.0.8291): voter indices ordered cheapest first by their mean read time (an unmeasured voter counts 0 ms). */
    fun cheapestFirst8291(avgMs: DoubleArray): IntArray = avgMs.indices.sortedBy { if (avgMs[it].isFinite()) avgMs[it] else 0.0 }.toIntArray()

    /** Pure (V5.0.8291): the share of [total] voters an assessment answered when [abstained] of them abstained on the budget. */
    fun coverage8291(abstained: Int, total: Int): Double = if (total <= 0) 1.0 else (1.0 - abstained.toDouble() / total).coerceIn(0.0, 1.0)

    private fun readOrder8291(wall: Long): IntArray {
        val cur = readOrderCache8291
        if (cur != null && cur.size == ALL.size && wall - readOrderAtMs8291 in 0L until READ_ORDER_REFRESH_MS_8291) return cur
        val next = cheapestFirst8291(DoubleArray(ALL.size) { readMs[ALL[it].id] ?: 0.0 })
        readOrderCache8291 = next; readOrderAtMs8291 = wall
        return next
    }

    private fun noteCoverage8291(c: Double) {
        val i = (coverageN8291.getAndIncrement() % coverageRing8291.size).toInt()
        synchronized(coverageRing8291) { coverageRing8291[i] = c }
    }

    /** "coverage8291[p10= p50= n= coreInline= refreshFilled=]" — per-assessment share of voters that answered. */
    fun coverageLine8291(): String {
        val n = coverageN8291.get().coerceAtMost(coverageRing8291.size.toLong()).toInt()
        val ring = synchronized(coverageRing8291) { coverageRing8291.copyOf() }
        val q = { p: Double -> if (n == 0) "-" else "%.2f".format(ring.copyOf(n).sorted()[((n - 1) * p).toInt()]) }
        return "coverage8291[p10=${q(0.1)} p50=${q(0.5)} n=${coverageN8291.get()} coreInline=${coreRead8291.get()} refreshFilled=${refreshFilled8291.get()}]"
    }

    /** Read every voter for (ts, lane). Index-aligned with [ALL]; NaN = abstained or failed. */
    fun readAll(ts: TokenState, lane: String, nowMs: Long, failures: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>? = null,
                core8291: BooleanArray? = null): DoubleArray {
        // V5.0.8169 — the lane-free voters (248 of 291 never read the lane) are read once per coin per moment and shared
        // by every lane assessing that coin (8168: 164.85 ms per assessment, the same coin assessed by up to 12 lanes).
        val wall = System.currentTimeMillis()
        val memo = memo8169[ts.mint]?.takeIf { memoReusable8169(it.ts === ts, it.priceAt, ts.lastPriceUpdate, it.wallMs, wall, it.nowMs, nowMs) }
        val free = laneFree8169
        val t0 = System.nanoTime()
        // V5.0.8291 — cheapest first; the lane's seated voters inline whatever the budget (see §VOTE_COVERAGE).
        val tally = ReadTally8291()
        val out = DoubleArray(ALL.size) { Double.NaN }
        for (i in readOrder8291(wall)) {
            out[i] = if (memo != null && free[i]) memo.vals[i]
                else readOne8169(i, ts, lane, nowMs, failures, (System.nanoTime() - t0) / 1_000_000.0, core8291?.getOrNull(i) == true, tally)
        }
        noteCoverage8291(coverage8291(tally.abstained, ALL.size))
        if (tally.core > 0) coreRead8291.addAndGet(tally.core.toLong())
        if (memo != null) memoHits8169.incrementAndGet() else {
            memoMisses8169.incrementAndGet()
            if (memo8169.size >= MEMO_MAX_8169) memo8169.entries.removeIf { wall - it.value.wallMs > MEMO_MS_8169 }
            if (memo8169.size < MEMO_MAX_8169) memo8169[ts.mint] = Memo8169(ts, ts.lastPriceUpdate, wall, nowMs, out)
        }
        return out
    }

    private fun readOne8169(i: Int, ts: TokenState, lane: String, nowMs: Long,
                            failures: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>?,
                            elapsedMs8187: Double = 0.0, core8291: Boolean = false, tally8291: ReadTally8291? = null): Double {
        val v = ALL[i]
        val avg = readMs[v.id] ?: 0.0
        // V5.0.8291 — a voter seated in this lane is never abstained by the budget: fusion weighs it.
        if (core8291) { tally8291?.let { it.core++ }; return timedRead8187(i, ts, lane, nowMs, failures) }
        // V5.0.8187 — slow (or over-budget) voters are served from their per-coin cache, refreshed in the background.
        if (servedFromCache8187(avg, elapsedMs8187)) {
            val key = cacheKey8187(i, ts.mint, lane)
            val wall = System.currentTimeMillis()
            val c = voterCache8187[key]
            val fresh = c != null && cacheFresh8187(c.atMs, wall)
            if (!fresh || wall - (c?.atMs ?: 0L) > VOTER_CACHE_TTL_MS_8187 / 2) scheduleRefresh8187(i, key, ts, lane)
            slowSkips.computeIfAbsent(v.id) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
            return if (fresh && c != null) { cacheServed8187.incrementAndGet(); c.v } else { budgetAbstained8187.incrementAndGet(); tally8291?.let { it.abstained++ }; Double.NaN }
        }
        return timedRead8187(i, ts, lane, nowMs, failures)
    }

    // ── V5.0.8169 — lane-free read sharing (behaviour-preserving: same coin object, same price stamp, within 3 s) ──

    /**
     * The voters whose read takes the assessment's lane (declared `{ ts, lane, now -> }`). Every other voter is declared
     * with `_` for the lane, so its read for one coin at one moment is the same whichever lane asks.
     * Aate8169CortexTest re-derives this set from the two voter sources: a new lane-reading voter must be added here.
     */
    internal val LANE_DEPENDENT_8169: Set<String> = setOf(
        "SPEC_OWN_SCORE", "SPEC_OWN_CONF", "LANE_TRUST", "CAPITAL_EFFICIENCY", "FORWARD_MODEL_EV", "FORWARD_MODEL_PWIN",
        "SCORE_EXPECTANCY", "EXPERT_PRIOR", "EDGE_GATE_CELL", "SHAPE_RULE", "STAGE_FIT", "PLAYBOOK_SETUP_V3",
        "SUPER_PLAN_EXPOSURE", "SUPER_WORLD_TACTICAL_EV", "SUPER_WORLD_THESIS_PWIN", "SUPER_WORLD_TAIL", "SUPER_WORLD_FAILURE_RISK",
        "SUPER_LATENT_STATE", "SUPER_CRITIC_FRAGILITY", "SUPER_TREE_CONFIDENCE", "SUPER_ARBITER_META_CONF",
        "FRL_CELL_MEAN", "FRL_CELL_WINRATE", "FRL_CELL_RUNNER_RATE", "FRL_CELL_PROOF_TIER", "SPECIALIST_MATCH",
        "HARVARD_LANE_EXPECTANCY", "BEHAVIOR_LEARNING_ADJ", "LOSING_PATTERN_MEAN", "LOSING_PATTERN_LOSS_RATE", "MODE_LEARNING_BONUS",
        "TOXIC_PATTERN", "CROSSTALK_CONF_BOOST", "CROSSTALK_SIZE", "CROSSTALK_CORRELATION", "CROSSTALK_EXIT_URGENCY",
        "CROSSTALK_PARTICIPANTS", "CROSSTALK_MEME_SHAPE", "ULTIMATE_EDGE_PWIN", "ULTIMATE_EDGE_EXP_PNL", "ULTIMATE_EDGE_OPPORTUNITY",
        "ULTIMATE_EDGE_REL_STRENGTH", "REGIME_FIT_LANE",
    )

    const val MEMO_MS_8169 = 3_000L
    private const val MEMO_MAX_8169 = 128

    private class Memo8169(val ts: TokenState, val priceAt: Long, val wallMs: Long, val nowMs: Long, val vals: DoubleArray)

    private val memo8169 = java.util.concurrent.ConcurrentHashMap<String, Memo8169>()
    private val memoHits8169 = java.util.concurrent.atomic.AtomicLong(0)
    private val memoMisses8169 = java.util.concurrent.atomic.AtomicLong(0)
    private val laneFree8169: BooleanArray by lazy { BooleanArray(ALL.size) { ALL[it].id !in LANE_DEPENDENT_8169 } }

    /**
     * Pure (V5.0.8169): may a coin's lane-free reads taken at wall time [readWallMs] (decision time [readNowMs], price stamp
     * [priceAtThen]) stand in for a read now? Only for the same coin object with the same price stamp, read at most
     * [MEMO_MS_8169] ago, for a decision at most [MEMO_MS_8169] apart.
     */
    fun memoReusable8169(sameObject: Boolean, priceAtThen: Long, priceAtNow: Long, readWallMs: Long, wallNowMs: Long,
                         readNowMs: Long, nowMs: Long): Boolean =
        sameObject && priceAtThen == priceAtNow && wallNowMs >= readWallMs && wallNowMs - readWallMs <= MEMO_MS_8169 &&
            kotlin.math.abs(nowMs - readNowMs) <= MEMO_MS_8169

    fun laneFreeCount8169(): Int = laneFree8169.count { it }

    /** "memo8169[hit= miss= laneFree=]" for the compute line. */
    fun memoLine8169(): String = "memo8169[hit=${memoHits8169.get()} miss=${memoMisses8169.get()} laneFree=${laneFreeCount8169()}/${ALL.size}]"
}
