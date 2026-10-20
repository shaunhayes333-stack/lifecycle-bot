package com.lifecyclebot.engine.truth

/** Evidence needed to attribute an observed peak to one position, rather than to a later holding of the mint. */
object RunnerEvidence8239 {
    enum class Hold { HELD, OUTSIDE, UNKNOWN }

    fun hold(peakAtMs: Long, entryAtMs: Long, closeAtMs: Long, mode: String, positionId: String): Hold {
        if (peakAtMs <= 0L || entryAtMs <= 0L || positionId.isBlank() ||
            mode.uppercase() !in setOf("LIVE", "PAPER")) return Hold.UNKNOWN
        if (closeAtMs > 0L && closeAtMs < entryAtMs) return Hold.UNKNOWN
        return if (peakAtMs >= entryAtMs && (closeAtMs <= 0L || peakAtMs <= closeAtMs)) Hold.HELD else Hold.OUTSIDE
    }

    /** A correction names the observation it invalidates; a later price decline is not such evidence. */
    fun invalidates(storedPeakAtMs: Long, invalidObservedAtMs: Long): Boolean =
        storedPeakAtMs > 0L && invalidObservedAtMs > 0L && storedPeakAtMs == invalidObservedAtMs

    /** Re-express an observed absolute price after a proven entry-basis repair. Never substitute a current price. */
    fun rebaseReturn(returnPct: Double, oldEntryUsd: Double, newEntryUsd: Double): Double =
        if (!returnPct.isFinite() || oldEntryUsd <= 0.0 || !oldEntryUsd.isFinite() ||
            newEntryUsd <= 0.0 || !newEntryUsd.isFinite()) Double.NaN
        else ((oldEntryUsd * (1.0 + returnPct / 100.0)) / newEntryUsd - 1.0) * 100.0
}
