package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap

/** V5.0.4277 — background runner-retention critic feed; no direct exit authority. */
object RunnerRetentionOptimizer {
    private const val MIN_MISSED_RUNNER_DELTA_PCT = 25.0
    private const val MIN_DEBOUNCE_MS = 10 * 60 * 1000L
    private val lastSubmitByLane = ConcurrentHashMap<String, Long>()

    fun recordTerminalExit(
        trade: Trade,
        lane: String,
        peakGainPct: Double,
        holdSeconds: Long,
        replayHint: String,
    ) {
        try {
            if (!trade.side.equals("SELL", true)) return
            val laneKey = lane.uppercase().ifBlank { "STANDARD" }.take(32)
            val reason = trade.reason
            val profitExit = reason.contains("profit", true) || reason.contains("lock", true) || reason.contains("take", true) || reason.contains("runner", true)
            val missedRunnerDelta = (peakGainPct - trade.pnlPct).coerceAtLeast(0.0)
            if (!profitExit || missedRunnerDelta < MIN_MISSED_RUNNER_DELTA_PCT || peakGainPct < 50.0) return
            val now = System.currentTimeMillis()
            val prev = lastSubmitByLane[laneKey] ?: 0L
            if (now - prev < MIN_DEBOUNCE_MS) return
            lastSubmitByLane[laneKey] = now
            val summary = "lane=$laneKey pnlPct=${trade.pnlPct.fmt(2)} peakPct=${peakGainPct.fmt(2)} missedRunnerDelta=${missedRunnerDelta.fmt(2)} holdSeconds=$holdSeconds reason=$reason replay={$replayHint}"
            val proposal = "bounded soft hold bias +0.04 to +0.08 for lane=$laneKey when terminal profit exits show missed-runner delta; keep hard safety/rug/strict-stop authority unchanged; no hard veto, no zero-size, no hot-path API"
            val accepted = MultiAgentCriticStack.reviewAndSubmit(
                lane = laneKey,
                closedTradeSummary = summary,
                candidateProposal = proposal,
                expectedMetric = "increase avg_win_pct and realized_net_sol without reducing terminal sell trainability or live throughput",
                rollbackCondition = "rollback if lane avg_loss worsens or realized net SOL drops over next bounded sample window",
                sourceTag = "BACKGROUND_RUNNER_RETENTION_4277",
            )
            try {
                PipelineHealthCollector.labelInc(if (accepted) "RUNNER_RETENTION_PROPOSAL_ACCEPTED_4277" else "RUNNER_RETENTION_PROPOSAL_REJECTED_4277")
                ForensicLogger.lifecycle("RUNNER_RETENTION_OPTIMIZER_4277", "lane=$laneKey accepted=$accepted missedRunnerDelta=${missedRunnerDelta.fmt(2)} peakPct=${peakGainPct.fmt(2)} pnlPct=${trade.pnlPct.fmt(2)} action=background_symbolic_review_only")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    fun reset() { lastSubmitByLane.clear() }

    private fun Double.fmt(digits: Int): String = try { java.lang.String.format(java.util.Locale.US, "% ." + digits + "f", this).replace(" ", "") } catch (_: Throwable) { toString() }
}
