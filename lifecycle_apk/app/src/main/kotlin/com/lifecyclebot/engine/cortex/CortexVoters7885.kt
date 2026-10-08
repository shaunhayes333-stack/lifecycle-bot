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
        Voter("SENTIMENT", "MARKET", e(-0.3, -0.05, 0.05, 0.3), setOf("sentiment")) { ts, _, _ ->
            ts.sentiment.takeIf { it.confidence > 0.0 }?.score?.let { fin(it) }
        },
    )

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

    /** Read every voter for (ts, lane). Index-aligned with [ALL]; NaN = abstained or failed. */
    fun readAll(ts: TokenState, lane: String, nowMs: Long, failures: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>? = null): DoubleArray =
        DoubleArray(ALL.size) { i ->
            val v = ALL[i]
            try { v.read(ts, lane, nowMs)?.takeIf { it.isFinite() } ?: Double.NaN } catch (_: Throwable) {
                failures?.computeIfAbsent(v.id) { java.util.concurrent.atomic.AtomicLong(0) }?.incrementAndGet()
                Double.NaN
            }
        }
}
