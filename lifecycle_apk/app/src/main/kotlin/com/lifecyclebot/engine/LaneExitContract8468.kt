package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8468 — EACH FAST LANE EXITS ON ITS OWN DESIGNED CONTRACT UNTIL THE POSITION PROVES IT RUNS (strategy audit fix 1;
 * owner: "all 4. but be smart").
 *
 * The audit (8467): RunnerExitProfile7277 lists EXPRESS, MANIPULATED, SHITCOIN, PROJECT_SNIPER and DIP_HUNTER as runner
 * lanes and the style router seals pump coins BREAKOUT_RUNNER / REACCUMULATION, so from the first tick their positions rode
 * the runner contract (no give-back lock under a +20 % peak, then a 35 %-of-peak-price trail, a 4-12 h clock that never binds
 * while green, a moonbag) while their own designed exits were held or skipped:
 *  - EXPRESS_MOMENTUM_DEATH and *_TIME_EXIT rank ORDINARY, so NetEdge8030 held them while green under cost + 1 % — a fade
 *    at +3 % rode down to the stop; the tiny-profit deferral and the style min-hold held them too;
 *  - MANIPULATED's own exit (forced out at 4 minutes) did not run at all on a long-hold sealed style.
 * Lifetime live runner-lane book: 30 trades, 6 won, 8 reached +5 % then closed red, 16 never reached +5 %.
 *
 * The rule now: while a contract-lane position is not promoted (RunnerPlan8153 plan SCALP: no runner-grab admission, verified
 * peak under +50 %, no latched structure promotion), its lane's own exit signals — the sub-trader's take-profit, stop,
 * trail, time and momentum exits, named with the lane's prefix — are not held by the cost floor, the moonbag gate, the
 * tiny-profit deferral or the style min-hold: they act. Once promoted, the runner contract owns the position exactly as
 * before, so a real runner is never capped. Hard safety exits were protective already and are untouched; entry is untouched.
 */
object LaneExitContract8468 {
    /** The exit-reason prefixes each contract lane's own sub-trader signs its exits with (BotService / Executor.sniperSell). */
    private val PREFIXES_8468: List<Pair<String, List<String>>> = listOf(
        "EXPRESS" to listOf("EXPRESS_"),
        "MANIP" to listOf("MANIPULATED_", "MANIP_"),
        "SHITCOIN" to listOf("SHITCOIN_"),
        "PROJECT_SNIPER" to listOf("SNIPER_"),
        "DIP_HUNTER" to listOf("DIP_"),
    )

    private val acted8468 = AtomicLong(0)
    private val promotedPassed8468 = AtomicLong(0)

    /** Pure: is [reason] one of [lane]'s own designed exits (signed with that lane's sub-trader prefix)? */
    fun designedExit8468(lane: String?, reason: String?): Boolean {
        val l = lane?.trim()?.uppercase().orEmpty()
        val r = reason?.trim()?.uppercase().orEmpty()
        if (l.isBlank() || r.isBlank() || !RunnerExitProfile7277.contractLane8468(l)) return false
        return PREFIXES_8468.any { (key, prefixes) -> l.contains(key) && prefixes.any { r.startsWith(it) } }
    }

    /** Pure: does a designed exit act un-held for a position on [lane] whose runner plan is [plan]? Only before promotion. */
    fun acts8468(lane: String?, reason: String?, plan: RunnerPlan8153.Plan8153): Boolean =
        plan == RunnerPlan8153.Plan8153.SCALP && designedExit8468(lane, reason)

    /** Executor / NetEdge8030: does [reason] on [ts] act as its lane's designed exit (never held by a profit-hold gate)? */
    fun acts8468(ts: TokenState, reason: String): Boolean {
        val lane = ts.position.tradingMode
        if (!designedExit8468(lane, reason)) return false
        val plan = try { RunnerLive8153.plan8153(ts) } catch (_: Throwable) { RunnerPlan8153.Plan8153.SCALP }
        val acts = acts8468(lane, reason, plan)
        if (acts) acted8468.incrementAndGet() else promotedPassed8468.incrementAndGet()
        try { PipelineHealthCollector.labelInc(if (acts) "LANE_EXIT_CONTRACT_ACTS_8468" else "LANE_EXIT_CONTRACT_PROMOTED_RUNNER_8468") } catch (_: Throwable) {}
        return acts
    }

    fun statusLine8468(): String =
        "laneExitContract8468[acted=${acted8468.get()} promotedRunner=${promotedPassed8468.get()} promoteAt=+${RunnerExitProfile7277.CONTRACT_PROMOTE_PEAK_PCT_8468.toInt()}%]"
}
