package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7802NativeSpecialistEvidenceAndHoldTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun fastDecayLanesDoNotUseMoonshotProfitLockArm() {
        val r=src("engine/RunnerExitProfile7277.kt")
        assertTrue(r.contains("isFatTailLane"))
        assertTrue(r.contains("s.contains(\"EXPRESS\") || s.contains(\"MANIPULATED\")"))
        assertTrue(r.contains("-> 8.0"))
        assertTrue(r.contains("s.contains(\"MOONSHOT\") -> MIN_PEAK_FOR_GIVEBACK_LOCK_PCT"))
        assertTrue(r.contains("if (isFatTailLane(lane))"))
    }

    @Test fun everyCoreSpecialistHasNativeHoldEnvelope() {
        val h=src("engine/HoldingLogicLayer.kt")
        listOf("MOONSHOT","PROJECT_SNIPER","EXPRESS","SHITCOIN","MANIPULATED",
            "DIP_HUNTER","CYCLIC","QUALITY","BLUE_CHIP","TREASURY","CASHGEN","CORE").forEach {
            assertTrue("missing hold envelope $it", h.contains("\"$it\" to ModeHoldParams"))
        }
        assertTrue(h.contains("\"EXPRESS\" -> \"EXPRESS\""))
        assertTrue(h.contains("\"MANIPULATED\" -> \"MANIPULATED\""))
        assertTrue(h.contains("\"DIP_HUNTER\" -> \"DIP_HUNTER\""))
    }

    @Test fun midHoldPivotUsesImmutableEntryOwner() {
        val p=src("engine/HeldPositionPivotArbiter.kt")
        assertTrue(p.contains("EntryStrategySnapshot6450"))
        assertTrue(p.contains("entryOwner7802"))
        assertTrue(p.contains("fast_specialist_patience_not_earned_7802"))
        assertTrue(p.contains("fat_tail_thesis_still_valid_7802"))
        assertTrue(p.contains("ts.position.tradingMode = bestLane"))
    }

    @Test fun nativeBrainsConsumeLaneSpecificEvidence() {
        val bridge=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(bridge.contains("ts.holderGrowthRate"))
        assertTrue(bridge.contains("launch?.distinctBuyers60s"))
        assertTrue(bridge.contains("launch?.repeatBuyerWallets60s"))
        assertTrue(bridge.contains("socialDepth7802"))
        assertTrue(bridge.contains("strongNative7802"))
    }

    @Test fun cashgenAndTreasuryUseExecutionEconomics() {
        val cash=src("v3/scoring/CashGenerationAI.kt")
        val treas=src("engine/TreasuryBrain.kt")
        assertTrue(cash.contains("CapitalEfficiencyBrain.sizeMultiplier(\"CASHGEN\""))
        assertTrue(cash.contains("LiquidityExitPathAI.estimateRoundTripSlippagePct"))
        assertTrue(treas.contains("LiquidityExitPathAI.estimateRoundTripSlippagePct"))
        assertTrue(treas.contains("routeReady7802"))
    }
}
