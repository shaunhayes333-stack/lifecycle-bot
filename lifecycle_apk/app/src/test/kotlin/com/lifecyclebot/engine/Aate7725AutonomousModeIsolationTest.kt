package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7725AutonomousModeIsolationTest {
    @Test fun autonomous_posteriors_and_pending_credit_are_separate_by_mode() {
        val policy = AutonomousMetaPolicy
        val mint = "ModeIsolationMint7725"
        val lane = "MODE_ISOLATION_7725"
        val liveKey = policy.contextKey(lane, 52, "NORMAL", "LIVE")
        val paperKey = policy.contextKey(lane, 52, "NORMAL", "PAPER")
        assertFalse(liveKey == paperKey)
        assertTrue(liveKey.startsWith("LIVE|"))
        assertTrue(paperKey.startsWith("PAPER|"))

        policy.stampDecision(mint, lane, 52, "NORMAL", "LIVE")
        policy.stampDecision(mint, lane, 52, "NORMAL", "PAPER")
        val before = policy.totalUpdateCount6512()
        policy.recordOutcome(mint, -5.0, lane, "LIVE")
        assertEquals(before + 1L, policy.totalUpdateCount6512())
        policy.recordOutcome(mint, -8.0, lane, "PAPER")
        assertEquals(before + 2L, policy.totalUpdateCount6512())

        val liveOnlyMint = "ModeIsolationWrongMode7725"
        policy.stampDecision(liveOnlyMint, lane, 52, "NORMAL", "LIVE")
        val afterStamp = policy.totalUpdateCount6512()
        policy.recordOutcome(liveOnlyMint, 12.0, lane, "PAPER")
        assertEquals("PAPER close must not consume LIVE decision credit", afterStamp, policy.totalUpdateCount6512())
        policy.recordOutcome(liveOnlyMint, 12.0, lane, "LIVE")
        assertEquals(afterStamp + 1L, policy.totalUpdateCount6512())
    }
}
