package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8631 — DIAG_AND_BUILD_8629 Step 1.4: opportunity "leaders" (top-of-move trending coins) are no longer forced into
 * every cycle slice; runner-lane hunted rows lost in every 1 h-move band in the 8622 export (PF 0.23-0.54).
 */
class Aate8631LeadersTest {
    @Test fun an_unread_live_stop_waits_briefly_for_the_venue() {
        val t0 = 1_800_000_000_000L
        // V5.0.8706 — after the wait only the position's own venue's mark fires; another venue's mark keeps asking.
        assertFalse(StopMarkConfirm8418.unreadStopFires8706(t0, t0 + 1_000L, -16.0, markOnVenue = true))
        assertTrue("after the wait its own venue's mark fires", StopMarkConfirm8418.unreadStopFires8706(t0, t0 + StopMarkConfirm8418.NO_READ_HOLD_MS_8631, -16.0, markOnVenue = true))
        // superseded by V5.0.8759 — the unread hold is bounded (MAX_UNREAD_HOLD_MS_8759); was: another venue's mark at -40 % after 10 min is not a sell.
        assertFalse("another venue's mark is not a sell inside the bound", StopMarkConfirm8418.unreadStopFires8706(t0, t0 + StopMarkConfirm8418.MAX_UNREAD_HOLD_MS_8759 - 1L, -40.0, markOnVenue = false))
        assertTrue("past the bound it fires", StopMarkConfirm8418.unreadStopFires8706(t0, t0 + StopMarkConfirm8418.MAX_UNREAD_HOLD_MS_8759, -40.0, markOnVenue = false))
        assertTrue("a mark at -60 % or worse fires at once", StopMarkConfirm8418.unreadStopFires8706(t0, t0 + 1_000L, -65.0, markOnVenue = false))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        val fn = src.substringAfter("fun stopRefutedByVenue8489(").substringBefore("val depth = stopDepthPct8489(reason)")
        // superseded by V5.0.8759 — the wait records the stop's depth so a recovered mark ends the breach; was: holdForVenueRead8631(ts, key8631, nowMs).
        assertTrue(fn.contains("return holdForVenueRead8631(ts, key8631, nowMs, stopDepthPct8489(reason) ?: impliedStopDepthPct8681(reason))"))
    }

    @Test fun leaders_are_not_forced_until_they_earn_it() {
        assertFalse(HunterWindow8293.forceLeaders8631)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/HunterWindow8293.kt").readText()
        val fn = src.substringAfter("private fun leaders8293(").substringBefore("private fun leadersPicked8293(")
        assertTrue(fn.contains("if (forceLeaders8631 || picked.isEmpty()) return picked"))
        assertTrue(fn.contains("OPPORTUNITY_LEADER_NOT_FORCED_8631"))
    }
}
