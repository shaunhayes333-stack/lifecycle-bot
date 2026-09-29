package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7448LaneHunterOwnershipTest {
    private val src = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

    @Test fun hunt_claim_is_consumed_before_core_ensemble_election() {
        val claim = src.indexOf("LaneHunter7297.claimFor(")
        val core = src.indexOf("// V5.0.7439 — CORE")
        assertTrue(claim > 0)
        assertTrue(core > claim)
    }

    @Test fun hunt_claim_never_manufactures_a_specialist_hypothesis() {
        val region = src.substringAfter("val huntClaim7448").substringBefore("// V5.0.7439 — CORE")
        assertTrue(region.contains("val claimed7448 = deskHypotheses[claimLane7448]"))
        assertTrue(region.contains("if (claimed7448 != null && eligible7448)"))
        assertFalse(region.contains("DeskHypothesis("))
        assertTrue(region.contains("LANE_HUNT_CLAIM_NO_HYPOTHESIS_7448_"))
    }

    @Test fun claimed_specialist_becomes_clear_owner_without_changing_its_policy_shape() {
        val region = src.substringAfter("val huntClaim7448").substringBefore("// V5.0.7439 — CORE")
        assertTrue(region.contains("otherBest7448 + 11.0"))
        assertTrue(region.contains("claimed7448.copy("))
        assertTrue(region.contains("conviction = ownershipConviction7448"))
        assertFalse(region.contains("entryStyle ="))
        assertFalse(region.contains("exitStyle ="))
        assertFalse(region.contains("sizeMult ="))
    }

    @Test fun lane_contract_still_gates_claim_consumption() {
        val region = src.substringAfter("val huntClaim7448").substringBefore("// V5.0.7439 — CORE")
        assertTrue(region.contains("LaneEntryContract6342.isLaneIdentityEligible7252"))
        assertTrue(region.contains("LANE_HUNT_CLAIM_INELIGIBLE_7448_"))
    }
}
