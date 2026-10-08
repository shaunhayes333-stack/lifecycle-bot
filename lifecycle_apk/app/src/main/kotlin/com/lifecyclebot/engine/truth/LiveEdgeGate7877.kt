package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7877 §ONLY_TRADE_WHAT_THE_BOT_PREDICTS_PAYS.
 *
 * Operator: "realistically we are only meant to be making trades that the bot
 * predicts makes money."
 *
 * 5.0.7876 live: canonical n=149 WR 28.2% PF 0.04; forward labels admitted60
 * net -6.4% (n=300) against refused60 net -2.4% (n=3150) — the live selection
 * was worse than the trades it skipped. LivePivotAuthority7876 already held
 * evidence-negative lanes to paper, but only while a loss limit was breached,
 * and only at lane resolution.
 *
 * This gate is always on in LIVE. A live entry proceeds only when there is a
 * measured, net-of-cost prediction that it pays:
 *
 *   1. the candidate's own forward-label cell (source | lane | mcap | age) has
 *      [CELL_MIN_N]+ 60-minute labels and mean minus one standard error is
 *      above [LIVE_MARGIN_PCT] (labels are already net of round-trip cost; the
 *      margin covers live slippage and latency the labels do not see), or
 *   2. the cell is thin and the lane's own evidence is PROVEN under
 *      LivePivotAuthority7876.verdict (live record, lane labels, or paper above
 *      its cost margin).
 *
 * A cell proven below the margin refuses even when the lane is proven: the
 * narrower evidence wins. Everything else trades paper/shadow, where the next
 * labels are made, and re-earns live automatically when its numbers turn —
 * no code change, no operator toggle. Exits are never touched here.
 */
object LiveEdgeGate7877 {
    private const val CELL_MIN_N = 30
    private const val LIVE_MARGIN_PCT = 2.0
    /**
     * V5.0.7878 — runner lanes (MOONSHOT, sniper, shitcoin ...) are judged on
     * the label mean itself (already net of the round-trip cost at a $5 ticket):
     * their payoff is a 10-20% runner tail, so a lower 60-minute mean with a
     * fat right tail is the profile, not a defect.
     */
    private const val RUNNER_MARGIN_PCT = 0.0
    private const val PROVEN_NEGATIVE_PCT = -2.0

    enum class Source { CELL, LANE, NONE }

    data class Verdict(val allow: Boolean, val source: Source, val edgePct: Double, val why: String)

    private val allowed = ConcurrentHashMap<String, AtomicLong>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure. [cell] is the candidate's forward-label cell (null when unseen);
     * [laneProven] is LivePivotAuthority7876's lane verdict == PROVEN.
     */
    fun judge(cell: ForwardReturnLabeler7731.CellStat?, laneProven: Boolean, marginPct: Double = LIVE_MARGIN_PCT): Verdict {
        if (cell != null && cell.n60 >= CELL_MIN_N) {
            val se = if (cell.stderr60Pct.isFinite()) cell.stderr60Pct else Double.POSITIVE_INFINITY
            val lower = cell.meanNet60Pct - se
            // A launch cell's runners pay after the hour (7769): the 4-hour mean
            // may carry a cell whose 60-minute floor is under the margin.
            val lower240 = if (cell.n240 >= CELL_MIN_N) cell.meanNet240Pct - (if (se.isFinite()) se else 0.0) else Double.NEGATIVE_INFINITY
            val best = maxOf(lower, lower240)
            return if (best > marginPct) {
                Verdict(true, Source.CELL, best, "CELL_EDGE_${"%.1f".format(best)}PCT")
            } else {
                Verdict(false, Source.CELL, best, "CELL_EDGE_BELOW_MARGIN_${"%.1f".format(best)}PCT")
            }
        }
        return if (laneProven) Verdict(true, Source.LANE, 0.0, "LANE_PROVEN_7876")
        else Verdict(false, Source.NONE, 0.0, "NO_PREDICTED_EDGE")
    }

