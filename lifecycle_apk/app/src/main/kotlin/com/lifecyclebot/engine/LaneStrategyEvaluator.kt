package com.lifecyclebot.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * V5.9.1285 — LANE STRATEGY EVALUATOR
 * ════════════════════════════════════════════════════════════════════════════
 * Closes the strategy-evaluation loop so we STOP patch-rotating one lane at a
 * time and instead let REPLAY ON REAL HISTORY decide what each lane should do.
 *
 * WHY THIS EXISTS (operator directive 2026-06-02):
 *   The tuning console proved 6 of 7 lanes run the SAME profit ladder
 *   [20,50,100,300,1000,3000,10000] but with tight stops (BLUECHIP -4/-7%),
 *   producing high-WR / net-NEGATIVE bleed (clip the small losers AND the
 *   would-be runners). The ONE lane that wins (SHITCOIN: WR 6% / net +2.7 SOL)
 *   uses the -15% floor + trail-the-runner so the fat tails survive. The
 *   question isn't "clamp TREASURY" — it's "does each lane have ANY edge, or is
 *   it a worse-shaped version of the one strategy that works?"
 *
 * HONEST BACKTEST — NO FABRICATED UPSIDE:
 *   Each historical close in CanonicalOutcomeBus carries the REAL realizedPnlPct,
 *   maxGainPct (peak the trade actually reached) and maxDrawdownPct (worst dip it
 *   actually saw). We only ever re-derive an outcome the trade's OWN price path
 *   could have produced:
 *     - TIGHTER STOP s: if the trade's dip (maxDrawdownPct) breached s, it would
 *       have stopped at s — UNLESS its peak came first and it already realized
 *       more than s, in which case the actual stands. Conservative: we clamp the
 *       loss, we never invent a save.
 *     - WIDER STOP / LET-RUN trail t from peak: we KNOW maxGainPct, so a runner
 *       banks ~ (maxGainPct - t), capped at the actual realized when the trade
 *       didn't round-trip. We never bank more than the peak the trade truly hit.
 *     - EARLIER FULL TP at target: if peak reached the target, realize = target;
 *       else the trade never got there → actual stands.
 *     - NO-TRADE: lane sits out → 0 (the honest floor every lane must beat).
 *
 * Output ranks profiles per lane by net SOL, then Sharpe, then drawdown. A lane
 * whose best profile can't beat NO-TRADE is a structural bleeder — the data, not
 * a human guess, says it should stop trading.
 */
object LaneStrategyEvaluator {

    private const val TAG = "LaneStrategyEval"

    /** A candidate exit shape. transform() returns the honest re-derived pnlPct. */
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
        fun transform(realizedPct: Double, peakPct: Double, drawdownPct: Double): Double {
            if (noTrade) return 0.0
            var out = realizedPct

            // EARLIER FULL TP — only if the trade truly reached the target. If the
            // peak reached the target we would have sold the full position there,
            // banking exactly tp. If it never reached tp, the actual outcome stands.
            fullTpPct?.let { tp ->
                if (peakPct >= tp) out = tp
            }

            // LET-RUN TRAIL from the REAL peak — bank peak minus trail, but never
            // more than the peak actually reached and never less than a clean stop.
            trailFromPeakPct?.let { t ->
                if (peakPct > 0.0) {
                    val trailed = peakPct - t
                    // a trail only helps a trade that round-tripped (realized << peak)
                    if (trailed > out) out = min(trailed, peakPct)
                }
            }

            // STOP — clamp the downside the trade's OWN dip would have triggered.
            stopPct?.let { s ->
                // If the worst dip breached the stop AND the trade ultimately did
                // worse than the stop, it would have exited at the stop instead.
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
    ) {
        val winRate: Double get() = if (n > 0) wins * 100.0 / n else 0.0
        fun oneLine(): String =
            "[$lane/$profile] n=$n WR=${"%.0f".format(winRate)}% avg=${"%+.1f".format(avgPct)}% " +
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

    /**
     * Replay every lane × every profile against the canonical outcome history.
     * Returns a flat list sorted by lane then net SOL desc.
     */
    fun evaluateAll(profiles: List<ExitProfile> = defaultProfiles()): List<LaneResult> {
        val outcomes = try { CanonicalOutcomeBus.recentSnapshot() } catch (_: Throwable) { emptyList() }
        // closed trades with a usable realized pnl
        val closed = outcomes.filter { it.realizedPnlPct != null && it.entrySol != null && it.entrySol!! > 0.0 }
        if (closed.isEmpty()) return emptyList()

        val byLane = closed.groupBy { it.mode.name }
        val out = mutableListOf<LaneResult>()

        for ((lane, trades) in byLane) {
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
                    val hyp = p.transform(realized, pk, dd)
                    val sol = (t.entrySol ?: 0.0) * (hyp / 100.0)
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
                out.add(LaneResult(lane, p.name, n, wins, netPctSum, avg, netSol, sharpe, maxDD))
            }
        }
        return out.sortedWith(compareBy({ it.lane }, { -it.netSol }))
    }

    /** Best profile per lane + verdict (what the lane SHOULD do). */
    fun bestPerLane(profiles: List<ExitProfile> = defaultProfiles()): Map<String, LaneResult> {
        val all = evaluateAll(profiles)
        return all.groupBy { it.lane }.mapValues { (_, rs) -> rs.maxByOrNull { it.netSol }!! }
    }

    fun renderReport(): String {
        val sb = StringBuilder()
        sb.appendLine("══════════════════════════════════════════════════")
        sb.appendLine("🧪 LANE STRATEGY REPLAY — V5.9.1285")
        sb.appendLine("  Honest replay on real peak/drawdown history.")
        sb.appendLine("  A lane whose best profile < NO_TRADE is a structural bleeder.")
        sb.appendLine("══════════════════════════════════════════════════")
        val all = evaluateAll()
        if (all.isEmpty()) { sb.appendLine("(no closed outcomes with peak data yet)"); return sb.toString() }
        val byLane = all.groupBy { it.lane }
        for ((lane, rs) in byLane) {
            val best = rs.maxByOrNull { it.netSol }!!
            val noTrade = rs.find { it.profile == "NO_TRADE" }
            val verdict = when {
                best.profile == "NO_TRADE" -> "⛔ STOP TRADING (no profile beats sitting out)"
                noTrade != null && best.netSol <= noTrade.netSol -> "⛔ STOP TRADING"
                best.profile == "CURRENT_ACTUAL" -> "✅ KEEP CURRENT (already optimal)"
                else -> "🔧 SWITCH → ${best.profile}"
            }
            sb.appendLine("─── $lane ─── $verdict")
            rs.sortedByDescending { it.netSol }.forEach { sb.appendLine("   ${it.oneLine()}") }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun logReport() {
        renderReport().lines().forEach { if (it.isNotBlank()) ErrorLogger.info(TAG, it) }
    }
}
