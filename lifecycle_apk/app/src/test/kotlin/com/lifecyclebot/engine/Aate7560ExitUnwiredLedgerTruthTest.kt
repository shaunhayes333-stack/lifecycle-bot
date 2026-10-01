package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7560ExitUnwiredLedgerTruthTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `canonical held management owns stop hold and learned exit shaping`() {
        val hold = src("engine/HoldingLogicLayer.kt")
        assertTrue(hold.contains("val params = getHoldParams(mode)"))
        assertTrue(hold.contains("AdvancedExitManager.evaluateExit("))
        assertTrue(hold.contains("LiveStrategyTuner.adjustment(mode)"))
        assertTrue(hold.contains("FluidLearningAI.getFluidMinHoldMinutes(layer)"))
        assertTrue(hold.contains("FluidLearningAI.getFluidMaxHoldMinutes(layer)"))

        assertFalse(hold.contains("ExitManager.shouldPartialSell("))
        assertFalse(hold.contains("TrailingStopManager.getRecommendedStopType("))
        assertFalse(hold.contains("SmartExitOptimizer.getExitPressure("))
    }

    @Test
    fun `legacy exit manager is explicitly non authoritative`() {
        val legacy = src("engine/ExitManager.kt")
        assertTrue(legacy.contains("AUDIT — DEAD CODE"))
        assertTrue(legacy.contains("DO NOT call ExitManager.evaluate()"))
        assertTrue(legacy.contains("fun shouldPartialSell("))
    }

    @Test
    fun `markets uncapped take profit accessor is closed loop not unwired`() {
        val forex = src("perps/ForexTrader.kt")
        val metals = src("perps/MetalsTrader.kt")
        val commodities = src("perps/CommoditiesTrader.kt")
        assertTrue(forex.contains("FluidLearningAI.getMarketsUncappedTpPct("))
        assertTrue(metals.contains("FluidLearningAI.getMarketsUncappedTpPct("))
        assertTrue(commodities.contains("FluidLearningAI.getMarketsUncappedTpPct("))
    }

    @Test
    fun `audit ledger records classifications instead of blindly wiring aliases`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7560 — exit-intelligence ledger truth + duplicate-authority guard"))
        assertTrue(audit.contains("ExitManager.shouldPartialSell"))
        assertTrue(audit.contains("LEGACY_DEAD / DO_NOT_WIRE"))
        assertTrue(audit.contains("TrailingStopManager.getRecommendedStopType"))
        assertTrue(audit.contains("FluidLearningAI.getLayerHoldParams"))
        assertTrue(audit.contains("ALIAS_WRAPPER"))
        assertTrue(audit.contains("FluidLearningAI.getMarketsUncappedTpPct"))
        assertTrue(audit.contains("CLOSED_LOOP / LEDGER_STALE"))
    }
}
