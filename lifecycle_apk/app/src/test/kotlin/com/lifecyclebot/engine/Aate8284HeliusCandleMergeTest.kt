package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.CandleCoverage8284.Coverage8284
import com.lifecyclebot.engine.truth.CandleCoverage8284.Quality8284
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import com.lifecyclebot.engine.truth.LocalCandleSynthesis7055
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8284 (owner directive 8282, P1) — Helius candle ingestion and quality-aware merge: a better candle replaces a
 * worse one of its minute without duplicating it, an established token on 4 h bars obtains 1 m bars in a compatible
 * store, coverage is stated per timeframe, history requests are not gated on history.size < 6, the job budget favours
 * held / near-decision / hand-off coins, and a skipped request still gets bars from observed tape prints only.
 */
class Aate8284HeliusCandleMergeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val mint = "MintAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val pool = "PoolPppppppppppppppppppppppppppppppppppppppp"
    private val now = 1_800_000_000_000L          // minute-aligned
    private val min = 60_000L

    private fun mark(ts: Long, px: Double) =
        Candle(ts = ts, priceUsd = px, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0)

    private fun trade(ts: Long, px: Double, n: Int = 4) = Candle(
        ts = ts, priceUsd = px, marketCap = 0.0, volumeH1 = 10.0, volume24h = 0.0, buysH1 = n, sellsH1 = 1,
        highUsd = px * 1.1, lowUsd = px * 0.9, openUsd = px * 0.95,
    )

    private fun minutes(h: Collection<Candle>) = h.map { it.ts / min }

    @Before fun setUp() {
        CandleCoverage8284.resetForTest8284(); HeliusSwapCandles7819.resetForTest()
        LocalCandleSynthesis7055.resetForTest(); TradeTape8100.resetForTest8100()
    }

    @After fun tearDown() { setUp() }

    @Test fun qualityOrderingIsTheDirectivesOrder() {
        val order = listOf(Quality8284.TRADE_OHLCV, Quality8284.VALID_POOL_OHLCV, Quality8284.TRADE_AGGREGATED,
            Quality8284.MARK_ONLY, Quality8284.INCOMPLETE, Quality8284.UNKNOWN)
        assertEquals(order, Quality8284.values().sortedByDescending { it.rank })
        assertEquals(order.size, order.map { it.rank }.toSet().size)
        // faces of untagged candles
        assertEquals(Quality8284.INCOMPLETE, CandleCoverage8284.infer8284(mark(now, 1.0)))
        val flatSynthetic = Candle(ts = now, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0,
            highUsd = 1.0, lowUsd = 1.0, openUsd = 1.0, synthetic = true)
        assertEquals(Quality8284.INCOMPLETE, CandleCoverage8284.infer8284(flatSynthetic))
        val kline = Candle(ts = now, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 900.0,
            highUsd = 1.1, lowUsd = 0.9, openUsd = 0.95)
        assertEquals(Quality8284.VALID_POOL_OHLCV, CandleCoverage8284.infer8284(kline))
        assertEquals(Quality8284.UNKNOWN, CandleCoverage8284.infer8284(trade(now, 1.0)))
        val tagged = CandleCoverage8284.tag8284(trade(now, 2.0), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool)
        assertEquals(Quality8284.TRADE_OHLCV, CandleCoverage8284.qualityOf8284(tagged))
        assertEquals(pool, CandleCoverage8284.tagOf8284(tagged)?.pool)
    }

    @Test fun incompleteOneMinuteMarkIsReplacedByAuthenticHeliusCandleWithoutDuplicatingTheMinute() {
        val ts = TokenState(mint = mint)
        val m = now - 5 * min
        ts.history.addLast(mark(m + 10_000L, 9.0))
        ts.history.addLast(mark(m + 40_000L, 9.5))           // two incomplete marks in one closed minute
        ts.history.addLast(mark(now + 5_000L, 7.0))          // the open minute's live point
        var heard = ""
        HeliusSwapCandles7819.onCandlesMerged8284Listener = { heard = it }
        val helius = listOf(trade(m, 2.0), trade(now, 3.0))
        val r = CandleCoverage8284.merge8284(ts, helius, Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool, now + 6_000L)
        assertEquals(1, r.replaced)
        assertEquals(1, r.kept)                              // the open minute's live point is never displaced
        assertEquals(0, r.added)
        assertEquals(listOf(m / min, now / min), minutes(ts.history))
        assertEquals(minutes(ts.history).size, minutes(ts.history).toSet().size)
        assertEquals(2.0, ts.history.first().priceUsd, 1e-12)
        assertEquals(Quality8284.TRADE_OHLCV, CandleCoverage8284.qualityOf8284(ts.history.first()))
        // the public mergeIntoHistory7819 path (keyless klines) also signals the integrator when it changes candles
        val r2 = HeliusSwapCandles7819.mergeIntoHistory7819(ts, listOf(trade(m - min, 1.5)))
        assertEquals(1, r2)
        assertEquals(mint, heard)
    }

    @Test fun betterCandleIsKeptAndEqualCandleNeverDuplicates() {
        val ts = TokenState(mint = mint)
        val m = now - 10 * min
        val first = CandleCoverage8284.merge8284(ts, listOf(trade(m, 2.0, n = 5)), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool, now)
        assertEquals(1, first.added)
        // a worse candle of the same minute is kept out
        val worse = CandleCoverage8284.merge8284(ts, listOf(trade(m, 9.0, n = 50)), Quality8284.TRADE_AGGREGATED, "local_tape8100", "", now)
        assertEquals(0, worse.changed); assertEquals(1, worse.kept)
        // the same Helius read again: kept, not doubled
        val again = CandleCoverage8284.merge8284(ts, listOf(trade(m, 2.0, n = 5)), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool, now)
        assertEquals(0, again.changed)
        // a fuller read of the same minute on the same venue replaces the partial one; another venue does not
        val other = CandleCoverage8284.merge8284(ts, listOf(trade(m, 4.0, n = 9)), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", "OtherPool", now)
        assertEquals(0, other.changed)
        val fuller = CandleCoverage8284.merge8284(ts, listOf(trade(m, 2.5, n = 9)), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool, now)
        assertEquals(1, fuller.replaced)
        assertEquals(1, ts.history.size)
        assertEquals(2.5, ts.history.first().priceUsd, 1e-12)
        // two bars for one minute in one batch: the better one only
        val ts2 = TokenState(mint = mint)
        val a = CandleCoverage8284.tag8284(trade(m, 1.0), Quality8284.TRADE_AGGREGATED, "local_tape8100")
        val b = CandleCoverage8284.tag8284(trade(m + 30_000L, 1.2), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool)
        CandleCoverage8284.mergeInto8284(ts2.history, listOf(a, b), now)
        assertEquals(1, ts2.history.size)
        assertEquals(1.2, ts2.history.first().priceUsd, 1e-12)
    }

    @Test fun tokenWithFourHourHistoryCanObtainOneMinuteHistory() {
        val ts = TokenState(mint = mint)
        ts.candleTimeframeMinutes = 240
        for (i in 0 until 30) ts.history.addLast(trade(now - (30 - i) * 240 * min, 1.0 + i))
        val fourHour = ts.history.toList()
        val ageMs = 5L * 24 * 3_600_000L
        assertEquals(Coverage8284.MISSING, CandleCoverage8284.coverage8284(ts, now, ageMs)[1])
        val bars = (1..8).map { trade(now - it * min, 2.0 + it) }
        val r = CandleCoverage8284.merge8284(ts, bars, Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool, now)
        assertEquals(8, r.added)
        assertEquals(fourHour, ts.history.toList())          // the 4 h series is untouched
        assertEquals(240, ts.candleTimeframeMinutes)
        assertEquals(8, CandleCoverage8284.oneMinuteBars8284(mint).size)
        assertEquals(Coverage8284.COVERED, CandleCoverage8284.coverage8284(ts, now, ageMs)[1])
        // the legacy path still never writes 1 m bars into a 4 h series
        assertEquals(0, HeliusSwapCandles7819.mergeIntoHistory7819(ts, bars))
        // and request7819 no longer refuses a token because its active timeframe is above 1 m
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertFalse(h.contains("ts.position.isOpen || ts.candleTimeframeMinutes > 1) return"))
    }

    @Test fun coverageIsIndependentPerTimeframeAndAYoungTokenHasNoFiveMinuteHistory() {
        val ts = TokenState(mint = mint)
        val nowL = System.currentTimeMillis()
        // a 15-second-old token: 15 s bars possible later, 1 m / 5 m / 15 m not yet possible
        val young = CandleCoverage8284.coverage8284(ts, nowL, 15_000L)
        assertEquals(Coverage8284.NOT_YET_POSSIBLE, young[2])
        assertEquals(Coverage8284.NOT_YET_POSSIBLE, young[3])
        assertEquals(Coverage8284.NOT_YET_POSSIBLE, young[1])
        // executed prints in three 15 s buckets: 15 s covered while 5 m stays not possible
        for (i in 0 until 3) TradeTape8100.note8100(mint, 1e-6, 0.5, true, nowL - 50_000L + i * 15_000L)
        val c = CandleCoverage8284.coverage8284(ts, nowL, 60_000L)
        assertEquals(Coverage8284.COVERED, c[0])
        assertEquals(Coverage8284.NOT_YET_POSSIBLE, c[2])
        // an old token: 1 m missing while 15 m is covered (each stated alone)
        for (i in 1..4) ts.history15m.addLast(trade(nowL - i * 15 * min, 1.0))
        val old = CandleCoverage8284.coverage8284(ts, nowL, 10L * 3_600_000L)
        assertEquals(Coverage8284.MISSING, old[1])
        assertEquals(Coverage8284.MISSING, old[2])
        assertEquals(Coverage8284.COVERED, old[3])
        assertEquals(Coverage8284.PARTIAL, CandleCoverage8284.coverageOf8284(null, 2, min, 6))
        assertEquals(CandleCoverage8284.TIMEFRAMES_8284, listOf("15s", "1m", "5m", "15m"))
    }

    @Test fun historyRequestIsNotGatedOnHistorySizeSix() {
        val orch = src("engine/DataOrchestrator.kt")
        assertFalse(orch.contains("if (ts.history.size < 6 && DataPipeline8191.seedPriority8191(mint)) try {"))
        assertTrue(orch.contains("if (com.lifecyclebot.engine.truth.HeliusSwapCandles7819.priority8284(mint) > 0) try {"))
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        val req = h.substringAfter("fun request7819(").substringBefore("private fun claimBudget(")
        assertTrue(req.contains("CandleCoverage8284.noteNeed8284(ts, nowMs)"))
        assertFalse(req.contains("history.size"))
    }

    @Test fun jobBudgetFavoursHeldNearDecisionAndHandoffOverBulk() {
        val h = HeliusSwapCandles7819
        // near-decision / held: the whole 8/min, 180/h
        assertTrue(h.budgetAdmits8284(h.PRIORITY_HELD_8284, 7, 179))
        assertTrue(h.budgetAdmits8284(h.PRIORITY_NEAR_DECISION_8284, 7, 0))
        assertFalse(h.budgetAdmits8284(h.PRIORITY_NEAR_DECISION_8284, 8, 0))
        assertFalse(h.budgetAdmits8284(h.PRIORITY_HELD_8284, 0, 180))
        // hand-off: 6/min, 140/h
        assertTrue(h.budgetAdmits8284(h.PRIORITY_HANDOFF_8284, 5, 139))
        assertFalse(h.budgetAdmits8284(h.PRIORITY_HANDOFF_8284, 6, 0))
        // bulk: 4/min, 100/h — never the decision coins' share
        assertTrue(h.budgetAdmits8284(0, 3, 99))
        assertFalse(h.budgetAdmits8284(0, 4, 0))
        assertFalse(h.budgetAdmits8284(0, 0, 100))
        assertEquals(0, h.priority8284("BulkMintZzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", now))
    }

    @Test fun skippedRequestGetsLocalTapeBarsFromObservedTradesOnlyAndNeverAnEmptyMinute() {
        val nowL = (System.currentTimeMillis() / min) * min + 30_000L
        val ts = TokenState(mint = mint)
        TradeTape8100.note8100(mint, 1e-6, 0.5, true, nowL - 10 * min)
        TradeTape8100.note8100(mint, 1.2e-6, 0.4, false, nowL - 9 * min)
        TradeTape8100.note8100(mint, 1.1e-6, 0.3, true, nowL - 9 * min + 20_000L)
        TradeTape8100.notePrice8169(mint, 9e-6, TradeTape8100.SRC_CURVE_8169, nowL - 7 * min)  // price-only: never a print
        TradeTape8100.note8100(mint, 1.3e-6, 0.2, true, nowL - 5 * min)
        var heard = ""
        HeliusSwapCandles7819.onCandlesMerged8284Listener = { heard = it }
        val r = HeliusSwapCandles7819.localTape8284(ts, nowL, "budget", solUsd = 100.0)
        assertNotNull(r)
        assertEquals(3, r!!.added)
        val expect = listOf(nowL - 10 * min, nowL - 9 * min, nowL - 5 * min).map { it / min }
        assertEquals(expect, minutes(ts.history))             // no bar for -8, -7 (price-only), -6
        assertEquals(1e-6 * 100.0, ts.history.first().priceUsd, 1e-12)
        assertEquals(Quality8284.TRADE_AGGREGATED, CandleCoverage8284.qualityOf8284(ts.history.first()))
        assertEquals(1, ts.history.toList()[1].buysH1); assertEquals(1, ts.history.toList()[1].sellsH1)
        assertEquals(mint, heard)
        // paced: a second call inside the gap does nothing
        assertEquals(null, HeliusSwapCandles7819.localTape8284(ts, nowL + 1_000L, "budget", solUsd = 100.0))
        // the authentic Helius candle later replaces the local tape bar of its minute
        val rh = CandleCoverage8284.merge8284(ts, listOf(trade(nowL - 9 * min - 30_000L, 0.5)), Quality8284.TRADE_OHLCV,
            "helius_enhanced_tx", pool, nowL)
        assertEquals(1, rh.replaced)
        assertEquals(expect, minutes(ts.history))
        // no SOL price: no bar is invented
        assertTrue(HeliusSwapCandles7819.localTapeBars8284(TradeTape8100.prints8128(mint, nowL, 30 * min), 0.0, nowL).isEmpty())
        val line = HeliusSwapCandles7819.statusLine8284()
        assertTrue(line, line.contains("localTape8284[runs=1 bars=3 changed=3 why{budget=1}]"))
        assertTrue(line, line.contains("local_tape8100:ret=3/add=3/repl=0/kept=0"))
        assertTrue(line, line.contains("helius_enhanced_tx:ret=1/add=0/repl=1/kept=0"))
    }

    @Test fun localSynthesisNeverAddsAWorseCandleToAMinuteHoldingAHeliusCandle() {
        val ts = TokenState(mint = mint)
        val m = 29_000_000L * min
        val helius = CandleCoverage8284.tag8284(trade(m, 1.0), Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool)
        ts.history.addLast(helius)
        LocalCandleSynthesis7055.noteTrade7819(ts, 1.0, 0.0, m + 5_000L)
        LocalCandleSynthesis7055.noteTrade7819(ts, 1.2, 0.0, m + 65_000L)   // rolls minute m
        assertEquals(1, ts.history.size)
        assertTrue(ts.history.first() === helius)
        assertTrue(LocalCandleSynthesis7055.minuteHeldAsWell8284(ts.history, trade(m + 1_000L, 3.0)))
        assertFalse(LocalCandleSynthesis7055.minuteHeldAsWell8284(ts.history, trade(m + min, 3.0)))
        val line = LocalCandleSynthesis7055.statusLine7055()
        assertTrue(line, line.contains("minuteHeldSkips8284=1"))
        assertTrue(line, line.contains("helius8284[returned="))
        assertTrue(line, line.contains("candles8284[merge{"))
        assertTrue(line, line.contains("ttf1m{n="))
    }

    @Test fun featureArrivalIsSignalledAtTheLandingSiteAndCountersAreReported() {
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertEquals(1, h.split("// V5.0.8284 FEATURE_ARRIVED_SHAPE(mint)").size - 1)
        val arrived = h.substringAfter("private fun featureArrived8284(").substringBefore("\n    }")
        assertTrue(arrived.indexOf("// V5.0.8284 FEATURE_ARRIVED_SHAPE(mint)") < arrived.indexOf("onCandlesMerged8284Listener(ts.mint)"))
        assertTrue(arrived.contains("evidenceArrived8252(ts.mint, setOf(\"shape\")"))
        val job = h.substringAfter("private fun runJob(").substringBefore("private fun get(")
        assertTrue(job.contains("Quality8284.TRADE_OHLCV, \"helius_enhanced_tx\", marketAddress"))
        assertTrue(job.contains("featureArrived8284(ts, System.currentTimeMillis())"))
        val req = h.substringAfter("fun request7819(").substringBefore("private fun claimBudget(")
        assertTrue(req.contains("localTape8284(ts, nowMs, \"budget\")"))
        assertTrue(req.contains("localTape8284(ts, nowMs, \"noMarket\")"))
        // the report line grows through statusLine7055, never PipelineHealthCollector.dumpText
        assertTrue(src("engine/truth/LocalCandleSynthesis7055.kt").contains("HeliusSwapCandles7819.statusLine8284()"))
        assertFalse(src("engine/PipelineHealthCollector.kt").contains("statusLine8284"))
    }

    @Test fun timeToFeatureIsMeasuredFromNeedToCoverage() {
        val ts = TokenState(mint = mint)
        ts.candleTimeframeMinutes = 240
        val nowL = System.currentTimeMillis()
        val age = 10L * 3_600_000L
        assertEquals(Coverage8284.MISSING, CandleCoverage8284.noteNeed8284(ts, nowL - 20_000L, age))
        CandleCoverage8284.merge8284(ts, (1..7).map { trade(nowL - it * min, 1.0) }, Quality8284.TRADE_OHLCV, "helius_enhanced_tx", pool, nowL)
        CandleCoverage8284.noteArrived8284(ts, nowL, age)
        val line = CandleCoverage8284.statusLine8284()
        assertTrue(line, line.contains("store1m=1mints/7bars"))
        assertTrue(line, line.contains("ttf1m{n=1 avgS=20 medS=20 maxS=20 waiting=0}"))
        assertTrue(line, line.contains("1m{NOT_YET_POSSIBLE=0,MISSING=0,PARTIAL=0,COVERED=1}"))
    }
}
