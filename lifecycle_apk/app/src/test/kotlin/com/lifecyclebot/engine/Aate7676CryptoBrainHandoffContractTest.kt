package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7676CryptoBrainHandoffContractTest {
    private fun trader() = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

    @Test fun cryptoBrainCanProduceActionableSignalsWithoutIdentityLoss() {
        val s = trader()
        assertTrue(s.contains("CryptoBrain.scoreAdjustment()"))
        assertTrue(s.contains("CryptoBrain.confidenceModifier()"))
        assertTrue(s.contains("CryptoBrain.activeTactic(tier, score)"))
        assertTrue(s.contains("dynExecutableSignals.add(AltSignal("))
        assertTrue(s.contains("dynAssetKey = refreshed.canonicalIdentity6544"))
        assertTrue(s.contains("CRYPTO_FRESH_TAPE_WARMUP_7472"))
    }

    @Test fun oneCanonicalCryptoHandoffOwnsFdgAndSealedIntent() {
        val s = trader()
        val block = s.substringAfter("val candidate = buildCryptoFinalBuyCandidate(signal, isSpot, finalSize)")
            .substringBefore("private suspend fun freshDynamicEntryBasis7275")
        assertTrue(block.contains("CanonicalEntryAuthority6551.submit("))
        assertTrue(block.contains("assetId = candidate.assetKey"))
        assertTrue(block.contains("assetClass = com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT"))
        assertTrue(block.contains("candidateVersion = candidate.candidateVersion"))
        assertTrue(block.contains("CRYPTO_POST_SEAL_DUPLICATE_GATE_ELIMINATED_7521"))
        assertFalse(block.contains("ExecutableOpenGate.canOpenExecutablePosition("))
    }

    @Test fun sourceAuditItemsClosedButRuntimeAcceptanceRemainsOpen() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Audit CryptoBrain actionable-signal logic"))
        assertFalse(a.contains("- [ ] Preserve static-vs-dynamic candidate identity"))
        assertTrue(a.contains("- [ ] Prove fresh/routable candidates can reach canonical V3/FDG"))
    }
}
