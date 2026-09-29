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
        assertTrue(s.contains("Tactic.PULLBACK"))
        assertTrue(s.contains("Tactic.BREAKOUT"))
        assertTrue(s.contains("Tactic.MEAN_REVERT"))
        assertTrue(s.contains("CRYPTO_TACTIC_CONSUMED_7443_"))
    }

    @Test fun non_momentum_tactics_use_local_structure_not_24h_change_as_shape() {
        val s = src("perps/CryptoAltTrader.kt")
        assertTrue(s.contains("pullbackPct7443 in 2.0..12.0"))
        assertTrue(s.contains("cur7443 >= priorHigh7443 * 1.005"))
        assertTrue(s.contains("reboundPct7443 >= 2.0"))
        assertTrue(s.contains("pullbackEvidence7443 || breakoutEvidence7443 || meanRevertEvidence7443"))
    }
}
