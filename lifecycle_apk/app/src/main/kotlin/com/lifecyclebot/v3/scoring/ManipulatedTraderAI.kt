package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ☠️ MANIPULATED TRADER AI - "RIDE THE PUMP" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Buys tokens showing CLEAR manipulation signals — bundle concentration,
 * wash trading, whale pump patterns — and rides the INITIAL pump (first 3-5
 * minutes) with tight risk control BEFORE the manipulators dump.
 *
 * WHERE OTHER LAYERS BLOCK — THIS LAYER ENTERS.
 *
 * PHILOSOPHY:
 * - Manipulation is detectable. Bundled buy pressure and extreme one-sided
 *   volume indicate coordinated pumping.
 * - Enter early (< 8 min token age), exit fast (≤ 4 min hold).
 * - TP 25%, SL -5%. If neither hits, FORCE EXIT at 4 minutes — no exceptions.
 * - These manipulators DO NOT WAIT for retail to catch up.
 *
 * MANIPULATION SCORE (0-100):
 * ─────────────────────────────────────────────────────────────────────────────
 * +30  bundlePct >= 60%  (mass bundled buy = coordinated pump)
 * +20  bundlePct >= 40%
 * +20  buyPressurePct >= 80%  (extreme one-sided buying)
 * +15  buyPressurePct >= 70%
 * +20  momentum >= 15  (token already ripping)
 * +15  momentum >= 10
 * +10  source is PUMP_FUN or PUMP_FUN_GRADUATE
 * +10  ageMinutes < 3  (very fresh = still in pump phase)
 * -20  rugcheckScore < 1  (known rug — skip even here)
 *
 * Entry trigger: ManipulationScore >= 60
 * Position sizing: lerp(0.015, 0.05) SOL via FluidLearningAI
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ManipulatedTraderAI {

    private const val TAG = "☠️ManipAI"

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - V5.6.8: Added fluid thresholds for learning progression
    // ═══════════════════════════════════════════════════════════════════════════

    // Entry score thresholds (fluid - VERY permissive for manipulation plays)
    // Realistic scores: bundle 40%=20 + bp 70%=15 + pump.fun=10 = 45 max typical
    // Many tokens will score 20-35, so we need LOW thresholds
    private const val MIN_MANIP_SCORE_BOOTSTRAP = 18        // V5.9.1556 tuning: MANIP WR~6%, μ~-25.7%; raise floor without disabling lane
    private const val MIN_MANIP_SCORE_MATURE = 32           // V5.9.1556 tuning: require stronger setup after negative console expectancy
    
    // Market cap range
    const val MCAP_UNHYDRATED_DEFER_8190 = "MCAP_UNHYDRATED_DEFER_8190"
    private const val MIN_MARKET_CAP_USD = 5_000.0          // $5K minimum
    private const val MAX_MARKET_CAP_USD = 300_000.0        // $300K maximum
    
    // Liquidity thresholds (fluid) - LOW for manipulation plays
    private const val MIN_LIQUIDITY_BOOTSTRAP = 1_500.0     // V5.6.8: $1.5K during bootstrap
    private const val MIN_LIQUIDITY_MATURE = 3_000.0        // V5.6.8: $3K when mature
    
    // Token age (fluid - tighter when experienced)
    private const val MAX_AGE_MINUTES_BOOTSTRAP = 12.0      // V5.6.8: 12 min during bootstrap
    private const val MAX_AGE_MINUTES_MATURE = 8.0          // V5.6.8: 8 min when mature

    // Position sizing — bootstrap→mature via FluidLearningAI
    private const val POSITION_SOL_BOOTSTRAP = 0.015
    private const val POSITION_SOL_MATURE = 0.05

    // Take profit / Stop loss (fixed — no fluid adaption on risk for this layer)
    // V5.9.1347 — TP/SL GEOMETRY FIX (the other half of the 3W/21L root cause).
    // Old: TP +25% / SL -5%. On a token deliberately selected for >=15% momentum
    // and >=70-80% buy pressure, intra-candle swings of 5%+ are NORMAL noise — so the
    // -5% stop fired on routine wiggle long before +25% could resolve. That asymmetry
    // (stop inside the noise band, target outside the reachable band) is a structural
    // coin-flip the lane loses ~4/5 of the time. Fix the geometry to the asset's
    // reality: give the stop room to survive normal manipulation volatility, and pull
    // the target IN so a winner banks before the fast reversal these tokens always do.
    // Net reward:risk stays favourable (+14 / -11 ≈ 1.27:1) but is now ACHIEVABLE.
    private const val TAKE_PROFIT_PCT = 14.0                   // bank the bounce before the dump (was 25 — unreachable)
    private const val STOP_LOSS_PCT = -11.0                    // survive normal pump noise (was -5 — noise-tight)
    private const val FORCE_EXIT_MINUTES = 4.0                 // Hard 4-minute time exit
    private const val TRAILING_STOP_ACTIVATION_PCT = 7.0       // activate trail earlier — lock the bounce (was 10)
    private const val TRAILING_STOP_FROM_HWM_PCT = 10.0        // tighter trail — these reverse hard (was 15)

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    data class ManipulatedPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val manipScore: Int,
        val bundlePct: Double,
        val buyPressure: Double,
        val isPaper: Boolean,
        var highWaterMark: Double = entryPrice,
        // V5.9.168 — shared laddered profit-lock
        var peakPnlPct: Double = 0.0,
        var partialRungsTaken: Int = 0,
        var pendingPartialRung8077: Int? = null,
    )

    data class ManipSignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double,
        val manipScore: Int,
        val reason: String,
    )

    data class ManipStats(
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyPnlSol: Double,
        val totalManipCaught: Int,
        val activeCount: Int,
    )

    enum class ManipExitSignal {
        HOLD,
        TAKE_PROFIT,
        STOP_LOSS,
        TIME_EXIT,
        RUG_EXIT,
        TRAILING_STOP,
        PARTIAL_TAKE,  // V5.9.168: laddered partial-sell signal
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile var isPaperMode: Boolean = true
    @Volatile private var initialized = false

    private val paperPositions = ConcurrentHashMap<String, ManipulatedPosition>()
    private val livePositions = ConcurrentHashMap<String, ManipulatedPosition>()
    val activePositions: ConcurrentHashMap<String, ManipulatedPosition>
        get() = if (isPaperMode) paperPositions else livePositions

    private val _dailyWins = AtomicInteger(0)
    private val _dailyLosses = AtomicInteger(0)
    private val _dailyPnlSolBps = AtomicLong(0)    // stored as (sol * 10000) for precision
    private val _totalManipCaught = AtomicInteger(0)

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    fun init(isPaper: Boolean) {
        if (initialized) {
            ErrorLogger.warn(TAG, "☠️ init() called again — BLOCKED (already initialized)")
            return
        }
        isPaperMode = isPaper
        initialized = true
        ErrorLogger.info(TAG, "☠️ ManipulatedTraderAI initialized | " +
            "mode=${if (isPaper) "PAPER" else "LIVE"} | " +
            "TP=${TAKE_PROFIT_PCT}% SL=${STOP_LOSS_PCT}% | " +
            "forceExit=${FORCE_EXIT_MINUTES}min | " +
            "size=${POSITION_SOL_BOOTSTRAP}->${POSITION_SOL_MATURE} SOL")
    }

    /** V5.9.761 — Per-loop paper/live mode sync (operator regression fix).
     *  init() is gated by `initialized`, so UI toggles never propagate.
     *  Mirrors setTradingMode() in CashGen/ShitCoin/BlueChip/Moonshot/Quality. */
    fun setTradingMode(isPaper: Boolean) {
        if (isPaperMode != isPaper) {
            isPaperMode = isPaper
            ErrorLogger.info(TAG, "☠️ mode switched → ${if (isPaper) "PAPER" else "LIVE"}")
        }
    }

    fun isEnabled(): Boolean = true

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION SIZING — fluid scaling via FluidLearningAI
    // ═══════════════════════════════════════════════════════════════════════════

    private fun lerp(bootstrap: Double, mature: Double): Double {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        return bootstrap + (mature - bootstrap) * progress
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MANIPULATION SCORE
    // ═══════════════════════════════════════════════════════════════════════════

    fun calcManipScore(
        bundlePct: Double,
        buyPressurePct: Double,
        momentum: Double,
        source: String,
        ageMinutes: Double,
        rugcheckScore: Int,
    ): Int {
        var score = 0

        // Bundle concentration — coordinated pump signal
        when {
            bundlePct >= 60.0 -> score += 30
            bundlePct >= 40.0 -> score += 20
        }

        // Extreme one-sided buying
        when {
            buyPressurePct >= 80.0 -> score += 20
            buyPressurePct >= 70.0 -> score += 15
        }

        // Momentum — token already ripping
        when {
            momentum >= 15.0 -> score += 20
            momentum >= 10.0 -> score += 15
        }

        // Common manipulation venue
        val srcUpper = source.uppercase()
        if (srcUpper.contains("PUMP_FUN") || srcUpper.contains("PUMP.FUN") ||
            srcUpper.contains("PUMPFUN") || srcUpper.contains("PUMP_FUN_GRADUATE")) {
            score += 10
        }

        // Very fresh token — still in pump phase
        if (ageMinutes < 3.0) score += 10

        // Known rug penalty — V5.0.8235: a confirmed 0 only; -1 is "not fetched", not a rug.
        if (rugcheckScore == 0) score -= 20

        // ── V5.9.1347 — OVER-EXTENSION PENALTY (the 3W/21L root cause) ──
        // The old scoring rewarded momentum>=15 (+20) AND buyPressure>=80 (+20)
        // simultaneously, so it scored HIGHEST exactly at the blow-off top — the
        // instant before coordinated manipulators dump on late retail. That is the
        // worst possible entry: we became the exit liquidity. A manipulator ride is
        // only good while pressure is BUILDING; once it's exhausted (extreme momentum
        // AND extreme one-sided buying at once) the next move is the dump. Penalize
        // that exhaustion signature so the lane stops buying tops and prefers the
        // earlier, still-building part of the pump where +25% is actually reachable.
        if (momentum >= 15.0 && buyPressurePct >= 85.0) {
            score -= 25        // both maxed = blow-off top → heavy demerit
        } else if (momentum >= 20.0 || buyPressurePct >= 92.0) {
            score -= 12        // one extreme = late, getting dangerous
        }

        return score.coerceIn(0, 100)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVALUATE — decide whether to enter a position
    // ═══════════════════════════════════════════════════════════════════════════

    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        momentum: Double,
        buyPressurePct: Double,
        bundlePct: Double,
        source: String,
        ageMinutes: Double,
        rugcheckScore: Int,
        isPaper: Boolean,
    ): ManipSignal {
        com.lifecyclebot.engine.NativeRuleReview8239.begin8239(mint, "MANIPULATED")
        fun noEnter(reason: String) = ManipSignal(
            shouldEnter = false, positionSizeSol = 0.0, manipScore = 0, reason = reason
        )

        // V5.0.7552 — local position projection cannot veto native strategy
        // grading; canonical occupancy remains the execution authority.
        if (activePositions.containsKey(mint)) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MANIP_LOCAL_OCCUPANCY_OBSERVED_7552")
        } catch (_: Throwable) {}

        // Market cap filter
        // V5.0.8190 — $0 / unread is an unhydrated cap, not a tiny one (EXPRESS 7971, SHITCOIN V5.9.160): it defers by name
        // (a data gap the hand-off waits on and re-fetches), it never reads as the MCAP_TOO_LOW judgement. Still no entry.
        if (!(marketCapUsd.isFinite() && marketCapUsd > 0.0)) return noEnter(MCAP_UNHYDRATED_DEFER_8190)
        if (marketCapUsd < MIN_MARKET_CAP_USD && !com.lifecyclebot.engine.NativeRuleReview8239.retires8239(mint, "MANIPULATED", "MCAP_TOO_LOW")) return noEnter("MCAP_TOO_LOW")
        if (marketCapUsd > MAX_MARKET_CAP_USD && !com.lifecyclebot.engine.NativeRuleReview8239.retires8239(mint, "MANIPULATED", "MCAP_TOO_HIGH")) return noEnter("MCAP_TOO_HIGH")

        // V5.6.8: Fluid liquidity filter
        val minLiq = getFluidMinLiquidity()
        // V5.0.8140 — an unread pool defers (never "low"); a real one below the floor refuses, naming MANIPULATED.
        com.lifecyclebot.engine.LaneAgeLiquidityTruth8140.laneLiquidityRefusal8140("MANIPULATED", liquidityUsd, minLiq)?.let { return noEnter(it) }
        if (liquidityUsd < minLiq) return noEnter("LOW_LIQUIDITY_${liquidityUsd.toInt()}<${minLiq.toInt()}")

        // V5.6.8: Fluid age filter — entry is everything on manipulation plays
        val maxAge = getFluidMaxAge()
        if (ageMinutes > maxAge && !com.lifecyclebot.engine.NativeRuleReview8239.retires8239(mint, "MANIPULATED", "TOKEN_TOO_OLD")) return noEnter("TOKEN_TOO_OLD_${ageMinutes.toInt()}m>${maxAge.toInt()}m")

        // V5.6.8: REMOVED rugcheck block — Manipulated layer INTENTIONALLY trades risky tokens
        // The whole point is to ride manipulator pumps that other layers reject.
        // V5.0.7425: risky is NOT the same as manipulated. Source+young-age alone
        // can score 20 points and used to admit ordinary fresh tokens into the
        // danger lane without any actual manipulation evidence.
        val knownManipulation7425 =
            bundlePct >= 40.0 ||
            (buyPressurePct >= 70.0 && momentum >= 10.0)
        if (!knownManipulation7425) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MANIPULATED_NO_KNOWN_DANGER_PROOF_7425") } catch (_: Throwable) {}
            return noEnter("NO_KNOWN_MANIPULATION_EVIDENCE_7425")
        }

        // Calculate manipulation score
        var score = calcManipScore(bundlePct, buyPressurePct, momentum, source, ageMinutes, rugcheckScore)

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.933 — HARVARD BRAIN PATTERN MEMORY (Pass 3: Manipulated lane).
        //
        // Manipulated is purpose-built to ride coordinated pumps that other
        // lanes correctly reject. Past-pattern memory is GOLD here — knowing
        // which (bundle, buy, mom, source) combos historically dumped vs ran
        // is exactly the prior that separates a good manipulator-ride from
        // a rug-and-die. Bounded [-4,+10]; fail-open per FDG doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val harvardSig = mapOf(
                "MANIPULATED_TRADER" to score.coerceIn(0, 100),
                "BUNDLE_PCT" to when {
                    bundlePct >= 60.0 -> 30
                    bundlePct >= 40.0 -> 20
                    else -> 0
                },
                "BUY_PRESSURE" to when {
                    buyPressurePct >= 80.0 -> 20
                    buyPressurePct >= 70.0 -> 15
                    else -> 0
                },
                "MOMENTUM" to when {
                    momentum >= 15.0 -> 20
                    momentum >= 10.0 -> 15
                    else -> 0
                },
                "VENUE_PUMP_FUN" to (if (source.uppercase().contains("PUMP")) 10 else 0),
                "FRESH_LAUNCH" to (if (ageMinutes < 3.0) 10 else 0),
            ).filterValues { it > 0 }
            val (harvardNudge, harvardReason) = EducationSubLayerAI.approvalBoostFor(harvardSig)
            if (harvardNudge != 0) {
                score = (score + harvardNudge).coerceIn(0, 100)
                ErrorLogger.debug(TAG, "🎓 MANIP HARVARD: nudge=${if (harvardNudge >= 0) "+" else ""}$harvardNudge | $harvardReason → score=$score")
            }
            val harvardComponents = harvardSig.map { (k, v) ->
                ScoreComponent(name = k, value = v, reason = "manip_harvard")
            }
            EducationSubLayerAI.recordEntryScores(mint, harvardComponents)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // V5.6.8: Fluid score gate
        val minScore = getFluidScoreThreshold()
        if (score < minScore) return noEnter("SCORE_TOO_LOW_${score}<${minScore}")

        // V5.0.4317 — learned expectancy is soft shaping, not a terminal
        // non-safety veto. FDG/executor should still see a tiny recovery probe.
        var manipExpectancySoftSize4317 = 1.0
        if (com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("MANIPULATED", score)) {
            val mean = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean("MANIPULATED", score)
            val n = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketSamples("MANIPULATED", score)
            manipExpectancySoftSize4317 = 0.25
            ErrorLogger.info(TAG, "☠️ MANIP_EXPECTANCY_RECOVERY_PROBE_4317: $symbol score=$score μ=${"%+.1f".format(mean ?: 0.0)}% n=$n — size×0.25")
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MANIP_EXPECTANCY_RECOVERY_PROBE_4317") } catch (_: Throwable) {}
        }

        // ── V5.9.1348 — LOSING-PATTERN-MEMORY SOFT-SHAPE (shared-layer parity) ──
        // Manipulated was the only meme lane FEEDING LosingPatternMemory (via the
        // journal) but never READING it back — so it kept re-entering proven
        // net-negative score bands it had already lost money on. Wire the SAME
        // soft-shape ShitCoin/Moonshot use: a danger bucket is NOT a veto (FDG is
        // final authority, doctrine 'soft-shape > veto'); a proven net-negative
        // bucket just reduces size 70% + trims confidence and routes via FDG.
        // Positive-mean danger buckets (low WR but big wins) are left full-size —
        // killing them would violate avg_win*WR > avg_loss*(1-WR).
        var dangerSoftSize = 1.0
        run {
            val d = try { com.lifecyclebot.engine.LosingPatternMemory.stats("MANIPULATED", score) } catch (_: Throwable) { null }
            if (d != null && d.isDangerous && d.meanPnl < 0.0) {
                val band = try { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(score) } catch (_: Throwable) { "" }
                ErrorLogger.info(TAG, "☠️🧯 MANIP_DANGER_BUCKET_SOFT: $symbol | band=$band score=$score losses=${d.losses} wins=${d.wins} mean=${"%+.1f".format(d.meanPnl)}% — size×0.3, routing via FDG")
                dangerSoftSize = 0.3
            }
        }

        val baseSize = lerp(POSITION_SOL_BOOTSTRAP, POSITION_SOL_MATURE)

        // ── V5.9.881 — BehaviorAI sizing wire-up for MANIPULATED lane ──
        // Final trader in the V5.9.878-881 batch closing the "dark lane" audit.
        // Manipulated trader rides bundle-buy momentum + early-pump signals —
        // exactly the lane where tilt protection matters because a misread
        // bundle = instant -30% rug. With BehaviorAI dark, tilt periods were
        // sizing full POSITION_SOL_MATURE into rug-heavy environments.
        //
        // Per doctrine #86: bounded soft-shape, fail-open, no veto.
        // Grade inferred from score vs minScore — A if 1.5× over threshold,
        // B if 1.2×, C if at threshold, else D.
        var behaviorSizeMult = 1.0
        var behaviorGradeMult = 1.0
        try {
            val rawSize = com.lifecyclebot.v3.scoring.BehaviorAI.getSizingMultiplier()
            behaviorSizeMult = rawSize.coerceIn(0.5, 1.5)

            val ratio = if (minScore > 0) score.toDouble() / minScore.toDouble() else 1.0
            val inferredGrade = when {
                ratio >= 1.5 -> "A"
                ratio >= 1.2 -> "B"
                ratio >= 1.0 -> "C"
                else -> "D"
            }
            val minGrade = com.lifecyclebot.v3.scoring.BehaviorAI.getMinQualityGrade()
            val gradeOrder = mapOf("A" to 5, "B" to 4, "C" to 3, "D" to 2, "F" to 1)
            val candidateRank = gradeOrder[inferredGrade] ?: 3
            val minRank = gradeOrder[minGrade.uppercase()] ?: 3
            if (candidateRank < minRank) {
                behaviorGradeMult = 0.7
            }
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        var positionSizeSol = baseSize * behaviorSizeMult * behaviorGradeMult * manipExpectancySoftSize4317 * dangerSoftSize  // V5.9.1348 danger soft-shape

        // V5.9.926 — GLOBAL COMPOUND MULTIPLIER (Pass A fix).
        try {
            val globalMultiplier = com.lifecyclebot.engine.AutoCompoundEngine.getSizeMultiplier()
            if (globalMultiplier.isFinite() && globalMultiplier > 1.0) {
                positionSizeSol *= globalMultiplier
                // Manipulated lane has no MAX_POSITION_SOL constant; use baseSize*3 as soft cap
                positionSizeSol = positionSizeSol.coerceAtMost(baseSize * 3.0)
            }
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // V5.9.1257 — calibration-aware shrink: if THIS score band has proven
        // net-negative (scorer-inversion), trim size. Soft-shape, never veto.
        try {
            val calMult = com.lifecyclebot.engine.ScoreExpectancyTracker.calibrationSizeMult("MANIPULATED", score)
            if (calMult < 1.0) {
                positionSizeSol *= calMult
                ErrorLogger.info(TAG, "☠️ MANIP CALIBRATION_SHRINK $symbol | band=S$score size×$calMult (net-negative band)")
            }
        } catch (_: Throwable) { /* fail-open */ }

        // V5.0.4329 — cache-only UltimateEdgeEngine readback for Manipulated.
        // Open/close events warm cards; entry path only consumes cached state.
        try {
            val edgeCard4329 = com.lifecyclebot.engine.UltimateEdgeEngine.cached(mint, "MANIPULATED")
            if (edgeCard4329 != null) {
                // V5.0.7112 — accept the graph's negative verdict too.
                val edgeBias4329 = edgeCard4329.scoreBias.coerceIn(-5, 5)
                if (edgeBias4329 != 0) score = (score + edgeBias4329).coerceAtLeast(0)
                val edgeSize4329 = edgeCard4329.sizeMult.coerceIn(0.90, 1.08)
                positionSizeSol = (positionSizeSol * edgeSize4329).coerceAtLeast(0.01)
                if (edgeSize4329 != 1.0) {
                    ErrorLogger.debug(TAG, "☠️🧠 ULTIMATE_EDGE_MANIP_CACHE_SHAPE_4329: $symbol score+$edgeBias4329 size×${String.format(java.util.Locale.US, "%.3f", edgeSize4329)} ${edgeCard4329.semanticReason.take(90)}")
                }
            }
        } catch (_: Throwable) { /* fail-open cache read */ }

        val learningPct = (FluidLearningAI.getLearningProgress() * 100).toInt()

        ErrorLogger.info(TAG, "☠️ MANIP SIGNAL: $symbol | score=$score (min=$minScore) | " +
            "bundle=${bundlePct.toInt()}% | bp=${buyPressurePct.toInt()}% | " +
            "mom=${momentum.toInt()}% | age=${ageMinutes.toInt()}min | " +
            "size=${String.format("%.4f", positionSizeSol)} SOL | learning=$learningPct%")

        return ManipSignal(
            shouldEnter = true,
            positionSizeSol = positionSizeSol,
            manipScore = score,
            reason = "MANIP_$score",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    fun addPosition(pos: ManipulatedPosition) {
        (if (pos.isPaper) paperPositions else livePositions)[pos.mint] = pos
        _totalManipCaught.incrementAndGet()
        try { com.lifecyclebot.engine.UltimateEdgeEngine.enqueueRefresh(pos.mint, pos.symbol, "MANIPULATED", "MANIP_OPEN", pos.manipScore.coerceIn(0, 100), "open_size_${String.format(java.util.Locale.US, "%.4f", pos.entrySol)}") } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "☠️ POSITION ADDED: ${pos.symbol} | " +
            "score=${pos.manipScore} | bundle=${pos.bundlePct.toInt()}% | " +
            "bp=${pos.buyPressure.toInt()}% | " +
            "entry=${String.format("%.8f", pos.entryPrice)} | " +
            "size=${String.format("%.4f", pos.entrySol)} SOL | " +
            "${if (pos.isPaper) "PAPER" else "LIVE"}")
    }

    fun hasPosition(mint: String, isPaper: Boolean = isPaperMode): Boolean =
        (if (isPaper) paperPositions else livePositions).containsKey(mint)

    fun getActivePositions(): List<ManipulatedPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }

    /** V5.9.1565 — metadata-only ghost eviction for BotService forcedOpen reaper. */
    fun evictGhost(mint: String, isPaper: Boolean = isPaperMode): Boolean {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) { positions.remove(mint) != null }
    }

    /**
     * V5.9.705 — Reduce sub-trader tracked entrySol after a confirmed partial sell.
     */
    fun onPartialSell(mint: String, soldFraction: Double, isPaper: Boolean = isPaperMode) {
        val partialPositions7858 = if (isPaper) paperPositions else livePositions
        val frac = soldFraction.coerceIn(0.0, 1.0)
        if (frac <= 0.0) return
        val pos = partialPositions7858[mint] ?: return
        val updated = confirmedPartial8077(pos, frac)
        partialPositions7858[mint] = updated
        ErrorLogger.debug(TAG, "🎭🔪 onPartialSell ${pos.symbol}: entrySol ${pos.entrySol} → ${updated.entrySol} (sold ${(frac*100).toInt()}%)")
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECK — called every tick for open positions
    // ═══════════════════════════════════════════════════════════════════════════

    internal fun confirmedPartial8077(pos: ManipulatedPosition, fraction: Double): ManipulatedPosition {
        if (!fraction.isFinite() || fraction <= 0.0 || fraction > 1.0) return pos
        val confirmedRung = pos.pendingPartialRung8077 == pos.partialRungsTaken
        return pos.copy(entrySol = pos.entrySol * (1.0 - fraction),
            partialRungsTaken = pos.partialRungsTaken + if (confirmedRung) 1 else 0,
            pendingPartialRung8077 = null)
    }

    fun checkExit(mint: String, currentPrice: Double, isPaper: Boolean = isPaperMode): ManipExitSignal {
        val exitPositions7858 = if (isPaper) paperPositions else livePositions
        val pos = exitPositions7858[mint] ?: return ManipExitSignal.HOLD

        val pnlPct = if (pos.entryPrice > 0) {
            com.lifecyclebot.engine.OpenPnlSanity.inspect(pos.entryPrice, currentPrice, context = "ManipulatedTraderAI_6038/${mint.take(8)}", emit = true).takeIf { it.ok }?.pnlPct ?: return ManipExitSignal.HOLD
        } else return ManipExitSignal.HOLD

        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
        val holdSeconds = (System.currentTimeMillis() - pos.entryTime) / 1000L

        // Update peak + HWM
        if (pnlPct > pos.peakPnlPct) pos.peakPnlPct = pnlPct
        if (currentPrice > pos.highWaterMark) pos.highWaterMark = currentPrice

        // The selected position's target/stop own this native tactical exit.
        // Grace is a stop-noise allowance, never a ban on banking a first-minute winner.
        tacticalExit8077(pos, pnlPct, currentPrice, holdSeconds)?.let { return it }

        // V5.9.168 — SHARED LADDERED PROFIT-LOCK
        val rungs = doubleArrayOf(20.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0)
        if (pos.partialRungsTaken < rungs.size && pnlPct >= rungs[pos.partialRungsTaken]) {
            pos.pendingPartialRung8077 = pos.partialRungsTaken
            return ManipExitSignal.PARTIAL_TAKE
        }
        // V5.9.169 — continuous fluid profit floor (shared engine).
        val _holdSec = (System.currentTimeMillis() - pos.entryTime) / 1000.0  // V5.9.835
        val profitFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(pos.peakPnlPct, holdSeconds = _holdSec)
        if ((holdSeconds >= 60L || pos.peakPnlPct >= TRAILING_STOP_ACTIVATION_PCT) && pnlPct < profitFloor) return ManipExitSignal.TRAILING_STOP

        // V5.9.437 — LIVE HOLD-BUCKET GATE. Cut flat stale Manipulated bags
        // whose hold-duration bucket has proven net-losing expectancy.
        // Manipulated is very time-sensitive (manipulators dump fast) so
        // this acts as an extra safety catch.
        if (com.lifecyclebot.engine.OutcomeGates.earlyExitByHoldBucket(
                layer = "MANIPULATED", holdMinutes = holdMinutes.toLong(), pnlPct = pnlPct)) {
            ErrorLogger.info(TAG, "☠️🧠 HOLD-BUCKET EARLY EXIT: ${pos.symbol} | ${"%.1f".format(pnlPct)}% — bucket bleeds")
            return ManipExitSignal.TIME_EXIT
        }

        // 3. Hard time exit — manipulators have already left
        // V5.9.437 — extend for winners when TIME_EXIT historically bleeds this lane.
        val manipTimeExt = com.lifecyclebot.engine.OutcomeGates.timeExitExtensionMult(
            layer = "MANIPULATED", exitReason = "TIME_EXIT", pnlPct = pnlPct)
        if (holdMinutes >= FORCE_EXIT_MINUTES * manipTimeExt) return ManipExitSignal.TIME_EXIT

        return ManipExitSignal.HOLD
    }

    /** No dispatch state changes: receipt confirmation alone consumes a partial rung. */
    internal fun tacticalExit8077(pos: ManipulatedPosition, pnlPct: Double,
        currentPrice: Double, holdSeconds: Long): ManipExitSignal? {
        if (!pnlPct.isFinite() || !currentPrice.isFinite() || currentPrice <= 0.0) return ManipExitSignal.HOLD
        if (pnlPct <= -15.0) return ManipExitSignal.STOP_LOSS
        if (pos.partialRungsTaken == 0 && pnlPct >= pos.takeProfitPct) return ManipExitSignal.TAKE_PROFIT
        // Once armed at a peak, the trail stays armed after price falls below activation.
        if (pos.peakPnlPct >= TRAILING_STOP_ACTIVATION_PCT &&
            currentPrice <= pos.highWaterMark * (1.0 - TRAILING_STOP_FROM_HWM_PCT / 100.0))
            return ManipExitSignal.TRAILING_STOP
        if (holdSeconds >= 60L && pnlPct <= pos.stopLossPct) return ManipExitSignal.STOP_LOSS
        if (holdSeconds in 60L..600L && pos.peakPnlPct < 1.0 && pnlPct < -3.0)
            return ManipExitSignal.STOP_LOSS
        return null
    }

    fun closePosition(mint: String, exitPrice: Double, reason: ManipExitSignal, isPaper: Boolean = isPaperMode) {
        val closePositions7858 = if (isPaper) paperPositions else livePositions
        val pos = synchronized(closePositions7858) { closePositions7858[mint] } ?: return
        if (!com.lifecyclebot.engine.SpecialistCloseProjection7858.mayClose(mint, isPaper, pos.entryTime)) return
        if (!closePositions7858.remove(mint, pos)) return

        // Canonical settlement owns wallet, treasury and learning. This native
        // inventory projection may display the receipt, never fabricate a second
        // outcome from exitPrice or publish another reward.
        val receipt = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.closedPositions()
            .filter { it.mint == mint && it.mode.equals(if (isPaper) "PAPER" else "LIVE", true) &&
                it.lastMutationMs >= pos.entryTime && it.remainingQtyRaw.signum() == 0 }
            .singleOrNull()
        if (receipt != null) {
            val pnlSol = receipt.realizedProceedsSol - receipt.soldCostBasisSol - receipt.feesSol
            if (pnlSol.isFinite()) {
                _dailyPnlSolBps.addAndGet((pnlSol * 10_000).toLong())
                if (pnlSol > 0.0) _dailyWins.incrementAndGet() else if (pnlSol < 0.0) _dailyLosses.incrementAndGet()
            }
        }
        ErrorLogger.info(TAG, "POSITION PROJECTION CLOSED: ${pos.symbol} reason=${reason.name} " +
            "canonicalReceipt=${receipt?.positionId ?: "ambiguous"} mode=${if (isPaper) "PAPER" else "LIVE"}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════

    val dailyWins: Int get() = _dailyWins.get()
    val dailyLosses: Int get() = _dailyLosses.get()
    val dailyPnlSolBps: Long get() = _dailyPnlSolBps.get()
    val totalManipCaught: Int get() = _totalManipCaught.get()

    fun getDailyPnlSol(): Double = _dailyPnlSolBps.get() / 10_000.0

    fun getStats(): ManipStats = ManipStats(
        dailyWins = _dailyWins.get(),
        dailyLosses = _dailyLosses.get(),
        dailyPnlSol = getDailyPnlSol(),
        totalManipCaught = _totalManipCaught.get(),
        activeCount = activePositions.size,
    )

    fun resetDaily() {
        _dailyWins.set(0)
        _dailyLosses.set(0)
        _dailyPnlSolBps.set(0)
        ErrorLogger.info(TAG, "☠️ ManipulatedTraderAI daily stats reset")
    }

    fun clearAll() {
        paperPositions.clear()
        livePositions.clear()
        ErrorLogger.info(TAG, "☠️ ManipulatedTraderAI — all positions cleared")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6.8: FLUID THRESHOLD FUNCTIONS
    // Thresholds relax during bootstrap (learning), tighten when mature (experienced)
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getFluidScoreThreshold(): Int {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        return (MIN_MANIP_SCORE_BOOTSTRAP + (MIN_MANIP_SCORE_MATURE - MIN_MANIP_SCORE_BOOTSTRAP) * progress).toInt()
    }
    
    fun getFluidMinLiquidity(): Double {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        return MIN_LIQUIDITY_BOOTSTRAP + (MIN_LIQUIDITY_MATURE - MIN_LIQUIDITY_BOOTSTRAP) * progress
    }
    
    fun getFluidMaxAge(): Double {
        val progress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        // Age gets TIGHTER as we mature (faster entries required)
        return MAX_AGE_MINUTES_BOOTSTRAP + (MAX_AGE_MINUTES_MATURE - MAX_AGE_MINUTES_BOOTSTRAP) * progress
    }
    
    fun getFluidPositionSize(): Double = lerp(POSITION_SOL_BOOTSTRAP, POSITION_SOL_MATURE)
}
