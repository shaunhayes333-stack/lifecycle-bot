package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6499 §3 — MARK AUTHORITY INVALID LATCH.
 *
 * OPERATOR MANDATE (verbatim, 6498 evidence):
 *
 *   "USDS proves the mark problem is still leaking through exits.
 *    You're correctly detecting LIQ_HALVED_MARK_INVALIDATED_6310
 *    bestPnl=1889.9% liquidity halvedTo=12.6% positive mark is
 *    phantom — Excellent guard.
 *
 *    But immediately around that you're still writing:
 *      USDS ... PARTIAL_SELL ... pnl=+0.321473 +0.323175 +0.865505
 *    and firing:
 *      SWEEP_TAKE_PROFIT ... pnl=1889
 *
 *    So the mark is being classified as invalid AFTER or independently
 *    of the TP/partial-close economic calculation.
 *
 *    That guard must move upstream:
 *      invalid mark → no TP calculation → no profit-labelled partial
 *      → no learning reward
 *
 *    The system can still emergency-exit the position, but the result
 *    should be economically quarantined until a valid exit valuation
 *    exists."
 *
 * DESIGN
 * ──────
 * When any authority detects a mark is untrustworthy (liquidity
 * halved, provider-degraded, sentinel/template tuple etc.) it
 * `mark(mint, reason)` this latch. All downstream TP / partial-close
 * / reward computation MUST call `isInvalid(mint)` first and bail
 * out on `true`.
 *
 * Emergency exits (STRICT_SL / rug net / zombie catastrophe) still
 * fire — but their economic outcome is routed to
 * `HistoricalEconomicQuarantine6496` so learners never see the
 * contaminated PnL.
 *
 * `clear(mint)` is called when a fresh valid mark arrives. TTL is
 * a safety net so a stale latch cannot indefinitely block exits
 * after the pool has genuinely recovered.
 */
object MarkAuthorityInvalidLatch6499 {

    data class Latch(
        val reason: String,
        val markedAtMs: Long,
    )

    private val latches = ConcurrentHashMap<String, Latch>()
    private val marks = AtomicLong(0L)
    private val checks = AtomicLong(0L)
    private val hits = AtomicLong(0L)

    // Latches auto-expire so we do not permanently freeze exits on a
    // pool that later recovers.
    private const val LATCH_TTL_MS = 5 * 60_000L

    /** Called by upstream mark-validity detectors. */
    fun mark(mint: String, reason: String) {
        if (mint.isBlank()) return
        latches[mint] = Latch(reason, System.currentTimeMillis())
        marks.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "MARK_AUTHORITY_INVALID_LATCHED_6499",
                "mint=${mint.take(10)} reason=$reason",
            )
            PipelineHealthCollector.labelInc("MARK_AUTHORITY_INVALID_LATCHED_6499")
        } catch (_: Throwable) {}
    }

    /**
     * Called by TP / partial-close / reward computation sites.
     * Returns true iff the mint's mark is currently latched invalid.
     * TP / profit-labelled partial / learning reward MUST bail on
     * `true`. Emergency exits may still fire but their outcome MUST
     * route through `HistoricalEconomicQuarantine6496`.
     */
    fun isInvalid(mint: String): Boolean {
        if (mint.isBlank()) return false
        checks.incrementAndGet()
        val l = latches[mint] ?: return false
        if (System.currentTimeMillis() - l.markedAtMs > LATCH_TTL_MS) {
            latches.remove(mint, l)
            return false
        }
        hits.incrementAndGet()
        return true
    }

    fun reasonOf(mint: String): String? = latches[mint]?.reason

    fun clear(mint: String) {
        if (mint.isBlank()) return
        latches.remove(mint)
    }

    fun statusLine(): String =
        "marks=${marks.get()} checks=${checks.get()} hits=${hits.get()} live=${latches.size}"

    internal fun resetForTest() {
        latches.clear()
        marks.set(0L); checks.set(0L); hits.set(0L)
    }
}
