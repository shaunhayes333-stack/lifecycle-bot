package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — CapitalEfficiencyAI
 *
 * Asks: "if I use 1 SOL of capital for 1 hour on this candidate, how much
 * PnL do I expect vs the same SOL-hour in a typical position?" Rejects
 * trades whose projected PnL/SOL-hour is below the bot's rolling median.
 *
 * Works by tracking per-layer average realized PnL/SOL-hour across recent
 * closed trades and applying a small score based on where the candidate's
 * expected hold-band falls.
 */
object CapitalEfficiencyAI {

    private const val TAG = "CapEff"

    private val bandPnlPerSolHour = ConcurrentHashMap<String, Double>()    // band → mean
    private val bandSampleCount = ConcurrentHashMap<String, Int>()
    private var globalMedianPnlPerSolHour: Double = 0.0

    /** V5.9.362 — wiring health: max samples per age-band vs warmup floor (5). */
    fun getWiringHealth(): Triple<Int, Int, Boolean> {
        val maxSamples = bandSampleCount.values.maxOrNull() ?: 0
        return Triple(maxSamples, 5, maxSamples >= 5)
    }

    private fun bandFor(ageMinutes: Double): String = when {
        ageMinutes < 15     -> "AGE_0_15m"
        ageMinutes < 60     -> "AGE_15_60m"
        ageMinutes < 240    -> "AGE_1_4h"
        ageMinutes < 1440   -> "AGE_4_24h"
        else                -> "AGE_1d_plus"
    }

    fun recordOutcome(ageBandMinutes: Double, pnlPct: Double, sizeSol: Double, holdHours: Double) {
        if (sizeSol <= 0 || holdHours <= 0) return
        val band = bandFor(ageBandMinutes)
        val pnlSolPerHour = (pnlPct / 100.0 * sizeSol) / holdHours
        val prev = bandPnlPerSolHour[band] ?: 0.0
        val n = bandSampleCount[band] ?: 0
        bandPnlPerSolHour[band] = (prev * n + pnlSolPerHour) / (n + 1)
        bandSampleCount[band] = n + 1
        // Recompute global median (fast, small map)
        val vals = bandPnlPerSolHour.values.sorted()
        globalMedianPnlPerSolHour = if (vals.isEmpty()) 0.0 else vals[vals.size / 2]
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val band = bandFor(candidate.ageMinutes)
        val expected = bandPnlPerSolHour[band] ?: return ScoreComponent("CapitalEfficiencyAI", 0, "💸 $band: no history")
        // V5.9.357 — warmup: need 5+ samples in this age band before voting,
        // otherwise a single early outcome could swing the score wildly.
        val n = bandSampleCount[band] ?: 0
        if (n < 5) return ScoreComponent("CapitalEfficiencyAI", 0, "💸 $band: warming ($n/5)")
        val median = globalMedianPnlPerSolHour
        // V5.0.6849 §MULTIPLICATIVE_COMPARISON_INVERTS_ON_A_NEGATIVE_MEDIAN — these
        // ratio tests are only meaningful when the median is POSITIVE. globalMedianPnlPerSolHour
        // is a median of band means and goes negative whenever the book is losing, which
        // inverts every branch: with median = -0.010, `median * 2.0` = -0.020, so a band
        // at expected = -0.008 — still destroying capital — satisfies
        // `expected > median * 2.0` and collects the MAXIMUM +5 bonus. Meanwhile a
        // genuinely profitable band sitting exactly at the median scores 0, because
        // `expected > median` is false on equality.
        // So during precisely the drawdowns where capital efficiency should be gating
        // hardest, this layer handed its strongest admission bonus to the least-bad
        // losers. Wired at UnifiedScorer:407/547/1047.
        // When the book median is non-positive there is no peer benchmark worth
        // dividing by: only genuinely positive expectancy earns credit.
        val value = when {
            median <= 0.0 -> if (expected > 0.0) +2 else -2
            expected > median * 2.0 -> +5
            expected > median       -> +2
            expected < median * 0.3 -> -5
            expected < median * 0.6 -> -2
            else                    -> 0
        }
        return ScoreComponent(
            "CapitalEfficiencyAI", value,
            "💸 $band PnL/SOL·h=${"%.4f".format(expected)} vs median=${"%.4f".format(median)} (n=$n)"
        )
    }
}
