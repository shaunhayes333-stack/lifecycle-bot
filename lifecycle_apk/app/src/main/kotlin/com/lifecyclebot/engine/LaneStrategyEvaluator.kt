package com.lifecyclebot.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lane exit-shape research from canonical terminal summaries.
 *
 * Realized return is observed. MFE/MAE are unordered extrema, not a chronological/executable price path:
 * the same extrema can describe stop-before-TP and TP-before-stop. No summary can prove a counterfactual fill,
 * trail activation, gap execution, fee or residual share. Alternate values below are explicitly hypothetical
 * sensitivity calculations for research only; they cannot select an executable exit policy.
 *
 * Chronological runner replay remains in RunnerReplay8153. Canonical closed-loop tuning remains in LaneExitTuner.
 */
object LaneStrategyEvaluator {

    private const val TAG = "LaneStrategyEval"

    enum class Evidence8527 { CANONICAL_REALIZED, SUMMARY_HYPOTHESIS, NO_TRADE_REFERENCE }

    data class SummaryValue8527(val pnlPct: Double, val evidence: Evidence8527)

    /** A candidate exit shape. Summary arithmetic is diagnostic, never a simulated or guaranteed fill. */
    data class ExitProfile(
        val name: String,
        /** stop floor in pct (negative). null = no hard stop beyond the trade's own. */
        val stopPct: Double?,
        /** trail width from peak in pct. null = no trail (keep actual upside). */
        val trailFromPeakPct: Double?,
        /** full take-profit target in pct. null = none. */
        val fullTpPct: Double?,
        /** true = lane sits out entirely (the NO-TRADE floor). */
        val noTrade: Boolean = false,
    ) {
        val evidence8527: Evidence8527 get() = when {
            noTrade -> Evidence8527.NO_TRADE_REFERENCE
            stopPct == null && trailFromPeakPct == null && fullTpPct == null -> Evidence8527.CANONICAL_REALIZED
            else -> Evidence8527.SUMMARY_HYPOTHESIS
        }

        fun summarize8527(realizedPct: Double, peakPct: Double, drawdownPct: Double): SummaryValue8527 =
            SummaryValue8527(transform(realizedPct, peakPct, drawdownPct), evidence8527)

        /** Legacy arithmetic API; use summarize8527 to carry its evidence kind. No chronology is implied. */
        fun transform(realizedPct: Double, peakPct: Double, drawdownPct: Double): Double {
            if (noTrade) return 0.0
            var out = realizedPct

            // Sensitivity assumption: a TP fill at the target. Extrema cannot establish order or executability.
            fullTpPct?.let { tp ->
                if (peakPct >= tp) out = tp
            }

            // Sensitivity assumption: a trail banks peak-minus-width. A later retracement is not proved.
            trailFromPeakPct?.let { t ->
                if (peakPct > 0.0) {
                    val trailed = peakPct - t
                    // a trail only helps a trade that round-tripped (realized << peak)
                    if (trailed > out) out = min(trailed, peakPct)
                }
            }

            // Sensitivity assumption: a stop fills at its threshold. A gap/failed sale is not represented.
            stopPct?.let { s ->
                // Kept for research comparison only, not a claim that the loss could actually have been clipped.
                if (drawdownPct <= s && out < s) out = s
            }
            return out
        }
    }

    data class LaneResult(
        val lane: String,
        val profile: String,
        val n: Int,
        val wins: Int,
        val netPctSum: Double,
        val avgPct: Double,
        val netSol: Double,
        val sharpe: Double,
        val maxDrawdownPct: Double,
        val evidence8527: Evidence8527 = Evidence8527.SUMMARY_HYPOTHESIS,
        val environment8527: String = "UNKNOWN",
    ) {
        val winRate: Double get() = if (n > 0) wins * 100.0 / n else 0.0
        fun oneLine(): String =
            "[$environment8527/$lane/$profile ${evidence8527.name}] n=$n WR=${"%.0f".format(winRate)}% avg=${"%+.1f".format(avgPct)}% " +
            "net=${"%+.3f".format(netSol)}◎ sharpe=${"%.2f".format(sharpe)} maxDD=${"%.0f".format(maxDrawdownPct)}%"
    }

    /** The candidate profiles every lane is tested against. */
    fun defaultProfiles(): List<ExitProfile> = listOf(
        ExitProfile("CURRENT_ACTUAL", stopPct = null, trailFromPeakPct = null, fullTpPct = null),
        ExitProfile("TIGHT_STOP_-5",  stopPct = -5.0, trailFromPeakPct = null, fullTpPct = null),
        ExitProfile("FLOOR_-15_LETRUN", stopPct = -15.0, trailFromPeakPct = 15.0, fullTpPct = null),
        ExitProfile("FLOOR_-15_TRAIL25", stopPct = -15.0, trailFromPeakPct = 25.0, fullTpPct = null),
        ExitProfile("EARLY_TP_+30",   stopPct = -10.0, trailFromPeakPct = null, fullTpPct = 30.0),
        ExitProfile("NO_TRADE",       stopPct = null, trailFromPeakPct = null, fullTpPct = null, noTrade = true),
    )

