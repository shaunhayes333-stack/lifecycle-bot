package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7887 — Cortex Phase 5: one ORDINARY stop distance per position, built
 * from the components that already own it (it sits beside them, it does not
 * replace them):
 *
 *   1. the position's plan (TradePlan7739): a planned trade's structure stop
 *      is the trader's own invalidation, so it owns the stop (never under 4%);
 *   2. otherwise the lane's educated base: the specialist lanes' own stop
 *      bands (ShitCoin/Quality/BlueChip/DipHunter/... trader constants), and
 *      HoldingLogicLayer's per-style band for anything else;
 *   3. x LaneExitTuner.getSlMult(lane): the closed-loop learned multiplier,
 *      which in LIVE already includes ExitRegret7752 (whether stops on this
 *      lane cut runners: 5.0.7876 HARD_STOP realised -5.2%, price +24.9% after);
 *   4. runner lanes never tighter than their 15% floor (7330/7366 doctrine);
 *      clamped to [4%, 25%] so it stays shallower than the catastrophe line.
 *
 * Before this, that learning only reached the lane sub-trader AIs; the generic
 * stops (risk clock, STRICT_SL fallback, tick floor, rapid catastrophe) fired on
 * the global config (about 5-10%) and cut the same runners regardless.
 * Catastrophe and hard-floor backstops stay independent and deeper.
 */
object StopAuthority7887 {
    private const val MIN_STOP_PCT = 4.0
    private const val MAX_STOP_PCT = 25.0
    private const val RUNNER_FLOOR_PCT = 15.0

    /** Mature stop bands of the specialist lanes' own traders (percent, positive). */
    private val LANE_BASE: Map<String, Double> = mapOf(
        "SHITCOIN" to 8.0, "MANIPULATED" to 8.0, "EXPRESS" to 10.0,
        "QUALITY" to 12.0, "BLUECHIP" to 10.0, "BLUE_CHIP" to 10.0,
        "DIP_HUNTER" to 15.0, "TREASURY" to 6.0, "CASHGEN" to 6.0,
        "CYCLIC" to 12.0, "CORE" to 12.0,
    )

    private val sources = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the stop magnitude from its parts. */
    fun compose(planStopPct: Double?, laneBasePct: Double, learnedMult: Double, runnerLane: Boolean): Double {
        if (planStopPct != null && planStopPct.isFinite() && planStopPct < 0.0) {
            // V5.0.7925 — the runner floor is an invariant for plan stops too.
            val planMag = kotlin.math.abs(planStopPct).coerceIn(MIN_STOP_PCT, MAX_STOP_PCT)
            return if (runnerLane) planMag.coerceAtLeast(RUNNER_FLOOR_PCT) else planMag
        }
        val mult = if (learnedMult.isFinite() && learnedMult > 0.0) learnedMult else 1.0
        var mag = (laneBasePct * mult).coerceIn(MIN_STOP_PCT, MAX_STOP_PCT)
        if (runnerLane) mag = mag.coerceAtLeast(RUNNER_FLOOR_PCT)
        return mag
    }

    /** The position's ordinary stop as a positive magnitude (e.g. 12.0 = -12%). */
    fun stopMagFor(ts: TokenState): Double {
        val pos = ts.position
        // V5.0.7930 — canonical lane (CortexExit keys on it; raw aliases missed LANE_BASE).
        val lane = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(pos.tradingMode).uppercase() }
            catch (_: Throwable) { "" }.ifBlank { pos.tradingMode.trim().uppercase() }
        val plan = try { com.lifecyclebot.engine.truth.TradePlan7739.planFor(ts.mint, pos.entryTime) } catch (_: Throwable) { null }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val base = LANE_BASE[lane] ?: try {
            kotlin.math.abs(com.lifecyclebot.engine.HoldingLogicLayer.getHoldParams(lane).stopLossPct)
        } catch (_: Throwable) { 15.0 }
        val mult = try { com.lifecyclebot.engine.learning.LaneExitTuner.getSlMult(lane) } catch (_: Throwable) { 1.0 }
        sources.computeIfAbsent(if (plan != null) "PLAN" else if (LANE_BASE.containsKey(lane)) "LANE" else "STYLE") { AtomicLong(0) }.incrementAndGet()
        return compose(plan?.stopPnlPct, base, mult, runner)
    }

    /** The same stop as a signed percent (e.g. -12.0). */
    fun stopPctFor(ts: TokenState): Double = -stopMagFor(ts)

    fun statusLine(): String =
        "sources=[${sources.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "bounds=${MIN_STOP_PCT.toInt()}-${MAX_STOP_PCT.toInt()}% runnerFloor=${RUNNER_FLOOR_PCT.toInt()}%"
}
