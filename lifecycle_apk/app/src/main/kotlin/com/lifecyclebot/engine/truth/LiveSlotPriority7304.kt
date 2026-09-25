package com.lifecyclebot.engine.truth

/**
 * V5.0.7304 §THE LAST SLOT GOES TO PROVEN EDGE.
 *
 * On the 5.0.7302 live wallet (~0.12 SOL) the routable floor means the
 * wallet carries two positions. Whichever candidate reached the executor
 * first took the last slot, so an unproven lane could hold it while a lane
 * with a measured positive net return had nowhere to go.
 *
 * When exactly one routable live slot is free, a lane whose recorded
 * history (OracleTradeHistory7287, net of fees) does not yet show
 * n >= PROVEN_MIN_CLOSES_7304 closes with a positive mean is deferred.
 * The slot is never locked: if no proven lane has taken it within
 * SLOT_RELEASE_MS_7304 of it becoming the last one, any lane may take it.
 * Paper is not touched. With two or more free slots nothing changes.
 */
object LiveSlotPriority7304 {
    private const val PROVEN_MIN_CLOSES_7304 = 20
    const val SLOT_RELEASE_MS_7304 = 10 * 60_000L

    @Volatile private var lastSlotSinceMs = 0L

    fun isProven(stat: OracleTradeHistory7287.Stat?): Boolean =
        stat != null && stat.n >= PROVEN_MIN_CLOSES_7304 && stat.meanNetPct > 0.0

    /** Pure verdict: true = defer this candidate. */
    fun shouldDefer(freeSlots: Int, proven: Boolean, slotSinceMs: Long, nowMs: Long): Boolean {
        if (freeSlots != 1 || proven) return false
        if (slotSinceMs <= 0L) return true
        return nowMs - slotSinceMs < SLOT_RELEASE_MS_7304
    }

    /** Live entry check. Returns true when the candidate should wait. */
    fun deferLive(lane: String, freeSlots: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (freeSlots != 1) {
            lastSlotSinceMs = 0L
            return false
        }
        if (lastSlotSinceMs <= 0L) lastSlotSinceMs = nowMs
        val laneKey = lane.trim().uppercase()
        val stat = try {
            OracleTradeHistory7287.lane(laneKey)
                ?: if (laneKey == "PROJECT_SNIPER") OracleTradeHistory7287.lane("PRESALE_SNIPE") else null
        } catch (_: Throwable) { null }
        val proven = isProven(stat)
        val defer = shouldDefer(freeSlots, proven, lastSlotSinceMs, nowMs)
        try {
            val label = when {
                defer -> "LIVE_LAST_SLOT_RESERVED_FOR_PROVEN_LANE_7304"
                proven -> "LIVE_LAST_SLOT_TAKEN_BY_PROVEN_LANE_7304"
                else -> "LIVE_LAST_SLOT_RELEASED_AFTER_WAIT_7304"
            }
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(label)
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("${label}_${laneKey.take(20)}")
        } catch (_: Throwable) {}
        return defer
    }
}
