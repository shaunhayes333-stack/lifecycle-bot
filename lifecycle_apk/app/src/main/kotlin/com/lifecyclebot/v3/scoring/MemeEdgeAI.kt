package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.AdaptiveLearningEngine
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.engine.LearningEnvironment7835
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import com.lifecyclebot.engine.truth.ExactStrategyPerformance7429

/**
 * V5.9.619 — MemeEdgeAI
 *
 * The "edge multiplier". Aggregates everything we've learned about a token
 * candidate into a small, bounded score-and-size adjustment that the meme
 * evaluators (ShitCoinTraderAI, MoonshotTraderAI) can apply right before
 * they emit `shouldEnter` / `eligible`.
 *
 * DESIGN PRINCIPLES:
 *   1. ADDITIVE & BOUNDED. Every output is clamped. Score nudge is +/-8 max,
 *      size multiplier is 0.70..1.40. We can never block an entry the
 *      evaluator wants, never force one it rejected.
 *   2. NO NEW CHOKES. No hard floors, no minimum trade counts that gate
 *      anything. Bad signals shrink size; they never veto.
 *   3. RAMP-IN BY MATURITY. Pattern feedback weight starts at 0% and ramps
 *      to 20% by 5,000 trades. Cold-start behaviour is identical to
 *      pre-V5.9.619 (no surprises in bootstrap).
 *   4. THROUGHPUT FIRST. Designed to keep the bot trading 500+ entries/day
 *      even at full maturity. Size adjustments preserve count; reductions
 *      are gentle (0.70x minimum from any single source).
 *
 * READBACK CHANNELS:
 *   A) Pattern feedback (AdaptiveLearningEngine.calculateAdaptiveScore)
 *   B) Mode/lane-specific net economic sizing nudge (last 50 whole-position closes)
 *   C) Recent streak shaping only in the direction supported by that economic cohort
 *   D) Cluster-correlation guard (too many open in same cluster -> -2 score)
 *
 * Returns one [Verdict] per call. Cheap; safe to invoke per-token per-pass.
 */
object MemeEdgeAI {

    private const val TAG = "MemeEdgeAI"

    // Bounds - every individual channel is capped, and the composite is also bounded.
    private const val MAX_SCORE_NUDGE   = 8.0
    private const val MAX_SIZE_MULT     = 1.40
    private const val MIN_SIZE_MULT     = 0.70

    // V5.9.643 — bootstrap ramp: pattern feedback now contributes from trade 1.
    // Previous design had 0% for <200 trades — this starved bootstrap learning of
    // the one signal that actually improves with every closed trade. New ramp starts
    // at 3% (enough to add directional signal without over-fitting on thin data).
    private fun patternBlend(tradeCount: Int): Double = when {
        tradeCount < 20    -> 0.03   // Cold-start: tiny nudge, mostly exploratory
        tradeCount < 50    -> 0.05   // Bootstrap warming up
        tradeCount < 100   -> 0.07   // Patterns accumulating
        tradeCount < 200   -> 0.10   // Enough data for real adjustment
        tradeCount < 500   -> 0.13
        tradeCount < 1000  -> 0.16
        tradeCount < 2000  -> 0.18
        tradeCount < 5000  -> 0.19
        else               -> 0.20   // Full maturity
    }

    enum class Layer { SHITCOIN, MOONSHOT }

    data class Verdict(
        val scoreNudge: Double,
        val sizeMultiplier: Double,
        val confidenceNudge: Double,
        val patternsGood: List<String>,
        val patternsBad: List<String>,
        val explanation: String,
    ) {
        companion object {
            val NEUTRAL = Verdict(0.0, 1.0, 0.0, emptyList(), emptyList(), "neutral")
        }
    }

