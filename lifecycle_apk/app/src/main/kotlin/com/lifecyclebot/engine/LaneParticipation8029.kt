package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8029 — every lane learns its own coins, graded on its own play.
 *
 * Owner: "do we consider the specialist roles when we grade the token against that lane's targets, abilities or
 * strategies? are we seeing the right tokens?" Three misses, all measured on 5.0.8027:
 *
 *  1. PARTICIPATION. One lane is elected owner of a coin; only it reaches the FDG and only it is labelled. A
 *     specialist that qualified the coin (its own native scorer said READY) learned nothing from it, so its cells
 *     stayed THIN for ever and watch-first refused it for ever: QUALITY 555 candidates / 0 selected, BLUECHIP
 *     587 / 0, TREASURY 318 / 0. Now a READY lane that is not elected gets a SHADOW label in its own cell
 *     ([shadow8029]) — paper learning only.
 *  2. GRADING. Every label was the coin's price move (5-minute return, spike ladder credited on the peak) whatever
 *     the lane: a CASHGEN scalp with a 6% stop and a MOONSHOT runner with a 15% floor were graded on the same
 *     number, and neither on its stop. MOONSHOT STRONG read +17% on paper while the live trades were stopped out
 *     on the dip before the run. A label is now what THIS lane's play banks on the observed path
 *     ([playGross8029]): the stop first if the coin fell through it before its peak; else the spike ladder on the
 *     peak with the remainder at the horizon — or at the stop, if it fell through it after the peak.
 *  3. INTAKE. The hot watchlist (220) was ~95% pump.fun: 476 evictions in 23 minutes pushed the market hunters'
 *     established coins out. Over [PUMP_MAX_SHARE] of the watchlist, an incoming coin evicts a pump.fun coin
 *     ([pumpFamily8029], GlobalTradeRegistry).
 */
object LaneParticipation8029 {
    const val PUMP_MAX_SHARE = 0.5
    const val SHADOW_REASON = "SHADOW_NOT_PRIMARY_8029"
    private val shadows = AtomicLong(0)
    private val stoppedFirst = AtomicLong(0)
    private val stoppedAfterPeak = AtomicLong(0)
    private val graded = AtomicLong(0)
    private val pumpEvictions = AtomicLong(0)

    // ── pure ──

    /**
     * The gross % this lane's play banks on a path: [stopMag] (positive, e.g. 8.0), the observed [peakPct], the
     * lowest point before that peak [dipBeforePeakPct] (NaN = no new high, all of [minPct] came first), the lowest
     * point so far [minPct], the mark now [grossNow]; [ladder] is the spike ladder (peak, remainder) -> gross.
     */
    fun playGross8029(stopMag: Double, peakPct: Double, dipBeforePeakPct: Double, minPct: Double, grossNow: Double,
                      ladder: (Double, Double) -> Double): Double {
        if (!(stopMag > 0.0) || !grossNow.isFinite()) return ladder(peakPct, grossNow)
        val dipFirst = if (dipBeforePeakPct.isFinite()) dipBeforePeakPct else minPct
        if (dipFirst.isFinite() && dipFirst <= -stopMag) return -stopMag
        val rest = if (minPct.isFinite() && minPct <= -stopMag) -stopMag else grossNow
        return ladder(peakPct, rest)
    }

    /** Is [source] a pump.fun-family intake (launch feed, portal, livestream)? */
    fun pumpFamily8029(source: String): Boolean = source.uppercase().contains("PUMP")

    // ── wiring ──

    /** SpecialistOwnership7951: [lane] held a READY proposal for [mint] but another lane owns it. */
    fun shadow8029(mint: String, lane: String) {
        try {
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(mint, lane, false, SHADOW_REASON)
            shadows.incrementAndGet()
        } catch (_: Throwable) {}
    }

    /** ForwardReturnLabeler7731: one label graded on its lane's play. */
    fun noteGraded8029(first: Boolean, afterPeak: Boolean) {
        graded.incrementAndGet()
        if (first) stoppedFirst.incrementAndGet() else if (afterPeak) stoppedAfterPeak.incrementAndGet()
    }

    /** GlobalTradeRegistry: an over-share eviction of a pump.fun coin. */
    fun notePumpEviction8029() { pumpEvictions.incrementAndGet() }

    fun statusLine(): String =
        "shadowLabels=${shadows.get()} graded=${graded.get()} stoppedBeforePeak=${stoppedFirst.get()} stoppedAfterPeak=${stoppedAfterPeak.get()} " +
            "pumpEvictedOverShare=${pumpEvictions.get()} pumpMaxShare=${(PUMP_MAX_SHARE * 100).toInt()}%"
}
