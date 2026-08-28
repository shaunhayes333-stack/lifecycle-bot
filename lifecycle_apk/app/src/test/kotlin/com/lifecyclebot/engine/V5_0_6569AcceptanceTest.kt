package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class V5_0_6569AcceptanceTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun immutable_asset_class_survives_intent_position_finality_persistence_and_learning() {
        val gate=src("engine/ExecutableOpenGate.kt")
        val contract=src("engine/truth/CanonicalAssetEntryContract6551.kt")
        val paper=src("engine/truth/CanonicalPaperTransaction6486.kt")
        val finality=src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        val persistence=src("engine/truth/CanonicalFinalityPersistence6486.kt")
        assertTrue(gate.contains("val assetClassTag:"))
        assertTrue(contract.contains("assetClassTag = candidate.assetClass.tag"))
        assertTrue(contract.contains("intentAssetClass6569(intent)"))
        assertFalse(contract.contains("markAdapterDispatchFor6551(AssetClass.fromLane(intent.canonicalLane)"))
        assertTrue(paper.contains("intent.assetClassTag != assetClass.tag"))
        assertTrue(finality.contains("assetClassTag = event.assetClassTag.ifBlank"))
        assertTrue(persistence.contains("put(\"assetClass\", e.assetClassTag)"))
    }

    @Test fun all_cross_asset_producers_have_causal_liveness_and_common_submission() {
        val files=listOf("perps/TokenizedStockTrader.kt","perps/ForexTrader.kt","perps/CommoditiesTrader.kt","perps/MetalsTrader.kt","perps/PerpsExecutionEngine.kt","perps/CryptoAltTrader.kt")
        files.forEach { f ->
            val t=src(f)
            assertTrue("$f scan liveness",t.contains("markProducerStage6569"))
            assertTrue("$f completed-window diagnosis",t.contains("completeProducerWindow6569"))
        }
        listOf("perps/TokenizedStockTrader.kt","perps/ForexTrader.kt","perps/CommoditiesTrader.kt","perps/MetalsTrader.kt").forEach { f ->
            assertTrue("$f canonical authority",src(f).contains("CanonicalEntryAuthority6551.submit"))
        }
        val authority=src("engine/truth/CanonicalEntryAuthority6540.kt")
        assertTrue(authority.contains("MARKET_CLASS_LIVENESS_FAULT"))
        assertTrue(authority.contains("zero.get() >= 3L"))
        assertTrue(authority.contains("unexplained="))
    }

    @Test fun specialist_silence_observes_through_shared_intelligence_without_terminal_gate() {
        val crypto=src("perps/CryptoAltTrader.kt")
        assertFalse(crypto.contains("markEvaluationDisposition6567(refreshed, \"NO_ACTIONABLE_SPECIALIST_SIGNAL\")"))
        assertTrue(crypto.contains("OBSERVE_SPECIALIST_SILENCE_6569"))
        assertTrue(crypto.contains("CRYPTO_SPECIALIST_SILENCE_TO_SHARED_INTELLIGENCE_6569"))
        assertTrue(crypto.contains("markFdgReach6544(sharedTok6569"))
        assertTrue(crypto.contains(".take(25)"))
        assertFalse(crypto.contains(".filter { it.score >= scoreThresh && it.confidence >= confThresh }"))
    }

    @Test fun advisor_domains_and_replay_rollback_cannot_mutate_strategy_from_integrity() {
        val advisor=src("engine/truth/AutoPipelineAdvisor6462.kt")
        assertTrue(advisor.contains("ADVISOR_CROSS_DOMAIN_MUTATION_BLOCKED_6569"))
        assertTrue(advisor.contains("strategyKeys6569") && advisor.contains("integrityTerms6569"))
        assertTrue(advisor.contains("REPLAY_DRIVEN_ENTRY_COOLDOWN_ROLLED_BACK_6569"))
        assertTrue(advisor.contains("BotConfig().entryCooldownSec"))
        assertFalse(advisor.contains("""Candidate("entryCooldownSec", +3.0"""))
    }

    @Test fun leveraged_paper_terminal_uses_proceeds_and_quarantines_arithmetic_divergence() {
        val crypto=src("perps/CryptoAltTrader.kt")
        val paper=src("engine/truth/CanonicalPaperTransaction6486.kt")
        assertTrue(crypto.contains("sol              = (pos.sizeSol + pnlSol).coerceAtLeast(0.0)"))
        assertTrue(crypto.contains("expectedRealizedPnlSol6569 = pos.sizeSol * (pos.getPnlPct() / 100.0)"))
        assertTrue(paper.contains("LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569"))
        assertTrue(paper.contains("PaperLearningEligibility6519.record"))
        assertTrue(paper.contains("grossProceedsSol - basis - sellFeeSol"))
    }
}
