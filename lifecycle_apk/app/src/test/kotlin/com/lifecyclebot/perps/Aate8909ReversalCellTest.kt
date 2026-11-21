package com.lifecyclebot.perps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8909 — a reclaim after a down day (24 h down, 5 m and 1 h up) is its own trend band, graded on the twin like any cell. */
class Aate8909ReversalCellTest {
    private val nan = Double.NaN

    @Test fun theDayDownAndTheHourUpIsTheReversalBand() {
        assertEquals("TR", CryptoTrend8886.band8886(listOf(0.8, 1.5, -2.0, -6.0, -9.0)))
        assertEquals("6 h unread still reads the reversal", "TR", CryptoTrend8886.band8886(listOf(0.8, 1.5, nan, -6.0)))
        assertEquals("the hour still falling is not a reclaim", "T1", CryptoTrend8886.band8886(listOf(0.8, -1.5, -2.0, -6.0)))
        assertEquals("the day up is not a reversal", "T2", CryptoTrend8886.band8886(listOf(0.8, 1.5, -2.0, 6.0)))
        assertEquals("no 24 h read, no reversal", "T2", CryptoTrend8886.band8886(listOf(0.8, 1.5, -2.0, nan)))
        assertEquals("all down stays T0", "T0", CryptoTrend8886.band8886(listOf(-0.8, -1.5, -2.0, -6.0)))
        assertFalse(CryptoTrend8886.reversal8909(listOf(nan, 1.5, -2.0, -6.0)))
        assertEquals("TRF1", CryptoTrend8886.cellOfReasons8886(listOf("TREND8886=TRF1V1")))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 97. The reversal cell (8909)"))
    }
}
