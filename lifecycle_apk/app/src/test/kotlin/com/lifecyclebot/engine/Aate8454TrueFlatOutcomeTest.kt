package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8454 — owner: "there isn't meant to be a lost mark ever, missing data ever, a stale price a stale mark nothing".
 * 8450 lost 1,034 labels in 10 minutes as flat. A flat read the market itself gave (off-watch fetch, curve account / tape,
 * independent basis) is the coin's real no-move outcome and is booked; a flat read from a token-state price alone waits
 * for a live fetch inside the window. (Supersedes 8337 FIX 2 for live marks; a token-state flat that never gets a live
 * confirmation still never books a -cost label.)
 */
class Aate8454TrueFlatOutcomeTest {
    private val l = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()

    @Test fun onlyAMarketObservationCountsAsLive() {
        assertTrue(ForwardReturnLabeler7731.liveMark8454("OFFWATCH", false))
        assertTrue(ForwardReturnLabeler7731.liveMark8454("CURVE_ACCOUNT", false))
        assertTrue(ForwardReturnLabeler7731.liveMark8454("DEXSCREENER_WS", true))
        assertFalse("a token-state price may be a reused value", ForwardReturnLabeler7731.liveMark8454("TOKEN_STATE", false))
        assertFalse(ForwardReturnLabeler7731.liveMark8454(null, false))
    }

    @Test fun aLiveFlatIsBookedAndATokenStateFlatWaitsForALiveFetch() {
        val sixty = l.substringAfter("private fun bookSixty7944(").substringBefore("// ── V5.0.7997 — PROGRESSIVE GRADING ──")
        val flat = sixty.indexOf("var flat8322 = com.lifecyclebot.engine.cortex.CortexFlatMark8322.flatMark8322(gross0, o.peakPct, o.minPct7962)")
        val live = sixty.indexOf("if (flat8322 && liveMark8454(o.lastMark8386)) { flat8322 = false; flatLiveBooked8454.incrementAndGet() }")
        assertTrue(live > flat && live < sixty.indexOf("book(o, 60, net, gross, w8324)"))
        val tick = l.substring(l.indexOf("private fun tickOnce8442("))
        val wait = tick.indexOf("flatAwaitingLive8454.incrementAndGet()")
        assertTrue("waits before the 5-minute slot is booked", wait in 0 until tick.indexOf("o.done60 = bookSixty7944(o, net, gross, nowMs)"))
        assertTrue(tick.contains("dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(0, o.atMs))"))
        assertTrue(l.contains("CortexFlatMark8322.flatMark8322(grossPct, o.peakPct, o.minPct7962) && !liveMark8454(o.lastMark8386)"))
    }
}
