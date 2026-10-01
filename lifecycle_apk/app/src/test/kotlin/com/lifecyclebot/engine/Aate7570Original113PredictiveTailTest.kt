package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7570Original113PredictiveTailTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `closed loop tail readers have real consumers`() {
        val inventory = src("engine/truth/InventoryPressureGovernor6829.kt")
        val oracle = src("engine/truth/PredictiveEntryOracle6915.kt")
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(inventory.contains("RuntimeTune6833.isHighEdge()") || inventory.contains("RuntimeTune6833.isHighEdge("))
        assertTrue(oracle.contains("TradingCopilot.convictionBoost()"))
        assertTrue(oracle.contains(".getPatternWinRate(ph, ema, src)"))
        assertTrue(fdg.contains("UnifiedPolicyHead.brierScore("))
    }

    @Test
    fun `source timing remains single vote`() {
        val score = src("v3/scoring/ScoreCard.kt")
        val oracle = src("engine/truth/PredictiveEntryOracle6915.kt")
        assertTrue(score.contains("SourceTimingRegistry.getSourceTimingPenalty"))
        assertFalse(oracle.contains("SourceTimingRegistry.isLateSignal(mint)"))
    }

    @Test
    fun `database and test helpers remain correctly classified`() {
        assertTrue(src("engine/TradeDatabase.kt").contains("fun getSignalWinRate("))
        assertTrue(src("engine/TradeDatabase.kt").contains("readableDatabase.rawQuery("))
        assertTrue(src("engine/learning/TacticSwitcher.kt").contains("internal fun posteriorLossProbAboveForTest("))
        assertTrue(src("engine/TradeLifecycle.kt").contains("fun noSignal("))
    }

    @Test
    fun `audit closes original 113 items 31 through 47`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7570 — original-113 A_PREDICT tail (items 31–47)"))
        assertTrue(audit.contains("Original-113 A_PREDICT items 31–47 are now classified/closed."))
    }
}
