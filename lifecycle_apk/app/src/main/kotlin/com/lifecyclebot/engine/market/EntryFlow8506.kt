package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.TradeTape8100

/** One recently observed market window. Keep the move, sided counts and volume from the same source. */
data class EntryFlow8506(
    val atMs: Long, val buys: Int, val sells: Int, val volume: Double, val move: Double,
) {
    val buyPct: Double get() = if (buys.toLong() + sells > 0) 100.0 * buys / (buys.toLong() + sells) else Double.NaN
    val active: Boolean get() = buys > 0 && volume > 0.0
    fun usable(nowMs: Long): Boolean = atMs > 0L && nowMs - atMs in 0L..120_000L &&
        buys >= 0 && sells >= 0 && volume.isFinite() && volume >= 0.0 && move.isFinite()

    companion object {
        fun provider(mint: String, nowMs: Long): EntryFlow8506? {
            val p = PairFlow8169.peek8169(mint)
            val pair = p?.let { EntryFlow8506(it.atMs, it.buysM5, it.sellsM5, it.volumeM5, it.chg5m) }
                ?.takeIf { it.usable(nowMs) }
            val sweep = MarketSweep7297.rowFor8169(mint, nowMs)?.flow5m?.takeIf { it.usable(nowMs) }
            val best = if (sweep != null && (pair == null || sweep.atMs > pair.atMs)) sweep else pair
            val poll = MarketSweep7297.latestFlow8506(mint, nowMs)
            return if (poll != null && (best == null || poll.atMs > best.atMs)) poll else best
        }

        fun forMint(mint: String, nowMs: Long): EntryFlow8506? {
            val cached = provider(mint, nowMs)
            if (cached != null && nowMs - cached.atMs <= 2_000L) return cached
            return TradeTape8100.entryFlow8506(mint, nowMs, cached?.atMs ?: 0L) ?: cached
        }
    }
}
