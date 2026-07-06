package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.CorrelationHedgeAI
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6126 — CorrelationGuard: portfolio-level correlated-holding damper.
 *
 * Emergent backlog item: "CorrelationGuard (portfolio-level correlated-
 * holding damper)."
 *
 * CorrelationHedgeAI already applies a SCORE penalty in UnifiedScorer, but
 * that only nudges the entry score. It does nothing to reduce the SIZE of
 * a new entry when the portfolio is already over-concentrated in the same
 * correlation cluster. The bot can still open a full-size 6th meme-root
 * position at score 60 (penalty knocked it from 80 to 60, still above the
 * 55 entry floor) and now has 6 correlated bets at full size = one bet
 * sized 6x. That is the hidden Sharpe killer.
 *
 * CorrelationGuard closes that gap by returning a SIZE multiplier that
 * damps new entries when the cluster is over-represented:
 *
 *   0-1 peers  ->  1.00 (no damping)
 *   2 peers    ->  0.85
 *   3 peers    ->  0.65
 *   4 peers    ->  0.40
 *   5+ peers   ->  0.20 (near-block, but still allows a small probe)
 *
 * This is a SOFT-SHAPE damper -- it never hard-blocks (throughput doctrine).
 * Hard safety (rugs, LP-unlocked, SL) remains untouched.
 *
 * INTEGRATION: Executor sizing stack -- wired as a multiplier in the
 * multiplierProduct cascade. Fail-open: any error -> 1.0 (no damping).
 */
object CorrelationGuard {

    private const val TAG = "CorrelationGuard"

    // Damping curve: linear interpolation between peer-count breakpoints.
    // Deliberately conservative -- we want to slow the cluster build-up,
    // not kill it entirely (a strong thesis can justify 3-4 correlated
    // positions at reduced size).
    private val DAMPING_BREAKPOINTS = arrayOf(
        Pair(0, 1.00),   // 0 peers -> full size
        Pair(1, 1.00),   // 1 peer  -> full size (pair is fine)
        Pair(2, 0.85),   // 2 peers -> 15% trim
        Pair(3, 0.65),   // 3 peers -> 35% trim
        Pair(4, 0.40),   // 4 peers -> 60% trim
        Pair(5, 0.20),   // 5+ peers -> 80% trim (near-block probe)
    )

    // Cache: last computed multiplier per mint (for telemetry)
    private val lastMultiplier = ConcurrentHashMap<String, Double>()

    /**
     * Core API: returns a size multiplier [0.20, 1.00] based on how many
     * open positions share the candidate's correlation cluster.
     *
     * @param mint      candidate mint
     * @param symbol    candidate symbol (for meme-root classification)
     * @param mcapUsd   candidate market cap (for mcap-band classification)
     * @return size multiplier (1.0 = no damping, 0.20 = heavily damped)
     */
    fun sizeMultiplier(mint: String, symbol: String, mcapUsd: Double): Double {
        return try {
            val cluster = CorrelationHedgeAI.classify(symbol, mcapUsd)
            val peersInCluster = countPeersInCluster(cluster, mint)
            val mult = interpolateDamping(peersInCluster)
            lastMultiplier[mint] = mult
            if (mult < 1.0) {
                try {
                    ForensicLogger.lifecycle(
                        "CORRELATION_GUARD_DAMPED_6126",
                        "mint=${mint.take(10)} symbol=$symbol cluster=$cluster peers=$peersInCluster sizeMult=${"%.2f".format(mult)}",
                    )
                    PipelineHealthCollector.labelInc("CORRELATION_GUARD_DAMPED_6126")
                } catch (_: Throwable) {}
            }
            mult
        } catch (_: Throwable) {
            1.0  // fail-open
        }
    }

    /**
     * Returns the last computed multiplier for a mint (for telemetry/UI).
     */
    fun lastMultiplierFor(mint: String): Double =
        try { lastMultiplier[mint] ?: 1.0 } catch (_: Throwable) { 1.0 }

    /**
     * Clear cached multiplier when position is closed or buy is rejected.
     */
    fun clear(mint: String) {
        try { lastMultiplier.remove(mint) } catch (_: Throwable) {}
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun countPeersInCluster(cluster: String, candidateMint: String): Int {
        // CorrelationHedgeAI.openPositionClusters maps open mints to cluster IDs.
        // Count peers in the same cluster (candidate is not open yet).
        return try {
            val field = CorrelationHedgeAI::class.java.getDeclaredField("openPositionClusters")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val clusters = field.get(CorrelationHedgeAI) as? ConcurrentHashMap<String, String>
                ?: return 0
            clusters.values.count { it == cluster }
        } catch (_: Throwable) {
            0  // fail-open: no peers detected, no damping
        }
    }

    private fun interpolateDamping(peers: Int): Double {
        if (peers <= 1) return 1.0
        for (i in DAMPING_BREAKPOINTS.indices) {
            val (threshold, mult) = DAMPING_BREAKPOINTS[i]
            if (peers == threshold) return mult
            if (i < DAMPING_BREAKPOINTS.size - 1 && peers < DAMPING_BREAKPOINTS[i + 1].first) {
                val (nextThreshold, nextMult) = DAMPING_BREAKPOINTS[i + 1]
                if (nextThreshold == threshold) return mult
                val t = (peers - threshold).toDouble() / (nextThreshold - threshold)
                return mult + (nextMult - mult) * t
            }
        }
        return DAMPING_BREAKPOINTS.last().second
    }
}
