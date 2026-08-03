package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6411 §9.3 — TOKEN-MAP / LANE-ROUTING VERSION GUARD.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * Build 6410 showed TOKEN_METRIC_STAGE_LANE_SOFT_MISMATCH = 5486
 * — repeated conflicting metric writes because token mapping and
 * lane assignment can shift between callback issuance and callback
 * commit. This module hands out monotonic version stamps and
 * rejects stale writes.
 *
 * DESIGN
 * ──────
 *   • Two independent monotonic counters:
 *       - mappingVersion (increments when token mapping changes)
 *       - laneRoutingVersion (increments when lane assignment changes)
 *   • Callers request `currentMappingVersion()` when they START a
 *     mapping/lane pipeline, then pass the version through to
 *     `guardMetricWrite(...)` at commit. Older versions are dropped.
 *   • Every drop increments TOKEN_MAP_VERSION_STALE_DROP_6411 or
 *     LANE_ROUTING_VERSION_STALE_DROP_6411.
 *
 * Advisory — enforcement happens where callers voluntarily consult
 * this guard. First integration point is the TOKEN_MAP_START →
 * TOKEN_MAP_OK path.
 */
object TokenMapVersionGuard6411 {

    private val mappingVersion = AtomicLong(1L)
    private val laneRoutingVersion = AtomicLong(1L)

    fun currentMappingVersion(): Long = mappingVersion.get()
    fun currentLaneRoutingVersion(): Long = laneRoutingVersion.get()

    /** Bump when a token map is materially updated (new decimals/pool). */
    fun bumpMappingVersion(mint: String, reason: String) {
        val next = mappingVersion.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "TOKEN_MAP_VERSION_BUMP_6411",
                "mint=${mint.take(10)} version=$next reason=${reason.take(64)}",
            )
        } catch (_: Throwable) {}
    }

    /** Bump when lane assignment materially changes. */
    fun bumpLaneRoutingVersion(mint: String, from: String, to: String) {
        val next = laneRoutingVersion.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "LANE_ROUTING_VERSION_BUMP_6411",
                "mint=${mint.take(10)} version=$next from=$from to=$to",
            )
        } catch (_: Throwable) {}
    }

    /**
     * Returns true when a metric write with mapping version [mappingVer]
     * and lane version [laneVer] should be accepted. Returns false when
     * either version is stale; the caller MUST drop the write.
     */
    fun guardMetricWrite(mint: String, stage: String, mappingVer: Long, laneVer: Long): Boolean {
        val currentMap = mappingVersion.get()
        val currentLane = laneRoutingVersion.get()
        if (mappingVer < currentMap) {
            try {
                PipelineHealthCollector.labelInc("TOKEN_MAP_VERSION_STALE_DROP_6411")
                // Log at very low volume (every 500th) so we can see it
                // exists in telemetry without dominating the ring.
                val n = PipelineHealthCollector.labelCountSnapshot("TOKEN_MAP_VERSION_STALE_DROP_6411")
                if (n % 500L == 0L) {
                    ForensicLogger.lifecycle(
                        "TOKEN_MAP_VERSION_STALE_DROP_6411",
                        "mint=${mint.take(10)} stage=$stage ver=$mappingVer current=$currentMap dropCount=$n",
                    )
                }
            } catch (_: Throwable) {}
            return false
        }
        if (laneVer < currentLane) {
            try {
                PipelineHealthCollector.labelInc("LANE_ROUTING_VERSION_STALE_DROP_6411")
                val n = PipelineHealthCollector.labelCountSnapshot("LANE_ROUTING_VERSION_STALE_DROP_6411")
                if (n % 500L == 0L) {
                    ForensicLogger.lifecycle(
                        "LANE_ROUTING_VERSION_STALE_DROP_6411",
                        "mint=${mint.take(10)} stage=$stage laneVer=$laneVer current=$currentLane dropCount=$n",
                    )
                }
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun statusLine(): String =
        "mappingVersion=${mappingVersion.get()} laneRoutingVersion=${laneRoutingVersion.get()} " +
            "mapStaleDrops=${try { PipelineHealthCollector.labelCountSnapshot("TOKEN_MAP_VERSION_STALE_DROP_6411") } catch (_: Throwable) { 0L }} " +
            "laneStaleDrops=${try { PipelineHealthCollector.labelCountSnapshot("LANE_ROUTING_VERSION_STALE_DROP_6411") } catch (_: Throwable) { 0L }}"

    internal fun resetForTest() {
        mappingVersion.set(1L)
        laneRoutingVersion.set(1L)
    }
}
