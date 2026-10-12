package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exit profiles must inherit the exact immutable setup traded, not a later chart read. */
class Aate8081ExactExitProfileTest {
    @Test fun entrySetupPresenceAndFallbackAreExplicit() {
        assertTrue(ExitProfile7955.setupFired7961("GRADUATION_APPROACH"))
        assertTrue(ExitProfile7955.setupFired7961("DIAMOND_HANDS_RUNNER"))
        assertFalse(ExitProfile7955.setupFired7961("NO_TRIGGER"))
        assertFalse(ExitProfile7955.setupFired7961("NONE"))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExitProfile7955.kt").readText()
        val entry = src.substringAfter("fun planFor7955(ts:")
            .substringBefore("fun notePeak7955(")
        assertTrue(entry.contains("EntryStrategySnapshot6450.snapshot(pos.positionId)"))
        assertTrue(entry.contains("sealed8081?.entrySetup?.takeIf { setupFired7961(it) }"))
        assertTrue(entry.contains("val lane = canon(sealed8081?.entryLane ?: pos.tradingMode)"))
        val close = src.substringAfter("fun onClose7955(env:")
            .substringBefore("fun setupFired7961(")
        assertTrue(close.contains("terminalEconomicsEligible8081(env.mode, env.proofState)"))
        assertTrue(close.contains("!env.learningEligible"))
        assertTrue(close.contains("env.entrySetup.takeIf { setupFired7961(it) }"))
        assertTrue(close.contains("val setup = keyParts?.getOrNull(1) ?: sealed8081 ?: \"NONE\""))
    }
}
