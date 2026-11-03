package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexVoters7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521.Frame8521
import org.junit.Assert.*
import org.junit.Test

class Aate8521MultiTimeframeLearningTest {
    private val now = 1_800_000_000_000L
    private val frames = listOf(Frame8521.M1, Frame8521.M5, Frame8521.H1, Frame8521.H4)
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")

    private fun bars(frame: Frame8521, prices: List<Double>, volumes: List<Double?> = List(prices.size) { 100.0 }): List<Candle> {
        val end = now / frame.intervalMs * frame.intervalMs
        return prices.mapIndexed { i, price ->
            val open = prices.getOrElse(i - 1) { price }
            CandleCoverage8284.tag8284(Candle(end - (prices.size - i) * frame.intervalMs, price, 50_000.0,
                999_999.0, 999_999.0, highUsd = maxOf(open, price) * 1.01, lowUsd = minOf(open, price) * 0.99,
                openUsd = open, intervalMs8521 = frame.intervalMs, periodVolumeUsd8521 = volumes[i]),
                CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "observed_test", "pool8521")
        }
    }

    private fun install(ts: TokenState, frame: Frame8521, rows: List<Candle>) {
        val h = when (frame) {
            Frame8521.M1 -> ts.history; Frame8521.M5 -> ts.history5m; Frame8521.M15 -> ts.history15m
            Frame8521.H1 -> ts.history1h; Frame8521.H4 -> ts.history4h
        }
        synchronized(h) { h.clear(); h.addAll(rows) }
    }

    private fun vote(frame: Frame8521, suffix: String) = CortexVoters7885.ALL.single {
        it.id == "CHART_${frame.label.uppercase()}_${suffix}_8521"
    }

    @Test fun eachRealFrameKeepsItsOwnDirectionAndOneEvidenceGroup() {
        val ts = TokenState("four-frame-chart8521")
        for ((i, frame) in frames.withIndex()) {
            val sign = if (i % 2 == 0) 1.0 else -1.0
            install(ts, frame, bars(frame, List(30) { 100.0 + sign * it }))
        }
        val registered = CortexVoters7885.ALL.filter { it.id.startsWith("CHART_") && it.id.endsWith("_8521") }
        assertEquals(28, registered.size)
        for ((i, frame) in frames.withIndex()) {
            val raw = requireNotNull(vote(frame, "MOVE").read(ts, "QUALITY", now))
            assertTrue(frame.label, if (i % 2 == 0) raw > 0.0 else raw < 0.0)
            val group = registered.filter { it.id.startsWith("CHART_${frame.label.uppercase()}_") }
            assertEquals(1, group.map { it.evidence }.distinct().size)
        }
    }

    @Test fun aYoungFourHourChartCanSupplyObservedMoveWithoutInventingAnEmaOrPattern() {
        val ts = TokenState("young-chart8521")
        assertTrue(LifecycleStrategy.chartFrames8521(ts, now).isEmpty())
        assertEquals(0.0, LifecycleStrategy.macroContext8521(emptyMap()), 0.0)
        install(ts, Frame8521.H4, bars(Frame8521.H4, listOf(100.0, 102.0, 105.0, 108.0, 110.0)))
        val read = LifecycleStrategy.chartFrames8521(ts, now).getValue(Frame8521.H4)
        assertEquals(5, read.bars)
        assertEquals(10.0, requireNotNull(read.movePct), 1e-10)
        assertNotNull(read.volumeScore)
        assertNull(read.ema)
        assertNull(read.pattern)
        assertNull(vote(Frame8521.H4, "EMA").read(ts, "PROJECT_SNIPER", now))
        assertNull(vote(Frame8521.H1, "MOVE").read(ts, "PROJECT_SNIPER", now))
        assertTrue(LifecycleStrategy.macroContext8521(mapOf(Frame8521.H4 to read)) in 0.0..8.0)
    }

    @Test fun missingVolumeDoesNotBecomeRollingVolumeAndObservedZeroVolumeIsRetained() {
        val prices = List(12) { 100.0 + it }
        val unknown = LifecycleStrategy.chartFrame8521(bars(Frame8521.H1, prices, List(12) { null }))!!
        assertNull(unknown.volumeScore)
        assertNull(unknown.volumeDivergence)
        val dried = LifecycleStrategy.chartFrame8521(bars(Frame8521.H1, prices,
            List(12) { if (it < 9) 100.0 else 0.0 }))!!
        assertEquals(0.0, requireNotNull(dried.volumeScore), 0.0)
        assertEquals(true, dried.volumeDivergence)
    }

    @Test fun chartReadDoesNotCompressAnOutageIntoContiguousPatternEvidence() {
        val rows = bars(Frame8521.H1, List(30) { 100.0 + it })
        val read = LifecycleStrategy.chartFrame8521(rows.filterIndexed { i, _ -> i != 25 })!!
        assertEquals(4, read.bars)
        assertNull(read.ema)
        assertNull(read.pattern)
        assertNull(LifecycleStrategy.chartFrame8521(rows.filterIndexed { i, _ -> i != 27 }))
    }

