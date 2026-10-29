package com.lifecyclebot.engine.sell

/**
 * V5.0.8435 — the sell door never defers a protective exit on a bookkeeping lock.
 *
 * 5.0.8434: one MOONSHOT HARD_STOP needed 35 attempts and 20.5 s to broadcast (sold at -79%). Every attempt returned
 * FAILED_RETRYABLE before any sell stage ran (SELL_PRE_ROUTE_WAIT_8288 = 35, TERMINAL_SELL_ABANDONED_6454 = 31 after the
 * terminal reservation), and the export kept no reason (SellRouteSpine8144 now keeps it). The one early return in
 * liveSell that logs no stage is the recovery-lock deferral, whose risk test was a list of name fragments; a reason the
 * shared emergency classifier knows (ProtectiveExitClass7807) but the fragments miss deferred there with no counter.
 * The fragments stay; the classifier is added, so every exit the rest of the sell path treats as an emergency is one here.
 */
object SellDoor8435 {
    /** Pure: an exit the shared emergency class protects (stops, catastrophes, rugs, hard floors, drains). */
    fun protectiveExit8435(reason: String?): Boolean =
        try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }
}
