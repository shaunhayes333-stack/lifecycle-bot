package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — TokenDNAClusteringAI
 *
 * Embeds every candidate into a compact feature space (mcap band, liq band,
 * buy-pressure band, age band, and optional holder density) and builds a
 * "cluster" key. Each cluster accumulates realized-trade outcomes over
 * time. Predict per-cluster expected win-rate and score accordingly.
 *
 * The clustering is discrete rather than ML-embedded — appropriate for
 * on-device use where we avoid pulling a real clustering library into
 * the APK. The discrete buckets cover the 4-5 axes memecoins vary on.
 */
object TokenDNAClusteringAI {

    private const val TAG = "TokenDNA"

    private data class ClusterStat(var wins: Int = 0, var losses: Int = 0) {
        val n get() = wins + losses
        val wr: Double get() = if (n == 0) 0.5 else wins.toDouble() / n
    }

    private val stats = ConcurrentHashMap<String, ClusterStat>()

    /** V5.9.362 — wiring health: total cluster samples (wins+losses) vs floor 10. */
    fun getWiringHealth(): Triple<Int, Int, Boolean> {
        val total = stats.values.sumOf { it.wins + it.losses }
        return Triple(total, 10, total >= 10)
    }

    private fun clusterKey(candidate: CandidateSnapshot): String {
        val mcapBand = when {
            candidate.marketCapUsd < 10_000   -> "MCAP_0_10K"
            candidate.marketCapUsd < 50_000   -> "MCAP_10_50K"
            candidate.marketCapUsd < 250_000  -> "MCAP_50_250K"
            candidate.marketCapUsd < 1_000_000 -> "MCAP_250K_1M"
            else                              -> "MCAP_1M_PLUS"
        }
        val liqBand = when {
            candidate.liquidityUsd < 5_000    -> "LIQ_0_5K"
            candidate.liquidityUsd < 25_000   -> "LIQ_5_25K"
            candidate.liquidityUsd < 100_000  -> "LIQ_25_100K"
            else                              -> "LIQ_100K_PLUS"
        }
        val buyBand = when {
            candidate.buyPressurePct < 40 -> "BUY_LOW"
            candidate.buyPressurePct < 60 -> "BUY_MID"
            candidate.buyPressurePct < 80 -> "BUY_HIGH"
            else                          -> "BUY_EXTREME"
        }
        val ageBand = when {
            candidate.ageMinutes < 5    -> "AGE_0_5m"
            candidate.ageMinutes < 60   -> "AGE_5_60m"
            candidate.ageMinutes < 720  -> "AGE_1_12h"
            else                        -> "AGE_12h_plus"
        }
        return "$mcapBand|$liqBand|$buyBand|$ageBand"
    }

    fun recordOutcome(candidate: CandidateSnapshot, won: Boolean) {
        val key = clusterKey(candidate)
        val s = stats.getOrPut(key) { ClusterStat() }
        if (won) s.wins++ else s.losses++
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val key = clusterKey(candidate)
        val s = stats[key] ?: return ScoreComponent("TokenDNAClusteringAI", 0, "🧬 $key: new cluster")
        if (s.n < 10) return ScoreComponent("TokenDNAClusteringAI", 0, "🧬 $key: ${s.n} samples (warming)")

        val wr = s.wr
        val value = when {
            wr > 0.65 -> +6
            wr > 0.55 -> +3
            wr < 0.25 -> -10
            wr < 0.40 -> -5
            else      -> 0
        }
        return ScoreComponent(
            "TokenDNAClusteringAI", value,
            "🧬 $key WR=${"%.0f".format(wr * 100)}%% (${s.wins}/${s.n})"
        )
    }
}
