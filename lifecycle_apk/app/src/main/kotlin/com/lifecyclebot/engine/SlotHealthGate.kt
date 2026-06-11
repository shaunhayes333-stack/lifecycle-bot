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
    // V5.9.1498 — ghost-defer aging. A persistent ghost count must NEVER park
    // the bot indefinitely (operator: 6h dead with ghosts dominating). We DEFER
    // while ghosts are fresh (gives the reaper a chance), but FAIL-OPEN once the
    // ghost condition has been continuously stuck past GHOST_DEFER_GRACE_MS — at
    // that point the reaper is clearly not clearing them and starving entries is
    // worse than running with a few stale slots. Mirrors the FDG fail-open rule.
    private val ghostStuckSinceMs = AtomicLong(0L)
    private const val GHOST_DEFER_GRACE_MS = 60_000L  // 1 min of deferring, then fail-open

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
        // V5.9.1498 — track how long ghosts have been continuously > 0.
        if (ghostOpen > 0) {
            ghostStuckSinceMs.compareAndSet(0L, System.currentTimeMillis())
        } else {
            ghostStuckSinceMs.set(0L)
        }
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
        // V5.9.1498 — AGING GHOST DEFER (fail-open). Defer while ghosts are fresh
        // so the reaper can catch up, but never permanently. Once the ghost
        // condition has been continuously stuck past the grace window, fail-open:
        // the reaper is not clearing them and parking all entries (the 6h-dead
        // failure mode) is far worse than trading with a few stale slots. Exits
        // and the -15% hard floor are unaffected.
        val ghosts = ghostOpenCount.get()
        if (ghosts > 0) {
            val stuckSince = ghostStuckSinceMs.get()
            val stuckMs = if (stuckSince > 0L) System.currentTimeMillis() - stuckSince else 0L
            if (stuckMs <= GHOST_DEFER_GRACE_MS) {
                return DeferDecision(true, "GHOST_OPEN=$ghosts(stuck=${stuckMs}ms)")
            }
            // Grace exceeded → fail-open so entries resume; reaper keeps working.
            return DeferDecision(false, "GHOST_OPEN=${ghosts}_FAIL_OPEN_stuck=${stuckMs}ms")
        }
        if (forcedOpenCount.get() > FORCED_OPEN_DIRTY) {
            return DeferDecision(true, "FORCED_OPEN=${forcedOpenCount.get()}>$FORCED_OPEN_DIRTY")
        }
        if (supervisorActive.get() > supervisorCap.get()) {
            return DeferDecision(true, "SUPERVISOR_OVER_CAP=${supervisorActive.get()}/${supervisorCap.get()}")
        }
        // V5.9.1487 — REMOVED the exitPending defer (was the executor stall).
        // Snapshot 5.0.3492 showed EXEC_DEFERRED_SLOT_HEALTH/EXIT_PENDING_NOT_HIGH_EDGE
        // firing nonstop, throttling the executor to ~10 trades/hour. Root cause: the
        // universal stop-loss sweep is REQUESTED EVERY CYCLE (correct — it enforces SLs
        // on open positions) and is only briefly consumed on the exit dispatcher, so
        // universalSlSweepPending — and thus exitPending — is true essentially always.
        // In bootstrap almost nothing is "confirmed high-edge", so this clause deferred
        // virtually every buy on a permanent basis. That is a volume veto, not the
        // one-cycle cleanup pause it was meant to be, and the routine SL sweep should
        // NEVER gate entries (exits run on their own dispatcher and don't share slots
        // with entry admission). The real dirty-slot pressure is already fully covered
        // by the ghost / forced-open / supervisor-over-cap checks above, which DO clear
        // once cleanup catches up. Exits are unaffected; the -15% hard floor and all FDG
        // vetoes remain. The candidateConfirmedHighEdge param is retained for callers.
        @Suppress("UNUSED_PARAMETER")
        val highEdgeBypassRetained = candidateConfirmedHighEdge
        return DeferDecision(false, "slot_health_ok")
    }

    fun snapshotLine(): String =
        "ghost=${ghostOpenCount.get()} forced=${forcedOpenCount.get()} open=${openPositionCount.get()} " +
        "sup=${supervisorActive.get()}/${supervisorCap.get()} exitPending=${exitPending.get()}"
}
