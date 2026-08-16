package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — TERMINAL CLOSE IDEMPOTENCY LATCH.
 *
 * OPERATOR MANDATE:
 *   "Every terminal SELL atomically transitions:
 *      OPEN/PARTIALLY_CLOSED -> CLOSING -> CLOSED
 *    Terminal close must be exactly-once/idempotent.
 *    Reject any second SELL for an already CLOSED PositionId before
 *    quantity/accounting/journal mutation.
 *
 *    Investigate duplicate-looking HXbqtb terminal SELL records and
 *    doubleConfirm=4."
 *
 * DESIGN
 * ──────
 * Compact latch keyed by canonical positionId + terminalEpoch. Once a
 * (positionId, terminalEpoch) pair is admitted, subsequent admission
 * attempts for the same key return REJECTED_DUPLICATE. Callers MUST call
 * `tryClaim` BEFORE mutating cash/PnL/journal/canonical for a terminal
 * SELL. If claim fails, the caller MUST skip all mutations.
 */
object TerminalCloseIdempotencyLatch6450 {

    enum class ClaimResult { CLAIMED, REJECTED_DUPLICATE, REJECTED_UNKNOWN_POSITION }

    private data class Claim(val positionId: String, val terminalEpoch: Long, val claimedAtMs: Long, val reason: String)

    private val claims = ConcurrentHashMap<String, Claim>() // key = "$positionId#$terminalEpoch"
    private val perPositionTerminal = ConcurrentHashMap<String, Long>() // positionId -> last terminalEpoch
    private val claimsGranted = AtomicLong(0L)
    private val duplicateRejects = AtomicLong(0L)
    private val unknownRejects = AtomicLong(0L)

    fun tryClaim(positionId: String, terminalEpoch: Long, reason: String): ClaimResult {
        if (positionId.isBlank()) {
            unknownRejects.incrementAndGet()
            return ClaimResult.REJECTED_UNKNOWN_POSITION
        }
        val key = "$positionId#$terminalEpoch"
        val prior = claims.putIfAbsent(key, Claim(positionId, terminalEpoch, System.currentTimeMillis(), reason.take(40)))
        if (prior != null) {
            duplicateRejects.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_CLOSE_DUPLICATE_REJECTED_6450",
                    "positionId=${positionId.take(12)} terminalEpoch=$terminalEpoch " +
                        "priorReason=${prior.reason} newReason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_CLOSE_DUPLICATE_REJECTED_6450")
            } catch (_: Throwable) {}
            return ClaimResult.REJECTED_DUPLICATE
        }
        // Also reject if this positionId has ANY previous terminal claim.
        val priorTerminal = perPositionTerminal.putIfAbsent(positionId, terminalEpoch)
        if (priorTerminal != null && priorTerminal != terminalEpoch) {
            duplicateRejects.incrementAndGet()
            claims.remove(key)
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_CLOSE_DUPLICATE_POSITION_REJECTED_6450",
                    "positionId=${positionId.take(12)} priorTerminalEpoch=$priorTerminal newTerminalEpoch=$terminalEpoch",
                )
                PipelineHealthCollector.labelInc("TERMINAL_CLOSE_DUPLICATE_POSITION_REJECTED_6450")
            } catch (_: Throwable) {}
            return ClaimResult.REJECTED_DUPLICATE
        }
        claimsGranted.incrementAndGet()
        return ClaimResult.CLAIMED
    }

    fun isTerminal(positionId: String): Boolean = perPositionTerminal.containsKey(positionId)

    fun statusLine(): String = "claimed=${claimsGranted.get()} dupRejects=${duplicateRejects.get()} unknownRejects=${unknownRejects.get()}"
}
