package com.lifecyclebot.engine.chart

import com.lifecyclebot.engine.truth.MarkBars7948
import com.lifecyclebot.engine.truth.PriceShapeBars8287
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
 * V5.0.8317: the minimum follows the coin's age ([minMinutes8317]): a launch-window coin needs the three minutes it
 * can have, an older or unknown-age coin the ten of today. Still no gap fill, still one source.
 */
object LocalTapeBars8128 {
    private const val MIN_MINUTES_8128 = 10
    private const val BUCKET_MS_8128 = 60_000L

    /** Pure (V5.0.8317): the traded / marked minutes a coin [ageMin] old must show — three inside the launch window, else ten. */
    fun minMinutes8317(ageMin: Double): Int = PriceShapeBars8287.ageAwareMin8317(ageMin, MIN_MINUTES_8128)

    /**
     * Pure: executed prints -> one-minute bars in USD (price x [solUsd]), USD volume and its buy share.
     * Null with no SOL price or under [minMinutes] (default [MIN_MINUTES_8128]) traded minutes.
     */
    fun fromPrints8128(prints: List<TradeTape8100.Print8128>, solUsd: Double, minMinutes: Int = MIN_MINUTES_8128): List<Bar7950>? {
        if (!solUsd.isFinite() || solUsd <= 0.0) return null
        val need = minMinutes.coerceAtLeast(1)
        val byMinute = prints.filter { it.atMs > 0L && it.priceSol.isFinite() && it.priceSol > 0.0 }
            .sortedBy { it.atMs }
            .groupBy { it.atMs / BUCKET_MS_8128 }
            .toSortedMap()
        if (byMinute.size < need) return null
        val bars = byMinute.mapNotNull { (m, ps) ->
            val px = ps.map { it.priceSol * solUsd }
            val hi = px.maxOrNull() ?: return@mapNotNull null
            val lo = px.minOrNull() ?: return@mapNotNull null
            val vol = ps.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 } * solUsd
            val buy = ps.filter { it.isBuy }.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 } * solUsd
            ChartParsers7955.bar7955(m * BUCKET_MS_8128, px.first(), hi, lo, px.last(), vol, buy)
        }
        return bars.takeIf { it.size >= need }
    }

    /** Pure: the mark tape's one-minute bars as chart bars (prices only, zero volume); null under [minMinutes]. */
    fun fromMarks8128(marks: List<TradePlan7739.Bar>, minMinutes: Int = MIN_MINUTES_8128): List<Bar7950>? {
        val bars = marks.mapNotNull { ChartParsers7955.bar7955(it.startMs, it.open, it.high, it.low, it.close, 0.0) }
        return bars.takeIf { it.size >= minMinutes.coerceAtLeast(1) }
    }

    /**
     * ChartSources7955: the executed-trade tape first, else the mark tape (one source, never mixed); null when neither is
     * long enough for a coin [ageMin] old (V5.0.8317: resolved from its canonical birth when the caller does not pass it).
     */
    fun bars8128(mint: String, nowMs: Long, windowMs: Long, solUsd: Double, ageMin: Double = PriceShapeBars8287.ageMin8287(mint, nowMs)): List<Bar7950>? {
        val need = minMinutes8317(ageMin)
        return fromPrints8128(TradeTape8100.prints8128(mint, nowMs, windowMs), solUsd, need)
            ?: fromMarks8128(MarkBars7948.bars7948(mint, nowMs, windowMs), need)
    }
}
