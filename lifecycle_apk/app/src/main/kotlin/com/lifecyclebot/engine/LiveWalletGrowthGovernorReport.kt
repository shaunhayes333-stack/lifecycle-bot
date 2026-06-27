package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** V5.0.4290 — report-only live wallet growth decomposition. Never fabricates PnL. */
object LiveWalletGrowthGovernorReport {
    data class DayStat(var buys: Int = 0, var sells: Int = 0, var openedSol: Double = 0.0, var closedProceedsSol: Double = 0.0, var realizedPnlSol: Double = 0.0, var feesSol: Double = 0.0, var quoteDragAbsPct: Double = 0.0, var compoundingReinvestedSol: Double = 0.0, var dustCleanupSol: Double = 0.0)
    private val days = ConcurrentHashMap<String, DayStat>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
    private const val MAX_DAYS = 21

    fun record(trade: Trade, isPaper: Boolean) {
        if (isPaper || trade.mode.equals("paper", true)) return
        val day = fmt.format(Date(trade.ts.takeIf { it > 0L } ?: System.currentTimeMillis()))
        val s = days.getOrPut(day) { DayStat() }
        synchronized(s) {
            when {
                trade.side.equals("BUY", true) -> {
                    s.buys++
                    s.openedSol += trade.sol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    s.compoundingReinvestedSol += trade.sol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                }
                trade.side.equals("SELL", true) || trade.side.equals("PARTIAL_SELL", true) -> {
                    s.sells++
                    val net = (trade.netPnlSol.takeIf { it.isFinite() && it != 0.0 } ?: trade.pnlSol).takeIf { it.isFinite() } ?: 0.0
                    s.realizedPnlSol += net
                    s.feesSol += trade.feeSol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    s.quoteDragAbsPct += kotlin.math.abs(trade.quoteDivergencePct.takeIf { it.isFinite() } ?: 0.0)
                    s.closedProceedsSol += (trade.sol + net).takeIf { it.isFinite() } ?: 0.0
                    if (trade.reason.contains("DUST", true) || trade.reason.contains("BURN", true)) s.dustCleanupSol += trade.sol.coerceAtLeast(0.0)
                }
            }
        }
        if (days.size > MAX_DAYS) days.keys.sorted().take(days.size - MAX_DAYS).forEach { days.remove(it) }
    }

    fun report(day: String = fmt.format(Date())): String {
        val s = days[day] ?: return "LIVE_WALLET_GROWTH_GOVERNOR_4290 day=$day no_live_events report_only=true"
        val idleOpenApprox = (s.openedSol - s.closedProceedsSol).coerceAtLeast(0.0)
        val avgSlip = if (s.sells <= 0) 0.0 else s.quoteDragAbsPct / s.sells.toDouble()
        return "LIVE_WALLET_GROWTH_GOVERNOR_4290 day=$day buys=${s.buys} sells=${s.sells} realizedNetSol=${s.realizedPnlSol.fmtLocal(6)} openedSol=${s.openedSol.fmtLocal(6)} closedProceedsSol=${s.closedProceedsSol.fmtLocal(6)} feesSol=${s.feesSol.fmtLocal(6)} avgQuoteDragPct=${avgSlip.fmtLocal(3)} dustCleanupSol=${s.dustCleanupSol.fmtLocal(6)} compoundingReinvestedSol=${s.compoundingReinvestedSol.fmtLocal(6)} idleOpenApproxSol=${idleOpenApprox.fmtLocal(6)} report_only=true no_phantom_pnl=true"
    }

    fun maybeEmit() { try { ForensicLogger.lifecycle("LIVE_WALLET_GROWTH_GOVERNOR_4290", report().take(900)); PipelineHealthCollector.labelInc("LIVE_WALLET_GROWTH_GOVERNOR_4290") } catch (_: Throwable) {} }
    fun exportState(): String = JSONArray().also { a -> days.entries.take(MAX_DAYS).forEach { (k, s) -> synchronized(s) { a.put(JSONObject().put("d", k).put("b", s.buys).put("se", s.sells).put("o", s.openedSol).put("cp", s.closedProceedsSol).put("p", s.realizedPnlSol).put("f", s.feesSol).put("q", s.quoteDragAbsPct).put("cr", s.compoundingReinvestedSol).put("du", s.dustCleanupSol)) } } }.toString()
    fun importState(raw: String?) { if (raw.isNullOrBlank()) return; try { val a = JSONArray(raw); days.clear(); for (i in 0 until a.length().coerceAtMost(MAX_DAYS)) { val o = a.optJSONObject(i) ?: continue; val d = o.optString("d"); if (d.isNotBlank()) days[d] = DayStat(o.optInt("b"), o.optInt("se"), o.optDouble("o"), o.optDouble("cp"), o.optDouble("p"), o.optDouble("f"), o.optDouble("q"), o.optDouble("cr"), o.optDouble("du")) } } catch (_: Throwable) {} }
    fun reset() { days.clear() }
}

private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "%.${decimals}f", this) } catch (_: Throwable) { this.toString() }
