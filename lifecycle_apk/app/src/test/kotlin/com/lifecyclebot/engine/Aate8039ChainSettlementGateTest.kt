package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveTerminalSemanticsAuthority7236
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A signature is not wallet-delta proof; LIVE realised learning needs settlement. */
class Aate8039ChainSettlementGateTest {
    @Test fun onlyWalletProvedLiveSellsContributeToRealizedEconomics() {
        assertFalse(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "LIVE_SIG_CONFIRMED"))
        assertFalse(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "LIVE_BROADCAST"))
        assertTrue(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "LIVE_FINALIZED"))
        assertTrue(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "LIVE_BALANCE_CONFIRMED"))
        assertFalse(LiveTerminalSemanticsAuthority7236.hasSettlementProof8039("LIVE_SIG_CONFIRMED"))
        assertFalse(LiveTerminalSemanticsAuthority7236.hasSettlementProof8039("LIVE_BROADCAST"))
        assertTrue(LiveTerminalSemanticsAuthority7236.hasSettlementProof8039("LIVE_RECONCILED"))
    }
    @Test fun signatureOrReasonCannotMintFinalizedSellStatus() {
        val journal = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val section = journal.substringAfter("private fun normalizeProofState(t: Trade)").substringBefore("fun recordTrade(")
        assertTrue(section.contains("if (isJournalSellLike(t.side)) return \"LIVE_BROADCAST\""))
        assertFalse(section.contains("r.contains(\"FINALIZED\")"))
        val valid = journal.substringAfter("fun isValidAccountingTrade(t: Trade)").substringBefore("fun normalizeTradeModeName")
        assertTrue(valid.contains("if (!isJournalSellLike(t.side)) true"))
        val truth = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTruthLedger.kt").readText()
        assertTrue(truth.contains("LIVE_SETTLEMENT_UNPROVEN_8039"))
    }
}
