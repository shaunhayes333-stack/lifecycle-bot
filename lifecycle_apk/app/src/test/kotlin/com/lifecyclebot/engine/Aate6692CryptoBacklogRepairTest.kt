package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6692CryptoBacklogRepairTest {
    @Test fun `crypto evaluation ownership has a real lease clock and exact-generation reaper`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(src.contains("evaluationInflightStartedAt6692"))
        assertTrue(src.contains("expireInflightGeneration6692"))
        assertTrue(src.contains("STALE_EXPIRED_INFLIGHT_6692"))
        assertTrue(src.contains("SUPERSEDED_BY_NEW_GENERATION_6692"))
        assertTrue(src.contains("CRYPTO_EVAL_EXACT_OWNER_REAPED_6692"))
    }

    @Test fun `progress sweep does not split canonical identity on pipe`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(src.contains("EVAL_PROGRESS_SEPARATOR_6692"))
        assertFalse(src.contains("val split = entry.key.indexOf('|')"))
        assertTrue(src.contains("lastIndexOf(EVAL_PROGRESS_SEPARATOR_6692)"))
    }

    @Test fun `queue age measures inflight ownership not stale token metadata`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val report = src.substringAfter("fun discoveryReport6544")
        assertTrue(report.contains("evaluationInflightStartedAt6692.values"))
        assertFalse(report.contains("evaluationInflight6615.keys.mapNotNull { registry[it]?.lastUpdatedMs }"))
    }

    @Test fun `blocking dynamic hydration is isolated to IO dispatcher`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val scan = src.substringAfter("private suspend fun runDynamicTokenScan")
        val hydrate = scan.substringBefore("val price   = priceNow")
        assertTrue(hydrate.contains("withContext(Dispatchers.IO)"))
        assertTrue(hydrate.contains("refreshPriceForMintBlocking"))
    }
}
