package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.AdaptiveLearningEngine
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore

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
 *   B) Layer rolling-WR sizing nudge (last 50 closes for this layer)
 *   C) Hot/cold streak Kelly damping (last 10 trades momentum)
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
                devWalletPct = 0.0,
                bondingCurveProgress = 0.0,
                rugcheckScore = rugcheckScore,
                emaFanState = "FLAT",
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

        // B) Layer rolling-WR sizing nudge
        val layerWr = layerRollingWinRate(layer, lookback = 50)
        if (layerWr != null) {
            val (wr, n) = layerWr
            // V5.9.643 — lower minimum from 20 to 5 so sizing nudges arrive
            // during bootstrap rather than after 20+ trades per layer.
            val sizingFromWr = when {
                n < 5        -> 1.0   // too sparse — neutral
                n < 10       -> when {  // small sample: gentler range
                    wr < 30.0 -> 0.88
                    wr > 65.0 -> 1.08
                    else -> 1.0
                }
                wr < 25.0    -> 0.75
                wr < 35.0    -> 0.90
                wr in 45.0..55.0 -> 1.0
                wr < 70.0    -> 1.10
                else         -> 1.15
            }
            sizeMult *= sizingFromWr
            if (sizingFromWr != 1.0) {
                expl.append("wr").append(wr.toInt()).append('=')
                    .append(String.format("%.2f", sizingFromWr)).append("x ")
            }
        }

        // C) Hot/cold streak Kelly damping
        val streak = recentStreak(layer, lookback = 10)
        val streakMult = when {
            streak >=  4 -> 1.10
            streak ==  3 -> 1.05
            streak <= -4 -> 0.90
            streak == -3 -> 0.95
            else -> 1.0
        }
        sizeMult *= streakMult
        if (streakMult != 1.0) {
            expl.append("strk").append(streak).append('=')
                .append(String.format("%.2f", streakMult)).append("x ")
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

    private fun layerRollingWinRate(layer: Layer, lookback: Int): Pair<Double, Int>? = try {
        val all = TradeHistoryStore.getAllSells()
        val matched = all.asSequence()
            .filter { matchesLayer(it, layer) }
            .toList()
            .takeLast(lookback)
        if (matched.isEmpty()) null
        else {
            val wins = matched.count { it.pnlPct > 0.0 }
            val wr = wins.toDouble() / matched.size.toDouble() * 100.0
            wr to matched.size
        }
    } catch (_: Exception) { null }

    private fun recentStreak(layer: Layer, lookback: Int): Int {
        return try {
            val all = TradeHistoryStore.getAllSells()
            val recent = all.asSequence()
                .filter { matchesLayer(it, layer) }
                .toList()
                .takeLast(lookback)
                .reversed()
            if (recent.isEmpty()) {
                0
            } else {
                val firstWin = recent.first().pnlPct > 0.0
                var streak = 0
                for (t in recent) {
                    val isWin = t.pnlPct > 0.0
                    if (isWin == firstWin) streak++ else break
                }
                if (firstWin) streak else -streak
            }
        } catch (_: Exception) { 0 }
    }

    private fun matchesLayer(t: Trade, layer: Layer): Boolean {
        val m = t.tradingMode.uppercase()
        return when (layer) {
            Layer.SHITCOIN -> m.contains("SHIT")
            Layer.MOONSHOT -> m.contains("MOON")
        }
    }

    fun throughputStatus(): String = try {
        val n = TradeHistoryStore.getTradeCount24h()
        val target = 500
        val pct = (n.toDouble() / target.toDouble() * 100.0).toInt()
        when {
            n >= target          -> "OK throughput ${n}/24h (${pct}% of ${target} target)"
            n >= target / 2      -> "WARN throughput ${n}/24h (${pct}% of ${target} target - investigate)"
            else                 -> "ALERT throughput ${n}/24h (${pct}% of ${target} target - likely choke)"
        }
    } catch (_: Exception) { "throughput unknown" }
}
