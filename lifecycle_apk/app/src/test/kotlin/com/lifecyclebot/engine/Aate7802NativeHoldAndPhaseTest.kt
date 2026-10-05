package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7802NativeHoldAndPhaseTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun manipulatedHasPhaseAwareNativeLogic() {
        val s=src("v3/scoring/ManipulatedTraderAI.kt")
        listOf("ACCUMULATION","IGNITION","PUBLIC_PUMP","EUPHORIA","DISTRIBUTION","COLLAPSE").forEach {
            assertTrue(s.contains(it))
        }
        assertTrue(s.contains("UNFAVOURABLE_MANIP_PHASE_7802"))
        assertTrue(s.contains("repeatBuyerWallets60s"))
        assertTrue(s.contains("devSellTx60s"))
    }

    @Test fun dipHunterConsumesHealthAndDemandReturn() {
        val dip=src("v3/scoring/DipHunterAI.kt")
        val bridge=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(dip.contains("holderGrowthRate"))
        assertTrue(dip.contains("smartMoneyBuys60s"))
        assertTrue(dip.contains("topHolderPct"))
        assertTrue(bridge.contains("holderChange24h=holderDelta7802"))
        assertTrue(bridge.contains("smartMoneyBuys60s=smart7802"))
    }

    @Test fun cyclicRequiresRepeatableStructure() {
        val s=src("engine/CyclicTradeEngine.kt")
        assertTrue(s.contains("CYCLIC_TOO_FEW_BARS_7802"))
        assertTrue(s.contains("CYCLIC_NO_REPEATABLE_STRUCTURE_7802"))
        assertTrue(s.contains("turns7802"))
        assertTrue(s.contains("REACCUMULATION_BREAKOUT"))
        assertTrue(s.contains("volExpand7802"))
    }

    @Test fun allTwelveSpecialistsHaveNativeHeldProfiles() {
        val s=src("engine/HoldingLogicLayer.kt")
        listOf("MOONSHOT","PROJECT_SNIPER","EXPRESS","SHITCOIN","MANIPULATED","DIP_HUNTER",
            "CYCLIC","QUALITY","BLUECHIP","TREASURY","CASHGEN","CORE").forEach { lane ->
            assertTrue("missing held profile $lane", s.contains("\"$lane\" to ModeHoldParams"))
        }
    }

    @Test fun holdLearningUsesImmutableObjectiveWhileTechniqueCanPivot() {
        val s=src("engine/HoldingLogicLayer.kt")
        assertTrue(s.contains("val activeMode = position.tradingMode"))
        assertTrue(s.contains("PositionEntryLaneRegistry6621"))
        assertTrue(s.contains("LiveStrategyTuner.adjustment(objectiveLane7802)"))
        assertTrue(s.contains("getLayerFromMode(objectiveLane7802)"))
        assertFalse(s.contains("LiveStrategyTuner.adjustment(mode)"))
    }

    @Test fun nativeProfitFloorsCarryLaneIdentity() {
        val cases=mapOf(
            "v3/scoring/MoonshotTraderAI.kt" to "lane = \"MOONSHOT\"",
            "v3/scoring/ShitCoinTraderAI.kt" to "lane = \"SHITCOIN\"",
            "v3/scoring/ShitCoinExpress.kt" to "lane = \"EXPRESS\"",
            "v3/scoring/ManipulatedTraderAI.kt" to "lane = \"MANIPULATED\"",
            "v3/scoring/DipHunterAI.kt" to "lane = \"DIP_HUNTER\"",
            "v3/scoring/QualityTraderAI.kt" to "lane = \"QUALITY\"",
            "v3/scoring/BlueChipTraderAI.kt" to "lane = \"BLUECHIP\""
        )
        cases.forEach { (p, needle) -> assertTrue("$p missing lane-aware profit floor", src(p).contains(needle)) }
    }
}
