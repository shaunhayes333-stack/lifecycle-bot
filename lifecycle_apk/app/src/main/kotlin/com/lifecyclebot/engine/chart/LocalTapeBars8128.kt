package com.lifecyclebot.engine.chart

import com.lifecyclebot.engine.truth.MarkBars7948
import com.lifecyclebot.engine.truth.TradePlan7739
import com.lifecyclebot.engine.truth.TradeTape8100

/**
 * V5.0.8128 — the chart backfill's keyless local rung.
 *
 * Audit: after the keyless OHLCV feed (GeckoTerminal / DexPaprika, mostly rate-limited or 403) every rung of
 * ChartSources7955's backfill needs a key (Moralis, SolanaTracker, Codex, Bitquery), so on a keyless device a
 * short tape stayed unread. The bot already holds one-minute history of its own: the PumpPortal executed-trade
 * tape (TradeTape8100, 65 min, with SOL size and side) and the mark tape (MarkBars7948, 35 min, prices only).
 * This builds bars from ONE of them — the trade tape first, it carries volume — never a mix, never a network
 * call, and only when at least [MIN_MINUTES_8128] distinct minutes really traded / were marked (no gap fill).
 */
object LocalTapeBars8128 {
    private const val MIN_MINUTES_8128 = 10
    private const val BUCKET_MS_8128 = 60_000L

    /**
     * Pure: executed prints -> one-minute bars in USD (price x [solUsd]), USD volume and its buy share.
     * Null with no SOL price or under [MIN_MINUTES_8128] traded minutes.
     */
    fun fromPrints8128(prints: List<TradeTape8100.Print8128>, solUsd: Double): List<Bar7950>? {
        if (!solUsd.isFinite() || solUsd <= 0.0) return null
        val byMinute = prints.filter { it.atMs > 0L && it.priceSol.isFinite() && it.priceSol > 0.0 }
            .sortedBy { it.atMs }
            .groupBy { it.atMs / BUCKET_MS_8128 }
            .toSortedMap()
        if (byMinute.size < MIN_MINUTES_8128) return null
        val bars = byMinute.mapNotNull { (m, ps) ->
            val px = ps.map { it.priceSol * solUsd }
            val hi = px.maxOrNull() ?: return@mapNotNull null
            val lo = px.minOrNull() ?: return@mapNotNull null
            val vol = ps.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 } * solUsd
            val buy = ps.filter { it.isBuy }.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 } * solUsd
            ChartParsers7955.bar7955(m * BUCKET_MS_8128, px.first(), hi, lo, px.last(), vol, buy)
        }
        return bars.takeIf { it.size >= MIN_MINUTES_8128 }
    }

    /** Pure: the mark tape's one-minute bars as chart bars (prices only, zero volume); null under [MIN_MINUTES_8128]. */
    fun fromMarks8128(marks: List<TradePlan7739.Bar>): List<Bar7950>? {
        val bars = marks.mapNotNull { ChartParsers7955.bar7955(it.startMs, it.open, it.high, it.low, it.close, 0.0) }
        return bars.takeIf { it.size >= MIN_MINUTES_8128 }
    }

    /** ChartSources7955: the executed-trade tape first, else the mark tape (one source, never mixed); null when neither is long enough. */
    fun bars8128(mint: String, nowMs: Long, windowMs: Long, solUsd: Double): List<Bar7950>? =
        fromPrints8128(TradeTape8100.prints8128(mint, nowMs, windowMs), solUsd)
            ?: fromMarks8128(MarkBars7948.bars7948(mint, nowMs, windowMs))
}
