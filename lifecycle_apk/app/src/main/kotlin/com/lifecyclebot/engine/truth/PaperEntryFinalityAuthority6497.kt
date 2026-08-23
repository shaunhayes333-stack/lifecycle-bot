package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6497 §2 — PAPER ENTRY FINALITY AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6496 evidence):
 *
 *   "168 BUY verdicts, 270 FDG allows, but 0 executor invocations
 *    and 0 paper buys.
 *
 *      LANE_BUY_INTENT_OVERRIDES_BASE_WAIT = 168
 *      EXEC_LEASE_SET = 159
 *      PAPER_BUY_NOT_OPENED = 159
 *      BUY_NON_TERMINAL_RELEASE = 159
 *
 *    P0: Repair PAPER_BUY_NOT_OPENED: one successful BUY-qualified
 *    candidate must produce either PAPER_BUY_OK or one explicit
 *    terminal rejection reason. No silent/non-terminal release."
 *
 * DESIGN
 * ──────
 * Per-attempt latch enforces terminal outcome:
 *
 *   • `beginAttempt(attemptId, mint, symbol, lane)` — called at top
 *     of paperBuy. Records the attempt.
 *   • `markOk(attemptId)` — called on successful open.
 *   • `markRejected(attemptId, reason)` — called by
 *     markPaperBuyNotOpened.
 *
 * A background sweep flushes any attempt older than TERMINAL_TTL_MS
 * that never marked terminal. That flush emits
 * `PAPER_ENTRY_FINALITY_MISSING_TERMINAL_6497` with the mint/symbol
 * and last-known state — this is THE label that surfaces the
 * "silent non-terminal release" pattern. Root cause classifier
 * consumes this label under the ENTRY_FINALITY tier.
 *
 * `sweepStale()` is called opportunistically from every
 * `beginAttempt` — no separate coroutine required, since attempts
 * are the only meaningful clock for this authority.
 */
object PaperEntryFinalityAuthority6497 {

    data class Attempt(
        val attemptId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val startedAtMs: Long,
    )

    private val open = ConcurrentHashMap<String, Attempt>()
    private val begun = AtomicLong(0L)
    private val ok = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val flushed = AtomicLong(0L)

    private const val TERMINAL_TTL_MS = 30_000L

    fun beginAttempt(attemptId: String, mint: String, symbol: String, lane: String) {
        if (attemptId.isBlank()) return
        // Opportunistic sweep for stragglers.
        sweepStale()
        open[attemptId] = Attempt(attemptId, mint, symbol, lane.uppercase(), System.currentTimeMillis())
        begun.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_ENTRY_FINALITY_BEGIN_6497") } catch (_: Throwable) {}
    }

    fun markOk(attemptId: String) {
        if (attemptId.isBlank()) return
        val a = open.remove(attemptId) ?: return
        ok.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_ENTRY_FINALITY_OK_6497") } catch (_: Throwable) {}
        // consume order-size seal on terminal outcome so a later tick reseals fresh
        try { SealedOrderSizeAuthority6497.consume(a.mint) } catch (_: Throwable) {}
    }

    fun markRejected(attemptId: String, reason: String) {
        if (attemptId.isBlank()) return
        val a = open.remove(attemptId) ?: return
        rejected.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PAPER_ENTRY_FINALITY_REJECT_6497")
            PipelineHealthCollector.labelInc("PAPER_ENTRY_FINALITY_REJECT_${reason.uppercase()}_6497")
        } catch (_: Throwable) {}
        try { SealedOrderSizeAuthority6497.consume(a.mint) } catch (_: Throwable) {}
    }

    /**
     * Flushes attempts older than TERMINAL_TTL_MS that never marked
     * terminal. Emits PAPER_ENTRY_FINALITY_MISSING_TERMINAL_6497.
     */
    fun sweepStale() {
        val now = System.currentTimeMillis()
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.startedAtMs > TERMINAL_TTL_MS) {
                it.remove()
                flushed.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "PAPER_ENTRY_FINALITY_MISSING_TERMINAL_6497",
                        "attemptId=${e.value.attemptId} mint=${e.value.mint.take(10)} symbol=${e.value.symbol} " +
                            "lane=${e.value.lane} ageMs=${now - e.value.startedAtMs}",
                    )
                    PipelineHealthCollector.labelInc("PAPER_ENTRY_FINALITY_MISSING_TERMINAL_6497")
                } catch (_: Throwable) {}
                try { SealedOrderSizeAuthority6497.consume(e.value.mint) } catch (_: Throwable) {}
            }
        }
    }

    fun statusLine(): String =
        "begun=${begun.get()} ok=${ok.get()} rejected=${rejected.get()} " +
            "missingTerminal=${flushed.get()} live=${open.size}"

    internal fun resetForTest() {
        open.clear()
        begun.set(0L); ok.set(0L); rejected.set(0L); flushed.set(0L)
    }
}
