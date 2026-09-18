package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7055 §FIVE_LANES_WERE_STARVED_BY_A_DEAD_CANDLE_FEED.
 *
 * THE CHAIN, every link verified in source and in the operator's 5.0.7047
 * snapshot:
 *
 *   1. ModeRouter gates three of its seven archetypes on candle history:
 *        BREAKOUT_CONTINUATION  hist.size >= 10   (ModeRouter:206)
 *        REVERSAL_RECLAIM       hist.size >= 8    (ModeRouter:217)
 *        TREND_PULLBACK         hist.size >= 15   (ModeRouter:246)
 *
 *   2. BotService.laneAffinityForTradeType reaches TREASURY, CASHGEN and
 *      DIP_HUNTER ONLY through REVERSAL_RECLAIM and TREND_PULLBACK.
 *
 *   3. ts.history is written in exactly one place — DataOrchestrator:216,
 *      from FETCHED klines. There is no tick-derived synthesis anywhere in
 *      the app.
 *
 *   4. The fetch is dead. Birdeye returns 401 (BIRDEYE_SEED_SKIPPED_AUTH_DEAD
 *      _6947 = 182) and GeckoTerminal runs at sr=19-26% with
 *      rateLimited6944=164, cooldownSkips6944=321, localSkips6982=485. The
 *      snapshot's own line: "Keyless OHLCV (§6916): fetches=501 served=0
 *      barsDelivered=0".
 *
 *   5. Therefore hist.size is ~0 forever, those three archetypes never score,
 *      and the lanes behind them never own a candidate:
 *
 *        TREASURY    candidateN=104 ownerSelectedN=0   INTENT_CHOKED
 *        CASHGEN     candidateN=84  ownerSelectedN=0   INTENT_CHOKED
 *        DIP_HUNTER  candidateN=54  ownerSelectedN=0   INTENT_CHOKED
 *
 * Those lanes are not choked by a gate, a threshold or a cap. They are choked
 * by an empty array, and no amount of lane tuning would ever have moved them.
 * Same shape as V5.0.7044's MOONSHOT finding — a lane structurally unable to
 * reach the archetype it needs — but with a data-supply cause rather than a
 * routing one.
 *
 * WHAT THIS DOES. The app already receives the raw material and throws it
 * away: it polls a price for every watched mint every cycle (§6452 reported
 * 17,053 quote notes in one session). This bins those ticks into 1-minute
 * OHLC candles locally and appends them to ts.history.
 *
 * Free and keyless, per standing operator constraint — no new provider, no
 * key, no request. It is arithmetic on data already in hand.
 *
 * PRECEDENCE: FETCHED DATA ALWAYS WINS. DataOrchestrator:215 clears history
 * before writing fetched klines, so a real OHLCV feed overwrites anything
 * synthesised here the moment it returns. This fills a vacuum; it never
 * competes.
 *
 * HONEST ABOUT WHAT A SYNTHESISED CANDLE IS. Volume and buy/sell counts are
 * NOT observable from a price tick, so they are left at zero rather than
 * invented. Candle.buyRatio then returns its 0.5 "no information" default,
 * which is the truthful answer. Anything reading volume off these bars gets a
 * zero it can detect, not a fabricated number it cannot. The OHLC is real:
 * open, high, low and close are genuine observations of the polled price
 * within the minute.
 */
object LocalCandleSynthesis7055 {

    private const val BUCKET_MS = 60_000L
    private const val MAX_HISTORY = 300          // matches DataOrchestrator:217
    private const val SYNTHETIC_SOURCE = "LOCAL_TICK_SYNTH_7055"

    private class Bucket(
        val startMs: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var mcap: Double,
        var ticks: Int,
    )

    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()
    private val candlesEmitted = AtomicLong(0L)
    private val ticksBinned = AtomicLong(0L)
    private val skippedHasFetched = AtomicLong(0L)

