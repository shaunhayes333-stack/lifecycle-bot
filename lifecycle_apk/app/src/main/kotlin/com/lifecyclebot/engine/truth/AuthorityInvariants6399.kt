package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6399 — AUTHORITY INVARIANTS.
 *
 * Hard-check functions that MUST pass before an EntryAuthorityTicket is
 * created. Any violation is a regression alarm — split-brain fault.
 *
 * Enforced (§"Enforce mandatory decision ordering"):
 *   1. outcome == ALLOW_LIVE
 *   2. effectiveScore >= effectiveFloor
 *   3. routeMode == LIVE
 *   4. NOT denylisted
 *   5. NOT shadow-only
 *
 * On violation:
 *   - increments authorityInvariantFailures counter
 *   - emits AUTHORITY_INVARIANT_FAILURE_6399
 *   - throws IllegalStateException (fail-fast; caller must catch)
 */
object AuthorityInvariants6399 {

    val authorityInvariantFailures = AtomicLong(0L)
    val shadowEnteredLivePathFailures = AtomicLong(0L)
    val denylistedEnteredLivePathFailures = AtomicLong(0L)
    val ticketIssuedBeforeAllowFailures = AtomicLong(0L)
    val scoreFloorAfterTicketFailures = AtomicLong(0L)

    /**
     * Assert an ALLOW_LIVE precondition BEFORE any ticket is minted.
     * Throws IllegalStateException on split-brain fault.
     */
    fun assertAllowLiveBeforeTicket(
        outcome: FdgTerminalOutcome6399,
        effectiveScore: Double,
        effectiveFloor: Double,
        routeMode: RouteMode6399,
        isDenylisted: Boolean,
        isShadowOnly: Boolean,
        mint: String,
    ) {
        val violations = mutableListOf<String>()
        if (outcome != FdgTerminalOutcome6399.FDG_ALLOW_LIVE) violations += "outcome=$outcome"
        if (effectiveScore < effectiveFloor) violations += "score=${"%.1f".format(effectiveScore)}<floor=${"%.1f".format(effectiveFloor)}"
        if (routeMode != RouteMode6399.LIVE) violations += "routeMode=$routeMode"
        if (isDenylisted) violations += "denylisted"
        if (isShadowOnly) violations += "shadowOnly"

        if (violations.isNotEmpty()) {
            authorityInvariantFailures.incrementAndGet()
            ticketIssuedBeforeAllowFailures.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("AUTHORITY_INVARIANT_FAILURE_6399") } catch (_: Throwable) {}
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "AUTHORITY_INVARIANT_FAILURE_6399",
                    "mint=${mint.take(10)} violations=${violations.joinToString(",")}"
                )
            } catch (_: Throwable) {}
            throw IllegalStateException("AUTHORITY_INVARIANT_FAILURE_6399 mint=$mint violations=$violations")
        }
    }

    /** Shadow candidates must never reach the live executor path. */
    fun assertNotShadowInLivePath(routeMode: RouteMode6399, isShadowOnly: Boolean, mint: String) {
        if (routeMode == RouteMode6399.SHADOW_READ_ONLY || isShadowOnly) {
            shadowEnteredLivePathFailures.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SHADOW_ENTERED_LIVE_PATH_6399") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("SHADOW_ENTERED_LIVE_PATH_6399", "mint=${mint.take(10)}") } catch (_: Throwable) {}
            throw IllegalStateException("SHADOW_ENTERED_LIVE_PATH_6399 mint=$mint")
        }
    }

    /** Denylisted candidates must never reach the live executor path. */
    fun assertNotDenylistedInLivePath(isDenylisted: Boolean, mint: String) {
        if (isDenylisted) {
            denylistedEnteredLivePathFailures.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DENYLISTED_ENTERED_LIVE_PATH_6399") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("DENYLISTED_ENTERED_LIVE_PATH_6399", "mint=${mint.take(10)}") } catch (_: Throwable) {}
            throw IllegalStateException("DENYLISTED_ENTERED_LIVE_PATH_6399 mint=$mint")
        }
    }

    /**
     * Any post-ticket score-floor decision is a regression. If the
     * executor / safety hold path attempts to recalculate, this fires.
     */
    fun reportScoreFloorAfterTicket(mint: String, callsite: String) {
        scoreFloorAfterTicketFailures.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SCORE_FLOOR_AFTER_TICKET_6399") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("SCORE_FLOOR_AFTER_TICKET_6399", "mint=${mint.take(10)} callsite=$callsite") } catch (_: Throwable) {}
    }

    internal fun clearAllForTest() {
        authorityInvariantFailures.set(0L)
        shadowEnteredLivePathFailures.set(0L)
        denylistedEnteredLivePathFailures.set(0L)
        ticketIssuedBeforeAllowFailures.set(0L)
        scoreFloorAfterTicketFailures.set(0L)
    }
}
