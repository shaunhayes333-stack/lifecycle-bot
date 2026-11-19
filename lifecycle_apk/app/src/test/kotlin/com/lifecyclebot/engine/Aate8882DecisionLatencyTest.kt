package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DecisionLatency8882
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8882 — the launch-hour replay says the doctrine's edge is in a launch's first seconds, and the first lane decision
 * comes a median 41 s after birth. The brief now splits that delay into birth->watchlist and watchlist->decision per source.
 */
class Aate8882DecisionLatencyTest {
    @Before fun setUp() = DecisionLatency8882.resetForTest8882()
    @After fun tearDown() = DecisionLatency8882.resetForTest8882()

    @Test fun theDelayIsSplitIntoHearingAndDeciding() {
        assertEquals(4L to 37L, DecisionLatency8882.split8882(1_000_000L, 1_004_000L, 1_041_000L))
        assertEquals("a watchlist time outside birth..decision is unknown, the whole delay is the decision's",
            -1L to 41L, DecisionLatency8882.split8882(1_000_000L, 900_000L, 1_041_000L))
        assertEquals("PUMP_PORTAL_WS", DecisionLatency8882.family8882("pump_portal_ws,SCANNER_DIRECT"))
        assertEquals("UNKNOWN", DecisionLatency8882.family8882(""))
        assertEquals("UNKNOWN", DecisionLatency8882.family8882(null))
    }

    @Test fun onlyTheFirstDecisionInsideTheHourCounts() {
        DecisionLatency8882.note8882("MintA", "PUMP_PORTAL_WS", 1_000_000L, 1_002_000L, 1_008_000L)
        DecisionLatency8882.note8882("MintA", "PUMP_PORTAL_WS", 1_000_000L, 1_002_000L, 1_090_000L)   // a later lane: ignored
        DecisionLatency8882.note8882("MintB", "PUMP_PORTAL_WS", 1_000_000L, 1_010_000L, 1_060_000L)
        DecisionLatency8882.note8882("MintC", "CHAIN_LAUNCH_METEORA", 1_000_000L, 1_001_000L, 1_000_000L + 3_700_000L)   // past the hour
        val line = DecisionLatency8882.statusLine8882()
        assertTrue(line, line.contains("PUMP_PORTAL_WS{n=2 heard50/90=2/2s decide50/90=6/6s within10s=50%}"))
        assertTrue(line, !line.contains("CHAIN_LAUNCH_METEORA"))
    }

    @Test fun itIsWiredAtTheLaunchHoursDecisionRow() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val hour = src("engine/truth/LaunchHour8628.kt").substringAfter("fun observeDecision(").substringBefore("private fun decision(")
        assertTrue(hour.contains("DecisionLatency8882.note8882(ts.mint, ts.source, born, ts.addedToWatchlistAt, at)"))
        assertTrue("measured only for a launch inside its first hour", hour.indexOf("if (at - born !in 0L..HOUR) return") < hour.indexOf("DecisionLatency8882.note8882("))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("DecisionLatency8882.statusLine8882()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 71. Where a launch's first seconds go (8882)"))
    }
}
