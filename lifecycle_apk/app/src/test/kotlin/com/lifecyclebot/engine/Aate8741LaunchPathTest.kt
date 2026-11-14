package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchHour8628
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8741 — every launch's first hour exported as 10-second bars with flow, for the entry/exit replay. */
class Aate8741LaunchPathTest {
    @Test fun printsFoldIntoTenSecondBars() {
        val bars = java.util.TreeMap<Long, DoubleArray>()
        LaunchHour8628.bars8741(bars, 1_000L, 1.0, true, 0.5)
        LaunchHour8628.bars8741(bars, 4_000L, 1.4, true, 1.0)
        LaunchHour8628.bars8741(bars, 9_000L, 0.9, false, 0.3)
        LaunchHour8628.bars8741(bars, 12_000L, 1.1, true, 0.2)
        LaunchHour8628.bars8741(bars, 15_000L, Double.NaN, true, 9.0)
        assertEquals(2, bars.size)
        val b0 = bars[0L]!!
        assertEquals(listOf(1.0, 1.4, 0.9, 0.9, 2.0, 1.0, 1.5, 0.3), b0.toList())
        assertEquals(1.1, bars[1L]!![3], 0.0)
    }

    @Test fun theExportCarriesPathsAndSizes() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchHour8628.kt").readText()
        // V5.0.8748 (supersedes the 6-column insert): the receive time and feed ride with every print
        assertTrue(src.contains("INSERT OR IGNORE INTO trade(m,t,s,side,p,v,r,src,w) VALUES(?,?,?,?,?,?,?,?,?)"))   // V5.0.8756 + wallet
        assertTrue(src.contains("ALTER TABLE trade ADD COLUMN v REAL"))
        assertTrue(src.contains("if (prints.size >= PATH_MIN_PRINTS_8741) w.appendLine(pathRow8741("))
    }
}
