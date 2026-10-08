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
            val f = com.lifecyclebot.engine.ForwardOutcomeModel.forecast(lane, ts.entryScore.toInt(), "U", regime(), "UNKNOWN")
            if (f.samples <= 0L) null else fin(f.expectedPnl)
        },
        Voter("FORWARD_MODEL_PWIN", "FORECASTER", PROB, setOf("fwd_labels")) { ts, lane, _ ->
            val f = com.lifecyclebot.engine.ForwardOutcomeModel.forecast(lane, ts.entryScore.toInt(), "U", regime(), "UNKNOWN")
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
        Voter("PLAYBOOK_SETUP", "PLAYBOOK", e(0.5, 1.5, 2.5, 3.5, 4.5, 5.5), setOf("playbook")) { ts, lane, now ->
            LanePlaybook7907.setupIndex(ts, lane, now)
        },
        Voter("SENTIMENT", "MARKET", e(-0.3, -0.05, 0.05, 0.3), setOf("sentiment")) { ts, _, _ ->
            ts.sentiment.takeIf { it.confidence > 0.0 }?.score?.let { fin(it) }
        },
    )

    private val V3_MODULE_EDGES = e(-10.0, -3.0, 0.0, 3.0, 10.0)

    /**
     * V5.0.7895 — each V3 UnifiedScorer module (about 50 of them, previously only
     * summed into one score) as its own voter, from the per-mint component snapshot
     * the scorer records. Absent snapshot = no votes.
     */
    fun dynamicVotes(ts: TokenState): List<CortexLedger7885.Vote> {
        val comps = com.lifecyclebot.v3.scoring.EducationSubLayerAI.peekEntryScores7895(ts.mint) ?: return emptyList()
        return comps.entries.sortedBy { it.key }.take(80).map { (name, v) ->
            val id = "V3M_" + name.uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(32)
            CortexLedger7885.Vote(id, V3_MODULE_EDGES, v.toDouble(), setOf("v3_module_$id"))
        }.distinctBy { it.voterId }
    }

    val IDS: List<String> = ALL.map { it.id }
    val EDGES: List<DoubleArray> = ALL.map { it.edges }

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

    /** Read every voter for (ts, lane). Index-aligned with [ALL]; NaN = abstained or failed. */
    fun readAll(ts: TokenState, lane: String, nowMs: Long, failures: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>? = null): DoubleArray =
        DoubleArray(ALL.size) { i ->
            val v = ALL[i]
            val avg = readMs[v.id] ?: 0.0
            if (avg > SLOW_MS && kotlin.random.Random.nextDouble() > 0.10) {
                slowSkips.computeIfAbsent(v.id) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
                Double.NaN
            } else {
                val t0 = System.nanoTime()
                val r = try { v.read(ts, lane, nowMs)?.takeIf { it.isFinite() } ?: Double.NaN } catch (_: Throwable) {
                    failures?.computeIfAbsent(v.id) { java.util.concurrent.atomic.AtomicLong(0) }?.incrementAndGet()
                    Double.NaN
                }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                readMs[v.id] = avg * 0.9 + ms * 0.1
                r
            }
        }
}
