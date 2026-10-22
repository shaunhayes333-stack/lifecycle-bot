package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8287 §A_PRICE_SEEN_IS_A_PRICE_BAR — shape from the prices the bot actually observed.
 *
 * 5.0.8286: featureSources8285 shape{localTape:a=1475,r=0; gecko:a=789,r=1}; candles8284 5m/15m COVERED=0. The local-tape
 * candle paths built bars only from EXECUTED prints: LocalTapeBars8128 (the 8285 provider alternate) needed >= 10 traded
 * minutes and a SOL price observed in the last 5 minutes, and since 8285 every bonding-curve snapshot is a price-only
 * print (curve=90,903 of 143,277 prints in 8282), so the coins the tape knew best — pump launches priced by their own
 * curve — had almost no executed minutes and never reached 10. A 4-minute-old launch cannot have 10 traded minutes.
 *
 * A price-only print is a valid PRICE observation of one venue. This builds one-minute bars from ONE venue's observed
 * prices (TradeTape8100.observedPrints8287): OHLC from the observed prices of that minute, volume and trade counts from
 * its EXECUTED prints only. A minute with an executed print is TRADE_AGGREGATED; a minute with only price observations
 * is MARK_ONLY with zero volume and zero trades — never volume, never buy pressure. A minute in which nothing was
 * observed has no bar (nothing is interpolated). How many minutes make a usable series depends on the lifecycle
 * ([minMinutes8287], LifecycleData8283): the shape a 4-minute launch can have is not the shape an established token needs.
 */
object PriceShapeBars8287 {
    private const val BUCKET_MS = 60_000L
    /** NEW: three one-minute closes are a shape (TradeTape8100.shape8100). */
    internal const val MIN_MINUTES_NEW_8287 = 3
    /** DEVELOPING: CandleCoverage8284's 1 m coverage (MIN_1M_BARS_8284). */
    internal const val MIN_MINUTES_DEVELOPING_8287 = 6
    /** ESTABLISHED / unknown age: LocalTapeBars8128's rule. */
    internal const val MIN_MINUTES_ESTABLISHED_8287 = 10

    /** One observed minute: OHLC from every observed price; volume / counts from executed prints only. */
    data class Bar8287(
        val minuteMs: Long, val open: Double, val high: Double, val low: Double, val close: Double,
        val volumeSol: Double, val buys: Int, val sells: Int, val executed: Int, val priceOnly: Int,
    ) {
        val quality: CandleCoverage8284.Quality8284
            get() = if (executed > 0) CandleCoverage8284.Quality8284.TRADE_AGGREGATED else CandleCoverage8284.Quality8284.MARK_ONLY
    }

    /** Pure: the minutes a series of observed bars needs at [ageMin] (unknown age claims nothing: the strictest rule). */
    fun minMinutes8287(ageMin: Double): Int = when (LifecycleData8283.lifecycleOf8283(ageMin)) {
        Lifecycle8283.NEW -> MIN_MINUTES_NEW_8287
        Lifecycle8283.DEVELOPING -> MIN_MINUTES_DEVELOPING_8287
        Lifecycle8283.ESTABLISHED, Lifecycle8283.UNKNOWN -> MIN_MINUTES_ESTABLISHED_8287
    }

    /**
     * Pure (V5.0.8317): the bars a gate needs at [ageMin] when its own rule asks for [today]. A NEW coin (inside the
     * 15-minute launch window, LifecycleData8283) is asked for the minutes it CAN have ([minMinutes8287]: three), never
     * more than [today]; every older or unknown-age coin keeps today's number. 5.0.8314: CandleCoverage8284 (6),
     * TradePlan7739 (5 / 6) and LocalTapeBars8128 (10) each waited for one-minute bars a launch could not have yet
     * (ttf1m avg 644 s; 7 of 18 live runner admits LOSS_BOUGHT_TOP). Unknown age claims nothing, as in [minMinutes8287].
     */
    fun ageAwareMin8317(ageMin: Double, today: Int): Int =
        if (LifecycleData8283.lifecycleOf8283(ageMin) == Lifecycle8283.NEW) minOf(minMinutes8287(ageMin), today) else today