    /** Summary sensitivity rows, separated by environment. Alternate profiles remain explicitly hypothetical. */
    fun evaluateAll(profiles: List<ExitProfile> = defaultProfiles()): List<LaneResult> =
        evaluateOutcomes8527(try { CanonicalOutcomeBus.recentSnapshot() } catch (_: Throwable) { emptyList() }, profiles)

    internal fun evaluateOutcomes8527(outcomes: List<CanonicalTradeOutcome>, profiles: List<ExitProfile> = defaultProfiles()): List<LaneResult> {
        val closed = outcomes.filter {
            !it.isPartial && it.isTrainable && it.exitTimeMs != null &&
                it.realizedPnlPct?.isFinite() == true && it.entrySol?.let { size -> size.isFinite() && size > 0.0 } == true
        }.sortedBy { it.exitTimeMs }
        if (closed.isEmpty()) return emptyList()

        val byLane = closed.groupBy { it.environment.name to it.mode.name }
        val out = mutableListOf<LaneResult>()

        for ((cohort, trades) in byLane) {
            val (environment, lane) = cohort
            if (trades.size < 5) continue   // not enough to evaluate honestly
            for (p in profiles) {
                var wins = 0
                var netPctSum = 0.0
                var netSol = 0.0
                val rets = ArrayList<Double>(trades.size)
                var equity = 0.0; var peak = 0.0; var maxDD = 0.0
                for (t in trades) {
                    val realized = t.realizedPnlPct ?: 0.0
                    val pk = t.maxGainPct ?: max(realized, 0.0)
                    val dd = t.maxDrawdownPct ?: min(realized, 0.0)
                    val hyp = p.summarize8527(realized, pk, dd).pnlPct
                    val sol = if (p.evidence8527 == Evidence8527.CANONICAL_REALIZED) {
                        t.realizedPnlSol?.takeIf { it.isFinite() } ?: (t.entrySol ?: 0.0) * (hyp / 100.0)
                    } else (t.entrySol ?: 0.0) * (hyp / 100.0)
                    if (hyp >= 1.0) wins++
                    netPctSum += hyp
                    netSol += sol
                    rets.add(hyp)
                    equity += hyp
                    if (equity > peak) peak = equity
                    val ddNow = peak - equity
                    if (ddNow > maxDD) maxDD = ddNow
                }
                val n = trades.size
                val avg = netPctSum / n
                val sharpe = if (rets.size > 1) {
                    val m = rets.average()
                    val v = rets.sumOf { (it - m) * (it - m) } / (rets.size - 1)
                    val sd = sqrt(v)
                    if (sd > 0) m / sd * sqrt(rets.size.toDouble()) else 0.0
                } else 0.0
                out.add(LaneResult(lane, p.name, n, wins, netPctSum, avg, netSol, sharpe, maxDD, p.evidence8527, environment))
            }
        }
        return out.sortedWith(compareBy({ it.environment8527 }, { it.lane }, { -it.netSol }))
    }

    /**
     * Compatibility ranking: only measured unchanged-policy baselines are eligible. A hypothetical summary,
     * even with a huge number or a misleading profile name, cannot beat a recorded outcome into execution authority.
     */
    fun bestPerLane(profiles: List<ExitProfile> = defaultProfiles()): Map<String, LaneResult> =
        supportedBaselines8527(evaluateAll(profiles))

    internal fun supportedBaselines8527(rows: List<LaneResult>): Map<String, LaneResult> =
        rows.filter { it.evidence8527 == Evidence8527.CANONICAL_REALIZED }
            .groupBy { "${it.environment8527}|${it.lane}" }
            .mapValues { (_, rs) -> rs.maxByOrNull { it.netSol }!! }

    fun renderReport(): String {
        val sb = StringBuilder()
        sb.appendLine("══════════════════════════════════════════════════")
        sb.appendLine("🧪 LANE EXIT SUMMARY RESEARCH — NOT EXECUTABLE REPLAY")
        sb.appendLine("  Actual baseline is recorded; alternate shapes assume fills and event order.")
        sb.appendLine("  No switch/stop-trading recommendation follows from unordered MFE/MAE.")
        sb.appendLine("══════════════════════════════════════════════════")
        val all = evaluateAll()
        if (all.isEmpty()) { sb.appendLine("(insufficient canonical closed outcomes per environment/lane)"); return sb.toString() }
        val byLane = all.groupBy { "${it.environment8527}|${it.lane}" }
        for ((lane, rs) in byLane) {
            sb.appendLine("─── $lane ─── research only; chronological execution evidence required")
            rs.sortedByDescending { it.netSol }.forEach { sb.appendLine("   ${it.oneLine()}") }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun logReport() {
        renderReport().lines().forEach { if (it.isNotBlank()) ErrorLogger.info(TAG, it) }
    }
}
