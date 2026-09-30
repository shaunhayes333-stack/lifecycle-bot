package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7530PumpScannerWasteCircuitTest {
    @Test fun direct_scanner_half_opens_on_wire_health_not_candidate_count() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SolanaMarketScanner.kt").readText()
        val fn = s.substringAfter("private suspend fun scanPumpFunDirect()")
            .substringBefore("private suspend fun scanPumpFunVolume()")
        assertTrue(fn.contains("validJsonResponses7530"))
        assertTrue(fn.contains("PUMPFUN_DIRECT_SCANNER_CIRCUIT_OPEN_7530"))
        assertTrue(fn.contains("PUMPFUN_DIRECT_SCANNER_CIRCUIT_SKIP_7530"))
        assertTrue(fn.contains("urls.take(1)"))
        assertFalse(fn.contains("if (totalFound == 0) {\n            pumpDirect"))
    }

    @Test fun circuit_comment_keeps_pumpportal_and_curve_authorities_separate() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SolanaMarketScanner.kt").readText()
        assertTrue(s.contains("PumpPortal WS, PumpPortal execution and curve RPC are separate authorities"))
    }
}
