package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6459 §P0 — SAME-MINT OCCUPANCY REGISTRY (upstream of EXEC_GATE).
 *
 * Operator: 620/654 executor blocks (94.8%) are same-mint. Push the
 * occupancy check upstream so lane/FDG never fans out on already-open
 * mints.
 */
object CanonicalMintOccupancyRegistry6459 {
    enum class Occupancy { NONE, PENDING_ENTRY, IN_FLIGHT_ENTRY, OPEN, PENDING_EXIT }
    private val states = ConcurrentHashMap<String, Occupancy>()
    private val transitions = AtomicLong(0L)
    private val upstreamBlocks = AtomicLong(0L)

    fun mark(mint: String, occ: Occupancy) {
        if (mint.isBlank()) return
        transitions.incrementAndGet()
        if (occ == Occupancy.NONE) states.remove(mint) else states[mint] = occ
    }

    fun occupancy(mint: String): Occupancy = states[mint] ?: Occupancy.NONE

    /** Returns true if lane/FDG fanout should be BLOCKED for this mint. */
    fun isBlocked(mint: String): Boolean {
        val o = states[mint] ?: return false
        val blocked = o == Occupancy.OPEN || o == Occupancy.PENDING_ENTRY ||
            o == Occupancy.IN_FLIGHT_ENTRY || o == Occupancy.PENDING_EXIT
        if (blocked) {
            upstreamBlocks.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "MINT_OCCUPANCY_UPSTREAM_BLOCK_6459",
                    "mint=${mint.take(10)} occupancy=$o",
                )
                PipelineHealthCollector.labelInc("MINT_OCCUPANCY_UPSTREAM_BLOCK_6459")
            } catch (_: Throwable) {}
        }
        return blocked
    }

    fun statusLine(): String = "tracked=${states.size} transitions=${transitions.get()} " +
        "upstreamBlocks=${upstreamBlocks.get()}"
}
