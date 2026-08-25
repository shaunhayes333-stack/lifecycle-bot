package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6466 §P0 — TERMINAL MUTATION AUTHORITY.
 *
 * SINGLE authoritative method every FULL/PARTIAL/SL/TP/TRAIL/CATASTROPHIC/
 * SCRATCH/FORCED close must route through. Enforces the (runId + mode +
 * positionId + generation + terminalSequence) idempotency key BEFORE any
 * side effect. First claimant owns terminal mutation; duplicates return
 * ALREADY_FINALIZED without wallet credit, journal SELL, PnL, learner
 * event, or registry close.
 *
 * The authority does NOT do the accounting itself — SellFinalizationCoordinator
 * still calculates proceeds/fees/PnL. This is the GATE + POST-FANOUT
 * substrate. Callers wrap their side-effect block in `withTerminalClaim`.
 */
object TerminalMutationAuthority6466 {

    enum class ClaimResult { GRANTED, ALREADY_FINALIZED, BLANK_KEY }

    data class TerminalEvent(
        val positionId: String, val mint: String, val symbol: String,
        val mode: String, val generation: Long, val terminalSequence: Long,
        val runId: String, val exitReason: String,
    )

    data class Record(val key: String, val claimedAtMs: Long, val exitReason: String)

    private val records = ConcurrentHashMap<String, Record>()
    private const val CAP = 8192

    private val claims = AtomicLong(0L)
    private val alreadyFinalized = AtomicLong(0L)
    private val blanks = AtomicLong(0L)
    fun buildKey(mode: String, positionId: String, generation: Long, terminalSequence: Long): String {
        if (positionId.isBlank() || generation <= 0L) return ""
        val closeType = if (terminalSequence == FULL_CLOSE_SEQUENCE_6522) "FULL_CLOSE" else "PARTIAL_CLOSE:$terminalSequence"
        return "${mode.lowercase()}|$positionId|$generation|$closeType"
    }

    const val FULL_CLOSE_SEQUENCE_6522 = 999L

    /**
     * Attempt to claim terminal mutation. Returns GRANTED on first claim,
     * ALREADY_FINALIZED on duplicate. Callers MUST bail out of all side
     * effects on ALREADY_FINALIZED.
     */
    fun claim(event: TerminalEvent): ClaimResult {
        val key = buildKey(event.mode, event.positionId, event.generation, event.terminalSequence)
        if (key.isBlank()) {
            blanks.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_MUTATION_BLANK_KEY_6466",
                    "mint=${event.mint.take(10)} exitReason=${event.exitReason}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_MUTATION_BLANK_KEY_6466")
            } catch (_: Throwable) {}
            return ClaimResult.BLANK_KEY
        }
        var existed = true
        records.compute(key) { _, cur ->
            if (cur != null) return@compute cur
            existed = false
            Record(key, System.currentTimeMillis(), event.exitReason)
        }
        return if (!existed) {
            claims.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("TERMINAL_MUTATION_CLAIMED_6466")
                ForensicLogger.lifecycle(
                    "TERMINAL_MUTATION_CLAIMED_6466",
                    "key=${key.take(48)} mint=${event.mint.take(10)} exit=${event.exitReason} " +
                        "gen=${event.generation} seq=${event.terminalSequence}",
                )
            } catch (_: Throwable) {}
            if (records.size > CAP) {
                records.entries.minByOrNull { it.value.claimedAtMs }?.key?.let { records.remove(it) }
            }
            ClaimResult.GRANTED
        } else {
            alreadyFinalized.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_MUTATION_ALREADY_FINALIZED_6466",
                    "key=${key.take(48)} mint=${event.mint.take(10)} rejected=${event.exitReason}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_MUTATION_ALREADY_FINALIZED_6466")
            } catch (_: Throwable) {}
            ClaimResult.ALREADY_FINALIZED
        }
    }

    fun statusLine(): String =
        "tracked=${records.size} claims=${claims.get()} alreadyFinalized=${alreadyFinalized.get()} blanks=${blanks.get()}"

    fun claimCount(): Long = claims.get()

    internal fun resetForTest() {
        records.clear()
        claims.set(0L); alreadyFinalized.set(0L); blanks.set(0L)
    }
}
