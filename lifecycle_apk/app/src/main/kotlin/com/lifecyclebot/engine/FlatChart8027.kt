package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Candle
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8027 — never buy a dead chart.
 *
 * Owner, 5.0.8026 (screenshots, 03:31-03:34): "90% of these have been bought when the token is completely
 * flat." ƙөƙ ran to $0.000063 at 02:52 and was bought at 03:15 at $0.0000034 (-95% off its top); Put-in topped
 * near $0.0000425 at 21:45 and was bought at 02:57 flat at the floor; eCash spiked at 02:15 and was bought
 * flat at 02:56; MINEPAD printed three candles and went flatline before the buy. No setup pays on a chart
 * nobody is trading.
 *
 * Hard check at the executor (every live buy, every lane, every ticket), before any lease or quote:
 *  DEAD_RUNNER — the coin's own recorded top is [DEAD_RATIO]x or more above the price now (it already ran
 *                and is down 80%+ from it).
 *  FLATLINE    — the last [FLAT_WINDOW_MS] holds [FLAT_MIN_BARS]+ real candles and every print in it sits
 *                inside a [FLAT_RANGE_PCT]% band (nothing is trading the coin).
 * A brand-new coin with too few candles is not judged flat; a coin with no history is not judged at all.
 */
object FlatChart8027 {
    const val DEAD_RATIO = 5.0
    const val FLAT_RANGE_PCT = 2.0
    const val FLAT_MIN_BARS = 5
    const val FLAT_WINDOW_MS = 6L * 60_000L
    private val deadRefused = AtomicLong(0)
    private val flatRefused = AtomicLong(0)

    /** Pure: the coin already ran and is down to 1/[DEAD_RATIO] (or less) of its top. */
    fun deadRunner8027(peakPrice: Double, nowPrice: Double): Boolean =
        peakPrice.isFinite() && nowPrice.isFinite() && nowPrice > 0.0 && peakPrice / nowPrice >= DEAD_RATIO

    /** Pure: [prices] (the window's prints) are enough and all inside the flat band. */
    fun flatline8027(prices: List<Double>): Boolean {
        val p = prices.filter { it.isFinite() && it > 0.0 }
        if (p.size < FLAT_MIN_BARS) return false
        val lo = p.min()
        return (p.max() / lo - 1.0) * 100.0 < FLAT_RANGE_PCT
    }

    /** Count distinct candles, then test their full range and the executable current price. */
    fun flatCandles8076(bars: List<Candle>, nowPrice: Double, nowMs: Long): Boolean {
        if (!nowPrice.isFinite() || nowPrice <= 0.0) return false
        val recent = bars.filter { !it.synthetic && nowMs - it.ts in 0L..FLAT_WINDOW_MS &&
            it.priceUsd.isFinite() && it.priceUsd > 0.0 }
        if (recent.map { it.ts }.distinct().size < FLAT_MIN_BARS) return false
        val prices = recent.flatMap { listOf(it.priceUsd, it.highUsd, it.lowUsd, it.openUsd) } + nowPrice
        return flatline8027(prices)
    }

