package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7607TwelveLaneParityRepairTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `lane fit uses same established predicate as primary routing`() {
        val s = src("engine/TokenMetricStageRouter.kt")
        val block = s.substringAfter("fun laneFit(").substringBefore("fun reasonFor(")
        assertTrue(block.contains("isEstablished7306(ts, s.marketCapUsd, s.liquidityUsd, s.ageMin)"))
        assertFalse(block.contains("s.ageMin >= 60.0"))
    }

    @Test
    fun `canonical execution intent mirrors specialist BUY_INTENT telemetry`() {
        val s = src("engine/ExecutableOpenGate.kt")
        val block = s.substringAfter("fun registerCanonicalIntent6554(")
            .substringBefore("internal fun sameDecisionContract6734")
        assertTrue(block.contains("SPECIALIST_CANONICAL_INTENT_MIRRORED_7607"))
        assertTrue(block.contains("ToolkitSignalSheet.recordDeskStage("))
        assertTrue(block.contains("\"BUY_INTENT\""))
        listOf(
            "QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN"
        ).forEach { assertTrue(block.contains("\"$it\"")) }
    }

    @Test
    fun `audit records 12 lane repair without strategy tuning`() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("V5.0.7607 — 12-lane specialist parity + causal intent continuity"))
        assertTrue(a.contains("No lane threshold, score floor, TP/SL, sizing multiplier"))
    }
}
