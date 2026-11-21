package com.lifecyclebot.perps

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8910 §THE_MAJORS_PLAYBOOK — owner, 21 Nov: "the market is down big today. there should be calculated buying
 * opportunities on majors to capture reversals surely", "if thats not built then we are missing fuck loads of important
 * strategies, thinking, trading and logic calls ... you need to go further and wider with this", then "all 5 now please one
 * build ... the crypto trader should be seeing alot better trades same as the meme trader".
 *
 * What was there: the static majors scan (CryptoAltTrader.runScanCycle -> analyzeAlt) reads a 24 h change, an RSI over its own
 * ticks and a 24 h high / low that every producer synthesises from the change (PerpsMarketData.rangeObserved8535 = false), and
 * funding / open interest that every producer returns as 0.0 (fundingObserved8535 = false). Its momentum gate takes a long
 * only on a rising day or an oversold RSI with the day already up, so on a down day no major is ever a candidate: the setups
 * that pay on a down day — the flush and reclaim, the crowded short, the range low, the retest — could not be seen at all.
 *
 * This file is the playbook, all pure, over the majors' real hourly tape (MajorTape8910: exchange candles with volume and
 * taker-buy volume, and perpetual funding / open interest where an exchange answers):
 *  1. [capitulation8910] — a flush (an hourly candle down >= 1.5 % on >= 2x the day's median volume, the day >= 3 % off its
 *     high), then a higher low, a reclaim of the last hour's high or of the day's volume-weighted price, buyers leading the last
 *     hour. Stop under the flush low; target the breakdown level (the open of the leg that flushed), capped at the day's high.
 *  2. [btcRegime8910] / [rsRotation8910] — BTC leads: UP / DOWN / RECLAIM / FLAT from its own tape; with BTC UP or RECLAIMING,
 *     the majors leading BTC over the day (top [RS_TOP_8910] by relative strength, >= +2 % ahead), green last hour, buyers
 *     leading, above the day's VWAP. Stop under the last four hours' low; target 2R.
 *  3. [fundingFlush8910] — the same flush and reclaim, when shorts are crowded (funding <= -0.01 % per 8 h) or the flush
 *     cleared leverage (open interest down >= 4 % over 4 h): the liquidation-cascade reversal. Same stop and target.
 *  4. [rangeLow8910] / [breakoutRetest8910] — a 48 h range at least 4 % wide, price in its bottom quarter on a higher low with
 *     a green, buyer-led last hour (stop under the range low, target the range middle); a close above the prior 48 h high in the
 *     last six hours, pulled back to within 0.5 % of it and holding (stop under the level, target 2R).
 *  5. [session8910] — the UTC session of the decision (ASIA / EU / US / LATE), and BTC's regime, ride every crypto close so the
 *     twin grades each strategy by session and by regime (CryptoTwin8883).
 *
 * Each setup is a named strategy (STRATEGY7803 = MAJ8910_<NAME>) with its own stop, target and hold, handed to the canonical
 * contract like every other crypto candidate. Nothing here admits anything: in LIVE a candidate the contract refuses on a
 * learned door opens the crypto twin with these levels, the twin files each close under lane|S=<strategy> (and @session,
 * @regime), and a strategy backs a live entry only once its own twin record backs (CryptoSpotPaperBacking8788, the same rule
 * and family-deflated margin as the trend cells); a strategy proven losing withdraws. Every setup needs its target at least
 * twice the round trip away and a reward at least [MIN_RR_8910] times the risk. A tape that is missing or stale fires nothing.
 */
object MajorSetups8910 {
    private const val PREFIX_8910 = "MAJ8910_"
    const val SESSION_TAG_8910 = "SESSION8910="
    const val REGIME_TAG_8910 = "BTCREG8910="
    const val SETUP_TAG_8910 = "SETUP8910="
    internal const val COST_PCT_8910 = 1.0          // round trip assumed for a wrapped major on Jupiter (conservative)
    internal const val MIN_RR_8910 = 1.2
    internal const val RS_TOP_8910 = 3
    internal const val FLUSH_RET_PCT_8910 = -1.5
    internal const val FLUSH_VOL_MULT_8910 = 2.0
    internal const val FLUSH_DRAWDOWN_PCT_8910 = 3.0
    internal const val FUNDING_CROWDED_8910 = -0.0001  // -0.01 % per 8 h, as a fraction
    internal const val OI_FLUSH_PCT_8910 = -4.0

    /** One closed hourly candle: open time, OHLC, base volume, taker-buy base volume (NaN when the exchange gave none). */
    data class Candle8910(val openMs: Long, val open: Double, val high: Double, val low: Double, val close: Double,
                          val volume: Double, val takerBuy: Double) {
        val retPct: Double get() = if (open > 0.0) (close / open - 1.0) * 100.0 else Double.NaN
        val buyShare: Double get() = if (volume > 0.0 && takerBuy.isFinite()) takerBuy / volume else Double.NaN
        val green: Boolean get() = close > open
    }

