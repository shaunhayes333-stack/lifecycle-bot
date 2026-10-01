package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7565ExitStaleLedgerAndHelperTruthTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `partial lifecycle and specialist proposal rows are already closed loop`() {
        val pos = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val sizing = src("engine/truth/CanonicalSizingBridge6532.kt")
        assertTrue(pos.contains("PositionStateLedger6454.onPartial(positionId)"))
        assertTrue(pos.contains("POSITION_STATE_PARTIAL_PROJECTED_7457"))
        assertTrue(sizing.contains("GlobalCapitalArbitration6617.recordSpecialistProposal6617("))
        assertTrue(sizing.contains("assetClass == AssetClass.SOLANA_TOKEN"))
    }

    @Test
    fun `remaining entries are helper shaped not parallel seller authority`() {
        assertTrue(src("engine/runtime/ExecutionCounterContract.kt").contains("fun recordJournalSellWrite()"))
        assertTrue(src("engine/truth/FinalizedSellProof6386.kt").contains("fun classifyPartial("))
        assertTrue(src("engine/truth/IdempotencyKeyStore6437.kt").contains("fun sellKey("))
        assertTrue(src("engine/truth/LearnerRuntimeBudgetGuard6441.kt").contains("fun shouldStop(): Boolean"))
        assertTrue(src("engine/LiveStrategyTuner.kt").contains("fun tpMultiplier(rawLane: String?): Double = adjustment(rawLane).tpMult"))
    }

    @Test
    fun `audit records stale ledger closure`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7565 — C_EXIT stale-ledger + classifier/idempotency cleanup"))
        assertTrue(audit.contains("PositionStateLedger6454.onPartial"))
        assertTrue(audit.contains("CLOSED_LOOP / LEDGER_STALE (7457)"))
        assertTrue(audit.contains("GlobalCapitalArbitration6617.recordSpecialistProposal6617"))
        assertTrue(audit.contains("CLOSED_LOOP / LEDGER_STALE (7458)"))
        assertTrue(audit.contains("FINALITY CLASSIFIER / TESTED HELPER"))
    }
}
