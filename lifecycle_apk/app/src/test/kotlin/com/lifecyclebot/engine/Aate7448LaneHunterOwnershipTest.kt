package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7448LaneHunterOwnershipTest {
    private val src = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

    // V5.0.7803 replaced the single 7448 hunt claim with resident hunter books:
    // each resident hunter may only nudge its OWN already-qualified hypothesis.
    private fun claimRegion(): String =
        src.substringAfter("residentHunterLanes7803.forEach").substringBefore("// V5.0.7803 audit")

    @Test fun hunt_claim_never_manufactures_a_specialist_hypothesis() {
        val region = claimRegion()
        assertTrue(region.contains("val existing = deskHypotheses[claimLane] ?: return@forEach"))
        assertTrue(region.contains("if (identityEligible)"))
        assertFalse(region.contains("DeskHypothesis("))
    }

    @Test fun claimed_specialist_becomes_clear_owner_without_changing_its_policy_shape() {
        val region = claimRegion()
        assertTrue(region.contains("existing.copy("))
        assertTrue(region.contains("conviction = (existing.conviction + 4.0).coerceAtMost(100.0)"))
        assertFalse(region.contains("entryStyle ="))
        assertFalse(region.contains("exitStyle ="))
        assertFalse(region.contains("sizeMult ="))
    }

    @Test fun lane_contract_still_gates_claim_consumption() {
        val region = claimRegion()
        assertTrue(region.contains("LaneEntryContract6342.isLaneIdentityEligible7252"))
        assertTrue(region.contains("catch (_: Throwable) { false }"))
    }
}
