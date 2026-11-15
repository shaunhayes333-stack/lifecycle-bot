package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8759 — code audit (exits F1, F2, F3, F9): the unread stop is bounded and keeps one breach clock for the request
 * path and the sell-time check; the doctrine trail runs after the catastrophe and hard-floor stops and sells on two marks.
 */
class Aate8759StopAndTrailOrderTest {
    @Test fun an_unread_stop_is_held_for_a_bounded_time() {
        val t0 = 1_800_000_000_000L
        assertFalse(StopMarkConfirm8418.unreadStopFires8706(t0, t0 + 20_000L, -18.0, markOnVenue = false))
        assertTrue("a -15 % stop is not held to -60 %", StopMarkConfirm8418.unreadStopFires8706(t0, t0 + StopMarkConfirm8418.MAX_UNREAD_HOLD_MS_8759, -18.0, markOnVenue = false))
        assertTrue("the ask gap covers the 30 s risk clock", StopMarkConfirm8418.BREACH_GAP_MS_8712 > 30_000L)
        assertEquals("a risk-clock re-ask 30 s later is the same breach", t0, StopMarkConfirm8418.breachStart8712(t0, t0, t0 + 30_000L))
        assertTrue("a mark back above half the stop ends the breach", StopMarkConfirm8418.breachEnded8759(-5.0, -15.0))
        assertFalse("a mark still under it does not", StopMarkConfirm8418.breachEnded8759(-12.0, -15.0))
        assertFalse("no recorded depth, no ending", StopMarkConfirm8418.breachEnded8759(5.0, null))
    }

    @Test fun the_sell_time_check_shares_the_request_paths_clock() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        val hold = s.substringAfter("private fun holdForVenueRead8631(").substringBefore("\n    }\n")
        assertFalse("a fired breach keeps its clock", hold.contains("if (fires) noReadSince8631.remove(key)"))
        val sell = s.substringAfter("fun sellsWithoutRouteQuote8706(").substringBefore("\n    }\n")
        assertFalse("no fresh clock at sell time", sell.contains("?: nowMs"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
            .contains("StopMarkConfirm8418.noteMark8759(ts, pnlPct)"))
    }

    @Test fun the_trail_prices_on_a_peak_two_marks_agree_on() {
        assertEquals("a one-print phantom does not set the peak: the two marks agree on +40 %", 40.0, RunnerDoctrine8584.twoMarkPeak8759(30.0, 40.0, 12_105.0), 1e-9)
        assertEquals(60.0, RunnerDoctrine8584.twoMarkPeak8759(30.0, 60.0, 65.0), 1e-9)
        val k = "pos8759"
        var at = 1_000L
        for (p in listOf(60.0, 62.0)) assertFalse(RunnerDoctrine8584.trailDue8759(k, "SHITCOIN", p, 12_105.0, at++))
        assertFalse("the same mark again pairs with nothing", RunnerDoctrine8584.trailDue8759(k, "SHITCOIN", 3.0, 12_105.0, at - 1))
        assertFalse("one mark under the line does not sell", RunnerDoctrine8584.trailDue8759(k, "SHITCOIN", 3.0, 12_105.0, at++))
        assertTrue("the second one does", RunnerDoctrine8584.trailDue8759(k, "SHITCOIN", 2.0, 12_105.0, at++))
        assertFalse("other lanes keep their exits", RunnerDoctrine8584.trailDue8759("q8759", "QUALITY", 0.0, 80.0, 5L))
        assertTrue(RunnerDoctrine8584.TWO_MARK_WINDOW_MS_8759 > 0L)
    }
}
