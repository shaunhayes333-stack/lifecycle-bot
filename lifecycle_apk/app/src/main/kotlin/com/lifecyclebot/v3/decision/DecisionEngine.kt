package com.lifecyclebot.v3.decision

import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.learning.LearningMetrics
import com.lifecyclebot.v3.risk.FatalRiskResult
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.engine.ToxicModeCircuitBreaker
import kotlin.math.roundToInt

/**
 * V3 Confidence Breakdown
 */
data class ConfidenceBreakdown(
    val statistical: Int,
    val structural: Int,
    val operational: Int,
    val effective: Int
)

/**
 * V3 Ops Metrics
 */
data class OpsMetrics(
    val apiHealthy: Boolean = true,
    val feedsHealthy: Boolean = true,
    val walletHealthy: Boolean = true,
    val latencyMs: Long = 100
)

/**
 * V3 Confidence Engine
 * Computes confidence from statistical, structural, and operational factors
 */
class ConfidenceEngine {
    
    /**
     * Compute confidence breakdown
     * Effective = 50% Statistical + 35% Structural + 15% Operational
     */
    fun compute(
        scoreCard: ScoreCard,
        metrics: LearningMetrics,
        ops: OpsMetrics
    ): ConfidenceBreakdown {
        val statistical = computeStatistical(metrics)
        val structural = computeStructural(scoreCard)
        val operational = computeOperational(ops)
        
        val effective = (
            0.50 * statistical +
            0.35 * structural +
            0.15 * operational
        ).roundToInt().coerceIn(0, 100)
        
        return ConfidenceBreakdown(
            statistical = statistical,
            structural = structural,
            operational = operational,
            effective = effective
        )
    }
    
    private fun computeStatistical(metrics: LearningMetrics): Int {
        var score = 10
        
        // More classified trades = more confidence
        score += (metrics.classifiedTrades / 10).coerceAtMost(20)
        
        // Win rate adjustment
        score += ((metrics.last20WinRatePct - 50.0) / 5.0).toInt().coerceIn(-10, 10)
        
        // Payoff ratio
        score += ((metrics.payoffRatio - 1.0) * 10.0).toInt().coerceIn(-10, 10)
        
        // Penalize false blocks and missed winners
        score -= (metrics.falseBlockRatePct / 10.0).toInt().coerceIn(0, 10)
        score -= (metrics.missedWinnerRatePct / 10.0).toInt().coerceIn(0, 10)
        
        return score.coerceIn(0, 100)
    }
    
    private fun computeStructural(scoreCard: ScoreCard): Int {
        var score = 30
        
        // Total score contribution
        score += (scoreCard.total / 2).coerceIn(-20, 35)
        
        // Positive signals boost confidence
        score += (scoreCard.positiveCount() * 2)
        
        // Negative signals reduce confidence
        score -= scoreCard.negativeCount()
        
        return score.coerceIn(0, 100)
    }
    
    private fun computeOperational(ops: OpsMetrics): Int {
        var score = 50
        
        if (ops.apiHealthy) score += 15 else score -= 20
        if (ops.feedsHealthy) score += 15 else score -= 20
        if (ops.walletHealthy) score += 10 else score -= 20
        
        // Latency penalty
        when {
            ops.latencyMs > 2_000 -> score -= 20
            ops.latencyMs > 750 -> score -= 10
        }
        
        return score.coerceIn(0, 100)
    }
}

/**
 * V3 Decision Result
 */
data class DecisionResult(
    val band: DecisionBand,
    val finalScore: Int,
    val statisticalConfidence: Int,
    val structuralConfidence: Int,
    val operationalConfidence: Int,
    val effectiveConfidence: Int,
    val reasons: List<String>,
    val fatalReason: String? = null
)

/**
 * V3 Final Decision Engine
 * Maps score + confidence to decision band
 * 
 * V3 SELECTIVITY TUNING:
 * - Compound weakness veto: C-grade + low conf + negative memory/narrative → WATCH
 * - AI degradation penalty: degraded AI = confidence cap
 * - Tighter C-grade thresholds: requires conf >= 40 for execute
 * 
 * Now integrates with V3ConfidenceConfig for user-adjustable thresholds:
 * - AGGRESSIVE mode: Lower thresholds, more trades
 * - STANDARD mode: Default thresholds
 * - CONSERVATIVE mode: Higher thresholds, fewer trades
 */
