package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LabelEvidence8386
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8578 — a chain-state mark of the held coin is the truth being asked about: no aggregator corroborates or contradicts
 * it. 8562 / 8572 / 8575 smokes: HZbiEpF2oM printed 2.2e-7 on its own curve (PUMP_PORTAL_TRADE_WS_7278, a rug) while
 * DexScreener still read 5.2e-4, and the paper close was refused on every tick (ABSURD_LOSS_CONTRADICTED_7273).
 */
class Aate8578ChainMarkIsTruthTest {
    @Test fun the_sources_the_smoke_refused_are_chain_state_marks() {
        for (s in listOf("PUMP_PORTAL_TRADE_WS_7278+RECONCILED_7046", "PUMPSWAP_VAULT_WS_8018", "POOL_VAULT_WS_8418_RAYDIUM",
                "PUMP_CURVE_RPC_KEYLESS_8359", "CURVE_ACCOUNT", "TAPE_LAST_PRINT_8475"))
            assertTrue(s, LabelEvidence8386.independentSource8418(s.uppercase()))
        for (s in listOf("DEXSCREENER_LANE_8287", "JUPITER_SWAP", "FANOUT_MEDIAN", "TOKEN_STATE", ""))
            assertFalse(s, LabelEvidence8386.independentSource8418(s))
    }

    @Test fun both_paper_mark_doors_treat_a_chain_mark_as_its_own_witness() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val gain = ex.substringAfter("private fun corroborateMarkOnDemand7272(").substringBefore("private fun markContradictedOnDemand7273(")
        assertTrue(gain.contains("src.contains(\"PUMP_CURVE_RPC\") || src.contains(\"JUPITER_QUOTE\") ||\n            com.lifecyclebot.engine.truth.LabelEvidence8386.independentSource8418(src)"))
        val loss = ex.substringAfter("private fun markContradictedOnDemand7273(").substringBefore("fun contradicts(other: Double?)")
        assertTrue(loss.contains("com.lifecyclebot.engine.truth.LabelEvidence8386.independentSource8418(src)"))
        assertTrue(loss.contains("PAPER_SELL_LOSS_CHAIN_MARK_IS_TRUTH_8578"))
        assertTrue("silence is still not contradiction", ex.substringAfter("private fun markContradictedOnDemand7273(").contains("other != null && other.isFinite() && other > 0.0 && other / mark >= 3.0"))
    }
}
