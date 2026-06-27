package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** V5.0.4271 — read-only live/paper parity sentinel. Never blocks or sizes trades. */
object LivePaperDriftSentinel {
    private const val WINDOW = 80
    private const val MIN_PAPER = 12
    private const val MIN_LIVE = 5
    private const val DEBOUNCE_MS = 5 * 60_000L
    private val lastEmitByLane = ConcurrentHashMap<String, Long>()

    data class Shape(
        val count: Int,
        val wins: Int,
        val losses: Int,
        val wr: Double,
        val avgSizeSol: Double,
        val avgHoldMs: Double,
        val avgPnlPct: Double,
    )

    fun onTerminalClose(trade: Trade) {
        try {
            if (!trade.side.equals("SELL", ignoreCase = true)) return
            val lane = TradeHistoryStore.normalizeTradeModeName(trade.tradingMode).ifBlank { "UNKNOWN" }
            val now = System.currentTimeMillis()
            val last = lastEmitByLane[lane] ?: 0L
            if (now - last < DEBOUNCE_MS) return
            val sells = TradeHistoryStore.getAllSells().asReversed()
                .asSequence()
                .filter { TradeHistoryStore.normalizeTradeModeName(it.tradingMode) == lane }
                .take(WINDOW * 3)
                .toList()
            val paper = shape(sells.filter { it.mode.equals("paper", true) }.take(WINDOW))
            val live = shape(sells.filter { it.mode.equals("live", true) }.take(WINDOW))
            if (paper.count < MIN_PAPER || live.count < MIN_LIVE) return
            val wrGap = abs(paper.wr - live.wr)
            val sizeRatio = if (paper.avgSizeSol > 0.0 && live.avgSizeSol > 0.0) live.avgSizeSol / paper.avgSizeSol else 1.0
            val holdRatio = if (paper.avgHoldMs > 1.0 && live.avgHoldMs > 1.0) live.avgHoldMs / paper.avgHoldMs else 1.0
            val pnlSignDrift = paper.avgPnlPct > 3.0 && live.avgPnlPct < -3.0
            val drift = wrGap >= 25.0 || sizeRatio >= 3.0 || sizeRatio <= 0.33 || holdRatio >= 3.0 || holdRatio <= 0.33 || pnlSignDrift
            if (!drift) return
            lastEmitByLane[lane] = now
            try {
                PipelineHealthCollector.labelInc("LIVE_PAPER_DRIFT_SENTINEL_4271")
                PipelineHealthCollector.labelInc("LIVE_PAPER_DRIFT_SENTINEL_4271_${lane}")
                ForensicLogger.lifecycle(
                    "LIVE_PAPER_DRIFT_SENTINEL_4271",
                    "lane=$lane paperN=${paper.count} liveN=${live.count} paperWR=${paper.wr.fmt(1)} liveWR=${live.wr.fmt(1)} wrGap=${wrGap.fmt(1)} sizeRatio=${sizeRatio.fmt(2)} holdRatio=${holdRatio.fmt(2)} paperPnl=${paper.avgPnlPct.fmt(1)} livePnl=${live.avgPnlPct.fmt(1)} action=report_only_no_pause_no_size_change",
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun shape(rows: List<Trade>): Shape {
        val wins = rows.count { it.pnlPct > 0.0 || it.netPnlSol > 0.0 || it.pnlSol > 0.0 }
        val losses = rows.count { it.pnlPct < 0.0 || it.netPnlSol < 0.0 || it.pnlSol < 0.0 }
        val decisive = wins + losses
        val avgSize = rows.map { it.sol }.filter { it > 0.0 }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgHold = rows.map { if (it.entryTsMs > 0L) (it.ts - it.entryTsMs).coerceAtLeast(0L).toDouble() else 0.0 }.filter { it > 0.0 }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgPnl = rows.map { it.pnlPct }.filter { it != 0.0 }.average().takeIf { !it.isNaN() } ?: 0.0
        return Shape(rows.size, wins, losses, if (decisive > 0) wins * 100.0 / decisive else 0.0, avgSize, avgHold, avgPnl)
    }

    private fun Double.fmt(d: Int): String = try { "% .${d}f".replace(" ", "").format(this) } catch (_: Throwable) { this.toString() }
}