    /**
     * Bin one observed price. Call from the price-write path; cheap and
     * allocation-light so it is safe on the scan cycle.
     *
     * Deliberately a no-op when a real feed has already supplied bars for this
     * mint beyond what we have synthesised — never degrade a fetched series.
     */
    fun note(ts: TokenState, priceUsd: Double, mcapUsd: Double) {
        if (!priceUsd.isFinite() || priceUsd <= 0.0) return
        val mint = ts.mint
        if (mint.isBlank()) return
        // CANDIDATES ONLY. An OPEN position already receives a per-tick candle
        // from openPositionTickLoop (BotService:11098) at 1Hz; binning here as
        // well would double-append and inflate hist.size with the same motion
        // counted twice. The gap this fills is the pre-entry window, where
        // intake seeds exactly ONE candle (BotService:14308/14331, both guarded
        // on history.isEmpty()) and nothing else ever arrives — which is why a
        // candidate reaches ModeRouter with hist.size=1 and can never satisfy
        // the >=8 / >=10 / >=15 archetype thresholds.
        try { if (ts.position.isOpen) return } catch (_: Throwable) {}
        try {
            // If the last bar came from a real OHLCV fetch, stand down. The
            // fetched series is richer (it carries volume and buy/sell counts)
            // and DataOrchestrator owns it.
            val last = ts.history.lastOrNull()
            if (last != null && last.volume24h > 0.0) {
                skippedHasFetched.incrementAndGet()
                return
            }
        } catch (_: Throwable) {}

        val now = System.currentTimeMillis()
        val start = now - (now % BUCKET_MS)
        ticksBinned.incrementAndGet()

        val existing = buckets[mint]
        if (existing == null || existing.startMs != start) {
            // Roll the completed bucket into history before opening the next.
            if (existing != null && existing.startMs < start) {
                emit(ts, existing)
            }
            buckets[mint] = Bucket(
                startMs = start, open = priceUsd, high = priceUsd,
                low = priceUsd, close = priceUsd, mcap = mcapUsd, ticks = 1,
            )
            return
        }
        if (priceUsd > existing.high) existing.high = priceUsd
        if (priceUsd < existing.low) existing.low = priceUsd
        existing.close = priceUsd
        if (mcapUsd.isFinite() && mcapUsd > 0.0) existing.mcap = mcapUsd
        existing.ticks += 1
    }

    private fun emit(ts: TokenState, b: Bucket) {
        try {
            // A single-tick bucket is a point, not a candle. Keeping it would
            // let hist.size cross the archetype thresholds on what is really
            // one observation repeated, so the count would lie about how much
            // price action the classifier actually saw.
            if (b.ticks < 2) return
            val candle = Candle(
                ts = b.startMs,
                priceUsd = b.close,
                marketCap = b.mcap,
                volumeH1 = 0.0,      // not observable from a price tick
                volume24h = 0.0,     // left zero rather than invented
                highUsd = b.high,
                lowUsd = b.low,
                openUsd = b.open,
            )
            ts.history.addLast(candle)
            while (ts.history.size > MAX_HISTORY) ts.history.removeFirst()
            val n = candlesEmitted.incrementAndGet()
            PipelineHealthCollector.labelInc("LOCAL_CANDLE_SYNTHESISED_7055")
            // One line per 50 so a hundred watched mints cannot drown the log.
            if (n % 50L == 1L) {
                ForensicLogger.lifecycle(
                    "LOCAL_CANDLE_SYNTHESISED_7055",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} bucket=${b.startMs} ticks=${b.ticks} " +
                        "o=${b.open} h=${b.high} l=${b.low} c=${b.close} histNow=${ts.history.size} " +
                        "source=$SYNTHETIC_SOURCE reason=ohlcv_feed_dead_barsDelivered_0",
                )
            }
        } catch (_: Throwable) {}
    }

    fun statusLine7055(): String =
        "ticksBinned=${ticksBinned.get()} candles=${candlesEmitted.get()} " +
            "openBuckets=${buckets.size} deferredToFetched=${skippedHasFetched.get()} " +
            "read=unlocks_BREAKOUT(>=10)_REVERSAL(>=8)_PULLBACK(>=15)_and_the_lanes_behind_them"

    internal fun resetForTest() {
        buckets.clear(); candlesEmitted.set(0L); ticksBinned.set(0L); skippedHasFetched.set(0L)
    }
}
