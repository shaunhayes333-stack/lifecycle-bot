package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.8419 — ONE AUTHORITY FOR THE RUNNER TRAIL.
 *
 * Owner rule (DIRECTIVE_LEDGER #34, V5.0.8370): a runner trails 35 % under its PEAK PRICE — it exits at 65 % of the
 * peak price, (0.65 x (1 + peak) - 1) in gross terms (GreenFloor8363.trail8370), with the owner's 8337 fix-block 3a rule
 * beneath it (GreenFloor8363.floor8370 = max(3a rule, trail)). Peak +100 % -> trail +30 %, floor +40 %; peak +300 % ->
 * +160 %; peak +500 % -> +290 %.
 *
 * Reviewers' tape: "runner trail tightened from 35 to 22.75" — 22.75 = 35 x 0.65. HoldingLogicLayer.evaluatePosition
 * read LaneHolds8418's 35-POINT runner trail and multiplied it by the SSI exit patience, floored at 0.65
 * (`trailFor8418(...) * ssiExitPatience6091`, HoldingLogicLayer :214), then subtracted the POINTS from the peak GAIN
 * (:363): a +100 % peak was "Trailing stop" EXIT_NOW at +77.25 %. Even at patience 1.0 it trailed 35 points (+65 %),
 * not 35 % of the peak price (+30 %). The DIAMOND_HANDS give-back (:379-383, `35.0 * patience`) stacked the same 0.65,
 * PeakDrawdownLock's 7282 ratchet (peak +500 % -> lock +418 %) and FluidLearningAI's tick lock (the same ratchet above
 * +100 %) sat inside the trail on runner lanes.
 *
 * Every one of those layers now reads this object for a runner lane and none multiplies or re-expresses it: the hold
 * layer's runner trail IS GreenFloor8363's floor; PeakDrawdownLock.shouldLock and the fluid tick lock never fire inside
 * it on a runner lane. Hard floors (catastrophe, -35 % runner hard stop, rug / sellability) and the lane stops are not
 * touched here; non-runner desks keep their table trails. Pure except the two `For8419` readers.
 */
object RunnerTrail8419 {
    /** The price kept at the trail: 65 % of the peak price (GreenFloor8363.TRAIL_KEEP_PRICE_FRACTION_8370). */
    private const val KEEP_PRICE_FRACTION_8419: Double = GreenFloor8363.TRAIL_KEEP_PRICE_FRACTION_8370
    /** The give-back the owner named: 35 % of the peak PRICE. */
    private const val GIVE_BACK_PRICE_PCT_8419: Double = 35.0
    const val SOURCE_TRAIL_8419 = "RUNNER_TRAIL_35PCT_OF_PEAK_PRICE_8370"
    const val SOURCE_RULE_3A_8419 = "RULE_8337_3A"

    /** Pure: is a position managed as [mode] and owned by [owner] on a runner lane? */
    fun runnerLane8419(mode: String?, owner: String?): Boolean =
        RunnerExitProfile7277.isRunnerLane(mode) || RunnerExitProfile7277.isRunnerLane(owner)

    /** Pure: the trail level (gross %) at [peakPct]: 65 % of the peak price. NaN for no peak. */
    fun trailLevelPct8419(peakPct: Double): Double =
        if (peakPct.isFinite() && peakPct > 0.0) GreenFloor8363.trail8370(peakPct) else Double.NaN

    /** Pure: the points under the peak GAIN the trail allows: peak - trail = 35 + 0.35 x peak (70 at +100 %, never 22.75). */
    fun pointsUnderPeak8419(peakPct: Double): Double {
        val t = trailLevelPct8419(peakPct)
        return if (t.isFinite()) peakPct - t else Double.NaN
    }

    /** Pure: the effective runner exit level: max(owner 8337 3a rule, the trail) — GreenFloor8363.floor8370; NaN until armed. */
    fun exitLevelPct8419(peakPct: Double, costPct: Double): Double = GreenFloor8363.floor8370(peakPct, costPct)

    /** Pure: has gross [grossPct] fallen to the runner exit level? (GreenFloor8363.breached8363 — the one rule.) */
    fun breached8419(peakPct: Double, grossPct: Double, costPct: Double = Double.NaN): Boolean =
        GreenFloor8363.breached8363(grossPct, peakPct, costPct)

    /** The hold layer's runner verdict: the level (gross %), its points under the peak and which rule bound. */
    data class Level8419(val levelPct: Double, val pointsUnderPeak: Double, val source: String)

    /** Pure: the hold layer's exit level for a runner-lane position at [peakPct] / [costPct], or null while not armed. */
    fun holdLayerLevel8419(peakPct: Double, costPct: Double): Level8419? {
        val floor = exitLevelPct8419(peakPct, costPct)
        if (!floor.isFinite()) return null
        val trail = trailLevelPct8419(peakPct)
        val src = if (trail.isFinite() && floor <= trail + 1e-9) SOURCE_TRAIL_8419 else SOURCE_RULE_3A_8419
        return Level8419(floor, peakPct - floor, src)
    }

    /**
     * Pure: HoldingLogicLayer's trail in POINTS under the peak gain. A non-runner desk keeps its table trail x the SSI
     * patience; a runner lane gets the trail's own points at its peak (35 + 0.35 x peak), never the table x patience
     * (35 x 0.65 = 22.75). With no peak yet a runner reads the 35-point table floor (nothing is armed anyway).
     */
    fun holdLayerTrailPoints8419(mode: String, owner: String, tablePct: Double, patience: Double, peakPct: Double): Double {
        if (!runnerLane8419(mode, owner)) return LaneHolds8418.trailFor8418(mode, owner, tablePct) * patience
        val pts = pointsUnderPeak8419(peakPct)
        return if (pts.isFinite()) pts else GIVE_BACK_PRICE_PCT_8419
    }

    /** Pure: PeakDrawdownLock.shouldLock on a runner lane — the give-back lock fires at the runner exit level, never inside it. */
    fun lockFires8419(peakPct: Double, currentPct: Double): Boolean = breached8419(peakPct, currentPct)

    /**
     * Pure: FluidLearningAI's positive tick-lock level [levelPct] for a position on [lane] at [peakPct]: on a runner lane it
     * never sits above the runner exit level (the lock is min(level, floor8370)); a non-runner lane keeps its level.
     */
    fun tickLockLevel8419(lane: String?, peakPct: Double, levelPct: Double): Double {
        if (!RunnerExitProfile7277.isRunnerLane(lane)) return levelPct
        val floor = exitLevelPct8419(peakPct, Double.NaN)
        if (!floor.isFinite()) return levelPct
        val out = if (levelPct.isFinite()) minOf(levelPct, floor) else floor
        if (out < levelPct) try { PipelineHealthCollector.labelInc("RUNNER_TICK_LOCK_HELD_TO_TRAIL_8419") } catch (_: Throwable) {}
        return out
    }

    // ── live readers ──

    /** HoldingLogicLayer: the runner exit level of [ts] (its confirmed peak, its priced round trip), or null (not a runner lane / not armed). */
    fun holdLayerLevelFor8419(ts: TokenState, mode: String, owner: String): Level8419? {
        if (!runnerLane8419(mode, owner)) return null
        val peak = ts.position.peakGainPct
        if (!peak.isFinite() || peak <= 0.0) return null
        val floor = try { GreenFloor8363.floorFor8363(ts) } catch (_: Throwable) { Double.NaN }
        if (!floor.isFinite()) return null
        val trail = trailLevelPct8419(peak)
        val src = if (trail.isFinite() && floor <= trail + 1e-9) SOURCE_TRAIL_8419 else SOURCE_RULE_3A_8419
        return Level8419(floor, peak - floor, src)
    }

    fun statusLine8419(): String =
        "runnerTrail8419[keep=${(KEEP_PRICE_FRACTION_8419 * 100).toInt()}%ofPeakPrice trail(+100)=${trailLevelPct8419(100.0).toInt()} " +
            "floor(+100)=${exitLevelPct8419(100.0, Double.NaN).toInt()} floor(+500)=${exitLevelPct8419(500.0, Double.NaN).toInt()} owners=hold,peakLock,tickLock,greenFloor]"
}
