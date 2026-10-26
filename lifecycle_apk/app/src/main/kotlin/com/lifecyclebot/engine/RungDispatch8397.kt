package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/** The production dispatch/receipt boundary for both spike and scalp rungs. */
object RungDispatch8397 {
    data class Offer8397(val entryTime: Long, val requestAtMs: Long?, val ladder: String?)

    /** Capture on the offering thread, before BotService queues its IO coroutine. */
    fun capture8397(ts: TokenState, reason: String): Offer8397 {
        val entryTime = ts.position.entryTime
        val pending = RunnerLive8153.pendingRequest8397(ts, reason)
        return Offer8397(entryTime, pending?.atMs, pending?.ladder)
    }

    /**
     * An attempt never consumes a rung. The executor returns true only for its applied receipt;
     * rejection, exception and cancellation all return the pending request to the retry path.
     * Parsing belongs to RunnerLive8153: the first "_T" in SCALP_TAKE_PROFIT is not its tier.
     */
    suspend fun dispatch8397(ts: TokenState, reason: String, offer: Offer8397 = capture8397(ts, reason),
                            sell: suspend () -> Boolean): Boolean {
        if (!ts.position.isOpen || ts.position.entryTime != offer.entryTime) return false
        if (RunnerLive8153.rungOf8153(reason) > 0 && offer.requestAtMs == null) return false
        if (offer.requestAtMs != null) {
            // An expired/superseded queued callback must not sell for a newer request with the same reason.
            if (!RunnerLive8153.acceptDispatch8397(ts, reason, offer.requestAtMs, System.currentTimeMillis(), offer.ladder)) return false
        } else SpikeCapture7943.dispatched8153(ts, reason)
        var applied = false
        try {
            applied = sell()
            return applied
        } finally {
            // A completed old position must never advance/release the replacement position's ladder.
            if (ts.position.isOpen && ts.position.entryTime == offer.entryTime) {
                if (applied) {
                    SpikeCapture7943.onApplied8153(ts, reason, offer.ladder)
                } else {
                    val tier = RunnerLive8153.rungOf8153(reason)
                    if (tier > 0 && offer.requestAtMs != null) SpikeCapture7943.rearm(ts, tier, reason, offer.requestAtMs, offer.ladder)
                }
            }
        }
    }
}
