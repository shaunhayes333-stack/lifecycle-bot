package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8019 — a price 1000x away from the entry is a broken basis, not a market move.
 *
 * 5.0.8018 live: GjVpdj6W was held at an entry of $24.83 on a coin trading at $0.0000032 (7,700,000x);
 * CATASTROPHIC_HARD_BACKSTOP_-25 fired eight seconds in and the sale cost 7% of real money. CgwweYm2
 * ($0.694 vs $0.0000044) and TM ($185.30, a +9,900% "peak" partial) carried the same shape. No
 * Solana coin moves a thousandfold between one mark and the next without a rug, and a rug has its
 * own structural exits (RUG / DEV / LIQUIDITY), which this never holds.
 *
 * A PRICE-DRIVEN exit (stop, backstop, catastrophe, trail, peak capture, profit lock, time-slip)
 * whose mark sits [MAX_RATIO] above or below the position's entry is refused and counted; the
 * position keeps its structural exits and the next same-basis mark. The pure test is
 * [brokenBasis8019]; [priceDriven8019] names the reasons it governs.
 */
object BasisBreak8019 {
    const val MAX_RATIO_8019 = 1_000.0
    private val refused = AtomicLong(0)

    /** Pure: is [markPrice] more than [MAX_RATIO] away from [entryPrice] in either direction? */
    fun brokenBasis8019(entryPrice: Double, markPrice: Double): Boolean {
        if (!entryPrice.isFinite() || !markPrice.isFinite() || entryPrice <= 0.0 || markPrice <= 0.0) return false
        val r = markPrice / entryPrice
        return r >= MAX_RATIO_8019 || r <= 1.0 / MAX_RATIO_8019
    }

    /** Pure: an exit that fires on the mark alone (never a structural, manual or operational exit). */
    fun priceDriven8019(reason: String): Boolean {
        val r = reason.uppercase()
        val structural = listOf("RUG", "DEV_", "DEV SOLD", "LIQUIDITY", "HONEYPOT", "FREEZE", "FROZEN", "DEAD", "MANUAL", "SHUTDOWN",
            "QUARANTINE", "ORPHAN", "RESURRECT", "STARTUP", "STALE_FEED", "EVICT", "DUST", "ZOMBIE", "RECONCIL", "KILL")
        if (structural.any { r.contains(it) }) return false
        val priced = listOf("STOP", "BACKSTOP", "CATASTROPH", "TRAIL", "PEAK", "PROFIT_LOCK", "STRICT_SL", "_SL_", "TP_", "CAPTURE", "SPIKE", "SLIP", "FLOOR")
        return priced.any { r.contains(it) }
    }

    /**
     * Pure: a peak-capture cut inside the first minute, on a "peak" of +100% or more that is already gone
     * (current at or under entry), is a phantom print, not a run that faded: 8018's Earth read +111.8% then
     * -1.3% six seconds after the fill and was cut for the round-trip cost. The position re-evaluates on
     * the next mark; a real spike sells at the print (SpikeCapture7943), which never came.
     */
    fun phantomPeak8019(reason: String, ageMs: Long, peakGainPct: Double, currentGainPct: Double): Boolean =
        reason.uppercase().contains("PEAK") && ageMs in 0L until 60_000L &&
            peakGainPct.isFinite() && peakGainPct >= 100.0 && currentGainPct.isFinite() && currentGainPct <= 0.0

    private val phantomRefused = AtomicLong(0)
    const val RELEASE_MS_8025 = 15L * 60_000L
    private val firstRefusedAt8025 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val releasedCount8025 = AtomicLong(0)

    /** Pure: has a broken-basis hold lasted long enough to let the exit through? */
    fun released8025(heldMs: Long): Boolean = heldMs >= RELEASE_MS_8025

    /** Executor.requestSellCore7948: true refuses this exit (the mark's basis is broken, or the peak was a phantom). */
    fun refuses8019(ts: TokenState, reason: String, markPrice: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition) return false
        if (!priceDriven8019(reason)) return false
        val cur = if (pos.entryPrice > 0.0 && markPrice > 0.0) (markPrice / pos.entryPrice - 1.0) * 100.0 else Double.NaN
        if (phantomPeak8019(reason, nowMs - pos.entryTime, pos.peakGainPct, cur)) {
            phantomRefused.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PHANTOM_PEAK_CUT_REFUSED_8019") } catch (_: Throwable) {}
            return true
        }
        if (!brokenBasis8019(pos.entryPrice, markPrice)) return false
        // V5.0.8025 — the refusal is a hold, not a prison: after [RELEASE_MS_8025] of broken-basis refusals the
        // price-driven exit goes through (the sell is sized from the wallet, so it sells what is really held at
        // what it is really worth). Nothing sits unmanaged for ever.
        val key = "${ts.mint}|${pos.entryTime}"
        val first = firstRefusedAt8025.getOrPut(key) { nowMs }
        if (firstRefusedAt8025.size > 2_000) firstRefusedAt8025.clear()
        if (released8025(nowMs - first)) {
            releasedCount8025.incrementAndGet()
            try { PipelineHealthCollector.labelInc("BASIS_BREAK_RELEASED_8025") } catch (_: Throwable) {}
            return false
        }
        refused.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("BASIS_BREAK_EXIT_REFUSED_8019")
            if (ForensicEmitRateLimiter6356.shouldEmit("BASIS_BREAK_8019", ts.mint)) {
                ForensicLogger.lifecycle(
                    "BASIS_BREAK_EXIT_REFUSED_8019",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} entry=${pos.entryPrice} mark=$markPrice ratio=${"%.3g".format(markPrice / pos.entryPrice)} src=${ts.lastPriceSource} reason=${reason.take(50)} action=refuse_price_driven_exit",
                )
            }
        } catch (_: Throwable) {}
        return true
    }

    fun statusLine(): String = "exitsRefused=${refused.get()} phantomPeaks=${phantomRefused.get()} released8025=${releasedCount8025.get()} bar=mark ${MAX_RATIO_8019.toInt()}x from entry"
}
