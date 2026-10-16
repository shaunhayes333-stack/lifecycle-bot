package com.lifecyclebot.perps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7244CryptoUniverseAuthorityTest {

    @Test
    fun dynamic_universe_has_native_crypto_brain_authority_and_truthful_telemetry() {
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()

        assertTrue(trader.contains("scoreDynamicCrypto7244"))
        assertTrue(trader.contains("CryptoBrain.scoreAdjustment()"))
        assertTrue(trader.contains("CryptoBrain.confidenceModifier()"))
        assertTrue(trader.contains("CryptoBrain.sizingMultiplier"))
        assertTrue(trader.contains("CRYPTO_BRAIN_NATIVE_ACTIONABLE_7244"))
        assertTrue(trader.contains("cryptoBrainSignals="))
        assertTrue(trader.contains("specialistSignals="))

        assertTrue(registry.contains("fun markCryptoBrainReach7244"))
        val evalBody = registry.substringAfter("fun markEvaluation6544").substringBefore("fun markCryptoBrainReach7244")
        assertFalse(evalBody.contains("freshReachedBrain6544.incrementAndGet()"))
        assertTrue(registry.contains("markCryptoBrainReach7244"))
    }

    @Test
    fun dynamic_universe_does_not_fabricate_specialist_silence_or_false_route_truth() {
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val dyn = trader.substringAfter("private suspend fun runDynamicTokenScan").substringBefore("private suspend fun runScanCycle")

        assertTrue(dyn.contains("CRYPTO_SPECIALIST_SILENCE_OBSERVATION_ONLY_7244"))
        assertFalse(dyn.contains("OBSERVE_SPECIALIST_SILENCE_6569"))
        assertFalse(dyn.contains("markFdgReach6544(sharedTok6569"))
        assertTrue(dyn.contains("SPOT_ONLY_SHORT_OBSERVATION_7244"))
    }

    @Test
    fun exposure_pressure_rotates_weak_paper_positions_without_raising_risk_cap() {
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

        // V5.0.7288 — still 80%, measured against what crypto can reach (its own
        // committed SOL plus free cash) instead of free cash alone.
        // V5.0.7835 — the local over-cap gate moved into CanonicalEntryAuthority6551:
        // the remaining 80% headroom is the lane risk cap and a full book is refused.
        assertTrue(trader.contains("val remainingExposure7835 = ((balance + totalRisk7835) * 0.80 - totalRisk7835).coerceAtLeast(0.0)"))
        assertTrue(trader.contains("\"laneRiskCapSol\" to minOf(1.0, remainingExposure7835).toString()"))
        assertTrue(trader.contains("if (remainingExposure7835 <= 0.0) \"CRYPTO_EXPOSURE_CAP_7835\" else null"))
    }

    @Test
    fun opportunity_queue_prioritizes_priced_evidence_without_dropping_unpriced_discovery() {
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val queue = registry.substringAfter("fun getBlendedOpportunityQueue6544").substringBefore("fun getTokensBySector")

        assertTrue(queue.contains("it.isFresh6544"))
        assertTrue(queue.contains("it.price.isFinite() && it.price > 0.0"))
        assertTrue(queue.contains("it.liquidityUsd.isFinite() && it.liquidityUsd > 0.0"))
        // V5.0.8019 — the only filter is the pump-mint exclusion; nothing is dropped for being unpriced.
        assertTrue(queue.contains("registry.values.filter { !pumpMint8019(it.mint) || it.isStatic }"))
        assertFalse(queue.substringAfter(".sortedWith(").contains(".filter"))
    }
}