    fun evaluate(
        layer: Layer,
        mcapUsd: Double,
        tokenAgeMinutes: Double,
        buyRatioPct: Double,
        volumeUsd: Double,
        liquidityUsd: Double,
        holderCount: Int,
        topHolderPct: Double,
        holderGrowthRate: Double,
        rugcheckScore: Double,
        baseEntryScore: Double,
        narrativeCluster: MemeNarrativeAI.Cluster,
        openClusterCount: Int,
        executionMode: String = LearningEnvironment7835.mode(),
    ): Verdict = try {
        val expl = StringBuilder()
        var scoreNudge = 0.0
        var sizeMult = 1.0
        var confNudge = 0.0
        val patternsGood = mutableListOf<String>()
        val patternsBad = mutableListOf<String>()

        // A) Pattern feedback via AdaptiveLearningEngine
        val tradeCount = AdaptiveLearningEngine.getTradeCount()
        val blend = patternBlend(tradeCount)
        if (blend > 0.0) {
            val adaptive = AdaptiveLearningEngine.calculateAdaptiveScore(
                mcapUsd = mcapUsd,
                tokenAgeMinutes = tokenAgeMinutes,
                buyRatioPct = buyRatioPct,
                volumeUsd = volumeUsd,
                liquidityUsd = liquidityUsd,
                holderCount = holderCount,
                topHolderPct = topHolderPct,
                holderGrowthRate = holderGrowthRate,
                // No dev/curve/EMA witness is carried by this adapter; zero/FLAT would be fabricated readings.
                devWalletPct = Double.NaN,
                bondingCurveProgress = Double.NaN,
                rugcheckScore = rugcheckScore.takeIf { it.isFinite() && it in 0.0..100.0 } ?: Double.NaN,
                emaFanState = "",
                baseEntryScore = baseEntryScore,
            )
            val rawDelta = adaptive.score - baseEntryScore
            val patternNudge = (rawDelta * blend).coerceIn(-MAX_SCORE_NUDGE, MAX_SCORE_NUDGE)
            scoreNudge += patternNudge
            val rawSizeDelta = adaptive.sizeMultiplier - 1.0
            sizeMult *= (1.0 + rawSizeDelta * blend)
            confNudge += patternNudge * 0.5

            if (adaptive.matchedGoodPatterns.isNotEmpty()) {
                patternsGood.addAll(adaptive.matchedGoodPatterns)
                expl.append("good=").append(adaptive.matchedGoodPatterns.size).append(' ')
            }
            if (adaptive.matchedBadPatterns.isNotEmpty()) {
                patternsBad.addAll(adaptive.matchedBadPatterns)
                expl.append("bad=").append(adaptive.matchedBadPatterns.size).append(' ')
            }
            if (patternNudge != 0.0) {
                expl.append("pat=").append(String.format("%+.1f", patternNudge)).append(' ')
            }
        }

        // B/C) One same-mode, same-lane, after-cost whole-position cohort. Win rate and
        // a short winning streak cannot overturn negative economic expectancy, or punish
        // a profitable asymmetric runner cohort just because most of its positions lose.
        val economics = try { evidenceFor8527(layer, executionMode) } catch (_: Throwable) { null }
        val shaping = economicShaping8527(economics)
        sizeMult *= shaping.sizeMultiplier * shaping.streakMultiplier
        if (shaping.sizeMultiplier != 1.0 || shaping.streakMultiplier != 1.0) {
            expl.append("net[").append(executionMode.uppercase()).append("]=")
                .append(economics?.n ?: 0).append('/')
                .append(String.format("%+.1f", economics?.meanReturnPct ?: 0.0)).append("% ")
                .append(String.format("%.2f", shaping.sizeMultiplier * shaping.streakMultiplier)).append("x ")
        }

        // D) Cluster-correlation guard (deprioritise, don't block)
        if (narrativeCluster != MemeNarrativeAI.Cluster.UNKNOWN && openClusterCount >= 3) {
            val clusterPenalty = -2.0
            scoreNudge += clusterPenalty
            sizeMult *= 0.90
            expl.append("cluster").append(narrativeCluster.name).append('x')
                .append(openClusterCount).append(' ')
        }

        // Final clamps
        scoreNudge = scoreNudge.coerceIn(-MAX_SCORE_NUDGE, MAX_SCORE_NUDGE)
        confNudge = confNudge.coerceIn(-MAX_SCORE_NUDGE, MAX_SCORE_NUDGE)
        sizeMult = sizeMult.coerceIn(MIN_SIZE_MULT, MAX_SIZE_MULT)

        Verdict(
            scoreNudge = scoreNudge,
            sizeMultiplier = sizeMult,
            confidenceNudge = confNudge,
            patternsGood = patternsGood,
            patternsBad = patternsBad,
            explanation = expl.toString().trim().ifEmpty { "neutral" },
        )
    } catch (e: Exception) {
        ErrorLogger.debug(TAG, "evaluate error: ${e.message}")
        Verdict.NEUTRAL
    }

    internal data class EconomicEvidence8527(
        val n: Int,
        val meanReturnPct: Double,
        val netSol: Double,
        val winRatePct: Double,
        val streak: Int,
    )

    internal data class EconomicShaping8527(val sizeMultiplier: Double = 1.0, val streakMultiplier: Double = 1.0)

    private data class CachedEvidence8527(val revision: Long, val atMs: Long, val evidence: EconomicEvidence8527?)
    private val evidenceCache8527 = java.util.concurrent.ConcurrentHashMap<String, CachedEvidence8527>()

