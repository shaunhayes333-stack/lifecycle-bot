package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * V5.9.123 — CorrelationHedgeAI
 *
 * Problem: the bot happily opens 10 correlated meme positions, thinking
 * it has 10 trades of risk. In reality memes cluster by narrative, age-band,
 * LP type — 10 correlated positions = one position sized 10x. This is the
 * biggest hidden driver of the Sharpe -0.18 shown in the user's Neural
 * Personality screen.
 *
 * This layer classifies every candidate into a "cluster" and counts how
 * many open positions already belong to each cluster. Penalty scales with
 * over-concentration:
 *
 *   0-1 cluster peers open  →  0 pts   (free pass)
 *   2   cluster peers open  → -3 pts
 *   3   cluster peers open  → -7 pts
 *   4   cluster peers open  → -12 pts
 *   5+  cluster peers open  → -20 pts  (effective veto vs the +25 entry cap)
 *
 * Cluster rule (cheap proxy for true correlation):
 *   • If candidate symbol contains a common meme root (DOGE, PEPE, BONK,
 *     WIF, MOG, INU, SHIB, BOBO, TRUMP, MAGA, BASE, AI, GROK, CAT, FROG),
 *     it's in that root's cluster.
 *   • Else the cluster is its mcap band (sub-10K, 10K-50K, 50K-250K,
 *     250K-1M, 1M+).
 *   • Plus we track an "overall open count" cluster that penalizes the
 *     total number of simultaneously-open positions past a threshold.
 *
 * This is deliberately simpler than a full Pearson matrix — on-device
 * Pearson over hundreds of tokens every scan would torch the battery.
 * The cluster proxy captures 80% of the risk at 1% of the compute.
 */
object CorrelationHedgeAI {

    private const val TAG = "CorrHedge"

    // mint -> clusterId, updated as positions open/close
    private val openPositionClusters = ConcurrentHashMap<String, String>()

    private val MEME_ROOTS = listOf(
        "DOGE", "PEPE", "BONK", "WIF", "MOG", "INU", "SHIB", "BOBO",
        "TRUMP", "MAGA", "BASE", "AI", "GROK", "CAT", "FROG", "NEIRO",
        "MOON", "FLOKI", "BRETT", "WOJAK", "COIN", "GIGA", "PONKE"
    )

    fun registerOpen(mint: String, symbol: String, mcapUsd: Double) {
        openPositionClusters[mint] = classify(symbol, mcapUsd)
    }

    fun registerClosed(mint: String) {
        openPositionClusters.remove(mint)
    }

    fun classify(symbol: String, mcapUsd: Double): String {
        val up = symbol.uppercase()
        for (root in MEME_ROOTS) {
            if (up.contains(root)) return "MEME_$root"
        }
        return when {
            mcapUsd < 10_000   -> "MCAP_0_10K"
            mcapUsd < 50_000   -> "MCAP_10_50K"
            mcapUsd < 250_000  -> "MCAP_50_250K"
            mcapUsd < 1_000_000 -> "MCAP_250K_1M"
            else               -> "MCAP_1M_PLUS"
        }
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        return try {
            val cluster = classify(candidate.symbol, candidate.marketCapUsd)
            val peersInCluster = openPositionClusters.values.count { it == cluster }
            val totalOpen = openPositionClusters.size

            val clusterPenalty = when (peersInCluster) {
                0, 1 -> 0
                2    -> -3
                3    -> -7
                4    -> -12
                else -> -20
            }
            val totalPenalty = if (totalOpen >= 25) -5 else 0

            val value = clusterPenalty + totalPenalty
            val reason = if (value == 0) {
                "🛡️ CLUSTER_OK: $cluster (${peersInCluster} peers / ${totalOpen} total)"
            } else {
                "⚠️ CLUSTER_HOT: $cluster has $peersInCluster peers open (penalty=$value)"
            }
            ScoreComponent(name = "CorrelationHedgeAI", value = value, reason = reason)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "score failed: ${e.message}")
            ScoreComponent(name = "CorrelationHedgeAI", value = 0, reason = "NO_DATA")
        }
    }
}
