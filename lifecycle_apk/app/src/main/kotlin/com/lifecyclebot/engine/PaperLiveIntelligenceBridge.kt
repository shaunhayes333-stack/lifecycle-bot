package com.lifecyclebot.engine

/**
 * V5.0.4262 — paper→live intelligence transfer, bounded and live-safe.
 *
 * Paper outcomes are useful for high-throughput learning, but they are not live PnL.
 * This bridge lets paper shape live size very softly only when live evidence is thin.
 * It never hard-blocks, never fabricates live stats, and fades out as live samples grow.
 */
object PaperLiveIntelligenceBridge {
    data class BridgeSignal(
        val lane: String,
        val paperTrades: Int,
        val liveTrades: Int,
        val paperWinRatePct: Double,
        val paperAvgPnlPct: Double,
        val multiplier: Double,
        val reason: String,
    )

    fun liveSizeMultiplier(lane: String, minPaperTrades: Int = 24, liveFadeTrades: Int = 40): BridgeSignal {
        val key = try { TradeHistoryStore.normalizeTradeModeName(lane).ifBlank { lane.uppercase() } } catch (_: Throwable) { lane.uppercase() }
        val rows = try { TradeHistoryStore.getRecentValidClosedForMode(key, 160) } catch (_: Throwable) { emptyList() }
        val paper = rows.filter { it.mode.equals("paper", ignoreCase = true) }.take(120)
        val live = rows.filter { it.mode.equals("live", ignoreCase = true) }.take(80)
        if (paper.size < minPaperTrades.coerceAtLeast(5)) return BridgeSignal(key, paper.size, live.size, -1.0, 0.0, 1.0, "insufficient_paper")
        val wins = paper.count { it.pnlPct >= 0.5 }
        val losses = paper.count { it.pnlPct <= -2.0 }
        val decisive = wins + losses
        if (decisive < minPaperTrades / 2) return BridgeSignal(key, paper.size, live.size, -1.0, 0.0, 1.0, "insufficient_decisive_paper")
        val wr = wins.toDouble() * 100.0 / decisive.toDouble()
        val avg = paper.map { it.pnlPct.coerceIn(-500.0, 5000.0) }.average().takeIf { it.isFinite() } ?: 0.0
        val liveFade = (1.0 - (live.size.toDouble() / liveFadeTrades.coerceAtLeast(1).toDouble())).coerceIn(0.0, 1.0)
        val edge = when {
            wr >= 58.0 && avg > 0.5 -> 1.08
            wr >= 52.0 && avg >= 0.0 -> 1.04
            wr <= 35.0 && avg < -1.0 -> 0.94
            wr <= 42.0 && avg < 0.0 -> 0.97
            else -> 1.0
        }
        val mult = (1.0 + ((edge - 1.0) * liveFade)).coerceIn(0.94, 1.08)
        return BridgeSignal(key, paper.size, live.size, wr, avg, mult, "paper_live_alignment_4262 fade=${fmt(liveFade)}")
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}
