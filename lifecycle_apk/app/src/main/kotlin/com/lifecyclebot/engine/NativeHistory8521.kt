package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521
import com.lifecyclebot.engine.truth.TradeTape8100

/** Native momentum and bounce read observed prices in event order, never history insertion order. */
object NativeHistory8521 {
    private const val FRESH_MS = 120_000L
    private const val WINDOW_MS = 24L * 3_600_000L
    private const val PRINT_INTERVAL_MS = 15_000L
    private val minute = MultiTimeframeCharts8521.Frame8521.M1

    data class Read(
        val prices: List<Double>, val recentPrices: List<Double>, val high: Double?,
        val volumeRatio: Double?, val bouncePrices: List<Double>,
    ) {
        fun bounce8521(price: Double, buyPressure: Double): Boolean {
            if (bouncePrices.size < 4 || !price.isFinite() || price <= 0.0 ||
                !buyPressure.isFinite() || buyPressure !in 50.0..100.0 ||
                volumeRatio == null || !volumeRatio.isFinite() || volumeRatio < 0.5) return false
            val tail = bouncePrices.takeLast(8)
            val low = tail.minOrNull() ?: return false
            val at = tail.indexOf(low)
            return low > 0.0 && at < tail.lastIndex - 1 && price >= low * 1.02
        }
    }

    private data class Point(val atMs: Long, val price: Double, val high: Double, val shape: Boolean,
                             val pool: String, val quality: Int)

    fun read8521(ts: TokenState, nowMs: Long): Read {
        val bars = MultiTimeframeCharts8521.snapshot8521(ts, minute, nowMs)
        val prints = try { TradeTape8100.observedPrints8287(ts.mint, nowMs, 65L * 60_000L).orEmpty() }
            catch (_: Throwable) { emptyList() }
        val pool = ts.lastPricePoolAddr.ifBlank { ts.tokenMap.poolAddress.ifBlank { ts.pairAddress } }
        return prepare8521(bars, prints, SolUsd8418.liveOrNull(nowMs), nowMs, pool)
    }

    internal fun prepare8521(bars: List<Candle>, prints: List<TradeTape8100.Print8128>, solUsd: Double?,
                             nowMs: Long, expectedPool: String = ""): Read {
        val closed = bars.filter { MultiTimeframeCharts8521.valid8521(it, minute, nowMs) }
        val tape = prints.filter { it.atMs > 0L && nowMs - it.atMs in 0L..WINDOW_MS &&
            it.priceSol.isFinite() && it.priceSol > 0.0 }.sortedBy { it.atMs }
        val points = closed.map { c -> Point(c.ts + minute.intervalMs, c.priceUsd, c.highUsd, true,
            CandleCoverage8284.tagOf8284(c)?.pool.orEmpty(), CandleCoverage8284.qualityOf8284(c).rank) }.toMutableList()
        if (solUsd != null && solUsd.isFinite() && solUsd > 0.0) tape.forEach { p ->
            val price = p.priceSol * solUsd
            if (price.isFinite() && price > 0.0) points += Point(p.atMs, price, price, !p.priceOnly, p.pool, 6)
        }
        val pool = expectedPool.ifBlank { points.filter { it.pool.isNotBlank() }.maxByOrNull { it.atMs }?.pool.orEmpty() }
        fun sameVenue(venue: String): Boolean = pool.isBlank() || venue.isBlank() || venue == pool
        val ordered = points.filter { nowMs - it.atMs in 0L..WINDOW_MS && sameVenue(it.pool) }
            .sortedWith(compareBy<Point> { it.atMs }.thenByDescending { it.quality })
            .distinctBy { it.atMs }
        if (ordered.isEmpty() || nowMs - ordered.last().atMs !in 0L..FRESH_MS)
            return Read(emptyList(), emptyList(), null, null, emptyList())
        val volume = tapeVolume8521(tape.filter { sameVenue(it.pool) }, nowMs)
            ?: barVolume8521(closed.filter { sameVenue(CandleCoverage8284.tagOf8284(it)?.pool.orEmpty()) }, nowMs)
        // V5.0.8539 — the bounce witness reads executed prints / closed candles first; where the bot holds fewer than four of
        // them in five minutes (8538: GeckoTerminal served 26 of 1,868 asks, the PumpPortal tape denied) it reads the venue's
        // observed price points instead — price states shape, never volume (volumeRatio above still needs executed prints).
        // 8538 run: DIP_HUNTER WAIT_FOR_DIP_HISTORY_8535 x3,969, 0 qualified.
        val shape5m = ordered.filter { it.shape && nowMs - it.atMs <= 300_000L }.map { it.price }
        val bounce8539 = if (shape5m.size >= 4) shape5m else ordered.filter { nowMs - it.atMs <= 300_000L }.map { it.price }
        return Read(ordered.map { it.price }, ordered.filter { nowMs - it.atMs <= 300_000L }.map { it.price },
            ordered.maxOf { it.high }, volume, bounce8539)
    }

