package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7460 — PER-MINT TOKEN-MAP / LANE-ROUTING VERSION GUARD.
 *
 * The old 6411 implementation used two global counters. A mapping change for
 * mint A therefore made an in-flight write for unrelated mint B appear stale.
 * This implementation versions each mint independently.
 */
object TokenMapVersionGuard6411 {

    data class Stamp(val mappingVersion: Long, val laneRoutingVersion: Long)

    private val mappingByMint = ConcurrentHashMap<String, AtomicLong>()
    private val laneByMint = ConcurrentHashMap<String, AtomicLong>()
    private val globalMapBumps = AtomicLong(0L)
    private val globalLaneBumps = AtomicLong(0L)

    private fun key(mint: String): String = mint.trim().ifBlank { "__blank__" }
    private fun mapCounter(mint: String) =
        mappingByMint.computeIfAbsent(key(mint)) { AtomicLong(1L) }
    private fun laneCounter(mint: String) =
        laneByMint.computeIfAbsent(key(mint)) { AtomicLong(1L) }

    fun stamp7460(mint: String): Stamp =
        Stamp(mapCounter(mint).get(), laneCounter(mint).get())

    fun currentMappingVersion(mint: String): Long = mapCounter(mint).get()
    fun currentLaneRoutingVersion(mint: String): Long = laneCounter(mint).get()

    // Compatibility/report-only aggregate accessors.
    fun currentMappingVersion(): Long = globalMapBumps.get() + 1L
    fun currentLaneRoutingVersion(): Long = globalLaneBumps.get() + 1L

    /**
     * Start a new canonical mapping generation for this mint.
     * The returned version belongs to this owner. Any older owner finishing
     * later will fail [guardMetricWrite].
     */
    fun beginMappingGeneration7460(mint: String, reason: String): Long {
        val next = mapCounter(mint).incrementAndGet()
        globalMapBumps.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("TOKEN_MAP_GENERATION_BEGIN_7460")
            ForensicLogger.lifecycle(
                "TOKEN_MAP_GENERATION_BEGIN_7460",
                "mint=${mint.take(10)} version=$next reason=${reason.take(64)}",
            )
        } catch (_: Throwable) {}
        return next
    }

    /** Bump when a token map is materially updated by an external authority. */
    fun bumpMappingVersion(mint: String, reason: String) {
        beginMappingGeneration7460(mint, reason)
    }

    /** Bump when lane assignment materially changes. */
    fun bumpLaneRoutingVersion(mint: String, from: String, to: String) {
        val next = laneCounter(mint).incrementAndGet()
        globalLaneBumps.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "LANE_ROUTING_VERSION_BUMP_6411",
                "mint=${mint.take(10)} version=$next from=$from to=$to",
            )
        } catch (_: Throwable) {}
    }

    /**
     * Accept only a write belonging to the CURRENT generation for THIS mint.
     * Unrelated mints never invalidate one another.
     */
    fun guardMetricWrite(
        mint: String,
        stage: String,
        mappingVer: Long,
        laneVer: Long,
    ): Boolean {
        val currentMap = currentMappingVersion(mint)
        val currentLane = currentLaneRoutingVersion(mint)
        if (mappingVer != currentMap) {
            try {
                PipelineHealthCollector.labelInc("TOKEN_MAP_VERSION_STALE_DROP_6411")
                PipelineHealthCollector.labelInc("TOKEN_MAP_VERSION_STALE_DROP_PER_MINT_7460")
                val n = PipelineHealthCollector.labelCountSnapshot("TOKEN_MAP_VERSION_STALE_DROP_6411")
                if (n % 100L == 0L) {
                    ForensicLogger.lifecycle(
                        "TOKEN_MAP_VERSION_STALE_DROP_6411",
                        "mint=${mint.take(10)} stage=$stage ver=$mappingVer current=$currentMap dropCount=$n",
                    )
                }
            } catch (_: Throwable) {}
            return false
        }
        if (laneVer != currentLane) {
            try {
                PipelineHealthCollector.labelInc("LANE_ROUTING_VERSION_STALE_DROP_6411")
                PipelineHealthCollector.labelInc("LANE_ROUTING_VERSION_STALE_DROP_PER_MINT_7460")
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun statusLine(): String =
        "trackedMints=${mappingByMint.size} mapBumps=${globalMapBumps.get()} laneBumps=${globalLaneBumps.get()} " +
            "mapStaleDrops=${try { PipelineHealthCollector.labelCountSnapshot("TOKEN_MAP_VERSION_STALE_DROP_6411") } catch (_: Throwable) { 0L }} " +
            "laneStaleDrops=${try { PipelineHealthCollector.labelCountSnapshot("LANE_ROUTING_VERSION_STALE_DROP_6411") } catch (_: Throwable) { 0L }}"

    internal fun resetForTest() {
        mappingByMint.clear()
        laneByMint.clear()
        globalMapBumps.set(0L)
        globalLaneBumps.set(0L)
    }
}
