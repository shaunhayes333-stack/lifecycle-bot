package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.ChartSources7955
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521 as Charts
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521.Frame8521 as Frame
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.OhlcvRoute8332
import com.lifecyclebot.network.SolanaOhlcvFeed6916
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8521MultiTimeframeDataTest {
    private val start = 1_728_000_000_000L // UTC day and four-hour boundary
    private fun bar(at: Long, frame: Frame, close: Double = 1.1, volume: Double? = 100.0) =
        Candle(at, close, 0.0, 0.0, volume ?: 0.0, openUsd = 1.0,
            highUsd = maxOf(1.2, close), lowUsd = 0.8,
            intervalMs8521 = frame.intervalMs, periodVolumeUsd8521 = volume)

    @Test fun independentHistoriesMergeDuplicatesWithoutReplacingTheOneMinuteTape() {
        val now = start + 24 * Frame.H1.intervalMs
        val ts = TokenState("distinct-timeframes")
        val primary = bar(now - Frame.M1.intervalMs, Frame.M1)
        ts.history.addLast(primary)
        for (frame in listOf(Frame.M5, Frame.H1, Frame.H4)) {
            val closed = (3 downTo 1).map { bar(now - it * frame.intervalMs, frame) }
            assertEquals(3, Charts.install8521(ts, frame, closed + closed + bar(now, frame), now, "provider", provider8521 = true))
            assertEquals(0, Charts.install8521(ts, frame, closed, now, "provider", provider8521 = true))
            assertEquals(closed.map { it.ts }, Charts.snapshot8521(ts, frame, now).map { it.ts })
        }
        assertEquals(listOf(primary), ts.history.toList())
        assertEquals(1, ts.candleTimeframeMinutes)
    }

    @Test fun fourHourResampleRequiresFourWholeHoursAndSumsOnlyTheirIntervalVolumes() {
        val now = start + 4 * Frame.H1.intervalMs
        val hourly = (0..3).map { bar(start + it * Frame.H1.intervalMs, Frame.H1, 1.1 + it, 10.0 + it) }
        val result = Charts.resample8521(hourly.reversed() + hourly.first(), Frame.H1, Frame.H4, now)
        assertEquals(1, result.size)
        assertEquals(start, result.single().ts)
        assertEquals(Frame.H4.intervalMs, result.single().intervalMs8521)
        assertEquals(1.0, result.single().openUsd, 0.0)
        assertEquals(4.1, result.single().priceUsd, 1e-9)
        assertEquals(4.1, result.single().highUsd, 1e-9)
        assertEquals(0.8, result.single().lowUsd, 0.0)
        assertEquals(46.0, result.single().periodVolumeUsd8521!!, 0.0)
        assertTrue(Charts.resample8521(hourly.drop(1), Frame.H1, Frame.H4, now).isEmpty())
        assertTrue(Charts.resample8521(hourly, Frame.H1, Frame.H4, now - 1L).isEmpty())
    }

    @Test fun snapshotsDoNotManufactureVolumeFromRollingMarketTotals() {
        val now = start + 4 * Frame.H1.intervalMs
        val hourly = (0..3).map {
            bar(start + it * Frame.H1.intervalMs, Frame.H1, volume = null).copy(volumeH1 = 50_000.0, volume24h = 900_000.0)
        }
        val result = Charts.resample8521(hourly, Frame.H1, Frame.H4, now).single()
        assertNull(result.periodVolumeUsd8521)
        val ts = TokenState("missing-volume")
        Charts.install8521(ts, Frame.H4, listOf(result), now, "aggregate")
        assertNull(Charts.snapshot8521(ts, Frame.H4, now).single().periodVolumeUsd8521)
        val poll = bar(start, Frame.H1).copy(intervalMs8521 = 0L, synthetic = true)
        assertTrue(Charts.stamp8521(listOf(poll), Frame.H1, "poll").isEmpty())
    }

    @Test fun sixHourBarsAndDifferentVenuesCannotMasqueradeAsOneFourHourChart() {
        val now = start + 4 * Frame.H1.intervalMs
        val ts = TokenState("wrong-duration")
        val wrong = bar(start, Frame.H4).copy(intervalMs8521 = 6 * Frame.H1.intervalMs)
        assertEquals(0, Charts.install8521(ts, Frame.H4, listOf(wrong), now, "provider", provider8521 = true))
        val mixed = (0..3).map {
            CandleCoverage8284.tag8284(bar(start + it * Frame.H1.intervalMs, Frame.H1),
                CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "provider", if (it == 3) "pool-b" else "pool-a")
        }
        assertTrue(Charts.resample8521(mixed, Frame.H1, Frame.H4, now).isEmpty())
    }

    @Test fun openStaleSyntheticAndMalformedBarsCannotBecomeCurrentChartEvidence() {
        val now = start + 24 * Frame.H1.intervalMs
        val ts = TokenState("invalid-bars")
        val recent = bar(now - Frame.H1.intervalMs, Frame.H1)
        val rows = listOf(recent, recent.copy(ts = now), recent.copy(ts = now + Frame.H1.intervalMs),
            recent.copy(ts = now - 2 * Frame.H1.intervalMs, synthetic = true),
            recent.copy(ts = now - 3 * Frame.H1.intervalMs, lowUsd = 9.0))
        Charts.install8521(ts, Frame.H1, rows, now, "provider", provider8521 = true)
        assertEquals(listOf(recent.ts), Charts.snapshot8521(ts, Frame.H1, now).map { it.ts })
        assertTrue(Charts.snapshot8521(ts, Frame.H1, now + 3 * Frame.H1.intervalMs).isEmpty())
        assertFalse(Charts.fetchPossible8521(Frame.H4, 60_000L))
        assertTrue(Charts.fetchPossible8521(Frame.H4, null))
        assertTrue(Charts.fetchPossible8521(Frame.H1, Frame.H1.intervalMs))
    }

    @Test fun repeatedRefreshesRetainBoundedChronologicalHistories() {
        val ts = TokenState("bounded-chart")
        val now = start + 120 * Frame.M5.intervalMs
        val rows = (0 until 120).map { bar(start + it * Frame.M5.intervalMs, Frame.M5) }
        Charts.install8521(ts, Frame.M5, rows.shuffled(), now, "provider", provider8521 = true)
        assertEquals(Frame.M5.capacity, ts.history5m.size)
        assertEquals(rows.takeLast(Frame.M5.capacity), ts.history5m.toList())
        repeat(3) { Charts.install8521(ts, Frame.M5, rows, now, "provider", provider8521 = true) }
        assertEquals(Frame.M5.capacity, ts.history5m.size)
    }

    @Test fun codexParserPreservesRealUsdVolumeAndKeepsMissingVolumeUnknown() {
        val sec = start / 1000L
        val root = JSONObject("""{"data":{"getBars":{"t":[$sec,${sec + 3600},${sec + 7200}],
            "o":[1,1,1],"h":[2,2,2],"l":[0.5,0.5,0.5],"c":[1.1,1.2,1.3],
            "volume":["100.5",null,"0"],"buyVolume":["40",null,"0"]}}}""")
        val rows = ChartSources7955.codexCandlesFromJson8521(root, 60)
        assertEquals(3, rows.size)
        assertEquals(start, rows[0].ts)
        assertEquals(Frame.H1.intervalMs, rows[0].intervalMs8521)
        assertEquals(100.5, rows[0].periodVolumeUsd8521!!, 0.0)
        assertNull(rows[1].periodVolumeUsd8521)
        assertEquals(0.0, rows[2].periodVolumeUsd8521!!, 0.0)
    }

    @Test fun birdeyeFourHourRangeAndV3FieldsUseTheirRealUnits() {
        val api = BirdeyeApi("")
        assertEquals(14_400L, api.timeframeToSeconds("4H"))
        assertEquals(3_600L, api.timeframeToSeconds("1h"))
        val sec = start / 1000L
        val json = """{"data":{"items":[{"unix_time":$sec,"o":1,"h":2,"l":0.5,"c":1.1,"v":9000000,"v_usd":15.5},
            {"unix_time":${sec + 14400},"o":1,"h":2,"l":0.5,"c":1.2,"v":8000000}]}}"""
        val rows = api.parseCandles8521(json, api.timeframeToSeconds("4H"))
        assertEquals(2, rows.size)
        assertEquals(start, rows.first().ts)
        assertEquals(Frame.H4.intervalMs, rows.first().intervalMs8521)
        assertEquals(15.5, rows.first().periodVolumeUsd8521!!, 0.0)
        assertNull(rows.last().periodVolumeUsd8521)
        assertEquals(0.0, rows.last().vol, 0.0)
    }

    @Test fun geckoParserDistinguishesAnAbsentVolumeColumnFromAnObservedZero() {
        val sec = start / 1000L
        val missing = SolanaOhlcvFeed6916.candleRow8521(JSONArray("[$sec,1,2,0.5,1.1]"), Frame.H4.intervalMs)!!
        val nulled = SolanaOhlcvFeed6916.candleRow8521(JSONArray("[$sec,1,2,0.5,1.1,null]"), Frame.H4.intervalMs)!!
        val zero = SolanaOhlcvFeed6916.candleRow8521(JSONArray("[$sec,1,2,0.5,1.1,0]"), Frame.H4.intervalMs)!!
        assertNull(missing.periodVolumeUsd8521)
        assertNull(nulled.periodVolumeUsd8521)
        assertEquals(0.0, zero.periodVolumeUsd8521!!, 0.0)
        assertEquals(start, zero.ts)
        assertEquals(Frame.H4.intervalMs, zero.intervalMs8521)
        assertNull(SolanaOhlcvFeed6916.candleRow8521(JSONArray("[$sec,1,0.7,0.5,1.1,50]"), Frame.H4.intervalMs))
    }

    @Test fun servingHourlyContextCannotOverwriteTheOneMinuteFreshnessStamp() {
        OhlcvRoute8332.resetForTest8332()
        val now = start + 24 * Frame.H1.intervalMs
        val one = bar(now - Frame.M1.intervalMs, Frame.M1)
        val four = bar(now - Frame.H4.intervalMs, Frame.H4)
        OhlcvRoute8332.served8332("gecko", "mint", listOf(one), now, 1)
        OhlcvRoute8332.served8332("gecko", "mint", listOf(four), now, 240)
        assertEquals(one.ts, OhlcvRoute8332.stamp8332("mint")!!.newestBarMs)
        assertEquals(four.ts, OhlcvRoute8332.stamp8332("mint", 240)!!.newestBarMs)
        assertTrue(OhlcvRoute8332.current8332("mint", 1, now))
        assertTrue(OhlcvRoute8332.current8332("mint", 240, now))
    }
}
