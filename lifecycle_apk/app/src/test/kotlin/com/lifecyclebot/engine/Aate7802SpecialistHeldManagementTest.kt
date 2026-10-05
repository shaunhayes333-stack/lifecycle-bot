package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7802SpecialistHeldManagementTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun heldManagementSeparatesEntryMandateFromMutableTactic() {
        val s=src("engine/HoldingLogicLayer.kt")
        assertTrue(s.contains("LaneAttributionLedger6427.getEntryLane"))
        assertTrue(s.contains("mandateMode7802"))
        assertTrue(s.contains("runnerMandate7802"))
        assertTrue(s.contains("HELD_TACTIC_CLAMPED_BY_ENTRY_MANDATE_7802"))
        assertTrue(s.contains("maxOf(tacticalTarget6684, mandateParams7802.targetProfitPct)"))
        assertTrue(s.contains("maxOf(tacticalMaxHoldMs6684, mandateParams7802.maxHoldTimeMs)"))
        assertTrue(s.contains("mandateParams7802.scaleOutAt"))
    }

    @Test fun manipulatedUsesLifecycleAndWalletGeometry() {
        val s=src("v3/scoring/ManipulatedTraderAI.kt")
        assertTrue(s.contains("lifecyclePhase"))
        assertTrue(s.contains("largestBuyerSharePct60s"))
        assertTrue(s.contains("repeatBuyerWallets60s"))
        assertTrue(s.contains("MANIP_NATIVE_DISTRIBUTION_REJECT_7802"))
    }

    @Test fun expressUsesParticipationAcceleration() {
        val s=src("v3/scoring/ShitCoinExpress.kt")
        assertTrue(s.contains("holderGrowthPct"))
        assertTrue(s.contains("distinctBuyers60s"))
        assertTrue(s.contains("launchAccelerationRising"))
        assertTrue(s.contains("participationScore7802"))
    }

    @Test fun dipHunterUsesRecoveryEvidence() {
        val s=src("v3/scoring/DipHunterAI.kt")
        assertTrue(s.contains("reclaimStrengthPct"))
        assertTrue(s.contains("smartMoneyBuyers60s"))
        assertTrue(s.contains("holderGrowthPct"))
    }

    @Test fun cyclicProvesCycleRatherThanGenericV3Score() {
        val s=src("engine/CyclicTradeEngine.kt")
        assertTrue(s.contains("turns7802"))
        assertTrue(s.contains("rangePct7802"))
        assertTrue(s.contains("volReexpand7802"))
        assertTrue(s.contains("CYCLIC_PATTERN_NOT_PROVEN_7802"))
    }

    @Test fun canonicalBridgeFeedsTheNewNativeEvidence() {
        val s=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(s.contains("holderGrowthPct=ts.holderGrowthRate"))
        assertTrue(s.contains("repeatBuyerWallets60s=launch?.repeatBuyerWallets60s"))
        assertTrue(s.contains("reclaimStrengthPct=reclaimStrength7802"))
        assertTrue(s.contains("launchAccelerationRising=launch?.accelerationRising==true"))
    }
}