    /** Pure: one-minute bars (SOL prices) from observed prints, oldest first; a minute nobody observed has no bar. */
    fun bars8287(prints: List<TradeTape8100.Print8128>): List<Bar8287> {
        val byMinute = prints.filter { it.atMs > 0L && it.priceSol.isFinite() && it.priceSol > 0.0 }
            .sortedBy { it.atMs }
            .groupBy { it.atMs / BUCKET_MS }
            .toSortedMap()
        return byMinute.map { (m, ps) ->
            val px = ps.map { it.priceSol }
            val exec = ps.filter { !it.priceOnly }
            Bar8287(
                minuteMs = m * BUCKET_MS, open = px.first(), high = px.maxOrNull() ?: px.first(), low = px.minOrNull() ?: px.first(),
                close = px.last(),
                volumeSol = exec.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 },
                buys = exec.count { it.isBuy }, sells = exec.count { !it.isBuy },
                executed = exec.size, priceOnly = ps.size - exec.size,
            )
        }
    }

    /** Pure: [bar] in USD at [solUsd]. A MARK_ONLY bar carries no volume and no trades. */
    fun candle8287(bar: Bar8287, solUsd: Double): Candle {
        val vol = if (bar.executed > 0) bar.volumeSol * solUsd else 0.0
        return Candle(
            ts = bar.minuteMs, priceUsd = bar.close * solUsd, marketCap = 0.0,
            volumeH1 = vol, volume24h = 0.0,
            buysH1 = if (bar.executed > 0) bar.buys else 0, sellsH1 = if (bar.executed > 0) bar.sells else 0,
            highUsd = bar.high * solUsd, lowUsd = bar.low * solUsd, openUsd = bar.open * solUsd,
        )
    }

    /**
     * Pure: the provider-alternate series — USD candles from observed bars when at least [minMinutes] distinct minutes
     * were observed and a SOL price is known; else null. Each candle is tagged with its quality (CandleCoverage8284).
     */
    fun series8287(prints: List<TradeTape8100.Print8128>, solUsd: Double, minMinutes: Int): List<Candle>? {
        if (!solUsd.isFinite() || solUsd <= 0.0) return null
        val bars = bars8287(prints)
        if (bars.size < minMinutes.coerceAtLeast(1)) return null
        return bars.map { b -> CandleCoverage8284.tag8284(candle8287(b, solUsd), b.quality, SOURCE_8287) }
    }

    const val SOURCE_8287 = "observed_prices8287"

    /** The SOL price for a USD bar: the fresh one, else the last observed (a level, not a ratio: shape is unaffected). */
    fun solUsd8287(nowMs: Long = System.currentTimeMillis()): Double {
        val fresh = try { com.lifecyclebot.engine.WalletManager.freshSolPrice8163(nowMs) } catch (_: Throwable) { 0.0 }
        if (fresh.isFinite() && fresh > 0.0) return fresh
        val last = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        return if (last.isFinite() && last > 0.0) last else 0.0
    }

    /** The age (minutes) of [mint] from its canonical birth, NaN when unresolved. */
    fun ageMin8287(mint: String, nowMs: Long): Double =
        try { CanonicalTokenBirthTime7440.resolve(mint, nowMs)?.let { (nowMs - it.birthMs) / 60_000.0 } } catch (_: Throwable) { null } ?: Double.NaN

    private val priceBarsAt = ConcurrentHashMap<String, Long>()
    private const val GAP_MS_8287 = 30_000L
    private val markOnlyMerged = AtomicLong(0L)
    private val runs = AtomicLong(0L)

    /**
     * HeliusSwapCandles7819 (beside its executed-tape bars): the minutes in which only PRICES were observed become
     * MARK_ONLY bars of [ts]'s 1 m series (CandleCoverage8284.merge8284: never above a trade candle of the same minute,
     * never a second candle for a minute). Paced per mint. Returns the candles added or replaced.
     */
    fun mergePriceOnly8287(ts: TokenState, nowMs: Long, windowMs: Long, solUsd: Double = solUsd8287(nowMs)): Int {
        val last = priceBarsAt[ts.mint] ?: 0L
        if (nowMs - last in 0L until GAP_MS_8287) return 0
        priceBarsAt[ts.mint] = nowMs
        if (priceBarsAt.size > 4_000) priceBarsAt.entries.removeIf { nowMs - it.value > 600_000L }
        if (!solUsd.isFinite() || solUsd <= 0.0) return 0
        runs.incrementAndGet()
        val prints = try { TradeTape8100.observedPrints8287(ts.mint, nowMs, windowMs) } catch (_: Throwable) { null } ?: return 0
        val markOnly = bars8287(prints).filter { it.executed == 0 }.map { candle8287(it, solUsd) }
        if (markOnly.isEmpty()) return 0
        val r = CandleCoverage8284.merge8284(ts, markOnly, CandleCoverage8284.Quality8284.MARK_ONLY, SOURCE_8287, "", nowMs)
        markOnlyMerged.addAndGet(r.changed.toLong())
        // V5.0.8287 FEATURE_ARRIVED_SHAPE(mint) — a waiting candidate re-reads its shape now
        if (r.changed > 0) try { CandidateAdmission8136.evidenceArrived8252(ts.mint, setOf("shape"), nowMs) } catch (_: Throwable) {}
        return r.changed
    }

    fun statusLine8287(): String = "priceBars8287[runs=${runs.get()} markOnlyMerged=${markOnlyMerged.get()}]"
}
