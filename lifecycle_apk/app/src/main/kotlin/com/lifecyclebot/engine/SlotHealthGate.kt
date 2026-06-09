package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1470 (spec item 7) — SLOT-HEALTH ENTRY ADMISSION.
 *
 * The bot idles because dirty/ghost slots and wedged leases occupy capacity. We must
 * NOT disable trading, but we should DEFER (not permanently block) new executable buys
 * for one cycle while slots are dirty, so cleanup can catch up. The operator's explicit
 * rule: "log EXEC_DEFERRED_SLOT_HEALTH, not block permanently".
 *
 * BotService publishes live slot-health each cycle via publish(); TradeAuthorizer reads
 * shouldDeferBuy() at the top of authorize() and returns a SOFT, retryable reject when
 * dirty. PROBE_ONLY / already-confirmed-high-edge candidates bypass (handled by caller).
 *
 * SAFETY: pure observation/admission timing. Never disables a lane, never blocks exits
 * (exits run on their own dispatcher and never call authorize()), never touches
 * positions or balances. A high-edge confirmed candidate is allowed through even when
 * dirty so genuine alpha is never starved.
 */
object SlotHealthGate {

    private val ghostOpenCount = AtomicInteger(0)
    private val forcedOpenCount = AtomicInteger(0)
    private val openPositionCount = AtomicInteger(0)
    private val supervisorActive = AtomicInteger(0)
    private val supervisorCap = AtomicInteger(48)
    private val exitPending = AtomicBoolean(false)
    private val lastPublishMs = AtomicLong(0L)

    // Thresholds straight from the spec.
    private const val FORCED_OPEN_DIRTY = 20

    fun publish(
        ghostOpen: Int,
        forcedOpen: Int,
        openPositions: Int,
        supActive: Int,
        supCap: Int,
        exitInFlight: Boolean,
    ) {
        ghostOpenCount.set(ghostOpen.coerceAtLeast(0))
        forcedOpenCount.set(forcedOpen.coerceAtLeast(0))
        openPositionCount.set(openPositions.coerceAtLeast(0))
        supervisorActive.set(supActive.coerceAtLeast(0))
        supervisorCap.set(supCap.coerceAtLeast(1))
        exitPending.set(exitInFlight)
        lastPublishMs.set(System.currentTimeMillis())
    }

    data class DeferDecision(val defer: Boolean, val reason: String)

    /**
     * Decide whether a NEW executable buy should defer one cycle.
     * @param candidateConfirmedHighEdge true if the candidate is already confirmed
     *        high-edge (then it bypasses the pending-exit defer, per spec).
     */
    fun shouldDeferBuy(candidateConfirmedHighEdge: Boolean): DeferDecision {
        // Stale snapshot (BotService not publishing) → never defer (fail-open).
        if (System.currentTimeMillis() - lastPublishMs.get() > 15_000L) {
            return DeferDecision(false, "stale_snapshot_fail_open")
        }
        if (ghostOpenCount.get() > 0) {
            return DeferDecision(true, "GHOST_OPEN=${ghostOpenCount.get()}")
        }
        if (forcedOpenCount.get() > FORCED_OPEN_DIRTY) {
            return DeferDecision(true, "FORCED_OPEN=${forcedOpenCount.get()}>$FORCED_OPEN_DIRTY")
        }
        if (supervisorActive.get() > supervisorCap.get()) {
            return DeferDecision(true, "SUPERVISOR_OVER_CAP=${supervisorActive.get()}/${supervisorCap.get()}")
        }
        if (exitPending.get() && !candidateConfirmedHighEdge) {
            return DeferDecision(true, "EXIT_PENDING_NOT_HIGH_EDGE")
        }
        return DeferDecision(false, "slot_health_ok")
    }

    fun snapshotLine(): String =
        "ghost=${ghostOpenCount.get()} forced=${forcedOpenCount.get()} open=${openPositionCount.get()} " +
        "sup=${supervisorActive.get()}/${supervisorCap.get()} exitPending=${exitPending.get()}"
}
