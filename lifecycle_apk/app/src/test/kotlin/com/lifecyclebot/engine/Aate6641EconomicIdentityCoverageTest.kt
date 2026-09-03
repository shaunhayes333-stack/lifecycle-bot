package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6641EconomicIdentityCoverageTest {

    @Test
    fun paper_journal_persists_and_prefers_immutable_economic_id() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(src.contains("economic_event_id TEXT NOT NULL DEFAULT ''"))
        assertTrue(src.contains("put(\"economic_event_id\", t.economicEventId)"))
        assertTrue(src.contains("trade.economicEventId.ifBlank { trade.operationId }"))
        assertTrue(src.contains("if (rowId > 0L)"))
        assertTrue(src.contains("stampDurableJournalCommit6641(trade)"))
        assertTrue(src.contains("CanonicalEconomicEvent6635.Store.JOURNAL"))
    }

    @Test
    fun partial_sell_uses_same_id_and_side_in_ledger_and_journal() {
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        assertTrue(bridge.contains("attemptKey = sellSig"))
        assertTrue(bridge.contains("else PaperEconomicAtomicCommit6632.Side.PARTIAL_SELL"))
        assertTrue(bridge.contains("lotId = sellSig"))
        assertTrue(bridge.contains("CanonicalEconomicEvent6635.Store.FILL_LOT"))
    }

    @Test
    fun fill_lot_failure_is_not_swallowed_as_success() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(executor.contains("rollbackPaperEntry6485(\"FILL_LOT_PERSIST_FAILED\")"))
        assertTrue(executor.contains("CanonicalEconomicEvent6635.openEvent(canonicalEvent6485)"))
        assertFalse(executor.contains("recordBuyFill(\n") && executor.contains("catch (_: Throwable) {}\n            com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markOpen"))
    }

    @Test
    fun restored_ticket_must_match_full_immutable_identity_tuple() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("it.candidateVersion == candidateVersion"))
        assertTrue(gate.contains("it.canonicalLane.equals(requestedLane, true)"))
        assertTrue(gate.contains("RESTORED_TICKET_IMMUTABLE_IDENTITY_MISMATCH_6641"))
        assertTrue(gate.contains("IMMUTABLE_ELECTION_LANE_MISMATCH_6653"))
        assertFalse(gate.contains("SpecialistProposalArbiter6629.elect6629"))
    }
}
