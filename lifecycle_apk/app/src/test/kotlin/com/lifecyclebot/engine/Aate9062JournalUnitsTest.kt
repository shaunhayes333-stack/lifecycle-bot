package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9062JournalUnitsTest {
    @Test fun aSolOnlyFillCannotBecomeUsdJournalEvidence() {
        val fill = CanonicalBuyFillRegistry.CanonicalBuyFill(
            mint = "units9062", walletVerifiedQty = 1000.0, decimals = 6,
            entryPriceSol = 0.00004, entryPriceUsd = 0.0028,
            solSpentNet = 0.04, entryTsMs = 1000L, buySignature = "sig9062",
            fillIndex = 0, lane = "EXPRESS")
        assertEquals(0.0028, fill.entryUsdForJournal9062()!!, 0.0)
        for (missingUsd in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(fill.copy(entryPriceUsd = missingUsd).entryUsdForJournal9062())
        }
        // Exercise the actual fill projection above, then guard its production consumer.
        val projection = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
            .substringAfter("val fill6320 =").substringBefore("// V5.0.6314")
        assertTrue(projection.contains("fill6320.entryUsdForJournal9062()"))
        assertFalse(projection.contains("fill6320.entryPriceSol"))
        assertTrue(projection.contains("else tradeWithMint.entryPriceSnapshot"))
    }
}
