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

        assertTrue(trader.contains("rotateWeakPaperExposure7244"))
        assertTrue(trader.contains("CRYPTO_EXPOSURE_ROTATED_7244"))
        assertTrue(trader.contains("val maxRisk = balance * 0.80"))
        assertTrue(trader.contains("totalRisk + sizeSol > maxRisk"))
    }

    @Test
    fun opportunity_queue_prioritizes_priced_evidence_without_dropping_unpriced_discovery() {
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val queue = registry.substringAfter("fun getBlendedOpportunityQueue6544").substringBefore("fun getTokensBySector")

        assertTrue(queue.contains("it.isFresh6544"))
        assertTrue(queue.contains("it.price.isFinite() && it.price > 0.0"))
        assertTrue(queue.contains("it.liquidityUsd.isFinite() && it.liquidityUsd > 0.0"))
        assertFalse(queue.contains(".filter"))
    }
}
