package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7553NativeOccupancyAuthorityTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun native_local_maps_are_observation_not_admission() {
        val checks=mapOf(
            "v3/scoring/QualityTraderAI.kt" to listOf("QUALITY_LOCAL_OCCUPANCY_OBSERVED_7552","QUALITY_LOCAL_CAP_OBSERVED_7552"),
            "v3/scoring/BlueChipTraderAI.kt" to listOf("BLUECHIP_LOCAL_OCCUPANCY_OBSERVED_7552","BLUECHIP_LOCAL_CAP_OBSERVED_7552"),
            "v3/scoring/ShitCoinTraderAI.kt" to listOf("SHITCOIN_LOCAL_OCCUPANCY_OBSERVED_7552","SHITCOIN_LOCAL_CAP_OBSERVED_7552"),
            "v3/scoring/ShitCoinExpress.kt" to listOf("EXPRESS_LOCAL_OCCUPANCY_OBSERVED_7552","EXPRESS_LOCAL_CAP_OBSERVED_7552"),
            "v3/scoring/ProjectSniperAI.kt" to listOf("SNIPER_LOCAL_OCCUPANCY_OBSERVED_7552","SNIPER_LOCAL_CAP_OBSERVED_7552"),
            "v3/scoring/DipHunterAI.kt" to listOf("DIP_LOCAL_OCCUPANCY_OBSERVED_7552","DIP_LOCAL_CAP_OBSERVED_7552"),
            "v3/scoring/ManipulatedTraderAI.kt" to listOf("MANIP_LOCAL_OCCUPANCY_OBSERVED_7552"),
            "v3/scoring/CashGenerationAI.kt" to listOf("CASHGEN_LOCAL_OCCUPANCY_OBSERVED_7552","CASHGEN_LOCAL_CAP_OBSERVED_7552")
        )
        checks.forEach { (path, markers) ->
            val s=src(path)
            markers.forEach { assertTrue("$path missing $it", s.contains(it)) }
        }
    }

    @Test fun canonical_held_authority_remains_the_shared_truth() {
        val h=src("engine/HeldPositionSupervisor7246.kt")
        assertTrue(h.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(h.contains("fun isHeld("))
        val c=src("engine/CyclicTradeEngine.kt")
        assertTrue(c.contains("HeldPositionSupervisor7246.isHeld"))
        assertTrue(c.contains("CanonicalPositionAuthority6441.hasOpenMint"))
    }

    @Test fun native_projection_maps_remain_available_for_lifecycle_work() {
        listOf(
            "v3/scoring/QualityTraderAI.kt" to "activePositions",
            "v3/scoring/ShitCoinExpress.kt" to "activeRides",
            "v3/scoring/ProjectSniperAI.kt" to "activeMissions",
            "v3/scoring/DipHunterAI.kt" to "activeDips",
            "v3/scoring/ManipulatedTraderAI.kt" to "activePositions",
            "v3/scoring/CashGenerationAI.kt" to "activePositions"
        ).forEach { (path, field) -> assertTrue(src(path).contains(field)) }
    }
}
