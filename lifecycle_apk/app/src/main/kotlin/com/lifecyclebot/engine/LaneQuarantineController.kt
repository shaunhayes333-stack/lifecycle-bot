package com.lifecyclebot.engine

/**
 * V5.0.6684 compatibility facade. LaneAutoPauseGuard owns pause state and
 * AdaptiveLaneReproof6684 owns exact Lab proof + autonomous re-entry.
 */
object LaneQuarantineController {
    const val VERSION = "V5.0.6684_LANE_QUARANTINE_FACADE"

    fun isQuarantined(lane: String): Boolean =
        try { LaneAutoPauseGuard.statusFor(lane) != null } catch (_: Throwable) { false }

    fun logBlockedEntry(lane: String, symbol: String, mint: String, primary: String) {
        try {
            ForensicLogger.lifecycle(
                "LANE_QUARANTINED_BLOCKED_ENTRY_6684",
                "lane=${lane.uppercase()} symbol=$symbol mint=${mint.take(10)} primary=$primary reason=awaiting_exact_lab_proof",
            )
            PipelineHealthCollector.labelInc("LANE_QUARANTINED_BLOCKED_ENTRY_6684_${lane.uppercase()}")
        } catch (_: Throwable) {}
    }

    fun quarantineSnapshot(): Map<String, Boolean> =
        try { LaneAutoPauseGuard.pausedLanes().associateWith { false } } catch (_: Throwable) { emptyMap() }

    fun statusLine(): String =
        "$VERSION paused=${try { LaneAutoPauseGuard.pausedLanes().joinToString(",").ifEmpty { "-" } } catch (_: Throwable) { "unavailable" }} " +
            AdaptiveLaneReproof6684.statusLine()
}
