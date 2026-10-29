package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceHold8336
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** V5.0.8418 directive #37 — the evidence hold is off: it never refuses a live entry. */
class Aate8418EvidenceHoldOffTest {
    @Test fun theHoldIsOffByDefaultAndRefusesNothing() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/EvidenceHold8336.kt").readText()
        org.junit.Assert.assertTrue(src.contains("@Volatile var enabled8336: Boolean = false"))
        val saved = EvidenceHold8336.enabled8336
        try {
            EvidenceHold8336.enabled8336 = false
            for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "QUALITY", "CORE", "CYCLIC", "TREASURY", "CASHGEN")) {
                assertNull(lane, EvidenceHold8336.refusal8336(lane, false))
            }
            assertFalse(EvidenceHold8336.enabled8336)
        } finally { EvidenceHold8336.enabled8336 = saved }
    }
}