    private fun evidenceFor8527(layer: Layer, rawMode: String): EconomicEvidence8527? {
        val mode = rawMode.trim().uppercase(java.util.Locale.ROOT)
        if (mode != "LIVE" && mode != "PAPER") return null
        val now = System.currentTimeMillis()
        val revision = CanonicalFinalizedTradeBus6464.canonicalRevision7493()
        val key = mode + "|" + layer.name
        evidenceCache8527[key]?.takeIf { it.revision == revision && now - it.atMs in 0L..30_000L }?.let { return it.evidence }
        val rows = CanonicalFinalizedTradeBus6464.terminalEnvelopes7863(mode)
        val evidence = economicEvidence8527(layer, mode, rows, now)
        if (CanonicalFinalizedTradeBus6464.canonicalRevision7493() == revision)
            evidenceCache8527[key] = CachedEvidence8527(revision, now, evidence)
        return evidence
    }

    /** Already-settled whole positions only: no journal partials, mixed-mode WR, broadcast or counterfactual labels. */
    internal fun economicEvidence8527(
        layer: Layer,
        rawMode: String,
        rows: List<CanonicalFinalizedTradeBus6464.Envelope>,
        nowMs: Long,
    ): EconomicEvidence8527? {
        val mode = rawMode.trim().uppercase(java.util.Locale.ROOT)
        if (mode != "LIVE" && mode != "PAPER") return null
        val selected = rows.asSequence().filter {
            it.terminal && it.learningEligible && it.positionId.isNotBlank() &&
                it.atMs > 0L && it.atMs <= nowMs && it.mode.equals(mode, true) &&
                CanonicalLaneIdentity6506.canonical(it.lane) == layer.name &&
                ExactStrategyPerformance7429.terminalEconomicsEligible8081(it.mode, it.proofState) &&
                it.realizedReturnPct.isFinite() && it.realizedPnlSol.isFinite() &&
                ((it.realizedReturnPct > 0.0 && it.realizedPnlSol > 0.0) ||
                    (it.realizedReturnPct < 0.0 && it.realizedPnlSol < 0.0) ||
                    (it.realizedReturnPct == 0.0 && it.realizedPnlSol == 0.0))
        }.sortedByDescending { it.atMs }.distinctBy { it.positionId }.take(50).toList()
        if (selected.isEmpty()) return null
        val meanReturn = selected.sumOf { it.realizedReturnPct } / selected.size
        val netSol = selected.sumOf { it.realizedPnlSol }
        if (!meanReturn.isFinite() || !netSol.isFinite()) return null
        val wins = selected.count { it.realizedPnlSol > 0.0 }
        fun netSign(value: Double): Int = when { value > 0.0 -> 1; value < 0.0 -> -1; else -> 0 }
        val headSign = netSign(selected.first().realizedPnlSol)
        val streak = if (headSign == 0) 0 else selected.take(10)
            .takeWhile { netSign(it.realizedPnlSol) == headSign }.size * headSign
        return EconomicEvidence8527(selected.size, meanReturn, netSol, wins * 100.0 / selected.size, streak)
    }

    /** Magnitudes retain the prior bounded ranges; economic evidence alone determines their direction. */
    internal fun economicShaping8527(e: EconomicEvidence8527?): EconomicShaping8527 {
        if (e == null || e.n !in 5..50 || !e.meanReturnPct.isFinite() || !e.netSol.isFinite() ||
            !e.winRatePct.isFinite() || e.winRatePct !in 0.0..100.0) return EconomicShaping8527()
        val positive = e.meanReturnPct > 0.0 && e.netSol > 0.0
        val negative = e.meanReturnPct < 0.0 && e.netSol < 0.0
        val size = when {
            positive && e.n < 10 -> 1.08
            positive -> if (e.winRatePct >= 70.0) 1.15 else 1.10
            negative && e.n < 10 -> 0.88
            negative -> if (e.winRatePct < 25.0) 0.75 else 0.90
            else -> 1.0
        }
        val streak = when {
            positive && e.streak >= 4 -> 1.10
            positive && e.streak == 3 -> 1.05
            negative && e.streak <= -4 -> 0.90
            negative && e.streak == -3 -> 0.95
            else -> 1.0
        }
        return EconomicShaping8527(size, streak)
    }

    fun throughputStatus(): String = try {
        val n = TradeHistoryStore.getSells24h().count {
            com.lifecyclebot.engine.truth.DeskPerformanceAuthority6648.classify(it) ==
                com.lifecyclebot.engine.truth.DeskPerformanceAuthority6648.Book.MEME
        }
        val target = 500
        val pct = (n.toDouble() / target.toDouble() * 100.0).toInt()
        when {
            n >= target          -> "OK throughput ${n}/24h (${pct}% of ${target} target)"
            n >= target / 2      -> "WARN throughput ${n}/24h (${pct}% of ${target} target - investigate)"
            else                 -> "ALERT throughput ${n}/24h (${pct}% of ${target} target - likely choke)"
        }
    } catch (_: Exception) { "throughput unknown" }
}
