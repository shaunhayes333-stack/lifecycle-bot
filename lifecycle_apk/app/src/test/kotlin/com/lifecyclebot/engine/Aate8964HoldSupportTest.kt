package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HoldSupport8964
import com.lifecyclebot.engine.truth.HoldSupport8964.Read8964
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8964 — a time exit reassesses the position's own live metrics; it holds only when two fresh signs support it. */
class Aate8964HoldSupportTest {
    private val nan = Double.NaN
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aWinnerThatIsStillWorkingIsNotSoldOnTheClock() {
        assertEquals("UP_BUYERS", HoldSupport8964.supports8964(Read8964(true, 12.0, 15.0, 2.0, nan, buyPct = 61.0)))
        assertEquals("UP_15M_AND_1H", HoldSupport8964.supports8964(Read8964(true, 4.0, 5.0, 0.5, 3.0)))
        assertEquals("UP_VOLUME_HELD", HoldSupport8964.supports8964(Read8964(true, 4.0, 5.0, 0.0, nan, participation = 0.9)))
    }

    @Test fun theClockStillClosesWhatTheMetricsDoNotSupport() {
        assertNull("stale price", HoldSupport8964.supports8964(Read8964(false, 12.0, 15.0, 2.0, 3.0, 70.0, 1.0)))
        assertNull("rolled over: under half the peak", HoldSupport8964.supports8964(Read8964(true, 6.0, 20.0, 2.0, 3.0, 70.0)))
        assertNull("last 15 minutes falling", HoldSupport8964.supports8964(Read8964(true, 6.0, 8.0, -0.5, 3.0, 70.0)))
        assertNull("sellers dominate", HoldSupport8964.supports8964(Read8964(true, 6.0, 8.0, 1.0, 3.0, 40.0)))
        assertNull("volume dying", HoldSupport8964.supports8964(Read8964(true, 6.0, 8.0, 1.0, 3.0, 60.0, 0.3)))
        assertNull("one sign is not two", HoldSupport8964.supports8964(Read8964(true, 6.0, 8.0, 1.0, nan)))
        assertNull("unread 15 minutes", HoldSupport8964.supports8964(Read8964(true, 6.0, 8.0, nan, 3.0, 70.0)))
    }

    @Test fun underWaterHoldsOnlyWhileRecoveringOnTwoSigns() {
        assertEquals("RECOVERING_BUYERS", HoldSupport8964.supports8964(Read8964(true, -4.0, 2.0, 1.5, nan, buyPct = 58.0)))
        assertEquals("RECOVERING_15M_AND_1H", HoldSupport8964.supports8964(Read8964(true, -4.0, 2.0, 1.5, 0.8)))
        assertNull(HoldSupport8964.supports8964(Read8964(true, -4.0, 2.0, 0.0, 2.0, 70.0)))
        assertNull(HoldSupport8964.supports8964(Read8964(true, -4.0, 2.0, 1.5, nan, buyPct = 52.0)))
        assertNull(HoldSupport8964.supports8964(Read8964(true, -4.0, 2.0, 1.5, 0.8, buyPct = 45.0)))
    }

    @Test fun onlyClockExitsAreReassessed() {
        assertTrue(HoldSupport8964.isTimeExit8964("ADAPTIVE_HOLD_MAX_6663:3600s pnl=1.2%"))
        assertTrue(HoldSupport8964.isTimeExit8964("UNDERWATER_TIME_STOP_7739_-3PCT"))
        assertTrue(HoldSupport8964.isTimeExit8964("TIME_FLUSH_30MIN_FLAT: pnl=0.4%"))
        assertTrue(HoldSupport8964.isTimeExit8964("STALE_POSITION"))
        assertFalse(HoldSupport8964.isTimeExit8964("LANE_HARD_15PCT_SL_SHITCOIN"))
        assertFalse(HoldSupport8964.isTimeExit8964("RUG_TIMEOUT_EMERGENCY"))
        assertFalse(HoldSupport8964.isTimeExit8964("RECONCILE_TIMEOUT"))
        assertFalse(HoldSupport8964.isTimeExit8964("AGE_8849_SELL_DOMINANT_TAPE"))
        assertFalse(HoldSupport8964.isTimeExit8964("TRAIL_STOP_8719"))
    }

    @Test fun movesAreReadFromThePositionsOwnMarks() {
        val pts = listOf(1_000L to 1.0, 2_000L to 1.1, 3_000L to 1.2)
        assertEquals(10.0, HoldSupport8964.moveSince8964(pts, 1_500L, 1.1), 1e-9)
        assertTrue(HoldSupport8964.moveSince8964(pts, 500L, 1.1).isNaN())
        val k = "TEST|8964"
        HoldSupport8964.noteMark8964(k, 1.00, 0L)
        HoldSupport8964.noteMark8964(k, 1.02, 46L * 60_000L)
        HoldSupport8964.noteMark8964(k, 1.04, 61L * 60_000L)
        assertTrue("15 min and 1 h both up", HoldSupport8964.holdKeyed8964(k, "TEST", 1.05, 61L * 60_000L, 5.0, 5.0, 1, 61L * 60_000L))
        assertFalse("a short gains when the price falls", HoldSupport8964.holdKeyed8964(k, "TEST", 1.05, 61L * 60_000L, 5.0, 5.0, -1, 61L * 60_000L))
        HoldSupport8964.forget8964(k)
    }

    @Test fun theThreeBooksReassessBeforeTheClockCloses() {
        val ca = src("perps/CryptoAltTrader.kt")
        assertTrue(ca.indexOf("HoldSupport8964.holdKeyed8964(\"ALT|\$id\"") in 0 until ca.indexOf("&& !holdSupported8964) return ExitTick8883(ExitAct8883.CLOSE,"))
        val xs = src("perps/TokenizedStockTrader.kt")
        assertTrue(xs.contains("if (!holdSupported8964) when {"))
        val ex = src("engine/Executor.kt")
        val hold = ex.indexOf("HoldSupport8964.holdMeme8964(ts, reason)")
        assertTrue(hold in ex.indexOf("NetEdge8030.exitRefusal8030(ts, reason, getActualPrice(ts), partial = false)") until ex.indexOf("FieldManual7715.noteExit(reason, ts.position.isPaperPosition)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("HoldSupport8964.statusLine8964()"))
    }
}
