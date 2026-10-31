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
    // V5.0.7385 — bank at the same +50% bar where runner give-back locks arm
    // (RunnerExitProfile7277). Between +50% and +100% every such lock sold 100%,
    // leaving no bag to ride.
    private const val BANK_MIN_PEAK_PCT = RunnerExitProfile7277.MIN_PEAK_FOR_GIVEBACK_LOCK_PCT
    const val BANK_FRACTION = 0.60
    const val MOONSHOT_BANK_FRACTION_7791 = 0.35
    fun bankFractionFor7791(lane:String?):Double=if(lane?.uppercase()?.contains("MOONSHOT")==true)MOONSHOT_BANK_FRACTION_7791 else BANK_FRACTION
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
        if (!RunnerExitProfile7277.runnerExits8468(lane, peakPct)) return Action.PASS   // V5.0.8468 — a contract lane only once promoted
        if (!Tier1Deference8141.holdMayAct8141(reason)) return Action.PASS  // V5.0.8141 — tier 1 is never banked or held
        if (!isGivebackExit(reason)) return Action.PASS
        if (!pnlPct.isFinite() || pnlPct <= 0.0) return Action.PASS
        if (bankedAtPeak != null) {
            val peak = maxOf(bankedAtPeak, if (peakPct.isFinite()) peakPct else 0.0)
            // V5.0.7388 — the bag's lock slides with the peak too: 1.5x the sliding
            // lock's give-back (PeakDrawdownLock), never more than the old 50%. A
            // +1408% bag held down to +704% before; it now holds to ~+1060%.
            val giveBack7388 = (1.5 * PeakDrawdownLock.triggerFracForPeak(peak)).coerceAtMost(1.0 - MOONBAG_KEEP_OF_PEAK)
            return if (pnlPct > peak * (1.0 - giveBack7388)) Action.HOLD_MOONBAG else Action.PASS
        }
        return if (peakPct.isFinite() && peakPct >= BANK_MIN_PEAK_PCT) Action.BANK_PARTIAL else Action.PASS
    }

    /**
     * Pure (V5.0.8144): the funding contract over a moonbag decision. A bank that keeps [keepFraction] is allowed only
     * when capital is recovered by confirmed proceeds or the bank sale itself recovers it after costs
     * (RunnerExitContract8136.tailFunded8136); a held bag whose capital never came back is released. Unfunded, the
     * ordinary exit sells everything — the moonbag is never an unfunded bet.
     */
    fun fundedAction8144(action: Action, pnlPct: Double, capitalRecovered: Boolean, sellCostPct: Double, keepFraction: Double): Action = when (action) {
        Action.PASS -> Action.PASS
        Action.BANK_PARTIAL ->
            if (RunnerExitContract8136.tailFunded8136(pnlPct, capitalRecovered, sellCostPct, keepFraction)) Action.BANK_PARTIAL else Action.PASS
        Action.HOLD_MOONBAG -> if (capitalRecovered) Action.HOLD_MOONBAG else Action.PASS
    }

    /**
     * Executor.moonbagGate7322 (V5.0.8144): [decide] under the funding contract for a LIVE position (capital recovered
     * = confirmed canonical + legacy-fallback proceeds, TailBag8034.capitalRecovered8090). Paper keeps [decide].
     */
    fun decideFunded8144(ts: com.lifecyclebot.data.TokenState, lane: String, reason: String, pnlPct: Double, peakPct: Double, bankedAtPeak: Double?): Action {
        val action = decide(lane, reason, pnlPct, peakPct, bankedAtPeak)
        if (action == Action.PASS || ts.position.isPaperPosition) return action
        val recovered = try { TailBag8034.capitalRecovered8090(ts.position.positionId, ts.mint) } catch (_: Throwable) { false }
        val cost = try { RunnerExitLive8136.sellCostPct8136(ts) } catch (_: Throwable) { 3.0 }
        val funded = fundedAction8144(action, pnlPct, recovered, cost, 1.0 - bankFractionFor7791(lane))
        if (funded != action) try { PipelineHealthCollector.labelInc("MOONBAG_UNFUNDED_REFUSED_8144_${action.name}") } catch (_: Throwable) {}
        return funded
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
