package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.KillSwitch
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.StrategyTelemetry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7876 — PIVOT, not pause and not blind size-down.
 *
 * Operator, on loss limits: "rethink its design. maybe it should pivot."
 * 5.0.7875 live: 121 canonical closes, WR 28.9%, PF 0.09, EV -0.0116 SOL,
 * drawdown 61% against the 25% limit; admitted 60-min outcomes net -6.7% vs
 * refused -3.7% (the entry selection had no edge). Since 7864 a breached loss
 * limit only shrank size, and at a one-unit wallet a smaller size is the
 * routable minimum anyway: the same strategies kept spending the same SOL.
 *
 * While any operator loss limit is breached (KillSwitch.pivotReasons7876:
 * daily loss, drawdown, loss streak), live entries continue ONLY on lanes whose
 * own evidence is positive:
 *   - live record: >= 5 clean live closes with positive PF expectancy, or
 *   - forward labels: >= 20 60-minute labels, mean minus one standard error > 0
 *     (net of cost), or
 *   - paper record: >= 30 clean paper closes with PF expectancy above the 4%
 *     round-trip cost margin (paper fills are optimistic).
 * A lane whose live record is negative (>= 5 closes) needs forward-label proof
 * to stay live. Every other lane trades paper/shadow and keeps learning; it
 * re-earns live by evidence. Exits are never touched. Outside a breach this
 * authority does nothing.
 */
object LivePivotAuthority7876 {
    enum class Evidence { PROVEN, NEGATIVE, UNPROVEN }

    data class LaneEvidence(
        val liveTrades: Int, val livePp: Double,
        val paperTrades: Int, val paperPp: Double,
        val labelN: Int, val labelMean: Double, val labelSe: Double,
    )

    private const val LIVE_MIN_N = 5
    private const val LABEL_MIN_N = 20
    private const val PAPER_MIN_N = 30
    private const val PAPER_COST_MARGIN_PCT = 4.0
    private const val CACHE_MS = 30_000L

    private val refused = ConcurrentHashMap<String, AtomicLong>()
    private val allowed = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var cache: Pair<Long, Map<String, Pair<StrategyTelemetry.StrategyMetric?, StrategyTelemetry.StrategyMetric?>>>? = null

    /** Pure: the lane's evidence verdict for live authority during a pivot. */
    fun verdict(e: LaneEvidence): Evidence {
        val liveNegative = e.liveTrades >= LIVE_MIN_N && e.livePp < 0.0
        val labelsProven = e.labelN >= LABEL_MIN_N && e.labelMean - (if (e.labelSe.isFinite()) e.labelSe else 0.0) > 0.0
        if (liveNegative) return if (labelsProven) Evidence.PROVEN else Evidence.NEGATIVE
        val liveProven = e.liveTrades >= LIVE_MIN_N && e.livePp > 0.0
        val paperProven = e.paperTrades >= PAPER_MIN_N && e.paperPp > PAPER_COST_MARGIN_PCT
        return if (liveProven || labelsProven || paperProven) Evidence.PROVEN else Evidence.UNPROVEN
    }

    private fun boards(nowMs: Long): Map<String, Pair<StrategyTelemetry.StrategyMetric?, StrategyTelemetry.StrategyMetric?>> {
        cache?.let { (at, m) -> if (nowMs - at in 0L until CACHE_MS) return m }
        val live = try { StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500) } catch (_: Throwable) { emptyList() }
        val paper = try { StrategyTelemetry.computeCleanPaperTerminalLeaderboard(limit = 1_500) } catch (_: Throwable) { emptyList() }
        val keys = (live.map { it.strategy.uppercase() } + paper.map { it.strategy.uppercase() }).toSet()
        val m = keys.associateWith { k ->
            live.firstOrNull { it.strategy.equals(k, true) } to paper.firstOrNull { it.strategy.equals(k, true) }
        }
        cache = nowMs to m
        return m
    }

    private fun evidenceFor(lane: String, nowMs: Long = System.currentTimeMillis()): LaneEvidence {
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase()
        val (live, paper) = boards(nowMs)[l] ?: (null to null)
        val label = try { ForwardReturnLabeler7731.laneStatFor7737(l) } catch (_: Throwable) { null }
        return LaneEvidence(
            liveTrades = live?.trades ?: 0, livePp = live?.pfExpectancyPp ?: 0.0,
            paperTrades = paper?.trades ?: 0, paperPp = paper?.pfExpectancyPp ?: 0.0,
            labelN = label?.n60 ?: 0, labelMean = label?.meanNet60Pct ?: 0.0, labelSe = label?.stderr60Pct ?: 0.0,
        )
    }

    /** Live refusal for [lane] while a loss limit is breached, or null. Paper is never refused. */
    fun liveRefusal(lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (paper) return null
        val reasons = KillSwitch.pivotReasons7876
        if (reasons.isEmpty()) return null
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase()
        val v = verdict(evidenceFor(l, nowMs))
        if (v == Evidence.PROVEN) {
            allowed.computeIfAbsent(l) { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_PIVOT_LANE_KEPT_LIVE_7876_$l") } catch (_: Throwable) {}
            return null
        }
        refused.computeIfAbsent("$l|${v.name}") { AtomicLong(0) }.incrementAndGet()
        val reason = "PIVOT_7876_${v.name}_${l}"
        try {
            PipelineHealthCollector.labelInc("LIVE_PIVOT_LANE_TO_PAPER_7876_$l")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("LIVE_PIVOT_7876", l)) {
                ForensicLogger.lifecycle(
                    "LIVE_PIVOT_LANE_TO_PAPER_7876",
                    "lane=$l evidence=${v.name} breaches=${reasons.joinToString("+")} action=live_entry_refused_paper_continues",
                )
            }
        } catch (_: Throwable) {}
        return reason
    }

    fun statusLine(): String =
        "breaches=${KillSwitch.pivotReasons7876.joinToString("+").ifBlank { "none" }} " +
            "keptLive=${allowed.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "toPaper=${refused.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}"
}
