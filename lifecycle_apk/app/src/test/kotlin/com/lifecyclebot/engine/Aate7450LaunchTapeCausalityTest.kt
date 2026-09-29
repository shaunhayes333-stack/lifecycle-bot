package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7450LaunchTapeCausalityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun launch_flow_measures_wallet_concentration_without_claiming_bundle_proof() {
        val s = src("engine/WhaleDetector.kt")
        assertTrue(s.contains("largestBuyerSharePct60s"))
        assertTrue(s.contains("top3BuyerSharePct60s"))
        assertTrue(s.contains("repeatBuyerWallets60s"))
        assertTrue(s.contains("groupBy { it.wallet }"))
    }

    @Test fun launch_phase_carries_smart_money_and_coordination_evidence() {
        val s = src("engine/truth/LaunchPhaseAuthority7401.kt")
        assertTrue(s.contains("SmartMoneyFeed6394.smartMoneyBuysLast60s"))
        assertTrue(s.contains("largestBuyerSharePct60s"))
        assertTrue(s.contains("smartMoneyBuyers60s"))
        assertTrue(s.contains("coordinatedIgnition7450"))
    }

    @Test fun toolkit_separates_organic_and_coordinated_early_theses() {
        val s = src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("launch_tape_organic_ignition"))
        assertTrue(s.contains("launch_tape_coordinated_ignition"))
        assertTrue(s.contains("lanes = setOf(\"PROJECT_SNIPER\", \"MOONSHOT\", \"SHITCOIN\")"))
        assertTrue(s.contains("lanes = setOf(\"MANIPULATED\", \"PROJECT_SNIPER\")"))
        assertFalse(s.contains("chart = \"fresh_pool_momentum\""))
    }

    @Test fun v3_receives_causal_launch_wallet_structure() {
        val s = src("v3/bridge/V3Adapter.kt")
        assertTrue(s.contains("launchLargestBuyerSharePct7450"))
        assertTrue(s.contains("launchTop3BuyerSharePct7450"))
        assertTrue(s.contains("launchRepeatBuyerWallets7450"))
        assertTrue(s.contains("launchSmartMoneyBuyers7450"))
    }
}
