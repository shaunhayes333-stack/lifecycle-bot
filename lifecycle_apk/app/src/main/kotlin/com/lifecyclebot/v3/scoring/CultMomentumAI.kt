package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.404 — CultMomentumAI
 *
 * Tracks how active each memetic cluster is in the recent past. If 2+ tokens
 * from the same cluster have *opened* in the last hour, the cluster is "alive"
 * and the next token in that cluster gets a +10 score boost — the symbolic
 * AATE leans into running narratives instead of fighting them.
 *
 * Decay is a sliding 60-minute window; out-of-window fires are evicted lazily
 * on each call to `noteOpen` / `bonusFor`.
 */
object CultMomentumAI {

    private const val TAG = "CultMomentumAI"

    private const val WINDOW_MS         = 60L * 60L * 1000L   // last 60 minutes
    private const val MOMENTUM_THRESHOLD = 2                  // ≥2 fires → "alive"
    private const val ALIVE_BONUS       = 10                  // additive score bonus
    private const val HOT_BONUS         = 18                  // when ≥4 fires in window

    // cluster → list of fire timestamps (most recent first; bounded)
    private val opens = ConcurrentHashMap<NarrativeAI.Cluster, MutableList<Long>>()

    /** Record a new entry for a cluster. */
    fun noteOpen(cluster: NarrativeAI.Cluster) {
        if (cluster == NarrativeAI.Cluster.UNKNOWN) return
        val list = opens.getOrPut(cluster) { mutableListOf() }
        synchronized(list) {
            list.add(0, System.currentTimeMillis())
            evictExpired(list)
            // Bounded: never keep more than 32 fires per cluster.
            while (list.size > 32) list.removeAt(list.size - 1)
        }
        ErrorLogger.info(TAG, "${cluster.emoji} ${cluster.name} fired — ${countWithin(cluster)} in last 60min")
    }

    /** Score boost to add to the next entry in this cluster, if it's alive. */
    fun bonusFor(cluster: NarrativeAI.Cluster): Int {
        if (cluster == NarrativeAI.Cluster.UNKNOWN) return 0
        val n = countWithin(cluster)
        return when {
            n >= 4 -> HOT_BONUS
            n >= MOMENTUM_THRESHOLD -> ALIVE_BONUS
            else -> 0
        }
    }

    /** True if a cluster is currently considered momentum-alive. */
    fun isAlive(cluster: NarrativeAI.Cluster): Boolean = countWithin(cluster) >= MOMENTUM_THRESHOLD

    /** Number of fires in the rolling window. */
    fun countWithin(cluster: NarrativeAI.Cluster): Int {
        val list = opens[cluster] ?: return 0
        synchronized(list) {
            evictExpired(list)
            return list.size
        }
    }

    /** Top-5 currently-alive clusters for UI/diagnostics. */
    fun topAlive(): List<Pair<NarrativeAI.Cluster, Int>> =
        opens.entries
            .map { it.key to countWithin(it.key) }
            .filter { it.second >= MOMENTUM_THRESHOLD }
            .sortedByDescending { it.second }
            .take(5)

    fun summary(): String {
        val live = topAlive()
        if (live.isEmpty()) return "no live narrative momentum"
        return "live: " + live.joinToString { "${it.first.emoji}${it.first.name}=${it.second}" }
    }

    private fun evictExpired(list: MutableList<Long>) {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (list.isNotEmpty() && list.last() < cutoff) {
            list.removeAt(list.size - 1)
        }
    }
}
