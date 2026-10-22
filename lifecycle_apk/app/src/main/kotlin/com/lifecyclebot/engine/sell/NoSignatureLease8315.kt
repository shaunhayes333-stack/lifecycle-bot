package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.8315 — AN EMERGENCY THAT SIGNED NOTHING KEEPS ITS LEASE AND ITS CADENCE.
 *
 * 5.0.8314 live: Au's emergency ended ROUTE_FAILED_NO_SIGNATURE on every attempt and re-fired ~400 ms later
 * (redispatchGap avg 402 ms, n=595). requestSell's terminal branch released the CloseLease on NO_SIGNATURE with no
 * retry delay, so the tick loop, the risk clock and the rapid stop monitor each re-acquired a fresh lease at once.
 *
 * Why not "recordRetry, then release": the 2 s floor (nextEligibleMs) and the 8094 self-retry both live on the lease —
 * scheduleEmergencyRetry8094 fires only while `current(mint) != null` — so a release right after would discard both and
 * change nothing. Here an EMERGENCY NO_SIGNATURE keeps its lease exactly as FAILED_RETRYABLE does: [CloseLease.recordRetry]
 * flips it out of flight (never a blocking lease: 3915 counts only in-flight leases for SellOnlySafeMode and the
 * CLOSE_LEASE_LEAK_AFTER_NO_SIGNATURE doctor), sets the 2 s floor, and the lease re-dispatches itself when the floor
 * passes; acquire() meanwhile refuses every re-fire until the 7807 cadence (2 / 3 / 5 / 8 / 10 s) allows the next one,
 * and single-flights it. A non-emergency NO_SIGNATURE releases as before (its exit re-asks on its own tick cadence).
 * Nothing here signs or sends; SignedTxLedger8085 and the lease still refuse a second concurrent sell.
 */
object NoSignatureLease8315 {

    /** Pure: does [result] under [reason] keep the lease — an emergency that produced no signature? */
    fun keepsLease8315(reason: String?, result: String): Boolean =
        result == "ROUTE_FAILED_NO_SIGNATURE" && ProtectiveExitClass7807.isEmergency(reason)

    /**
     * Executor.requestSell terminal branch: settle the lease of [mint] for [result]. An emergency NO_SIGNATURE records a
     * retry (2 s floor, 7807 cadence on re-entry, 8094 self-retry) and keeps the lease; every other terminal result
     * releases it as before. Returns true when the lease was kept.
     */
    fun settle8315(mint: String, reason: String, result: String): Boolean {
        if (!keepsLease8315(reason, result)) {
            CloseLease.release(mint, result)
            return false
        }
        val attempt = CloseLease.recordRetry(mint, "NO_SIGNATURE")
        try {
            PipelineHealthCollector.labelInc("EMERGENCY_NO_SIGNATURE_LEASE_KEPT_8315")
            ForensicLogger.lifecycle("EMERGENCY_NO_SIGNATURE_LEASE_KEPT_8315",
                "mint=${mint.take(10)} reason=${reason.take(60)} attempt=$attempt waitMs=${CloseLease.msUntilEligible(mint)} action=cadence_retry_no_release")
        } catch (_: Throwable) {}
        return true
    }
}
