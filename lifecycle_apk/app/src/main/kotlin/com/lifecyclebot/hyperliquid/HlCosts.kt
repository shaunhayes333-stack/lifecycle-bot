package com.lifecyclebot.hyperliquid

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8797 — what it really costs to trade each coin, learned, not assumed:
 *  - the exchange's taker fee: [FEE_MAIN] on Hyperliquid's own perps, [FEE_HIP3] (twice the base) on HIP-3 builder markets;
 *  - the book: half the spread plus the depth walked to fill the desk's own order size, read from the live order book
 *    every hour and averaged;
 *  - the fills: each live fill's price against the mark the order was priced from, averaged once there are three.
 * The replay charges each coin its own cost per unit of weight traded, so an expensive coin has to earn its keep.
 */
object HlCosts {
    const val FEE_MAIN = 0.00045
    const val FEE_HIP3 = 0.0009
    const val UNMEASURED_SLIP = 0.0005
    private const val ALPHA = 0.2
    private const val MIN_FILLS = 3

    class Est(var book: Double = Double.NaN, var books: Int = 0, var fill: Double = Double.NaN, var fills: Int = 0)
    private val est = ConcurrentHashMap<String, Est>()

    fun feeOf(coin: String): Double = if (coin.contains(':')) FEE_HIP3 else FEE_MAIN

    /** Pure: the cost, as a fraction of the mid, of buying (or selling) [sizeUsd] against [levels] (best first). */
    fun walk(levels: List<Pair<Double, Double>>, mid: Double, sizeUsd: Double, buy: Boolean): Double {
        if (!(mid > 0.0) || !(sizeUsd > 0.0) || levels.isEmpty()) return Double.NaN
        var left = sizeUsd; var units = 0.0; var paid = 0.0
        for ((px, sz) in levels) {
            val take = minOf(left, px * sz)
            units += take / px; paid += take; left -= take
            if (left <= 1e-9) break
        }
        if (left > 1e-9 || units <= 0.0) return Double.NaN            // the book is thinner than the order
        val avg = paid / units
        return if (buy) avg / mid - 1.0 else 1.0 - avg / mid
    }

    /** Pure: one side's cost from a book for an order of [sizeUsd]: the mean of buying and selling it. */
    fun bookSide(b: HlApi.Book, sizeUsd: Double): Double {
        val bid = b.bids.firstOrNull()?.first ?: return Double.NaN
        val ask = b.asks.firstOrNull()?.first ?: return Double.NaN
        val mid = (bid + ask) / 2.0
        val buy = walk(b.asks, mid, sizeUsd, true); val sell = walk(b.bids, mid, sizeUsd, false)
        if (!buy.isFinite() || !sell.isFinite()) return Double.NaN
        return maxOf(0.0, (buy + sell) / 2.0)
    }

    private fun ema(old: Double, x: Double): Double = if (old.isFinite()) old + ALPHA * (x - old) else x

    fun observeBook(coin: String, b: HlApi.Book, sizeUsd: Double) {
        val x = bookSide(b, sizeUsd)
        if (!x.isFinite()) return
        val e = est.getOrPut(coin) { Est() }
        synchronized(e) { e.book = ema(e.book, x); e.books++ }
    }

    /** A live fill at [avgPx] for an order priced from [refPx]; adverse slippage is positive. */
    fun observeFill(coin: String, isBuy: Boolean, refPx: Double, avgPx: Double) {
        if (!(refPx > 0.0) || !(avgPx > 0.0)) return
        val slip = if (isBuy) avgPx / refPx - 1.0 else 1.0 - avgPx / refPx
        val e = est.getOrPut(coin) { Est() }
        synchronized(e) { e.fill = ema(e.fill, slip); e.fills++ }
    }

    /** Pure: the slippage part of the cost from what has been measured. */
    fun slipOf(e: Est?): Double {
        if (e == null) return UNMEASURED_SLIP
        val b = if (e.books > 0 && e.book.isFinite()) e.book else Double.NaN
        val f = if (e.fills >= MIN_FILLS && e.fill.isFinite()) maxOf(0.0, e.fill) else Double.NaN
        return when {
            b.isFinite() && f.isFinite() -> (b + f) / 2.0
            b.isFinite() -> b
            f.isFinite() -> f
            else -> UNMEASURED_SLIP
        }
    }

    /** The cost of trading one unit of weight in [coin] once. */
    fun costSide(coin: String): Double = feeOf(coin) + slipOf(est[coin])

    fun line(coin: String): String {
        val e = est[coin]
        return "$coin ${"%.3f".format(costSide(coin) * 100)}%" + (e?.let { "(b${it.books} f${it.fills})" } ?: "(unmeasured)")
    }

    fun toJson(): JSONObject = JSONObject().also { o ->
        for ((k, e) in est) synchronized(e) {
            o.put(k, JSONObject().put("book", if (e.book.isFinite()) e.book else -1.0).put("books", e.books)
                .put("fill", if (e.fill.isFinite()) e.fill else -9.0).put("fills", e.fills))
        }
    }

    fun restore(o: JSONObject?) {
        if (o == null) return
        for (k in o.keys()) {
            val j = o.optJSONObject(k) ?: continue
            est[k] = Est(j.optDouble("book", -1.0).let { if (it >= 0.0) it else Double.NaN }, j.optInt("books", 0),
                j.optDouble("fill", -9.0).let { if (it > -1.0) it else Double.NaN }, j.optInt("fills", 0))
        }
    }
}
