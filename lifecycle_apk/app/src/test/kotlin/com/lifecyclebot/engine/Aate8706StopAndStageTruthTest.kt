package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitQuoteCorroboration7928
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8706 — 8704 live: three "-15 % / -25 %" stops realised -0.9 %, -3.3 %, -3.4 % (no executable read answered and the
 * trigger mark was not the position's venue), and live bought LAUNCH, the worst stage on its own board, because the stage
 * door named two other stages by hand.
 */
class Aate8706StopAndStageTruthTest {
    @Test fun noRouteQuoteIsNotAConfirmation() {
        ExitQuoteCorroboration7928.resetForTest8521()
        assertFalse("no quote and nothing on the venue: hold", ExitQuoteCorroboration7928.allowSell("m8706a", "X", "RAPID_HARD_FLOOR_STOP", -18.0, 0.04,
            stopThresholdPct8521 = -15.0, sellsUnquoted8706 = { false }) { null })
        assertTrue("no quote, the venue's own mark breached: sell", ExitQuoteCorroboration7928.allowSell("m8706b", "X", "RAPID_HARD_FLOOR_STOP", -18.0, 0.04,
            stopThresholdPct8521 = -15.0, sellsUnquoted8706 = { true }) { null })
        var asked = false
        assertTrue("a quote that confirms never consults the unread rule", ExitQuoteCorroboration7928.allowSell("m8706c", "X", "RAPID_HARD_FLOOR_STOP", -18.0, 0.04,
            stopThresholdPct8521 = -15.0, sellsUnquoted8706 = { asked = true; false }) { 0.030 })
        assertFalse(asked)
    }

    @Test fun oneRuleServesTheRequestPathAndTheSellTimeCheck() {
        val stop = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        assertTrue(stop.contains("val fires = unreadStopFires8706(first, nowMs, markPnlPct8706(ts), onVenue)"))
        assertTrue(stop.contains("val fires = unreadStopFires8706(first, nowMs, markPnlPct8706(ts), markOnVenue8706(ts))"))
        assertTrue(stop.contains("if (ts.position.isPaperPosition || !priceLossStop8489(reason)) return true"))
        assertFalse(stop.contains("fun holdsAwaitingRead8631("))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
            .contains("sellsUnquoted8706 = { StopMarkConfirm8418.sellsWithoutRouteQuote8706(ts, reason) }) {"))
        assertTrue("a rug is never a price-loss stop", !StopMarkConfirm8418.priceLossStop8489("RUG_PULL_DETECTED"))
    }
}
