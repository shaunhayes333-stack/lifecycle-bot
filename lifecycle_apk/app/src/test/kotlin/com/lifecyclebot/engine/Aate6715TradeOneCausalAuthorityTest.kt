package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class Aate6715TradeOneCausalAuthorityTest {
    @Before fun reset() { CausalFeedbackAuthority6715.resetForTest6715() }

    @Test fun `cold owner lane admits multiple attempts under V5-0-6721 cross-asset parity soft-mode`() {
        val lane = "EXPRESS"; val mode = "PAPER"; val score = 20
        assertTrue(CausalFeedbackAuthority6715.stampDecision("a1", "mint-a", mode, lane, score))
        val first = CausalFeedbackAuthority6715.admit("a1", "mint-a", mode, lane, score)
        assertTrue(first.reason, first.allowed)
        assertTrue(CausalFeedbackAuthority6715.stampDecision("a2", "mint-b", mode, lane, score))
        // V5.0.6721 §CAUSAL_ALIGN_TO_CROSS_ASSET_PARITY — the second admit no
        // longer hard-blocks with UNRESOLVED_FEEDBACK_CAP_6715. Meme deck now
        // uses the same fail-open contract as the crypto deck, with the cap
        // recorded as CAUSAL_EXEC_SOFT_MISS_UNRESOLVED_CAP_6721 telemetry
        // instead of a hard reject.
        val second = CausalFeedbackAuthority6715.admit("a2", "mint-b", mode, lane, score)
        assertTrue("cap must be soft-mode advisory, not a hard block", second.allowed)
    }

    @Test fun `terminal invalidates old tickets and learner ack path still exercises without hard-blocking new admits`() {
        val lane = "PROJECT_SNIPER"; val mode = "PAPER"; val score = 20
        CausalFeedbackAuthority6715.stampDecision("a1", "mint-a", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("a1", "mint-a", mode, lane, score).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("p1", mode, "mint-a", lane)
        // Ticket a2 is genuinely made before trade 1 finalizes.
        CausalFeedbackAuthority6715.stampDecision("a2", "mint-b", mode, lane, score)
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId="t1", atMs=System.currentTimeMillis(), realizedPnlSol=-0.01,
            realizedReturnPct=-20.0, mint="mint-a", lane=lane, positionId="p1",
            mode=mode, entryScore=score, scoreBand="S11-25", learningEligible=true,
        )
        assertTrue(CausalFeedbackAuthority6715.onTerminal(env))
        // Post-terminal, a2's stamp is still stale (epoch changed) so this
        // integrity gate still hard-blocks — that behaviour is preserved.
        val stale = CausalFeedbackAuthority6715.admit("a2", "mint-b", mode, lane, score)
        assertFalse(stale.allowed)
        assertTrue(stale.forceRevalidate)
        // A brand-new post-terminal decision — under V5.0.6721 the pending-
        // learning gate is soft-miss, not a hard block. Attempt is allowed
        // and CAUSAL_EXEC_SOFT_MISS_FEEDBACK_PENDING_6721 is emitted.
        CausalFeedbackAuthority6715.stampDecision("a3", "mint-c", mode, lane, score)
        val pending = CausalFeedbackAuthority6715.admit("a3", "mint-c", mode, lane, score)
        assertTrue("pending-learning must be soft-mode advisory, not a hard block", pending.allowed)
        assertTrue(CausalFeedbackAuthority6715.markLearned("p1"))
        // Learning revision changed — a3's stamp is now stale, integrity gate
        // still hard-blocks.
        val postAckStale = CausalFeedbackAuthority6715.admit("a3", "mint-c", mode, lane, score)
        assertFalse(postAckStale.allowed)
        assertTrue(postAckStale.forceRevalidate)
        CausalFeedbackAuthority6715.stampDecision("a4", "mint-d", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("a4", "mint-d", mode, lane, score).allowed)
    }

    @Test fun `executable gate reset clears causal reservations and epochs`() {
        val lane = "SHITCOIN"; val mode = "PAPER"; val score = 20
        CausalFeedbackAuthority6715.stampDecision("reset-a1", "reset-mint-a", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("reset-a1", "reset-mint-a", mode, lane, score).allowed)
        ExecutableOpenGate.resetForTests()
        CausalFeedbackAuthority6715.stampDecision("reset-a2", "reset-mint-b", mode, lane, score)
        assertTrue("reset must remove the prior unresolved causal reservation", CausalFeedbackAuthority6715.admit("reset-a2", "reset-mint-b", mode, lane, score).allowed)
    }

    @Test fun `paper score calibration responds on the first loss without hard rejecting`() {
        ScoreExpectancyTracker.reset()
        ScoreExpectancyTracker.record("EXPRESS", 20, -20.0)
        assertEquals(1, ScoreExpectancyTracker.bucketSamples("EXPRESS", 20))
        assertNotNull(ScoreExpectancyTracker.bucketRawMean6715("EXPRESS", 20))
        assertFalse("hard reject must retain its mature sample rule", ScoreExpectancyTracker.shouldReject("EXPRESS", 20))
        val mult = ScoreExpectancyTracker.calibrationSizeMult("EXPRESS", 20)
        assertTrue("first loss must trim next paper size, got $mult", mult < 1.0)
        assertTrue("trade-one evidence must remain bounded, got $mult", mult > 0.25)
    }

    @Test fun `source contract has exact terminal consumer and exact owner learner ack`() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val fabric = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(bus.contains("CausalFeedback6715"))
        assertTrue(bridge.contains("deliverToCausalFeedback6715"))
        assertTrue(bridge.contains("setOf(\"Dashboard\", \"CausalFeedback6715\")"))
        assertTrue(fabric.contains("CausalFeedbackAuthority6715.markLearned(env.positionId)"))
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_CAUSAL_FEEDBACK_6715"))
        assertTrue(gate.contains("stampDecision("))
    }

    @Test fun `from trade one no longer means wait eight or fifteen for all soft influence`() {
        val damper = File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        val score = File("src/main/kotlin/com/lifecyclebot/engine/ScoreExpectancyTracker.kt").readText()
        val tactic = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(damper.contains("m.trades < 1"))
        assertFalse(damper.contains("if (m.trades < MIN_TRADES) continue"))
        assertTrue(score.contains("bucketRawMean6715"))
        assertTrue(score.contains("samples.toDouble() / (samples.toDouble() + 3.0)"))
        assertTrue(tactic.contains("TRADE_ONE_CATASTROPHIC_PNL = -25.0"))
    }
}
