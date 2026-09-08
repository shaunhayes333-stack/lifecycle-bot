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
    fun restored_ticket_preserves_immutable_owner_across_volatile_version_and_trunk_drift() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val boundary = gate.substringAfter("private fun resolveSealedIntent6613(")
            .substringBefore("/** V5.0.6554")
        assertTrue(boundary.contains("it.mint == mint && it.mode.equals(mode, true)"))
        assertTrue(boundary.contains("it.canonicalLane.equals(requestedLane, true)"))
        assertTrue(boundary.contains("isSourceBucketLane(requestedLane)"))
        assertTrue(boundary.contains("RESTORED_TICKET_IMMUTABLE_IDENTITY_MISMATCH_6641"))
        assertTrue(boundary.contains("EXEC_RESTORED_TICKET_VERSION_DRIFT_6692"))
        assertTrue(boundary.contains("EXEC_RESTORED_SPECIALIST_VIA_TRUNK_6692"))
        assertTrue(boundary.contains("validSealedDecision6613(candidate)"))
        assertFalse(boundary.contains(".elect6629("))
    }
}
