package com.lifecyclebot.perps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Dated m5 proof must enter on discovery and survive all subsequent metadata merges. */
class Aate8833CryptoDiscoveryProofTest {
    @Test fun never_replace_newer_five_minute_evidence_with_an_older_or_undated_row() {
        assertFalse(DynamicAltTokenRegistry.preferIncomingShortWindow8833(1_000L, 0L))
        assertFalse(DynamicAltTokenRegistry.preferIncomingShortWindow8833(1_000L, 999L))
        assertTrue(DynamicAltTokenRegistry.preferIncomingShortWindow8833(1_000L, 1_001L))
        assertTrue(DynamicAltTokenRegistry.preferIncomingShortWindow8833(0L, 1L))
    }

    @Test fun source_observations_cross_discovery_and_both_upsert_merges() {
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val discovery = registry.substringAfter("private fun dynTokenFromPair6544").substringBefore("private fun upsert(")
        val upsert = registry.substringAfter("private fun upsert(").substringBefore("private fun meetsQualityGate(")
        assertTrue("discovery captures provider m5 buy counts", discovery.contains("buys5m8832 = if (pair.buysM5 >= 0"))
        assertTrue(discovery.contains("sells5m8832 = if (pair.buysM5 >= 0"))
        assertTrue(discovery.contains("volume5m8832 = pair.volumeM5.takeIf"))
        assertTrue(discovery.contains("change5mPct8832 = pair.priceChangeM5.takeIf"))
        assertTrue(discovery.contains("shortWindowObservedAtMs8832 = if ("))
        assertTrue("static and dynamic upserts both preserve provenance", upsert.split("shortWindowObservedAtMs8832 = newestM58833.shortWindowObservedAtMs8832").size >= 3)
        assertTrue("both upsert routes reject old observation prices", upsert.split("priceObservedAtMs8372 = if (acceptIncomingPrice8833)").size >= 3)
        assertTrue("a late refresh must use the same timestamp preference", registry.contains("val newerPairM58833 = validPairM58833 && preferIncomingShortWindow8833("))
        assertTrue("a delayed HTTP pair cannot roll back a newer source price", registry.contains("price = if (newerPrice8833) price else latest8752.price"))
    }
}