    /** A fired setup: the strategy, its stop and target prices, how long it may hold, and what it read. */
    data class Setup8910(val name: String, val stop: Double, val target: Double, val holdSec: Int, val why: String) {
        val strategy: String get() = PREFIX_8910 + name
    }

    // ── pure ──

    private fun median(x: List<Double>): Double {
        val s = x.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return Double.NaN
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** Pure: the volume-weighted typical price of [c]; NaN with no volume. */
    private fun vwap8910(c: List<Candle8910>): Double {
        var pv = 0.0; var v = 0.0
        for (k in c) if (k.volume > 0.0) { pv += (k.high + k.low + k.close) / 3.0 * k.volume; v += k.volume }
        return if (v > 0.0) pv / v else Double.NaN
    }

    /** Pure: the UTC session of [nowMs]: ASIA 00-07, EU 07-13, US 13-21, LATE 21-24. */
    fun session8910(nowMs: Long): String {
        val h = ((nowMs / 3_600_000L) % 24L).toInt()
        return when { h < 7 -> "ASIA"; h < 13 -> "EU"; h < 21 -> "US"; else -> "LATE" }
    }

    /** Pure: is a setup worth taking at [entry] — target at least twice the round trip away, reward >= MIN_RR x risk? */
    fun worth8910(entry: Double, stop: Double, target: Double, costPct: Double = COST_PCT_8910): Boolean =
        entry > 0.0 && stop.isFinite() && target.isFinite() && stop > 0.0 && stop < entry && target > entry &&
            (target / entry - 1.0) * 100.0 >= 2.0 * costPct && (target - entry) >= MIN_RR_8910 * (entry - stop)

    /** Pure: the most recent flush in the last [within] candles of [c] (index), or -1. */
    internal fun flushIndex8910(c: List<Candle8910>, within: Int): Int {
        if (c.size < 12) return -1
        val day = c.takeLast(24)
        val med = median(day.map { it.volume })
        val dayHigh = day.maxOf { it.high }
        if (!(med > 0.0)) return -1
        for (i in c.indices.reversed()) {
            if (i < c.size - within) break
            val k = c[i]
            if (k.retPct.isFinite() && k.retPct <= FLUSH_RET_PCT_8910 && k.volume >= FLUSH_VOL_MULT_8910 * med &&
                dayHigh > 0.0 && (1.0 - k.low / dayHigh) * 100.0 >= FLUSH_DRAWDOWN_PCT_8910) return i
        }
        return -1
    }

    /** Pure: the breakdown level of the flush at [f]: the open of the run of red candles that ends at it. */
    internal fun breakdownLevel8910(c: List<Candle8910>, f: Int): Double {
        var i = f
        while (i - 1 >= 0 && !c[i - 1].green) i--
        return c[i].open
    }

    /** Pure: the flush-and-reclaim read shared by [capitulation8910] and [fundingFlush8910]; (stop, target, why) or null. */
    private fun flushReclaim8910(c: List<Candle8910>, price: Double, within: Int): Triple<Double, Double, String>? {
        val f = flushIndex8910(c, within)
        if (f < 0 || f >= c.size - 1 || !(price > 0.0)) return null
        val flush = c[f]
        val after = c.subList(f + 1, c.size)
        val last = c.last()
        val higherLow = after.minOf { it.low } > flush.low
        val vwap = vwap8910(c.takeLast(24))
        val reclaim = price > last.high || (vwap.isFinite() && price > vwap)
        val buyers = last.buyShare.isFinite() && last.buyShare > 0.5
        if (!higherLow || !reclaim || !buyers) return null
        val stop = flush.low * 0.997
        val target = minOf(breakdownLevel8910(c, f), c.takeLast(24).maxOf { it.high })
        if (!worth8910(price, stop, target)) return null
        return Triple(stop, target, "flush${"%.1f".format(flush.retPct)}%@${"%.1f".format(flush.volume / median(c.takeLast(24).map { it.volume }))}x " +
            "hl reclaim${if (price > last.high) "1h" else "vwap"} buy${(last.buyShare * 100).toInt()}%")
    }

    /** Pure: the capitulation reversal on [c] (closed hourly candles, oldest first) at [price]. */
    fun capitulation8910(c: List<Candle8910>, price: Double): Setup8910? =
        flushReclaim8910(c, price, within = 12)?.let { (s, t, why) -> Setup8910("CAPITULATION_RECLAIM", s, t, 6 * 3600, why) }

    /**
     * Pure: the funding / liquidation-cascade reversal — a flush and reclaim in the last six hours with shorts crowded
     * ([funding8h] <= -0.01 %, a fraction) or leverage cleared ([oiChange4hPct] <= -4 %). Unobserved inputs fire nothing.
     */
    fun fundingFlush8910(c: List<Candle8910>, price: Double, funding8h: Double?, oiChange4hPct: Double?): Setup8910? {
        val crowded = funding8h != null && funding8h.isFinite() && funding8h <= FUNDING_CROWDED_8910
        val cleared = oiChange4hPct != null && oiChange4hPct.isFinite() && oiChange4hPct <= OI_FLUSH_PCT_8910
        if (!crowded && !cleared) return null
        return flushReclaim8910(c, price, within = 6)?.let { (s, t, why) ->
            Setup8910("FUNDING_FLUSH_REVERSAL", s, t, 6 * 3600, why +
                (if (crowded) " funding${"%.3f".format(funding8h!! * 100)}%" else "") + (if (cleared) " oi${"%.1f".format(oiChange4hPct)}%" else ""))
        }
    }

    /** Pure: BTC's regime from its own tape [c] at [price]: UP / DOWN / RECLAIM / FLAT, or U (unread). */
    fun btcRegime8910(c: List<Candle8910>, price: Double): String {
        if (c.size < 24 || !(price > 0.0)) return "U"
        val day = c.takeLast(24)
        val change24 = (price / day.first().open - 1.0) * 100.0
        val change4 = (price / c[c.size - 4].open - 1.0) * 100.0
        val vwap = vwap8910(day)
        val last = c.last()
        return when {
            change24 <= -2.0 && price > last.high && last.buyShare.isFinite() && last.buyShare > 0.5 -> "RECLAIM"
            change4 > 0.0 && vwap.isFinite() && price > vwap -> "UP"
            change4 < 0.0 && vwap.isFinite() && price < vwap -> "DOWN"
            else -> "FLAT"
        }
    }

    /** Pure: an asset's 24 h change on its tape at [price]; NaN when unread. */
    private fun change24h8910(c: List<Candle8910>, price: Double): Double =
        if (c.size < 24 || !(price > 0.0)) Double.NaN else (price / c.takeLast(24).first().open - 1.0) * 100.0

    /** Pure: relative-strength rotation — [rank] (0 = strongest) of the asset's lead [rsPct] over BTC, under BTC's [regime]. */
    fun rsRotation8910(c: List<Candle8910>, price: Double, regime: String, rsPct: Double, rank: Int): Setup8910? {
        if (regime != "UP" && regime != "RECLAIM") return null
        if (!rsPct.isFinite() || rsPct < 2.0 || rank !in 0 until RS_TOP_8910 || c.size < 24) return null
        val last = c.last()
        val vwap = vwap8910(c.takeLast(24))
        if (!last.green || !(last.buyShare > 0.5) || !(vwap.isFinite() && price > vwap)) return null
        val stop = c.takeLast(4).minOf { it.low } * 0.997
        val risk = (price - stop) / price * 100.0
        if (risk < 0.8 || risk > 6.0) return null
        val target = price + 2.0 * (price - stop)
        if (!worth8910(price, stop, target)) return null
        return Setup8910("RS_ROTATION", stop, target, 4 * 3600, "btc=$regime rs+${"%.1f".format(rsPct)}% rank${rank + 1}")
    }

    /** Pure: the 48 h range low reclaim on [c] at [price]. */
    fun rangeLow8910(c: List<Candle8910>, price: Double): Setup8910? {
        if (c.size < 48 || !(price > 0.0)) return null
        val r = c.takeLast(48)
        val hi = r.maxOf { it.high }; val lo = r.minOf { it.low }
        if (!(lo > 0.0) || (hi / lo - 1.0) * 100.0 < 4.0) return null
        if (price > lo + 0.25 * (hi - lo)) return null
        val last = c.last()
        val higherLow = c.takeLast(3).minOf { it.low } > lo
        if (!higherLow || !last.green || !(last.buyShare > 0.5) || price <= last.close) return null
        val stop = lo * 0.997
        val target = (lo + hi) / 2.0
        if (!worth8910(price, stop, target)) return null
        return Setup8910("RANGE_LOW_RECLAIM", stop, target, 6 * 3600, "range${"%.1f".format((hi / lo - 1.0) * 100.0)}% at${"%.0f".format((price - lo) / (hi - lo) * 100.0)}%")
    }

    /** Pure: the breakout retest on [c] at [price]: a close above the prior 48 h high in the last 6 h, back to it and holding. */
    fun breakoutRetest8910(c: List<Candle8910>, price: Double): Setup8910? {
        if (c.size < 54 || !(price > 0.0)) return null
        val prior = c.subList(c.size - 54, c.size - 6)
        val level = prior.maxOf { it.high }
        val recent = c.takeLast(6)
        val b = recent.indexOfFirst { it.close > level * 1.003 }
        if (b < 0 || b >= recent.size - 1) return null
        val since = recent.subList(b + 1, recent.size)
        val lowSince = since.minOf { it.low }
        val last = c.last()
        if (lowSince > level * 1.005 || price < level || price > level * 1.015 || !(last.buyShare > 0.5)) return null
        val stop = minOf(level * 0.992, lowSince * 0.997)
        val target = price + 2.0 * (price - stop)
        if (!worth8910(price, stop, target)) return null
        return Setup8910("BREAKOUT_RETEST", stop, target, 4 * 3600, "level=${"%.6g".format(level)} retestLow=${"%.6g".format(lowSince)}")
    }

    /**
     * Pure: the setup [c] (the asset's closed hourly candles) shows at [price], strongest first: the funding / liquidation
     * reversal, the capitulation reversal, the breakout retest, the range low, the relative-strength rotation.
     */
    fun best8910(c: List<Candle8910>, price: Double, regime: String, rsPct: Double, rsRank: Int,
                 funding8h: Double?, oiChange4hPct: Double?): Setup8910? =
        fundingFlush8910(c, price, funding8h, oiChange4hPct) ?: capitulation8910(c, price) ?: breakoutRetest8910(c, price) ?:
            rangeLow8910(c, price) ?: rsRotation8910(c, price, regime, rsPct, rsRank)

    // ── the live read ──

    private val fired = ConcurrentHashMap<String, AtomicLong>()
    private val read = AtomicLong(0L)

    /**
     * CryptoAltTrader.runScanCycle: the setup [symbol]'s tape shows at [price] now, or null (no fresh tape, no setup). Reads
     * MajorTape8910's cache only (a stale tape is refreshed in the background, never awaited here).
     */
    fun setupFor8910(symbol: String, price: Double, nowMs: Long = System.currentTimeMillis()): Setup8910? = try {
        val sym = symbol.trim().uppercase()
        val c = MajorTape8910.candles8910(sym, nowMs)
        if (c == null) null else {
            read.incrementAndGet()
            val btc = MajorTape8910.candles8910("BTC", nowMs)
            val btcPrice = btc?.lastOrNull()?.close ?: Double.NaN
            val regime = if (btc == null) "U" else btcRegime8910(btc, btcPrice)
            val (rs, rank) = rsOf8910(sym, price, nowMs)
            val d = MajorTape8910.derivs8910(sym, nowMs)
            best8910(c, price, regime, rs, rank, d?.first, d?.second)?.also { s ->
                fired.computeIfAbsent(s.name) { AtomicLong(0L) }.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MAJOR_SETUP_8910_${s.name}") } catch (_: Throwable) {}
            }
        }
    } catch (_: Throwable) { null }

    /** BTC's regime now, from its cached tape ("U" when unread): rides every crypto close (CryptoTwin8883). */
    fun regimeNow8910(nowMs: Long = System.currentTimeMillis()): String = try {
        val btc = MajorTape8910.candles8910("BTC", nowMs)
        if (btc == null) "U" else btcRegime8910(btc, btc.last().close)
    } catch (_: Throwable) { "U" }

    /** [symbol]'s lead over BTC on the day and its rank among the majors with a fresh tape (rank -1 when unread). */
    private fun rsOf8910(symbol: String, price: Double, nowMs: Long): Pair<Double, Int> {
        val btc = MajorTape8910.candles8910("BTC", nowMs) ?: return Double.NaN to -1
        val btc24 = change24h8910(btc, btc.last().close)
        if (!btc24.isFinite() || symbol == "BTC") return Double.NaN to -1
        val mine = MajorTape8910.candles8910(symbol, nowMs)?.let { change24h8910(it, price) } ?: return Double.NaN to -1
        if (!mine.isFinite()) return Double.NaN to -1
        val others = MajorTape8910.fresh8910(nowMs).filter { it.key != symbol && it.key != "BTC" }
            .map { (_, c) -> change24h8910(c, c.last().close) - btc24 }.filter { it.isFinite() }
        val rs = mine - btc24
        return rs to others.count { it > rs }
    }

    /** Brief: the setups fired by name, the tapes read, BTC's regime and the session. */
    fun statusLine8910(nowMs: Long = System.currentTimeMillis()): String {
        val f = fired.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "majorSetups8910[read=${read.get()} fired{$f} btc=${regimeNow8910(nowMs)} session=${session8910(nowMs)} ${MajorTape8910.statusLine8910(nowMs)} " +
            "rule=flush+reclaim / crowded-short or OI-flush reversal / range low / breakout retest / RS rotation under BTC; real hourly tape; " +
            "target >= 2x cost and >= ${MIN_RR_8910}R; each a named strategy graded on the twin (lane|S=), backs live only on its own record]"
    }
}