    /**
     * Pure. V5.0.7878 — a runner-lane candidate is judged on the best measured
     * cohort it actually belongs to: its own cell, and the plan cohort its tape
     * puts it in right now (PLANWAIT_<read>, PLANWAIT_LAUNCH_<verdict>). 5.0.7876:
     * MOONSHOT's own picks n=65 net -14.6% runner-rate 2%, while the fresh
     * launches the selector refused (PLANWAIT_LAUNCH_REFUSED) were n=1043 net
     * +3.1% runner-rate 15% — the tail the lane exists for was in the cohort it
     * was not allowed to buy. Its own cell proven clearly negative still refuses.
     */
    fun judgeRunner(cell: ForwardReturnLabeler7731.CellStat?, cohorts: List<ForwardReturnLabeler7731.CellStat?>, laneProven: Boolean): Verdict {
        if (cell != null && cell.n60 >= CELL_MIN_N) {
            val se = if (cell.stderr60Pct.isFinite()) cell.stderr60Pct else 0.0
            val late = cell.n240 >= CELL_MIN_N && cell.meanNet240Pct >= 0.0
            if (cell.meanNet60Pct + se < PROVEN_NEGATIVE_PCT && !late) {
                return Verdict(false, Source.CELL, cell.meanNet60Pct + se, "RUNNER_CELL_PROVEN_NEGATIVE_${"%.1f".format(cell.meanNet60Pct)}PCT")
            }
        }
        val measured = (listOf(cell) + cohorts).filterNotNull().filter { it.n60 >= CELL_MIN_N }
        if (measured.isEmpty()) return judge(null, laneProven, RUNNER_MARGIN_PCT)
        val best = measured.map { judge(it, false, RUNNER_MARGIN_PCT) }.maxByOrNull { it.edgePct }!!
        return best.copy(why = "RUNNER_" + best.why)
    }

    /** Pure: a runner lane's plan-wait cohort is evidence enough to overrule that wait. */
    fun runnerCohortAllows(stat: ForwardReturnLabeler7731.CellStat?): Boolean =
        stat != null && stat.n60 >= CELL_MIN_N && judge(stat, false, RUNNER_MARGIN_PCT).allow

    /** Side-effect-free read for sizing and diagnostics. */
    fun verdictFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Verdict {
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        val cell = try { ForwardReturnLabeler7731.cellStatFor(ts, l, nowMs) } catch (_: Throwable) { null }
        val laneProven = try {
            LivePivotAuthority7876.laneVerdict(l, nowMs) == LivePivotAuthority7876.Evidence.PROVEN
        } catch (_: Throwable) { false }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l) } catch (_: Throwable) { false }
        if (!runner) return judge(cell, laneProven)
        val cohorts = try {
            TradePlan7739.runnerCohortKeys7878(ts, nowMs).map { ForwardReturnLabeler7731.laneStatFor7737(it) }
        } catch (_: Throwable) { emptyList() }
        return judgeRunner(cell, cohorts, laneProven)
    }

    /** LIVE refusal reason, or null to admit. Paper is never refused. */
    fun liveRefusal(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (paper) return null
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        val v = verdictFor(ts, l, nowMs)
        if (v.allow) {
            allowed.computeIfAbsent("$l|${v.source.name}") { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_EDGE_ADMIT_7877_${v.source.name}") } catch (_: Throwable) {}
            return null
        }
        refused.computeIfAbsent("$l|${v.why.substringBefore("_PCT").take(28)}") { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("LIVE_EDGE_REFUSED_7877")
            PipelineHealthCollector.labelInc("LIVE_EDGE_REFUSED_7877_$l")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("LIVE_EDGE_7877", "$l|${ts.mint}")) {
                ForensicLogger.lifecycle(
                    "LIVE_EDGE_REFUSED_7877",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$l why=${v.why} action=live_refused_paper_continues",
                )
            }
        } catch (_: Throwable) {}
        return "EDGE_7877_${v.why}_$l"
    }

    fun statusLine(): String =
        "bar=cellN>=$CELL_MIN_N&&mean-se>+$LIVE_MARGIN_PCT%|laneProven runner=bestCohort(mean-se>$RUNNER_MARGIN_PCT%) " +
            "admit=[${allowed.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "refuse=[${refused.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}]"
}
