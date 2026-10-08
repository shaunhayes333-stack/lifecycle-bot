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

    enum class Source { CELL, LANE, NONE }

    data class Verdict(val allow: Boolean, val source: Source, val edgePct: Double, val why: String)

    private val allowed = ConcurrentHashMap<String, AtomicLong>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure. [cell] is the candidate's forward-label cell (null when unseen);
     * [laneProven] is LivePivotAuthority7876's lane verdict == PROVEN.
     */
    fun judge(cell: ForwardReturnLabeler7731.CellStat?, laneProven: Boolean): Verdict {
        if (cell != null && cell.n60 >= CELL_MIN_N) {
            val se = if (cell.stderr60Pct.isFinite()) cell.stderr60Pct else Double.POSITIVE_INFINITY
            val lower = cell.meanNet60Pct - se
            // A launch cell's runners pay after the hour (7769): the 4-hour mean
            // may carry a cell whose 60-minute floor is under the margin.
            val lower240 = if (cell.n240 >= CELL_MIN_N) cell.meanNet240Pct - (if (se.isFinite()) se else 0.0) else Double.NEGATIVE_INFINITY
            val best = maxOf(lower, lower240)
            return if (best > LIVE_MARGIN_PCT) {
                Verdict(true, Source.CELL, best, "CELL_EDGE_${"%.1f".format(best)}PCT")
            } else {
                Verdict(false, Source.CELL, best, "CELL_EDGE_BELOW_MARGIN_${"%.1f".format(best)}PCT")
            }
        }
        return if (laneProven) Verdict(true, Source.LANE, 0.0, "LANE_PROVEN_7876")
        else Verdict(false, Source.NONE, 0.0, "NO_PREDICTED_EDGE")
    }

    /** Side-effect-free read for sizing and diagnostics. */
    fun verdictFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Verdict {
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        val cell = try { ForwardReturnLabeler7731.cellStatFor(ts, l, nowMs) } catch (_: Throwable) { null }
        val laneProven = try {
            LivePivotAuthority7876.laneVerdict(l, nowMs) == LivePivotAuthority7876.Evidence.PROVEN
        } catch (_: Throwable) { false }
        return judge(cell, laneProven)
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
        "bar=cellN>=$CELL_MIN_N&&mean-se>+$LIVE_MARGIN_PCT%|laneProven " +
            "admit=[${allowed.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "refuse=[${refused.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}]"
}
