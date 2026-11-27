package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate9043CausalHandoffTest {
    private val wall = 1_800_000_000_000L
    @Before fun before() {
        CausalFeedbackAuthority6715.resetForTest6715()
        CausalFeedbackAuthority6715.clockForTest9043 = { wall }
    }
    @After fun after() { CausalFeedbackAuthority6715.resetForTest6715() }

    private fun terminal(id: String, lane: String = "SHITCOIN") = CanonicalFinalizedTradeBus6464.Envelope(
        tradeId = "trade-$id", atMs = wall, realizedPnlSol = -0.01, realizedReturnPct = -20.0, mint = "closed-$id",
        lane = lane, positionId = "position-$id", mode = "PAPER", entryScore = 40,
        scoreBand = CausalFeedbackAuthority6715.scoreBand(40), learningEligible = true)

    @Test fun sameMillisecondTerminalAfterStampCannotBeErasedByOwnerHandoff() {
        assertTrue(CausalFeedbackAuthority6715.stampDecision("same-ms", "mint-a", "PAPER", "MOONSHOT", 40))
        assertTrue(CausalFeedbackAuthority6715.onTerminal(terminal("same-ms")))
        val denied = CausalFeedbackAuthority6715.admit("same-ms", "mint-a", "PAPER", "SHITCOIN", 40)
        assertFalse(denied.allowed)
        assertTrue(denied.forceRevalidate)
    }

    @Test fun destinationLearningRevisionAfterStampIsDetectedWithoutANewTerminalOrClockTick() {
        assertTrue(CausalFeedbackAuthority6715.onTerminal(terminal("learn")))
        assertTrue(CausalFeedbackAuthority6715.stampDecision("learn-handoff", "mint-b", "PAPER", "MOONSHOT", 40))
        assertTrue(CausalFeedbackAuthority6715.markLearned("position-learn"))
        CausalFeedbackAuthority6715.clockForTest9043 = { wall - 60_000L }
        val denied = CausalFeedbackAuthority6715.admit("learn-handoff", "mint-b", "PAPER", "SHITCOIN", 40)
        assertFalse(denied.allowed)
        assertTrue(denied.forceRevalidate)
    }

    @Test fun unchangedFeedbackTransfersAndChangedOriginFeedbackDoesNot() {
        assertTrue(CausalFeedbackAuthority6715.stampDecision("clean", "mint-c", "PAPER", "MOONSHOT", 40))
        assertTrue(CausalFeedbackAuthority6715.admit("clean", "mint-c", "PAPER", "SHITCOIN", 40).allowed)
        CausalFeedbackAuthority6715.resetForTest6715()
        CausalFeedbackAuthority6715.clockForTest9043 = { wall }
        assertTrue(CausalFeedbackAuthority6715.stampDecision("origin", "mint-d", "PAPER", "MOONSHOT", 40))
        assertTrue(CausalFeedbackAuthority6715.onTerminal(terminal("origin", "MOONSHOT")))
        assertFalse(CausalFeedbackAuthority6715.admit("origin", "mint-d", "PAPER", "SHITCOIN", 40).allowed)
    }
}