class FinalDecisionEngine(
    private val config: TradingConfigV3
) {
    // V5.8: Anti-starvation — relax floors by 2-4 pts if no trades execute over a window
    companion object {
        @Volatile private var consecutiveNonExecute = 0
        private const val STARVATION_WINDOW = 50
        private const val STARVATION_RELIEF_SMALL = 2
        private const val STARVATION_RELIEF_LARGE = 4

        fun getStarvationRelief(): Int = when {
            consecutiveNonExecute >= 100 -> STARVATION_RELIEF_LARGE
            consecutiveNonExecute >= STARVATION_WINDOW -> STARVATION_RELIEF_SMALL
            else -> 0
        }

        fun recordExecute() { consecutiveNonExecute = 0 }
        fun recordNonExecute() { consecutiveNonExecute++ }

        // V5.9.1516 — P1 FIX 4: PRE_RUNNER soft-shape telemetry. A PRE_RUNNER is
        // a token that landed just BELOW the execute floor (WATCH range) but shows
        // a CONFIRMED runner precursor — momentum AND volume both strongly positive
        // on the same tick. Operator audit: these near-miss accelerating tokens were
        // dumped to WATCH and never converted, starving the lane of exactly the
        // launches that become runners. We promote them to EXECUTE_SMALL (smallest
        // size — risk-bounded probe) WITHOUT bypassing FDG (still gates downstream)
        // and WITHOUT a new enum value (keeps every when(band) exhaustive). Counters
        // let the operator measure PRE_RUNNER hit-rate separately.
        val preRunnerCandidates = java.util.concurrent.atomic.AtomicLong(0)
        val preRunnerPromoted   = java.util.concurrent.atomic.AtomicLong(0)
        val preRunnerSuppressed = java.util.concurrent.atomic.AtomicLong(0)
        fun preRunnerSnapshot(): Map<String, Long> = mapOf(
            "preRunnerCandidates" to preRunnerCandidates.get(),
            "preRunnerPromoted"   to preRunnerPromoted.get(),
            "preRunnerSuppressed" to preRunnerSuppressed.get(),
        )
    }

    /**
     * Make final decision based on score, confidence, and fatal check
     *
     * V3.2 ADDITION: ToxicModeCircuitBreaker integration
     * Checks for hard-disabled modes and liquidity floors BEFORE scoring
     */
    fun decide(
        scoreCard: ScoreCard,
        confidence: ConfidenceBreakdown,
        fatal: FatalRiskResult,
        tradingMode: String = "",
        source: String = "",
        phase: String = "",
        isAIDegraded: Boolean = false,
        isPaperMode: Boolean = false,  // V5.2: Paper mode bypasses liquidity floors
        marketCapUsd: Double = 0.0,    // V5.9.939: tier-aware floor adjustment
        ageMinutes: Double = 999.0,    // V5.9.1586: fresh-launch probe gate
    ): DecisionResult {
        // ═══════════════════════════════════════════════════════════════════
        // V5.9.939 — TIER-AWARE FLOOR ADJUSTMENT.
        //
        // Operator doctrine: "executors, V3, lanes are ALL meant to be
        // mcap-aware for each tier." Pre-V5.9.939 the DecisionEngine had
        // ZERO mcap awareness — same threshold for $500 micro and $500M
        // chip. That treated tier-MICRO learning churn the same as
        // tier-INSTITUTIONAL high-conviction-only entries.
        //
        // tierAdj is SUBTRACTED from the final score/conf floors:
        //   MICRO   (< $50K):       -10  (loose — learning territory)
        //   STANDARD ($50K-$500K):   -5
        //   GROWTH   ($500K-$5M):     0  (neutral — current default)
        //   SCALED   ($5M-$50M):     +5  (tighter — more selective)
        //   BLUECHIP (≥ $50M):      +10  (only A+ entries here)
        //
        // Computed once, applied to BOTH score floor and conf floor.
        // Fail-open: mcap=0 → tierAdj=0 (no change).
        // ═══════════════════════════════════════════════════════════════════
        val tierAdj = when {
            marketCapUsd <= 0.0           -> 0   // unknown, no opinion
            marketCapUsd < 50_000.0       -> -10
            marketCapUsd < 500_000.0      -> -5
            marketCapUsd < 5_000_000.0    -> 0
            marketCapUsd < 50_000_000.0   -> 5
            else                          -> 10
        }

        // Fatal block overrides everything
        if (fatal.blocked) {
            return DecisionResult(
                band = DecisionBand.BLOCK_FATAL,
                finalScore = scoreCard.total,
                statisticalConfidence = confidence.statistical,
                structuralConfidence = confidence.structural,
                operationalConfidence = confidence.operational,
                effectiveConfidence = confidence.effective,
                reasons = listOf("Fatal block"),
                fatalReason = fatal.reason
            )
        }
        
        val score = scoreCard.total
        val conf = confidence.effective
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: Extract component scores and context
        // ═══════════════════════════════════════════════════════════════════
        val memoryScore = scoreCard.components.find { it.name == "memory" }?.value ?: 0
        val narrativeScore = scoreCard.components.find { it.name == "narrative" }?.value ?: 0
        val suppressionScore = scoreCard.components.find { it.name == "suppression" }?.value ?: 0
        val liquidityUsd = scoreCard.components.find { it.name == "liquidity" }?.let { 
            // Extract raw liquidity from reason string if available
            it.reason.substringAfter("liq=").substringBefore(" ").toDoubleOrNull()
        } ?: 0.0
        
        // Extract phase from scoring - check entry module reason
        val extractedPhase = scoreCard.components.find { it.name == "entry" }?.reason?.let {
            when {
                it.contains("early_unknown") -> "early_unknown"
                it.contains("pre_pump") -> "pre_pump"
                it.contains("pump_building") -> "pump_building"
                it.contains("accumulation") -> "accumulation"
                else -> "unknown"
            }
        } ?: "unknown"
        
        // Use extracted phase if not provided
        val effectivePhase = if (phase.isNotBlank()) phase else extractedPhase
        
        // Check for AI degradation (ops.apiHealthy = false means degraded)
        val effectiveAIDegraded = isAIDegraded || confidence.operational < 50
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2 TOXIC MODE CIRCUIT BREAKER CHECK
        // 
        // CRITICAL: This check happens BEFORE any scoring-based decisions.
        // If the circuit breaker blocks entry, we return WATCH immediately.
        // 
        // Blocks:
        // - COPY_TRADE mode (hard disabled after -92% loss)
        // - WHALE_FOLLOW below $15k liquidity
        // - Any mode below liquidity floor
        // - Frozen modes (circuit breaker tripped)
        // ═══════════════════════════════════════════════════════════════════
        if (tradingMode.isNotBlank()) {
            val circuitBlockReason = ToxicModeCircuitBreaker.checkEntryAllowed(
                mode = tradingMode,
                source = source,
                liquidityUsd = liquidityUsd,
                phase = effectivePhase,
                memoryScore = memoryScore,
                isAIDegraded = effectiveAIDegraded,
                confidence = conf,
                isPaperMode = isPaperMode  // V5.2: Paper mode bypasses liquidity floors
            )
            
            if (circuitBlockReason != null) {
                return DecisionResult(
                    band = DecisionBand.WATCH,  // Block to WATCH, not REJECT
                    finalScore = score,
                    statisticalConfidence = confidence.statistical,
                    structuralConfidence = confidence.structural,
                    operationalConfidence = confidence.operational,
                    effectiveConfidence = conf,
                    reasons = listOf("CIRCUIT_BREAKER: $circuitBlockReason", "mode=$tradingMode", "liq=$liquidityUsd"),
                    fatalReason = "ToxicModeCircuitBreaker: $circuitBlockReason"
                )
            }
        }
        
        val minScoreForExecute = try {
            val fluidExecuteFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.getExecuteFloor()
            val configMinScore = com.lifecyclebot.engine.V3ConfidenceConfig.getMinScoreForExecute(config.executeStandardMin)
            // V5.9.495z51 — operator directive: paper-learned thresholds MUST
            // flow into live mode. The previous live-only `coerceAtLeast(34)`
            // override imposed a hard 34-point floor on live regardless of
            // what paper learning had discovered, which was ~14 points above
            // the fluid floor at bootstrap. Removed — both modes now use the
            // SAME `minOf(fluidExecuteFloor, configMinScore)`.
            minOf(fluidExecuteFloor, configMinScore)
        } catch (e: Exception) {
            config.executeStandardMin
        }
        
        val isCGrade = score < minScoreForExecute
        val isBGrade = score >= minScoreForExecute
        // V5.9.1586 — 3501 fresh-launch bootstrap behavior. AGE_0_15m tokens
        // naturally lack holders/orderflow/social/smart-money/history. Unknown or
        // immature layers must be neutral/size-shaped, not converted into WATCH-only.
        val isFreshLaunchProbe = ageMinutes <= 15.0
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: HARD C-GRADE EXECUTION BAN
        // 
        // V3.3: FLUID THRESHOLDS - looser during bootstrap to learn
        // 
        // For quality=C, require ALL of:
        //   - conf >= 18 at bootstrap → 25 at mature
        //   - memory > -15 at bootstrap → -12 at mature
        //   - AI not degraded
        //   - phase is not early_unknown (ONLY for mature - allow early learning)
        //
        // If ANY fail → WATCH ONLY, SHADOW TRACK, NO BUY
        // ═══════════════════════════════════════════════════════════════════
        val cGradeProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }
        
        // Fluid thresholds for C-grade
        // V5.7: Fixed — minimum floor was 30% even at 1% learning (too tight at bootstrap, slowed trading)
        // Genuine bootstrap uses lower floors; only mature (50%+ learning) triggers strict thresholds.
        // At 1% learning: floor=20. At 75% learning: floor=39. At 100% learning: floor=45.
        val cGradeConfFloor = (20 + (cGradeProgress * 25)).toInt().coerceIn(20, 45)
        // Memory floor: tighter at maturity, permissive at bootstrap
        val cGradeMemoryFloor = (-20 + (cGradeProgress * 10)).toInt().coerceIn(-20, -12)

        // Extract momentum and volume for weak-signal veto
        val momentumScoreV = scoreCard.components.find { it.name == "momentum" }?.value ?: 0
        val volumeScoreV   = scoreCard.components.find { it.name == "volume" }?.value ?: 0

        if (isCGrade) {
            val cGradeBlockReasons = mutableListOf<String>()

            if (conf < cGradeConfFloor && !isFreshLaunchProbe) {
                cGradeBlockReasons.add("conf=$conf<$cGradeConfFloor")
            }
            if (memoryScore <= cGradeMemoryFloor && !isFreshLaunchProbe) {
                cGradeBlockReasons.add("memory=$memoryScore<=$cGradeMemoryFloor")
            }
            if (effectiveAIDegraded && !isFreshLaunchProbe) {
                cGradeBlockReasons.add("AI_DEGRADED")
            }
            // V5.5b: Only block early_unknown phase when very mature (>70% learning)
            // Meme coins are almost always "early_unknown" — blocking at 30% silently
            // killed all scanner entries. 70% threshold means bot must see 700+ trades
            // with decent WR before it stops trading fresh launches.
            if (effectivePhase == "early_unknown" && cGradeProgress > 0.70) {
                cGradeBlockReasons.add("phase=early_unknown")
            }
            // V5.4: Weak-signal veto — if score < 20 AND both momentum AND volume are flat/negative,
            // there is no directional edge. Block regardless of learning phase.
            if (score < 20 && momentumScoreV <= 0 && volumeScoreV <= 0 && !isFreshLaunchProbe) {
                cGradeBlockReasons.add("no_momentum_no_volume_score=$score")
            }
            
            if (cGradeBlockReasons.isNotEmpty()) {
                return DecisionResult(
                    band = DecisionBand.WATCH,
                    finalScore = score,
                    statisticalConfidence = confidence.statistical,
                    structuralConfidence = confidence.structural,
                    operationalConfidence = confidence.operational,
                    effectiveConfidence = conf,
                    reasons = listOf("C_GRADE_BAN: ${cGradeBlockReasons.joinToString(", ")} → WATCH ONLY")
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: AI DEGRADATION CAP
        // 
        // If AI is degraded:
        //   - C-grade = WATCH only (handled above in C_GRADE_BAN)
        //   - B+ grade = can still execute but confidence capped at 50
        // ═══════════════════════════════════════════════════════════════════
        val effectiveConf = if (effectiveAIDegraded) {
            minOf(conf, 50)
        } else {
            conf
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 BAND SELECTION
        //
        // At this point, C-grade trash has been filtered by C_GRADE_BAN above.
        // Only legitimate setups reach here.
        //
        // V3.3: FLUID THRESHOLDS based on learning progress
        // Bootstrap (0-20% learning): Much looser - take more shots to learn
        // Mature (50%+ learning): Tighter - only high-conviction trades
        // ═══════════════════════════════════════════════════════════════════
        val learningProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }

        // Fluid confidence threshold: 15% at bootstrap → 50% at mature
        // V5.9.152: bootstrap start lowered 25→15 so low-conviction shots
        // can execute during bootstrap (user: 'it won't learn if it can't
        // trade'). Mature end unchanged. At 32% learning (Freshman) the
        // floor becomes 15 + 0.32*35 = 26 instead of 33.
        val fluidMinConfForExecute = (20 + (learningProgress * 30)).toInt().coerceIn(20, 50)  // V5.9.184: bootstrap floor 15→20 → targets 25-50% WR

        val minConfForExecute = try {
            val configMinConf = com.lifecyclebot.engine.V3ConfidenceConfig.getMinConfidenceForExecute(35)
            // Use fluid (lower) threshold during bootstrap
            minOf(fluidMinConfForExecute, configMinConf)
        } catch (e: Exception) {
            fluidMinConfForExecute
        }

        // C-grade confidence floor for EXECUTE_SMALL: 10% at bootstrap → 40% at mature
        // V5.9.152: C-grade smallest-size probe should fire freely in
        // bootstrap. At 32% learning: 10 + 0.32*30 = 20 instead of 26.
        val cGradeMinConf = (15 + (learningProgress * 25)).toInt().coerceIn(15, 40)  // V5.9.184: C-grade floor 10→15

        // ═══════════════════════════════════════════════════════════════════
        // V5.5: DIRECTIONAL GATE — block only when BOTH signals are actively
        // negative (< 0). A score of 0 means "no data / neutral" for new tokens
        // and should not block execution. Only a confirmed declining momentum
        // AND declining volume means no directional edge.
        // ═══════════════════════════════════════════════════════════════════
        val hasMomentumOrVolume = !(momentumScoreV < 0 && volumeScoreV < 0)

        // V5.8: Anti-starvation — relax floors if no executes in recent window
        // V5.9.939 — Apply tierAdj on top of starvation relief. Lower-tier
        // tokens (MICRO) get extra slack; higher-tier (BLUE_CHIP) tighten up.
        val starvationRelief = getStarvationRelief()
        val effectiveMinScore = minScoreForExecute - starvationRelief + tierAdj
        val effectiveMinConf = minConfForExecute - starvationRelief + tierAdj
        val effectiveCGradeConf = cGradeMinConf - starvationRelief + tierAdj

        // V5.9.93: Tie EXECUTE_AGGRESSIVE confidence floor to the downstream
        // TradeAuthorizer promotion-gate floor. V5.9.97 added hard maxOf(..., 40/50)
        // which boosted win-rate but killed bootstrap volume. V5.9.150 removed
        // it entirely which restored volume but dropped win-rate back below
        // target.
        //
        // V5.9.168 — FLUID aggressive gate. The hardness of the V5.9.97
        // floor now *ramps* with learning progress:
        //   bootstrap (progress=0)   → floor = effectiveMinConf + 10  (lenient)
        //   mature    (progress=1.0) → floor = max(..., 50)           (V5.9.97 strict)
        // Same shape for the score floor. Quality enforced as the bot
        // matures, without freezing the learner at bootstrap.
        val aggressiveConfHard  = (40 + learningProgress * 10).toInt()   // 40 → 50
        val aggressiveScoreHard = (30 + learningProgress * 10).toInt()   // 30 → 40
        val aggressiveConfFloor  = maxOf(effectiveMinConf + 10, aggressiveConfHard)
        val aggressiveScoreFloor = maxOf((effectiveMinScore * 1.3).toInt(), aggressiveScoreHard)
        // V5.9.152: the hard `maxOf(..., 40)` B-grade floor was
        // re-introducing the same bootstrap-freeze the fluid floors are
        // trying to avoid. User (Freshman 32%): '37 trades, 19 losses —
        // it won't learn if it can't trade'. Scale the B-grade floor with
        // learning too: 20 at bootstrap → 40 at mature. C-grade path
        // keeps its own fluid floor via effectiveCGradeConf.
        val bGradeConfFloor = (20 + (learningProgress * 20)).toInt().coerceIn(20, 40)
        val standardConfFloor    = maxOf(effectiveMinConf, if (isCGrade) 25 else bGradeConfFloor)
        val smallConfFloor       = maxOf(effectiveCGradeConf, 15)

        val rawBand = when {
            // V5.9.1586 — AGE_0_15m probe restore. Low/unknown data is not a
            // directional negative. Let fresh launches produce executable probe
            // candidates when confidence is at least weakly present; FDG/safety and
            // live sizing still decide actual risk.
            isFreshLaunchProbe && score >= -5 && effectiveConf >= 20 -> DecisionBand.EXECUTE_SMALL
            hasMomentumOrVolume && score >= aggressiveScoreFloor && effectiveConf >= aggressiveConfFloor -> DecisionBand.EXECUTE_AGGRESSIVE
            hasMomentumOrVolume && score >= effectiveMinScore && effectiveConf >= standardConfFloor -> DecisionBand.EXECUTE_STANDARD
            hasMomentumOrVolume && score >= (effectiveMinScore * 0.7).toInt() && effectiveConf >= smallConfFloor -> DecisionBand.EXECUTE_SMALL
            score >= config.watchScoreMin -> DecisionBand.WATCH
            else -> DecisionBand.REJECT
        }

        // V5.9.1516 — P1 FIX 4: PRE_RUNNER soft-shape promotion. When the raw band
        // is WATCH (a near-miss: above watchScoreMin but below the EXECUTE_SMALL
        // floor) AND the token shows a CONFIRMED runner precursor — momentum AND
        // volume BOTH strongly positive on this tick, plus a non-trivial score —
        // promote to EXECUTE_SMALL (smallest risk-bounded probe). This converts the
        // accelerating near-miss launches the lane was throwing away. FDG still
        // gates downstream; promotion only lifts WATCH→SMALL, never bypasses a veto.
        val band = if (rawBand == DecisionBand.WATCH) {
            val nearMissFloor = (effectiveMinScore * 0.7).toInt()
            val isNearMiss = score >= (nearMissFloor - 4) && score < nearMissFloor
            val confirmedPrecursor = momentumScoreV >= 6 && volumeScoreV >= 6 &&
                effectiveConf >= (smallConfFloor - 3)
            if (isNearMiss && confirmedPrecursor) {
                preRunnerCandidates.incrementAndGet()
                preRunnerPromoted.incrementAndGet()
                DecisionBand.EXECUTE_SMALL
            } else {
                if (isNearMiss) { preRunnerCandidates.incrementAndGet(); preRunnerSuppressed.incrementAndGet() }
                rawBand
            }
        } else rawBand

        if (band == DecisionBand.EXECUTE_AGGRESSIVE || band == DecisionBand.EXECUTE_STANDARD || band == DecisionBand.EXECUTE_SMALL) {
            recordExecute()
        } else {
            recordNonExecute()
        }

        return DecisionResult(
            band = band,
            finalScore = score,
            statisticalConfidence = confidence.statistical,
            structuralConfidence = confidence.structural,
            operationalConfidence = confidence.operational,
            effectiveConfidence = effectiveConf,
            reasons = scoreCard.components.map { "${it.name}:${it.value} (${it.reason})" }
        )
    }
}
