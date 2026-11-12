package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8728 — with no route quote in time, an executable read decides a live price-loss stop by its value, not its presence. */
class Aate8728StopReadDecidesTest {
    @Test fun theJjaemuCase() {
        // LANE_HARD_15PCT_SL: the executable value was about flat; it refutes a -15 % stop, so the stop holds
        assertTrue(StopMarkConfirm8418.venueRefutes8489(-0.5, StopMarkConfirm8418.stopDepthPct8489("LANE_HARD_15PCT_SL_SHITCOIN") ?: -15.0))
        // a read past half the stop's depth confirms it: the stop sells
        assertFalse(StopMarkConfirm8418.venueRefutes8489(-11.0, -15.0))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        val fn = src.substringAfter("fun sellsWithoutRouteQuote8706(").substringBefore("\n    }\n")
        assertFalse("presence of a read no longer sells", fn.contains("if (venuePnlPct8489(ts, nowMs) != null) return true"))
        assertTrue(fn.contains("val read8728 = venuePnlPct8489(ts, nowMs) ?: executablePnlSinceAsk8681(ts, ts.position.positionId.ifBlank { ts.mint })"))
        assertTrue(fn.contains("return !refutes"))
        assertTrue("a rug or a non-price exit still sells", fn.contains("if (ts.position.isPaperPosition || !priceLossStop8489(reason)) return true"))
    }
}