    @Test fun aKnownVenueSwitchCannotBecomeALargeLearnedChartMove() {
        val rows = bars(Frame8521.H1, List(26) { 100.0 + it } + listOf(200.0, 201.0, 202.0, 203.0))
        rows.takeLast(4).forEach { CandleCoverage8284.tag8284(it,
            CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "provider_b", "pool_b") }
        val read = LifecycleStrategy.chartFrame8521(rows)!!
        assertEquals(4, read.bars)
        assertEquals(1.5, requireNotNull(read.movePct), 1e-9)
        assertNull(read.ema)
        assertNull(read.pattern)
    }

    @Test fun wideningBearishFanUsesThePreviousCompletedWindowAndTheBearishDirection() {
        val bearish = List(25) { 100.0 } + listOf(99.0, 97.0, 93.0, 85.0, 69.0)
        val bullish = List(25) { 100.0 } + listOf(101.0, 103.0, 107.0, 115.0, 131.0)
        for (frame in frames) {
            val bear = LifecycleStrategy.chartFrame8521(bars(frame, bearish))!!.ema!!
            val bull = LifecycleStrategy.chartFrame8521(bars(frame, bullish))!!.ema!!
            assertEquals(LifecycleStrategy.EmaAlignment.BEAR_FAN, bear.alignment)
            assertEquals(LifecycleStrategy.EmaAlignment.BULL_FAN, bull.alignment)
            assertTrue(bear.widening && bull.widening)
            assertTrue(bear.spreadPct < 0.0 && bull.spreadPct > 0.0)
            assertTrue(bear.score < 0.0 && bull.score > 0.0)
        }
    }

    @Test fun existingBullFlagDetectorRecognizesTheSameShapeOnEachActualFrame() {
        val flag = listOf(100.0, 101.0, 104.0, 110.0, 118.0, 125.0, 128.0, 126.0,
            125.0, 126.0, 125.0, 126.0, 127.0, 127.0, 128.0)
        for (frame in frames) {
            val ordinary = LifecycleStrategy.chartFrame8521(bars(frame, flag))!!.pattern!!
            val scaled = LifecycleStrategy.chartFrame8521(bars(frame, flag.map { it * 1_000.0 }))!!.pattern!!
            assertEquals(LifecycleStrategy.ChartPattern.BULL_FLAG, ordinary.pattern)
            assertEquals(ordinary.pattern, scaled.pattern)
            assertEquals(ordinary.confidence, scaled.confidence, 1e-9)
        }
    }

    @Test fun allLanesLearnAllFourFrozenChartMovesAndAChangedHeldChartCannotRewriteThem() {
        val positive = TokenState("positive-chart8521")
        val negative = TokenState("negative-chart8521")
        for (frame in frames) {
            install(positive, frame, bars(frame, List(30) { 100.0 + it }))
            install(negative, frame, bars(frame, List(30) { 100.0 - it }))
        }
        val voters = frames.map { vote(it, "MOVE") }
        val ids = voters.map { it.id }
        val bins = voters.map { it.edges }
        val first = voters.map { requireNotNull(it.read(positive, "QUALITY", now)) }.toDoubleArray()
        val second = voters.map { requireNotNull(it.read(negative, "QUALITY", now)) }.toDoubleArray()
        val model = CortexLedger7885()
        for (lane in lanes) {
            repeat(300) { index ->
                val raw = if (index % 2 == 0) first else second
                val expected = if (index % 2 == 0) 20.0 else -10.0
                val original = model.predictionSnapshot8076(lane, ids, bins, raw)
                model.grade(lane, ids, bins, raw, expected, expected, prediction8076 = original)
            }
            for (i in frames.indices) assertTrue("$lane ${frames[i].label}",
                model.predict(ids[i], lane, bins[i], first[i]).netPct > model.predict(ids[i], lane, bins[i], second[i]).netPct)
        }
        positive.position = Position(qtyToken = 10.0, entryPrice = 100.0, costSol = 1.0, entryTime = now - 60_000L)
        for (frame in frames) install(positive, frame, bars(frame, List(30) { 100.0 - it }))
        val held = voters.map { requireNotNull(it.read(positive, "QUALITY", now + 1L)) }
        assertTrue(first.all { it > 0.0 })
        assertTrue(held.all { it < 0.0 })
    }

    @Test fun newlyClosedHigherFrameChangesTheExistingSetupInputIdentity() {
        val ts = TokenState("chart-input-version8521")
        val before = LanePlaybook7907.inputKey8084(ts)
        install(ts, Frame8521.H4, bars(Frame8521.H4, listOf(100.0, 102.0, 104.0)))
        val after = LanePlaybook7907.inputKey8084(ts)
        assertNotEquals(before, after)
        assertEquals(after, LanePlaybook7907.inputKey8084(ts))
    }
}
