package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Repair6494LaneElectionAcceptanceTest {
    @Before fun reset() = LaneExecutionCoordinator.resetForTests()

    @Test fun qualifiedElectionIsSealedAndLegacyReleaseUsesImmutableActiveReceipt() {
        val mint = "So11111111111111111111111111111111111111112"
        val version = 6494001L
        LaneExecutionCoordinator.registerAffinity(mint, setOf("QUALITY", "MOONSHOT", "SHITCOIN"))

        val elected = LaneExecutionCoordinator.canRequestExecution(mint, "QUALITY", version, 77L)
        assertTrue(elected.allowed)
        assertEquals("QUALITY", elected.primaryLane)
        assertTrue(elected.electionId.isNotBlank())
        assertTrue(elected.authorityVersion > 0L)

        val late = LaneExecutionCoordinator.canRequestExecution(mint, "SHITCOIN", version, 77L)
        assertFalse(late.allowed)
        assertEquals(elected.electionId, late.electionId)
        assertEquals("QUALITY", late.primaryLane)

        assertTrue(LaneExecutionCoordinator.releaseIfPrimary(mint, "QUALITY", "BUY_NOT_OPENED", version + 1L, 77L))
    }
}
