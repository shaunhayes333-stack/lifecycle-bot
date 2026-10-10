package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
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
            flatline8027(bars.filter { nowMs - it.ts in 0L..FLAT_WINDOW_MS }.flatMap { listOf(it.priceUsd, it.highUsd, it.lowUsd) } + now) -> {
                flatRefused.incrementAndGet(); "FLATLINE_8027"
            }
            else -> return null
        }
        try {
            PipelineHealthCollector.labelInc("LIVE_BUY_REFUSED_$why")
            ForensicLogger.lifecycle("LIVE_BUY_REFUSED_$why", "mint=${ts.mint.take(10)} sym=${ts.symbol} now=$now peak=$peak ratio=${"%.1f".format(peak / now)} bars=${bars.size}")
        } catch (_: Throwable) {}
        return why
    }

    fun statusLine(): String = "deadRunnerRefused=${deadRefused.get()} flatlineRefused=${flatRefused.get()} bar=top>=${DEAD_RATIO.toInt()}x now | ${FLAT_MIN_BARS}+ bars in ${FLAT_WINDOW_MS / 60_000}m inside ${FLAT_RANGE_PCT}%"
}
