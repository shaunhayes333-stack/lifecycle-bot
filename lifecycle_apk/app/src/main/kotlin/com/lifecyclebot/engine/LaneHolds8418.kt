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
 *  - RUNNER positions (RunnerExitProfile7277.runnerExits8468) never trail tighter than RUNNER_TRAIL_FLOOR_PCT_8418 points
 *    in this layer, and their clock does not bind while the position is green ([clockBinds8418]): the 35 % runner trail
 *    and the protective stops own a green runner; the clock only retires flat / red positions.
 *  - Stops are unchanged: getHoldParams still composes the lane stop (FieldManual7715.laneStopPct8392).
 *
 * superseded by V5.0.8468 (owner: "all 4. but be smart" — the strategy audit's fixes 1 and 2):
 *  - the fast CONTRACT lanes (EXPRESS, MANIPULATED, SHITCOIN, PROJECT_SNIPER, DIP_HUNTER) carry their own specialist
 *    table's clock and trail again; the runner floor and the green-runner clock exemption apply once the position is
 *    PROMOTED (verified peak +50 %, RunnerExitProfile7277.CONTRACT_PROMOTE_PEAK_PCT_8468). Before that a 4-12 h clock and
 *    a 35-point trail held fast-lane coins past their designed exits (lifetime live runner-lane book: 30 trades, 8 reached
 *    +5 % and closed red, 16 never reached +5 %). A promoted runner is never capped.
 *  - Non-runner desks run their own sub-trader's clock (CashGenerationAI 45 min, QualityTraderAI 60 min, BlueChipTraderAI
 *    8 h) instead of 8392's 60-minute floor and the 30-day BLUE_CHIP table; the Cortex grades them on that same clock
 *    ([deskHorizonMs8468], CortexLabelHygiene8144.horizonMsFor8468) rather than bending their exits to the grader.
 */
object LaneHolds8418 {
    /** One named lane's documented hold. [base] is the HoldingLogicLayer table whose scale-out ladder it uses. */
    data class LaneHold8418(val base: String, val maxHoldMs: Long, val trailPct: Double, val why: String)

    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 60L * MIN_MS
    /** The widest give-back (points under the peak gain) this layer may apply to a runner lane: GreenFloor8363 owns it. */
    private const val RUNNER_TRAIL_FLOOR_PCT_8418 = 35.0

    private val HOLDS_8418: Map<String, LaneHold8418> = mapOf(
        // Fast contract lanes — their own specialist clock and trail until promoted (V5.0.8468); then the runner trail.
        "EXPRESS" to LaneHold8418("PUMP_SNIPER", 30L * MIN_MS, 10.0,
            "momentum ride, +30% or out: PUMP_SNIPER's 30-minute clock and 10-point trail until promoted at +50%"),
        "MANIPULATED" to LaneHold8418("PUMP_DUMP", 15L * MIN_MS, 8.0,
            "first-pump ride (ManipulatedTraderAI forces out at 4 min): PUMP_DUMP's 15-minute clock until promoted"),
        "SHITCOIN" to LaneHold8418("MICRO_CAP", 12L * HOUR_MS, 25.0,
            "fresh-launch desk: MICRO_CAP's 12 h backstop and 25-point trail; its own stops / time exit act first"),
        "PROJECT_SNIPER" to LaneHold8418("MICRO_CAP", 12L * HOUR_MS, 25.0,
            "launch sniper: MICRO_CAP's 12 h backstop and 25-point trail; its own TP tiers / abort act first"),
        "DIP_HUNTER" to LaneHold8418("REVIVAL", 6L * HOUR_MS, 18.0,
            "bounce trade: REVIVAL's 6 h clock and 18-point trail until promoted"),
        "CORE" to LaneHold8418("STANDARD", 4L * HOUR_MS, RUNNER_TRAIL_FLOOR_PCT_8418,
            "ensemble of the runner lanes (7389): STANDARD's 4 h clock, runner trail"),
        // Non-runner desks — their own sub-trader's clock (V5.0.8468), graded on that clock.
        "TREASURY" to LaneHold8418("MARKET_MAKER", 45L * MIN_MS, 3.0,
            "range-reversion scalp desk: CashGenerationAI's 45-minute clock, 3-point trail, MARKET_MAKER's 5/8% scale-outs"),
        "CASHGEN" to LaneHold8418("MARKET_MAKER", 45L * MIN_MS, 3.0,
            "cash-generation scalp desk: same contract as TREASURY (same mandate, FieldManual7715 TREASURY_LANE)"),
        "QUALITY" to LaneHold8418("BLUE_CHIP", HOUR_MS, 5.0,
            "established-token desk: QualityTraderAI's 60-minute hold, BLUE_CHIP's 5-point trail"),
        "BLUECHIP" to LaneHold8418("BLUE_CHIP", 8L * HOUR_MS, 5.0,
            "established-token swing desk: BlueChipTraderAI's 8 h window, BLUE_CHIP's 5-point trail"),
    )

    /** The desks that are not runner lanes: graded on their own clock (V5.0.8468). */
    private val DESKS_8468 = setOf("TREASURY", "CASHGEN", "QUALITY", "BLUECHIP")

    /** Pure: [lane]'s documented hold, or null (an unnamed lane falls back to STANDARD). */
    fun holdFor8418(lane: String): LaneHold8418? = HOLDS_8418[lane.trim().uppercase()]

    /** Pure (V5.0.8468): a non-runner desk's own clock — the horizon the Cortex grades it on — or null for any other lane. */
    fun deskHorizonMs8468(lane: String?): Long? {
        val l = lane?.trim()?.uppercase() ?: return null
        return if (l in DESKS_8468) HOLDS_8418[l]?.maxHoldMs else null
    }

    /**
     * Pure: the trail this layer applies to a position managed as [mode], owned by [owner] at verified peak [peakPct] —
     * never tighter than the runner floor on a runner position (V5.0.8468: a contract lane once promoted).
     */
    fun trailFor8418(mode: String, owner: String, tablePct: Double, peakPct: Double = Double.NaN): Double =
        if (RunnerTrail8419.runnerLaneAt8468(mode, owner, peakPct)) maxOf(tablePct, RUNNER_TRAIL_FLOOR_PCT_8418)
        else tablePct

    /**
     * Pure: does a hold clock (legacy max hold or the fluid clock) bind for a position managed as [mode] and owned by
     * [owner] at gross [pnlPct] and verified peak [peakPct]? Not on a GREEN runner — the 35 % trail and the protective
     * stops own it (V5.0.8468: a contract lane is a runner once promoted). Unknown P&L binds.
     */
    fun clockBinds8418(mode: String, owner: String, pnlPct: Double, peakPct: Double = Double.NaN): Boolean {
        val runner = RunnerTrail8419.runnerLaneAt8468(mode, owner, peakPct)
        return !(runner && pnlPct.isFinite() && pnlPct > 0.0)
    }

    fun statusLine8418(): String =
        "laneHolds8418[" + HOLDS_8418.entries.joinToString(",") { "${it.key}=${it.value.base}/${it.value.maxHoldMs / MIN_MS}m/t${it.value.trailPct.toInt()}" } +
            "] rule=runner clock never binds while green; runner trail >= ${RUNNER_TRAIL_FLOOR_PCT_8418.toInt()}pt; " +
            "contract lanes promote at +${RunnerExitProfile7277.CONTRACT_PROMOTE_PEAK_PCT_8468.toInt()}% (8468)"
}