    private fun ratio8521(values: List<Double>): Double? {
        val tail = values.takeLast(8)
        if (tail.size < 4) return null
        val recent = tail.takeLast(3).average()
        val prior = tail.dropLast(3).average()
        return (recent / prior).takeIf { recent.isFinite() && prior.isFinite() && prior > 0.0 && it.isFinite() && it >= 0.0 }
    }

    private fun barVolume8521(bars: List<Candle>, nowMs: Long): Double? = bars
        .filter { it.periodVolumeUsd8521?.let { v -> v.isFinite() && v >= 0.0 } == true }
        .groupBy { CandleCoverage8284.tagOf8284(it)?.let { tag -> tag.source to tag.pool } ?: ("observed" to "") }
        .values.sortedByDescending { group -> group.maxOf { it.ts } }.firstNotNullOfOrNull { group ->
            val ordered = group.distinctBy { it.ts }.sortedBy { it.ts }.takeLast(8)
            if (nowMs - (ordered.last().ts + minute.intervalMs) !in 0L..FRESH_MS) null
            else {
                val afterGap = (1 until ordered.size).lastOrNull {
                    ordered[it].ts - ordered[it - 1].ts != minute.intervalMs
                } ?: 0
                ratio8521(ordered.drop(afterGap).map { it.periodVolumeUsd8521!! })
            }
        }

    /** Equal 15-second periods from executed trades; price-only marks carry no volume.
     * This does not require a completed minute or any higher-timeframe candle.
     */
    private fun tapeVolume8521(prints: List<TradeTape8100.Print8128>, nowMs: Long): Double? = prints
        .filter { !it.priceOnly && it.sol.isFinite() && it.sol > 0.0 }
        .groupBy { it.source to it.pool }.values.sortedByDescending { it.maxOf { p -> p.atMs } }
        .firstNotNullOfOrNull { source ->
            val periods = source.groupBy { it.atMs / PRINT_INTERVAL_MS * PRINT_INTERVAL_MS }
                .filterKeys { it + PRINT_INTERVAL_MS <= nowMs }.toSortedMap()
            val last = periods.lastKeyOrNull8521() ?: return@firstNotNullOfOrNull null
            if (nowMs - (last + PRINT_INTERVAL_MS) !in 0L..FRESH_MS) null
            else {
                val ordered = periods.entries.toList().takeLast(8)
                val afterGap = (1 until ordered.size).lastOrNull {
                    ordered[it].key - ordered[it - 1].key != PRINT_INTERVAL_MS
                } ?: 0
                // Missing feed intervals are neither observed zero volume nor adjacent periods.
                ratio8521(ordered.drop(afterGap).map { it.value.sumOf { print -> print.sol } })
            }
        }

    private fun java.util.SortedMap<Long, List<TradeTape8100.Print8128>>.lastKeyOrNull8521(): Long? =
        if (isEmpty()) null else lastKey()
}
