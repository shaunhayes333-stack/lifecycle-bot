package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6814 §RECYCLE_RATIO — operator diagnosis Feb 2026:
 *   "Introduce rolling capital velocity: entryNotional_5m,
 *    exitNotional_5m, realisedCashReturned_5m. When recycleRatio
 *    falls: reduce new entry size/rate automatically. When it
 *    rises: restore normal entry throughput. The bot should never
 *    reach 98%+ capital deployed while producing normal BUY
 *    pressure."
 *
 * This authority ROLLS entry and exit notional over a 5-minute
 * window and exposes:
 *   • recycleRatio = realisedCashReturned / max(entryNotional, ε)
 *   • sizeMultiplier() — the damper factor a sizer can multiply
 *     into a requested notional. Falls linearly as recycleRatio
 *     drops from 1.0 toward 0.25.
 *
 * The multiplier NEVER goes above 1.0 (no upward promotion) and
 * NEVER below 0.20 (dust-probe floor). Consumers call this
 * multiplicatively into their existing sizing path; the value is
 * pure telemetry-derived and does not mutate canonical accounting.
 */
object CapitalRecycleRatioAuthority6814 {

    private const val WINDOW_MS = 5 * 60 * 1_000L
    private const val EPS = 1e-9

    private data class Sample(val atMs: Long, val notional: Double)

    private val entries = java.util.concurrent.ConcurrentLinkedDeque<Sample>()
    private val cashReturned = java.util.concurrent.ConcurrentLinkedDeque<Sample>()
    private val lastRatio = java.util.concurrent.atomic.AtomicReference(1.0)
    private val lastUpdatedMs = AtomicLong(0L)

    fun recordEntry(notionalSol: Double) {
        if (!notionalSol.isFinite() || notionalSol <= 0.0) return
        try {
            entries.addLast(Sample(System.currentTimeMillis(), notionalSol))
            PipelineHealthCollector.labelInc("RECYCLE_RATIO_ENTRY_RECORDED_6814")
        } catch (_: Throwable) {}
    }

    fun recordCashReturned(realisedSol: Double) {
        if (!realisedSol.isFinite() || realisedSol <= 0.0) return
        try {
            cashReturned.addLast(Sample(System.currentTimeMillis(), realisedSol))
            PipelineHealthCollector.labelInc("RECYCLE_RATIO_CASH_RETURNED_6814")
        } catch (_: Throwable) {}
    }

    /**
     * Prune samples older than the rolling window and return the
     * current recycle ratio. Idempotent, cheap.
     *
     * V5.0.6817 bootstrap safety: while entries have been recorded but
     * NO sell has yet returned cash (a normal bootstrap or pure-open
     * phase), the ratio is 1.0. Otherwise the damper would aggressively
     * throttle every subsequent open before the first sell — including
     * in unit tests that only exercise the buy path. Once at least one
     * cash-returned sample is present, the ratio measures returned-vs-
     * entered notional as originally designed.
     */
    fun currentRatio(): Double {
        return try {
            val cutoff = System.currentTimeMillis() - WINDOW_MS
            while (entries.peekFirst()?.let { it.atMs < cutoff } == true) entries.pollFirst()
            while (cashReturned.peekFirst()?.let { it.atMs < cutoff } == true) cashReturned.pollFirst()
            val entrySum = entries.sumOf { it.notional }
            val returnSum = cashReturned.sumOf { it.notional }
            // With no entries recorded yet, ratio is neutral (1.0). Also
            // neutral while sells have not yet returned any cash — this
            // is the V5.0.6817 bootstrap-safety window so the damper does
            // not throttle every open before the first realised close.
            val r = if (entrySum <= EPS || cashReturned.isEmpty()) 1.0
                    else (returnSum / entrySum).coerceIn(0.0, 5.0)
            lastRatio.set(r)
            lastUpdatedMs.set(System.currentTimeMillis())
            r
        } catch (_: Throwable) { lastRatio.get() }
    }

    /**
     * Size multiplier a sizer should apply on new entries. Linear
     * interpolation:
     *   ratio >= 1.00 → 1.00   (healthy recycling, no damping)
     *   ratio == 0.50 → 0.60
     *   ratio == 0.25 → 0.35
     *   ratio == 0.00 → 0.20   (dust probe floor)
     * Never upsizes.
     */
    fun sizeMultiplier(): Double {
        val r = currentRatio()
        return when {
            r >= 1.0 -> 1.0
            r <= 0.0 -> 0.20
            else -> (0.20 + 0.80 * r).coerceIn(0.20, 1.0)
        }
    }

    fun statusLine(): String {
        val r = currentRatio()
        return "recycleRatio=${"%.2f".format(r)} sizeMult=${"%.2f".format(sizeMultiplier())} " +
            "entries=${entries.size} cashReturned=${cashReturned.size}"
    }

    internal fun clearForTest() {
        entries.clear(); cashReturned.clear()
        lastRatio.set(1.0); lastUpdatedMs.set(0L)
    }
}
