package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7322 §A RUNNER KEEPS A MOONBAG.
 *
 * Live runner-lane positions were closed 100% by whichever give-back lock
 * fired first: the 500ms rapid trail sat ~4 points under the peak (25nV9u
 * MOONSHOT sold at +31.8% from a ~+36% peak), and the rapid profit capture
 * re-sold slices every tick and sold everything at +500%. None of them left
 * a bag to ride, so a 1000% move could never be held in live.
 *
 * One rule, applied at the sell door so it covers every lane's exit path:
 *   - a give-back / take-profit exit on a RUNNER lane whose peak reached
 *     +100% banks [BANK_FRACTION] of the position the first time it fires,
 *     instead of closing it;
 *   - the remaining moonbag then ignores give-back exits while it holds more
 *     than [MOONBAG_KEEP_OF_PEAK] of the peak gain, and exits normally below it.
 * Stops, hard floors, rug/catastrophe, manual and reconciler exits are never
 * touched, and nothing changes for a position that never reached +100%.
 *
 * The rapid profit capture takes one 25% slice per tier crossed (never the
 * whole position), remembered per position so it cannot re-fire every tick.
 */
object MoonbagRunner7322 {
    private const val BANK_MIN_PEAK_PCT = 100.0
    const val BANK_FRACTION = 0.60
    private const val MOONBAG_KEEP_OF_PEAK = 0.50
    private const val CAPTURE_SLICE = 0.25

    enum class Action { PASS, BANK_PARTIAL, HOLD_MOONBAG }

    private val HARD_EXIT_MARKERS = listOf(
        "HARD_FLOOR", "CATASTROPHE", "RUG", "STOP_LOSS", "STRICT_SL", "MANUAL", "EMERGENCY",
        "DRAIN", "LIQUIDATE", "KILL", "WALLET_ZERO", "SHUTDOWN", "HONEYPOT", "CANNOT_SELL",
        "SELL_SIG_CONFIRMED", "MOONBAG_", "RECONCILER", "INVARIANT", "ORPHAN", "COLLAPSED",
        "[PARTIAL", "PARTIAL→FULL",
    )
    private val GIVEBACK_MARKERS = listOf(
        "TRAIL", "PROFIT_LOCK", "PEAK_LOCK", "PEAK_GIVEBACK", "DRAWDOWN_FROM_PEAK", "FLOOR_LOCK",
        "TAKE_PROFIT", "MOMENTUM_FADE", "PROFIT_CAPTURE", "UNIVERSAL_PEAK", "FLUID",
    )

    /** peak recorded when the bag was banked, per position key. */
    private val bankedPeak = ConcurrentHashMap<String, Double>()
    private val captureTierTaken = ConcurrentHashMap<String, Int>()

    private fun isGivebackExit(reason: String): Boolean {
        val r = reason.uppercase()
        if (HARD_EXIT_MARKERS.any { r.contains(it) }) return false
        return GIVEBACK_MARKERS.any { r.contains(it) }
    }

    /** Pure decision. [bankedAtPeak] is the peak stored at banking, or null if not banked. */
    fun decide(lane: String, reason: String, pnlPct: Double, peakPct: Double, bankedAtPeak: Double?): Action {
        if (!RunnerExitProfile7277.isRunnerLane(lane)) return Action.PASS
        if (!isGivebackExit(reason)) return Action.PASS
        if (!pnlPct.isFinite() || pnlPct <= 0.0) return Action.PASS
        if (bankedAtPeak != null) {
            val peak = maxOf(bankedAtPeak, if (peakPct.isFinite()) peakPct else 0.0)
            return if (pnlPct > peak * MOONBAG_KEEP_OF_PEAK) Action.HOLD_MOONBAG else Action.PASS
        }
        return if (peakPct.isFinite() && peakPct >= BANK_MIN_PEAK_PCT) Action.BANK_PARTIAL else Action.PASS
    }

    fun bankedPeakFor(key: String): Double? = bankedPeak[key]

    fun markBanked(key: String, peakPct: Double) {
        if (key.isBlank()) return
        if (bankedPeak.size > 500) bankedPeak.clear()
        bankedPeak[key] = peakPct
    }

    fun notePeak(key: String, peakPct: Double) {
        bankedPeak.computeIfPresent(key) { _, p -> if (peakPct.isFinite() && peakPct > p) peakPct else p }
    }

    /** Pure: capture tiers for a lane (runner lanes wait for +100%). */
    fun captureTiers(lane: String, tpPct: Double): List<Double> =
        if (RunnerExitProfile7277.isRunnerLane(lane)) listOf(100.0, 300.0, 1000.0)
        else listOf(tpPct.coerceAtLeast(1.0), 100.0, 300.0).distinct().sorted()

    /** Pure: index of the highest tier [pnlPct] has crossed, or -1. */
    fun tierIndex(tiers: List<Double>, pnlPct: Double): Int = tiers.indexOfLast { pnlPct >= it }

    /**
     * The slice the rapid capture should sell now, or null when this tier was
     * already taken (or none crossed). Remembers the tier per position.
     */
    fun nextCaptureFraction(key: String, lane: String, pnlPct: Double, tpPct: Double): Double? {
        val idx = tierIndex(captureTiers(lane, tpPct), pnlPct)
        if (idx < 0) return null
        val taken = captureTierTaken[key] ?: -1
        if (idx <= taken) return null
        if (captureTierTaken.size > 500) captureTierTaken.clear()
        captureTierTaken[key] = idx
        return CAPTURE_SLICE
    }
}
