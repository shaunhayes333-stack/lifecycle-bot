package com.lifecyclebot.engine

/**
 * V5.0.8418 §EVERY_NAMED_LANE_HAS_ITS_OWN_DOCUMENTED_HOLD — HoldingLogicLayer.getHoldParams for the named entry lanes.
 *
 * 8384 (185e33687) gave the named lanes aliased specialist tables, silently: EXPRESS -> PUMP_SNIPER (30-minute max hold),
 * MANIPULATED -> PUMP_DUMP (15 min), TREASURY / CASHGEN -> MARKET_MAKER (30 min, 3-point trail). The hold layer's
 * "Max hold time exceeded" EXIT_NOW fires whatever the P&L, so a runner lane's green position read as "exit now" at 30
 * minutes while the owner's runner rule is the 35 % trail from peak (GreenFloor8363.floor8370, ledger #34) and no win caps.
 *
 * Each named lane now has one row here: the specialist table it borrows its scale-outs from, its own max hold and its own
 * trail, and why. Rules:
 *  - RUNNER lanes (RunnerExitProfile7277.isRunnerLane) never trail tighter than RUNNER_TRAIL_FLOOR_PCT_8418 points in this
 *    layer, and their clock does not bind while the position is green ([clockBinds8418]): the 35 % runner trail and the
 *    protective stops own a green runner; the clock only retires flat / red positions.
 *  - Non-runner desks keep their specialist clock, at least the 60-minute window the Cortex grades them on (8392).
 *  - Stops are unchanged: getHoldParams still composes the lane stop (FieldManual7715.laneStopPct8392).
 */
object LaneHolds8418 {
    /** One named lane's documented hold. [base] is the HoldingLogicLayer table whose scale-out ladder it uses. */
    data class LaneHold8418(val base: String, val maxHoldMs: Long, val trailPct: Double, val why: String)

    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 60L * MIN_MS
    /** The widest give-back (points under the peak gain) this layer may apply to a runner lane: GreenFloor8363 owns it. */
    private const val RUNNER_TRAIL_FLOOR_PCT_8418 = 35.0

    private val HOLDS_8418: Map<String, LaneHold8418> = mapOf(
        // Runner lanes — the pre-8384 four-hour clock (STANDARD) for flat / red positions; green ones belong to the trail.
        "EXPRESS" to LaneHold8418("PUMP_SNIPER", 4L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "runner lane graded on the hour slot (8337 10e): no 30-minute cap; the 35% trail owns a green runner"),
        "MANIPULATED" to LaneHold8418("PUMP_DUMP", 4L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "runner lane (RunnerExitProfile7277 MANIPULATED): no 15-minute cap; stops and the 35% trail own it"),
        "SHITCOIN" to LaneHold8418("MICRO_CAP", 12L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "fresh-launch runner: MICRO_CAP's 12 h clock for flat / red, the 35% trail for green"),
        "PROJECT_SNIPER" to LaneHold8418("MICRO_CAP", 12L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "fresh-launch runner: MICRO_CAP's 12 h clock for flat / red, the 35% trail for green"),
        "DIP_HUNTER" to LaneHold8418("REVIVAL", 6L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "reversal runner: REVIVAL's 6 h clock for flat / red, the 35% trail for green"),
        "CORE" to LaneHold8418("STANDARD", 4L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "ensemble of the runner lanes (7389): STANDARD's 4 h clock, runner trail"),
        // Non-runner desks — their own scalp / quality clocks, never under the 60-minute graded window.
        "TREASURY" to LaneHold8418("MARKET_MAKER", HOUR_MS, 3.0,
            "range-reversion scalp desk: 60-minute graded window (8392), 3-point trail, MARKET_MAKER's 5/8% scale-outs"),
        "CASHGEN" to LaneHold8418("MARKET_MAKER", HOUR_MS, 3.0,
            "cash-generation scalp desk: same contract as TREASURY (same mandate, FieldManual7715 TREASURY_LANE)"),
        "QUALITY" to LaneHold8418("BLUE_CHIP", 30L * 24L * HOUR_MS, 5.0,
            "established-token desk: BLUE_CHIP's patient clock and 5-point trail"),
        "BLUECHIP" to LaneHold8418("BLUE_CHIP", 30L * 24L * HOUR_MS, 5.0,
            "established-token desk: BLUE_CHIP's patient clock and 5-point trail"),
    )

    /** Pure: [lane]'s documented hold, or null (an unnamed lane falls back to STANDARD). */
    fun holdFor8418(lane: String): LaneHold8418? = HOLDS_8418[lane.trim().uppercase()]

    /** Pure: the trail this layer applies to a position managed as [mode], owned by [owner] — never tighter than the runner floor on a runner lane. */
    fun trailFor8418(mode: String, owner: String, tablePct: Double): Double =
        if (RunnerExitProfile7277.isRunnerLane(mode) || RunnerExitProfile7277.isRunnerLane(owner)) maxOf(tablePct, RUNNER_TRAIL_FLOOR_PCT_8418)
        else tablePct

    /**
     * Pure: does a hold clock (legacy max hold or the fluid clock) bind for a position managed as [mode] and owned by
     * [owner] at gross [pnlPct]? Not on a GREEN runner — the 35 % trail and the protective stops own it. Unknown P&L binds.
     */
    fun clockBinds8418(mode: String, owner: String, pnlPct: Double): Boolean {
        val runner = RunnerExitProfile7277.isRunnerLane(mode) || RunnerExitProfile7277.isRunnerLane(owner)
        return !(runner && pnlPct.isFinite() && pnlPct > 0.0)
    }

    fun statusLine8418(): String =
        "laneHolds8418[" + HOLDS_8418.entries.joinToString(",") { "${it.key}=${it.value.base}/${it.value.maxHoldMs / MIN_MS}m/t${it.value.trailPct.toInt()}" } +
            "] rule=runner clock never binds while green; runner trail >= ${RUNNER_TRAIL_FLOOR_PCT_8418.toInt()}pt"
}