    /** Executor.liveBuy: the refusal label when [ts]'s chart is dead or flat, else null. */
    fun refusal8027(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        val now = ts.lastPrice
        if (!(now > 0.0)) return null
        val bars = try { synchronized(ts.history) { ts.history.filter { !it.synthetic }.toList() } } catch (_: Throwable) { return null }
        if (bars.isEmpty()) return null
        // The second-highest bar is the top: one rogue print cannot call a coin dead.
        val peak = bars.map { maxOf(it.highUsd, it.priceUsd) }.sortedDescending().let { if (it.size >= 2) it[1] else it[0] }
        val why = when {
            deadRunner8027(peak, now) -> { deadRefused.incrementAndGet(); "DEAD_RUNNER_8027" }
            flatCandles8076(bars, now, nowMs) -> {
                flatRefused.incrementAndGet(); "FLATLINE_8027"
            }
            // V5.0.8036 — USDP (CASHGEN, entry "$204M"): a market cap climbing $140M -> $250M in a straight line
            // with no pullback, on depth nowhere near that size, then rugged to -99.99%. Organic charts breathe.
            manufacturedClimb8036(bars.filter { nowMs - it.ts in 0L..CLIMB_WINDOW_MS_8036 }.sortedBy { it.ts }.map { it.priceUsd }) -> {
                climbRefused.incrementAndGet(); "MANUFACTURED_CLIMB_8036"
            }
            thinDepthForCap8036(try { com.lifecyclebot.engine.truth.TrustedMcap8019.mcap8019(ts) } catch (_: Throwable) { ts.lastMcap }, try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }) -> {
                thinRefused.incrementAndGet(); "THIN_DEPTH_FOR_CAP_8036"
            }
            else -> return null
        }
        try {
            PipelineHealthCollector.labelInc("LIVE_BUY_REFUSED_$why")
            ForensicLogger.lifecycle("LIVE_BUY_REFUSED_$why", "mint=${ts.mint.take(10)} sym=${ts.symbol} now=$now peak=$peak ratio=${"%.1f".format(peak / now)} bars=${bars.size}")
        } catch (_: Throwable) {}
        return why
    }

    /**
     * V5.0.8304 — the same DEAD_RUNNER / FLATLINE verdict as [refusal8027], pure (no counters, no log), so TradeAuthorizer
     * can refuse a dead or flat chart before it seals a ticket the executor would refuse a moment later.
     */
    fun preSealRefusal8304(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        val now = ts.lastPrice
        if (!(now > 0.0)) return null
        val bars = try { synchronized(ts.history) { ts.history.filter { !it.synthetic }.toList() } } catch (_: Throwable) { return null }
        if (bars.isEmpty()) return null
        val peak = bars.map { maxOf(it.highUsd, it.priceUsd) }.sortedDescending().let { if (it.size >= 2) it[1] else it[0] }
        return when {
            deadRunner8027(peak, now) -> "DEAD_RUNNER_8027"
            flatCandles8076(bars, now, nowMs) -> "FLATLINE_8027"
            else -> null
        }
    }

    // ── V5.0.8036 — fake charts ──
    private const val CLIMB_WINDOW_MS_8036 = 60L * 60_000L
    const val CLIMB_MIN_BARS_8036 = 20
    const val CLIMB_MIN_RISE_PCT_8036 = 30.0
    const val CLIMB_MAX_DOWN_SHARE_8036 = 0.10
    const val MAX_CAP_TO_DEPTH_8036 = 200.0
    private val climbRefused = AtomicLong(0)
    private val thinRefused = AtomicLong(0)

    /** Pure: [closes] (oldest first, the last hour) rise 30%+ with almost no down bars — a laddered, wash-built climb. */
    fun manufacturedClimb8036(closes: List<Double>): Boolean {
        val c = closes.filter { it.isFinite() && it > 0.0 }
        if (c.size < CLIMB_MIN_BARS_8036) return false
        val rise = (c.last() / c.first() - 1.0) * 100.0
        if (rise < CLIMB_MIN_RISE_PCT_8036) return false
        val downs = c.zipWithNext().count { (a, b) -> b < a * 0.995 }
        return downs.toDouble() / (c.size - 1) <= CLIMB_MAX_DOWN_SHARE_8036
    }

    /** Pure: a market cap 200x+ its pool depth is a number nobody can sell into. Unknown depth (0) is not judged. */
    fun thinDepthForCap8036(capUsd: Double, liqUsd: Double): Boolean =
        capUsd.isFinite() && liqUsd.isFinite() && capUsd > 0.0 && liqUsd > 0.0 && capUsd / liqUsd > MAX_CAP_TO_DEPTH_8036

    fun statusLine(): String = "deadRunnerRefused=${deadRefused.get()} flatlineRefused=${flatRefused.get()} manufacturedClimb=${climbRefused.get()} thinDepth=${thinRefused.get()} bar=top>=${DEAD_RATIO.toInt()}x now | ${FLAT_MIN_BARS}+ bars in ${FLAT_WINDOW_MS / 60_000}m inside ${FLAT_RANGE_PCT}%"
}
