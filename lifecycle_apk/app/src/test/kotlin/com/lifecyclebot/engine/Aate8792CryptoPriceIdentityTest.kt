package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoAltTrader
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8792CryptoPriceIdentityTest {
    private val now = 1_728_086_400_000L

    @Test fun recentSameAssetReferenceCanRefuseAnObviousDecimalShift() {
        assertEquals(99.0, CryptoAltTrader.preSubmitSameAssetDiffPct8792(
            signalPrice = 0.01, referencePrice = 1.0, observedAtMs = now - 1_000L, nowMs = now,
            signalIdentity = "base|0xcoin", referenceIdentity = "base|0xcoin")!!, 0.0001)
        assertEquals(5.0, CryptoAltTrader.preSubmitSameAssetDiffPct8792(
            signalPrice = 1.05, referencePrice = 1.0, observedAtMs = now - 1_000L, nowMs = now,
            signalIdentity = "base|0xcoin", referenceIdentity = "base|0xcoin")!!, 0.001)
    }

    @Test fun staleOrNonexistentReferenceCannotInventPriceCertainty() {
        assertNull(CryptoAltTrader.preSubmitSameAssetDiffPct8792(0.01, 1.0, now - 120_001L, now, "base|a", "base|a"))
        assertNull(CryptoAltTrader.preSubmitSameAssetDiffPct8792(0.01, 1.0, now + 1L, now, "base|a", "base|a"))
        assertNull(CryptoAltTrader.preSubmitSameAssetDiffPct8792(0.01, 1.0, 0L, now, "base|a", "base|a"))
        assertNull(CryptoAltTrader.preSubmitSameAssetDiffPct8792(0.01, 0.0, now - 1_000L, now, "base|a", "base|a"))
        assertNull(CryptoAltTrader.preSubmitSameAssetDiffPct8792(0.01, 1.0, now - 1_000L, now, "base|coin", "ethereum|coin"))
        assertNull(CryptoAltTrader.preSubmitSameAssetDiffPct8792(0.01, 1.0, now - 1_000L, now, "", "base|coin"))
    }

    @Test fun dynamicSignalNeverComparesAgainstTheSharedDynMarketCache() {
        val source = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val sanity = source.substringAfter("val now8792 = System.currentTimeMillis()")
            .substringBefore("val tpPct =")
        assertTrue(sanity.contains("getTokenByCanonicalIdentity6544(it)"))
        assertTrue(sanity.contains("signalIdentity8792, it.first"))
        assertTrue(sanity.contains("if (signal.isDynamic)"))
        assertFalse(sanity.contains("getCachedPrice(PerpsMarket.DYN)"))
        assertTrue(sanity.contains("getCachedPrice(signal.market)"))
    }
}
