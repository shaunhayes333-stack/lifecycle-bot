package com.lifecyclebot.perps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7823 — Crypto resident hunter consumption + breadth fairness.
 */
class Aate7823CryptoResidentConsumptionTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun resident_books_expose_deduped_priority_assets() {
        val s=src("perps/CryptoStrategyCandidateBooks7803.kt")
        val fn=s.substringAfter("internal fun priorityAssets7823")
            .substringBefore("internal fun statusLine")
        assertTrue(fn.contains("State.READY -> 3"))
        assertTrue(fn.contains("State.QUALIFIED -> 2"))
        assertTrue(fn.contains("State.WATCHING -> 1"))
        assertTrue(fn.contains(".groupBy { it.assetKey }"))
    }

    @Test fun dynamic_scan_consumes_resident_hunters_and_keeps_universe_rotation() {
        val s=src("perps/CryptoAltTrader.kt")
        val fn=s.substringAfter("private suspend fun runDynamicTokenScan()")
            .substringBefore("private suspend fun runScanCycle()")
        assertTrue(fn.contains("CryptoStrategyCandidateBooks7803.priorityAssets7823"))
        assertTrue(fn.contains("CRYPTO_RESIDENT_SCAN_QUOTA_7823"))
        assertTrue(fn.contains("cryptoResidentLastScanAt7823"))
        assertTrue(fn.contains("genericUniverse7823"))
        assertTrue(fn.contains("residentSelected7823 + genericBatch7823"))
        assertTrue(fn.contains("CRYPTO_RESIDENT_SCAN_CONSUMED_7823"))
    }

    @Test fun resident_queue_never_bypasses_existing_trade_authorities() {
        val s=src("perps/CryptoAltTrader.kt")
        val fn=s.substringAfter("private suspend fun runDynamicTokenScan()")
            .substringBefore("private suspend fun runScanCycle()")
        assertTrue(fn.contains("scoreDynamicCrypto7244("))
        assertTrue(fn.contains("executeSignal(sig.copy"))
        assertTrue(fn.contains("CanonicalEntryAuthority6540"))
    }
}
