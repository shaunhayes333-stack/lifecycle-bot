package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryEconomics8329
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8918 — the send boundary judges the move, not the admission's own economics; an expired ticket is still sent inside the
 * grace when the re-quote passes; a proven market cell answers the unproven falling-hour wait with its own lower bound.
 */
class Aate8918SendBoundaryTest {
    @Test fun theSendJudgesTheMove() {
        // MAYA, 21 Nov 23:01: expected 5.68 %, cost 3.03 %, moved 1.59 % — at a 2x margin the admission's economics never cleared
        // the margin unmoved; what remains (3.97 %) still beats the round trip: sent
        assertNull(EntryEconomics8329.consumed8329(1.59, EntryEconomics8329.UNCAPPED_8723, 5.68, 3.03, 2.0))
        // a remaining move at or under cost is still refused
        assertEquals("EDGE_CONSUMED", EntryEconomics8329.consumed8329(2.8, EntryEconomics8329.UNCAPPED_8723, 5.68, 3.03, 2.0))
        // cost above the expected move (DPRK SHITCOIN 19.11 % vs 22.80 %): refused
        assertEquals("EDGE_CONSUMED", EntryEconomics8329.consumed8329(1.68, EntryEconomics8329.UNCAPPED_8723, 19.11, 22.80, 2.0))
        // a true chase (cleared unmoved, consumed by the move) is refused as before
        assertEquals("EDGE_CONSUMED", EntryEconomics8329.consumed8329(5.0, 8.0, 20.0, 10.0, 1.5))
        assertTrue(EntryEconomics8329.line8918().startsWith("sendBoundary8918[lateSent="))
    }

    @Test fun theSecondsAreSplitMarkToSealAndSealToSend() {
        assertEquals("-", EntryEconomics8329.quantiles8919(emptyList()))
        assertEquals("3.0/9.0s", EntryEconomics8329.quantiles8919(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 10_000L)))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryEconomics8329.kt").readText().contains("noteTiming8919(observedAt, intent.createdAt, nowMs)"))
    }

    @Test fun aLateTicketIsSentInsideTheGrace() {
        assertTrue(EntryEconomics8329.lateSendAllowed8918(1_000L, 1_000L + 30_000L))
        assertTrue(EntryEconomics8329.lateSendAllowed8918(1_000L, 1_000L + EntryEconomics8329.LATE_SEND_GRACE_MS_8918))
        assertFalse(EntryEconomics8329.lateSendAllowed8918(1_000L, 1_000L + EntryEconomics8329.LATE_SEND_GRACE_MS_8918 + 1))
        assertFalse("a clock running backwards is no grace", EntryEconomics8329.lateSendAllowed8918(10_000L, 1_000L))
    }

    @Test fun wired() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryEconomics8329.kt").readText()
        assertTrue(e.contains("(nowMs >= intent.expiresAtMs6613 && !lateSendAllowed8918(intent.expiresAtMs6613, nowMs))) return \"ENTRY_CHASE_DECISION_STALE_\$lane\""))
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        val fh = a.substring(a.indexOf("marketCellCarried8918"))
        assertTrue(fh.indexOf("grossFromMarketCellLower8918") in 0 until fh.indexOf("deferUntilHourTurns"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("EntryEconomics8329.line8918()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 106. The send judges the move (8918)"))
    }
}
