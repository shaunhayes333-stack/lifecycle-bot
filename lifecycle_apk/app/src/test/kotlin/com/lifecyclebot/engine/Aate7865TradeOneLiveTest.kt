package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SelectionQualityAuthority6829
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7865TradeOneLiveTest {

    @After fun cleanup() = SelectionQualityAuthority6829.clearForTest()

    @Test fun paperClosesNeverRaiseTheLiveSelectionFloor() {
        repeat(20) { SelectionQualityAuthority6829.recordTerminal("PAPER", "SHITCOIN", false) }
        assertEquals("PAPER|SHITCOIN", SelectionQualityAuthority6829.ringKey7865("PAPER", "shitcoin"))
        assertEquals("LIVE|SHITCOIN", SelectionQualityAuthority6829.ringKey7865("LIVE", "SHITCOIN"))
        assertEquals("PAPER|SHITCOIN", SelectionQualityAuthority6829.ringKey7865("unknown", "SHITCOIN"))
        assertTrue(SelectionQualityAuthority6829.statusLine().contains("PAPER|SHITCOIN=0.0/20"))
        assertFalse(SelectionQualityAuthority6829.statusLine().contains("LIVE|SHITCOIN"))
    }

    @Test fun busFeedsTheRingWithTheEnvelopeMode() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        assertTrue(bus.contains("SelectionQualityAuthority6829.recordTerminal(env.mode, env.lane, won)"))
    }

    @Test fun noEvidenceIsBootstrapSizeNotDeepDeficitFloor() {
        val wr = File("src/main/kotlin/com/lifecyclebot/engine/WrRecoveryPartial.kt").readText()
        assertTrue(wr.contains("return State(Band.FLUID, 0.0, 0.0, total.toInt(), -1.0, false, bootstrap = true)"))
        assertFalse(wr.contains("return State(Band.AGGRESSIVE, 0.0, 0.0, total.toInt(), -1.0, false)"))
        assertTrue(wr.contains("if (s.bootstrap) return BOOTSTRAP_SIZE_MULT_7865"))
        assertEquals(0.75, WrRecoveryPartial.BOOTSTRAP_SIZE_MULT_7865, 1e-9)
    }

    @Test fun strongRugcheckFallbackIsNotTaggedAsARefusedLiveProbe() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertFalse(fdg.contains("tags.add(\"rc_timeout_live_probe\")"))
        assertTrue(fdg.contains("tags.add(\"rugcheck_timeout_fallback\")"))
    }
}
