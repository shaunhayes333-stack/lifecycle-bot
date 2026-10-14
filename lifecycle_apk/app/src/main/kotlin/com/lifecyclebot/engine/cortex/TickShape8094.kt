package com.lifecyclebot.engine.cortex

/**
 * V5.0.8094 — price shape and recent change from the prices the bot already
 * observed for a token (MarkBars7948 ticks: DexScreener polls, pump-curve reads,
 * Jupiter, launch events), for a token whose minute history is too short.
 *
 * 5.0.8093: SETUP_DATA_INCOMPLETE_8084=14,473 and Cortex STRONG n=0 on most
 * lanes. A fresh launch's history gains one synthesised minute bar per closed
 * minute, so the 3-point shape the classifier requires took 3+ minutes, and the
 * Helius swap-candle feed that filled it ran out of credit. These are real
 * observed prices, not estimates; nothing here invents volume or buy pressure.
 */
object TickShape8094 {
    data class Shape(val runupPct: Double, val pxPeak: Double, val ddPct: Double, val chg5mPct: Double, val chgAllPct: Double, val spanMs: Long)

    private const val MIN_SPAN_MS = 10_000L
    private const val MAX_AGE_MS = 20L * 60_000L
    private const val CHG_MIN_SPAN_MS = 60_000L

    /** Pure: the shape of [ticks] (oldest first) at [nowMs], or null when they cannot vouch for one. */
    fun of(ticks: List<Pair<Long, Double>>, nowMs: Long): Shape? {
        if (ticks.size < 3) return null
        for (i in ticks.indices) {
            val (t, p) = ticks[i]
            if (t <= 0L || !p.isFinite() || p <= 0.0) return null
            if (i > 0) {
                val (pt, pp) = ticks[i - 1]
                if (t <= pt) return null
                val r = p / pp
                if (!r.isFinite() || r > 1_000.0 || r < 0.001) return null
            }
        }
        val first = ticks.first(); val last = ticks.last()
        val span = last.first - first.first
        if (span < MIN_SPAN_MS) return null
        val age = nowMs - last.first
        if (age < -60_000L || age > MAX_AGE_MS) return null
        val cur = last.second
        val hi = ticks.maxOf { it.second }
        val lo = ticks.minOf { it.second }
        val base5m = ticks.firstOrNull { it.first >= last.first - 5L * 60_000L } ?: first
        val chg5m = if (span >= CHG_MIN_SPAN_MS) (cur / base5m.second - 1.0) * 100.0 else Double.NaN
        return Shape(
            runupPct = (cur / lo - 1.0) * 100.0,
            pxPeak = cur / hi,
            ddPct = (1.0 - cur / hi) * 100.0,
            chg5mPct = chg5m,
            chgAllPct = if (span >= CHG_MIN_SPAN_MS) (cur / first.second - 1.0) * 100.0 else Double.NaN,
            spanMs = span,
        )
    }
}
