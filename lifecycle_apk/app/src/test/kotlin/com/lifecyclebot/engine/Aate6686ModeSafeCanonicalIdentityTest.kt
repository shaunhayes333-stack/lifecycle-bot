package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6686ModeSafeCanonicalIdentityTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `executor canonical mirror identity is mode plus mint`() {
        val mirror = src("com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt")
        assertTrue(mirror.contains("activePositionIdByModeMint"))
        assertTrue(mirror.contains("modeKey(mint: String, paperMode: Boolean)"))
        assertTrue(mirror.contains("positionIdOf(mint: String, paperMode: Boolean? = null)"))
        assertTrue(mirror.contains("positionIdOf(mint, paperMode)"))
        assertTrue(mirror.contains("it.mode.equals(mode, ignoreCase = true)"))
        assertTrue(mirror.contains("CANONICAL_POSITION_MODE_AMBIGUITY_6686"))
        assertFalse(mirror.contains("activePositionIdByMint"))
        assertFalse(mirror.contains("lastClosedPositionIdByMint"))
    }

    @Test fun `live sell finalizer uses explicit live canonical identity`() {
        val sell = src("com/lifecyclebot/engine/sell/SellFinalizationCoordinator.kt")
        assertTrue(sell.contains("positionIdOf(intent.mint, paperMode = false)"))
    }
}
