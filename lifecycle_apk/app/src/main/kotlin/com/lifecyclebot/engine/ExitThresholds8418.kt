package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 — ONE BINDING VALUE PER EXIT THRESHOLD, AND WHO SET IT.
 *
 * Every exit threshold below has a GLOBAL value (BotConfig / AutoModeEngine.ModeConfig: stopLossPct 10,
 * trailingStopBasePct 8, maxHoldMinsHard 120, minHoldMins 3) and a LANE value (FieldManual7715 mandate,
 * StopAuthority7887 lane base x LaneExitTuner / StopLearner8040, ExitProfile7955 winner dips, ExitDoctrine8169
 * floors, RunnerExitProfile7277 arm peaks, LifecycleHold8065 floor). The documented precedence (7887: "the generic
 * stops ... fired on the global config and cut the same runners regardless"; 8169: "never inside the lane's Field
 * Manual invalidation"; 8065: "catastrophe ticks above the floor are HELD") is, per threshold:
 *
 *   ORDINARY STOP   plan stop > lifecycle floor > lane stop (learned 8040, else base x tuner) floored by the lane
 *                   doctrine (no plan) and the runner floor (runner lanes) > winner-dip 7962 (re-floored the same way)
 *                   > noise + cost floor. The global / mode stopLossPct binds ONLY when the lane stop is unknown.
 *   CATASTROPHE     -25% (entry x 0.75), never above a lifecycle hold's own floor (ExitDoctrine8169.catastropheMag8169).
 *   CYCLIC          the scalp contract of [CyclicContract8418] (lane stop, scalp take, the label's 60-minute read).
 *
 * Each resolver returns a [Binding8418] (value + source) and counts which source bound, per door, so the report
 * shows where a global value still decides an exit.
 */
object ExitThresholds8418 {
    /** A threshold's binding value (positive magnitude in %) and the authority that set it. */
    data class Binding8418(val value: Double, val source: String)

    private const val CATASTROPHE_BASE_PCT_8418 = 25.0

    private val bound = ConcurrentHashMap<String, AtomicLong>()

    private fun count(door: String, source: String) {
        val k = "$door:$source"
        if (bound.size < 128 || bound.containsKey(k)) bound.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
    }

    // ── pure ──

    /**
     * Pure: the ordinary stop a door applies — the lane's stop [laneMag] (positive; NaN / <= 0 = unknown) owns it; the
     * global / mode stop [globalPct] (sign-insensitive) binds only when the lane stop is unknown.
     */
    fun ordinaryStopOf8418(laneMag: Double, laneSource: String, globalPct: Double): Binding8418 {
        if (laneMag.isFinite() && laneMag > 0.0) return Binding8418(laneMag, "LANE_" + laneSource.ifBlank { "STOP" })
        val g = kotlin.math.abs(globalPct)
        return if (g.isFinite() && g > 0.0) Binding8418(g, "GLOBAL") else Binding8418(Double.NaN, "UNKNOWN")
    }

    /** Pure: the protective catastrophe magnitude — [CATASTROPHE_BASE_PCT_8418], never above a held lifecycle floor. */
    fun catastropheOf8418(lifecycleFloorMag: Double): Binding8418 {
        val mag = ExitDoctrine8169.catastropheMag8169(-CATASTROPHE_BASE_PCT_8418, Double.NaN, lifecycleFloorMag)
        return Binding8418(mag, if (mag > CATASTROPHE_BASE_PCT_8418 + 1e-9) "LIFECYCLE_FLOOR" else "BASE")
    }

    // ── live resolvers ──

    /** The ordinary stop (positive) [door] applies to [ts]; [globalPct] is the door's global / mode value. */
    fun ordinaryStop8418(ts: TokenState, globalPct: Double, door: String): Binding8418 {
        val lane = try { com.lifecyclebot.engine.cortex.StopAuthority7887.stopFor8418(ts) } catch (_: Throwable) { null }
        val b = ordinaryStopOf8418(lane?.value ?: Double.NaN, lane?.source.orEmpty(), globalPct)
        count(door, b.source.substringBefore('_'))
        return b
    }

    /** [ordinaryStop8418] as a magnitude, the global value when even that is unknown. */
    fun ordinaryStopMag8418(ts: TokenState, globalPct: Double, door: String): Double {
        val v = ordinaryStop8418(ts, globalPct, door).value
        return if (v.isFinite()) v else kotlin.math.abs(globalPct)
    }

    /** The protective catastrophe magnitude for [ts] (Executor.protectiveExitThresholds6882 / the risk clock). */
    fun catastropheMag8418(ts: TokenState): Double {
        val held = try { LifecycleHold8065.holding8065(ts) } catch (_: Throwable) { false }
        val b = catastropheOf8418(if (held) LifecycleHold8065.FLOOR_PCT else Double.NaN)
        count("CATASTROPHE", b.source)
        return b.value
    }

    fun statusLine8418(): String =
        "exitThresholds8418[" + bound.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "] rule=stop:plan>lifecycle>lane(doctrine,runnerFloor)>winnerDip>noise; global only when lane unknown; " +
            "catastrophe>=${CATASTROPHE_BASE_PCT_8418.toInt()}%,lifecycleFloor " + CyclicContract8418.statusLine8418()
}

/**
 * V5.0.8418 — CYCLIC's three contracts, made to agree. CYCLIC is a SCALP lane: it is not a lifecycle lane
 * (LifecycleHold8065), not a runner lane (RunnerExitProfile7277), not a runner-from-entry lane
 * (TradeExitContract8077), and its forward labels are graded on the scalp family: the lane stop
 * (StopAuthority7887.laneStopMag8029 "CYCLIC"), the spike ladder (SpikeCapture7943.TIERS) and the 60-minute read.
 *
 * Live, CyclicTradeEngine traded something else: its stop / take came from the V3 mode the token happened to be
 * routed as (MOONSHOT 120/12, SHITCOIN 35/9, STANDARD 65/8, loss streak down to 3-6), its hold ran 90 minutes and up
 * to 270 with the runner bypass, and its hard floor was a flat -15% — while the generic exits held the same position on
 * StopAuthority7887's CYCLIC stop. The records it learned from were therefore produced by a different holding contract
 * than the labels that graded its decisions, which is how CYCLIC's measured ordering read INVERTED.
 *
 * Now: the stop is the position's StopAuthority7887 stop (the same authority the labels and every generic exit use),
 * the runner activation is the first spike rung, the hold ends at the label's final read, and the hard floor is the
 * one ExitDoctrine8169 gives every other position.
 */
object CyclicContract8418 {
    /** The label's final read (ForwardReturnLabeler7731 H240 = 60 minutes) and HoldingLogicLayer's CYCLIC max hold. */
    const val HOLD_MS_8418 = 60L * 60_000L
    private const val LANE_8418 = "CYCLIC"

    data class Contract8418(val stopPct: Double, val takePct: Double, val holdMs: Long, val stopSource: String)

    private val stopsFromLane = AtomicLong(0)
    private val stopsFallback = AtomicLong(0)

    /** Pure: the scalp take — the first rung of the ladder CYCLIC's labels are credited on. */
    fun takePct8418(): Double = SpikeCapture7943.TIERS.first().first

    /** Pure: the contract with lane stop [laneStopMag] (NaN = unknown, then [fallbackStopPct]). */
    fun contractOf8418(laneStopMag: Double, fallbackStopPct: Double): Contract8418 {
        val lane = laneStopMag.isFinite() && laneStopMag > 0.0
        return Contract8418(if (lane) laneStopMag else kotlin.math.abs(fallbackStopPct), takePct8418(), HOLD_MS_8418,
            if (lane) "LANE" else "FALLBACK")
    }

    /** Pure: has a CYCLIC position held [holdMs] reached its contract's end? No runner extension on a scalp lane. */
    fun holdEnded8418(holdMs: Long): Boolean = holdMs >= HOLD_MS_8418

    /** The contract for an open CYCLIC position [ts] (its StopAuthority7887 stop), else the lane's label stop. */
    fun contractFor8418(ts: TokenState?, fallbackStopPct: Double): Contract8418 {
        val mag = try {
            if (ts != null && ts.position.isOpen) com.lifecyclebot.engine.cortex.StopAuthority7887.stopMagFor(ts)
            else com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029(LANE_8418)
        } catch (_: Throwable) { Double.NaN }
        val c = contractOf8418(mag, fallbackStopPct)
        (if (c.stopSource == "LANE") stopsFromLane else stopsFallback).incrementAndGet()
        return c
    }

    /** CyclicTradeEngine's hard floor (positive): the one ExitDoctrine8169 gives every position, else 15%. */
    fun hardFloorMag8418(ts: TokenState): Double =
        try { ExitDoctrine8169.rapidHardFloorMag8169(ts) } catch (_: Throwable) { 15.0 }

    fun statusLine8418(): String =
        "cyclic8418[scalp stop=StopAuthority7887 take=+${takePct8418().toInt()}% hold=${HOLD_MS_8418 / 60_000L}m " +
            "stops(lane/fallback)=${stopsFromLane.get()}/${stopsFallback.get()}]"
}
