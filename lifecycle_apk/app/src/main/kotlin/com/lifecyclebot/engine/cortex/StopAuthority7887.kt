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
    fun stopMagFor(ts: TokenState): Double = stopFor8418(ts).value

    /**
     * V5.0.8418 — the position's ordinary stop and the authority that bound it (ExitThresholds8418): PLAN, LIFECYCLE,
     * LEARNED_8040, LANE, STYLE, WINNER_DIP (each possibly +DOCTRINE / +RUNNER_FLOOR / +NOISE).
     */
    fun stopFor8418(ts: TokenState): com.lifecyclebot.engine.ExitThresholds8418.Binding8418 {
        val pos = ts.position
        // V5.0.8091 — the live lifecycle position and its forward label use the same floor.
        if (com.lifecyclebot.engine.LifecycleHold8065.holding8065(ts)) {
            sources.computeIfAbsent("LIFECYCLE_8091") { AtomicLong() }.incrementAndGet()
            return com.lifecyclebot.engine.ExitThresholds8418.Binding8418(com.lifecyclebot.engine.LifecycleHold8065.FLOOR_PCT, "LIFECYCLE")
        }
        // V5.0.7930 — canonical lane (CortexExit keys on it; raw aliases missed LANE_BASE).
        val lane = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(pos.tradingMode).uppercase() }
            catch (_: Throwable) { "" }.ifBlank { pos.tradingMode.trim().uppercase() }
        val plan = try { com.lifecyclebot.engine.truth.TradePlan7739.planFor(ts.mint, pos.entryTime) } catch (_: Throwable) { null }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        // V5.0.8040 — the stop this lane's own graded paths chose replaces the constant (and its multiplier).
        val learned8040 = try { StopLearner8040.learnedStop8040(lane) } catch (_: Throwable) { null }
        val base = learned8040 ?: LANE_BASE[lane] ?: try {
            kotlin.math.abs(com.lifecyclebot.engine.HoldingLogicLayer.getHoldParams(lane).stopLossPct)
        } catch (_: Throwable) { 15.0 }
        val mult = if (learned8040 != null) 1.0 else try { com.lifecyclebot.engine.learning.LaneExitTuner.getSlMult(lane) } catch (_: Throwable) { 1.0 }
        sources.computeIfAbsent(if (plan != null) "PLAN" else if (learned8040 != null) "LEARNED_8040" else if (LANE_BASE.containsKey(lane)) "LANE" else "STYLE") { AtomicLong(0) }.incrementAndGet()
        val composed0 = compose(plan?.stopPnlPct, base, mult, runner)
        // V5.0.8169 — no plan: the lane base (learned or constant) never sits inside the lane's Field Manual
        // invalidation (5.0.8168: BLUECHIP / QUALITY learned 5% against a 10% doctrine).
        val composed = if (plan == null) com.lifecyclebot.engine.ExitDoctrine8169.doctrineFloored8169(lane, composed0) else composed0
        // V5.0.7962 — the key's own winners decide how much room they need (ExitProfile7955).
        val learned = try {
            com.lifecyclebot.engine.ExitProfile7955.learnedStopMag7962(com.lifecyclebot.engine.ExitProfile7955.keyProfileFor7962(ts), composed)
        } catch (_: Throwable) { null }
        if (learned != null) sources.computeIfAbsent("WINNER_DIP_7962") { AtomicLong(0) }.incrementAndGet()
        // V5.0.8418 — the winner-dip stop (bounded 4..25) replaced the floored stop AFTER the floors ran, so it silently
        // took a runner lane under its 15% invariant and a no-plan lane inside its Field Manual invalidation.
        val chosen = if (learned != null) winnerDipFloored8418(learned, lane, plan == null, runner) else composed
        val base8418 = if (plan != null) "PLAN" else if (learned8040 != null) "LEARNED_8040" else if (LANE_BASE.containsKey(lane)) "LANE" else "STYLE"
        val src8418 = (if (learned != null) "WINNER_DIP" else base8418) + (if (chosen > (learned ?: composed0) + 1e-9) "+FLOOR" else "")
        // V5.0.8169 — and never inside the position's round trip + 2x its measured 1-minute range (ordinary noise).
        val out = try { com.lifecyclebot.engine.ExitDoctrine8169.noiseCostFloored8169(ts, lane, chosen) } catch (_: Throwable) { chosen }
        return com.lifecyclebot.engine.ExitThresholds8418.Binding8418(out, if (out > chosen + 1e-9) "$src8418+NOISE" else src8418)
    }

    /** V5.0.8418 — pure: a winner-dip stop [learnedMag] kept on the runner floor and (no plan) the lane doctrine. */
    fun winnerDipFloored8418(learnedMag: Double, lane: String, noPlan: Boolean, runnerLane: Boolean): Double {
        var m = learnedMag
        if (runnerLane) m = m.coerceAtLeast(RUNNER_FLOOR_PCT)
        if (noPlan) m = com.lifecyclebot.engine.ExitDoctrine8169.doctrineFloored8169(lane, m)
        return m
    }

    /**
     * V5.0.8029 — a lane's ordinary stop with no position (forward-label grading): the lane's base band x its
     * learned multiplier, runner floor applied. Positive magnitude.
     */
    fun laneStopMag8029(lane: String, longHold8077: Boolean? = null): Double {
        val l = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase() } catch (_: Throwable) { "" }
            .ifBlank { lane.trim().uppercase() }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l) } catch (_: Throwable) { false }
        // Not a trading lane (a plan / audit key labelled through the same book): no play to grade on.
        if (!LANE_BASE.containsKey(l) && !runner && l != "MOONSHOT") return Double.NaN
        // V5.0.8065 — a lifecycle lane is graded on the lifecycle hold's floor, not a scalp stop.
        if (longHold8077 ?: com.lifecyclebot.engine.LifecycleHold8065.lifecycleLane8065(l)) return com.lifecyclebot.engine.LifecycleHold8065.FLOOR_PCT
        val learned8040 = try { StopLearner8040.learnedStop8040(l) } catch (_: Throwable) { null }
        val base = learned8040 ?: LANE_BASE[l] ?: try { kotlin.math.abs(com.lifecyclebot.engine.HoldingLogicLayer.getHoldParams(l).stopLossPct) } catch (_: Throwable) { 15.0 }
        val mult = if (learned8040 != null) 1.0 else try { com.lifecyclebot.engine.learning.LaneExitTuner.getSlMult(l) } catch (_: Throwable) { 1.0 }
        // V5.0.8169 — labels are graded on the same doctrine-floored stop live positions take.
        return com.lifecyclebot.engine.ExitDoctrine8169.doctrineFloored8169(l, compose(null, base, mult, runner || l == "MOONSHOT"))
    }

    /** The same stop as a signed percent (e.g. -12.0). */
    fun stopPctFor(ts: TokenState): Double = -stopMagFor(ts)

    fun statusLine(): String =
        "sources=[${sources.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "bounds=${MIN_STOP_PCT.toInt()}-${MAX_STOP_PCT.toInt()}% lifecycleFloor=${com.lifecyclebot.engine.LifecycleHold8065.FLOOR_PCT.toInt()}% runnerFloor=${RUNNER_FLOOR_PCT.toInt()}%"
}
