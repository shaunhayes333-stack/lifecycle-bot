package com.lifecyclebot.v3.scoring

import java.util.concurrent.ConcurrentHashMap

/**
 * Current candidate observations for Cortex voters. Trade-entry observations have
 * a different lifetime: they stay frozen until that position is settled.
 * Each component keeps its own read time so a partial specialist refresh cannot
 * make the rest of an old V3 committee look fresh.
 */
internal class CandidateComponentBook8521(
    private val ttlMs: Long = 45_000L,
    private val maxCandidates: Int = 4_096,
) {
    private data class Value(val score: Int, val atMs: Long)
    private data class Snapshot(val values: Map<String, Value>, val atMs: Long)
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    fun record(key: String, scores: Map<String, Int>, atMs: Long, mergeFresh: Boolean) {
        if (key.isBlank() || scores.isEmpty() || atMs <= 0L) return
        snapshots.compute(key) { _, prior ->
            if (prior != null && prior.atMs > atMs) prior
            else {
                val retained = if (mergeFresh) prior?.values.orEmpty().filterValues {
                    atMs - it.atMs in 0L..ttlMs
                } else emptyMap()
                Snapshot(retained + scores.mapValues { Value(it.value, atMs) }, atMs)
            }
        }
        if (snapshots.size > maxCandidates) {
            snapshots.entries.removeIf { atMs - it.value.atMs > ttlMs }
            val excess = (snapshots.size - maxCandidates).coerceAtLeast(0)
            if (excess > 0) snapshots.entries.sortedBy { it.value.atMs }.take(excess).forEach {
                snapshots.remove(it.key, it.value)
            }
        }
    }

    fun read(key: String, nowMs: Long): Map<String, Int>? {
        val snapshot = snapshots[key] ?: return null
        val values = snapshot.values.filterValues { nowMs - it.atMs in 0L..ttlMs }.mapValues { it.value.score }
        return values.takeIf { it.isNotEmpty() }
    }

    internal fun size(): Int = snapshots.size
}
