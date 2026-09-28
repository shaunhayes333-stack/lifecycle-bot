package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7413CryptoHeldTelemetryTest {
    @Test fun stale_dynamic_mark_uses_one_rate_limited_state_emitter() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val monitor = s.substringAfter("private suspend fun monitorPositions()").substringBefore("private fun partialPosition6566")
        assertFalse(monitor.contains("labelInc(\"CRYPTO_DYN_MARK_STALE_OR_MISSING_6654\")"))
        assertTrue(monitor.contains("holdUntrustedDynamicPosition7245(position, \"MARK_STALE_OR_MISSING\")"))
        assertTrue(s.contains("heldRefreshCoalescedEmitAt7413"))
    }
}
