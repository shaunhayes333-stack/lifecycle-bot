package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8712 — 8711 live: two BASE entries sold 17 s and 68 s after the buy at -4.0 % / -3.3 % on "-15 %" stops. */
class Aate8712UnbrokenBreachTest {
    @Test fun aSecondWickStartsANewWait() {
        val t0 = 1_000_000L
        assertEquals("first ask starts the wait", t0, StopMarkConfirm8418.breachStart8712(null, null, t0))
        assertEquals("still asking: the same breach", t0, StopMarkConfirm8418.breachStart8712(t0, t0 + 1_000L, t0 + 2_000L))
        assertEquals("asked again right at the gap: the same breach", t0,
            StopMarkConfirm8418.breachStart8712(t0, t0 + 1_000L, t0 + 1_000L + StopMarkConfirm8418.BREACH_GAP_MS_8712))
        val later = t0 + 9_000L
        assertEquals("the price came back and wicked again: a new wait", later, StopMarkConfirm8418.breachStart8712(t0, t0 + 1_000L, later))
        // so the wick that used to sell at once is held like any first ask
        assertTrue(!StopMarkConfirm8418.unreadStopFires8706(StopMarkConfirm8418.breachStart8712(t0, t0 + 1_000L, later), later, -16.0, markOnVenue = true))
        val s = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        assertTrue(s.contains("val first = breachStart8712(noReadSince8631[key], lastUnreadAsk8712.put(key, nowMs), nowMs)"))
    }
}
