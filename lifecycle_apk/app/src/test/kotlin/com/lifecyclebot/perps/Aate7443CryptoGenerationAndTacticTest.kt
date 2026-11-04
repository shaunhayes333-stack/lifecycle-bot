package com.lifecyclebot.perps

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7443CryptoGenerationAndTacticTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun no_action_is_terminal_for_exact_generation_not_retry_released() {
        val reg = src("perps/DynamicAltTokenRegistry.kt")
        val trader = src("perps/CryptoAltTrader.kt")
        val retry = reg.substringAfter("private fun isRetryableProgress7418").substringBefore("fun markEvaluationStarted6567")
        assertFalse(retry.contains("CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244"))
        assertTrue(trader.contains("markEvaluationDisposition6567("))
        assertTrue(trader.contains("CRYPTO_NO_ACTION_GENERATION_COMPLETED_7443"))
        assertTrue(reg.contains("evaluationCompleted6615[identity] == generation"))
    }

    @Test fun crypto_tactic_switcher_is_consumed_by_entry_logic() {
        val s = src("perps/CryptoAltTrader.kt")
        assertTrue(s.contains("CryptoBrain.activeTactic(tier, score)"))
        assertTrue(s.contains("CryptoLaneDesk7391.prices(tok.canonicalIdentity6544)"))
        // superseded: 8535 §C11 — the per-tactic shapes live in CryptoTacticEvidence8535; the trader consumes them via forTactic()
        val shapes = src("perps/crypto/brain/CryptoTacticEvidence8535.kt")
        assertTrue(shapes.contains("Tactic.PULLBACK -> pullback"))
        assertTrue(shapes.contains("Tactic.BREAKOUT -> breakout"))
        assertTrue(shapes.contains("Tactic.MEAN_REVERT -> meanRevert"))
        assertTrue(s.contains("shape8535.forTactic(tactic7443)"))
        assertTrue(s.contains("CRYPTO_TACTIC_CONSUMED_7443_"))
    }

    @Test fun non_momentum_tactics_use_local_structure_not_24h_change_as_shape() {
        // superseded: 8535 §C11 moved the shapes into CryptoTacticEvidence8535 so the buyer reading carries its provenance
        val s = src("perps/crypto/brain/CryptoTacticEvidence8535.kt")
        assertTrue(s.contains("pullbackPct in 2.0..12.0"))
        assertTrue(s.contains("cur >= priorHigh * 1.005"))
        assertTrue(s.contains("reboundPct >= 2.0"))
        assertTrue(s.contains("Tactic.LAB_PROPOSED -> pullback || breakout || meanRevert"))
        assertTrue(src("perps/CryptoAltTrader.kt").contains("CryptoTacticEvidence8535.evaluate("))
    }
}
