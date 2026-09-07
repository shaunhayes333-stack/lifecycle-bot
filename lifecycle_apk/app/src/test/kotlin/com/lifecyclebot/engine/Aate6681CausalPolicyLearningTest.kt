package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6681 — causal learning regression locks.
 *
 * A terminal trade may update the global entry model exactly once and only
 * the canonical execution owner lane. Decision-time observations from sibling
 * lanes are counterfactual evidence, not labels for the trade that actually
 * opened. Entry features must also be immutable once a position is linked.
 */
class Aate6681CausalPolicyLearningTest {

    @Test
    fun `position-bound outcome trains global once and owner lane only`() {
        val mint = "Causal6681${System.nanoTime()}"
        val positionId = "pos-$mint"
        val owner = "EXPRESS"
        val contributor = "SHITCOIN"

        val globalBefore = UnifiedPolicyHead.trainedCount()
        val ownerBefore = UnifiedPolicyHead.laneOwnHeadTrainedCount6605(owner)
        val contributorBefore = UnifiedPolicyHead.laneOwnHeadTrainedCount6605(contributor)

        // Simulate the still-present broad TradingModeTag stamp plus a sibling
        // specialist observation for the same mint.
        UnifiedPolicyHead.stamp(
            mint, "MEME_GENERIC",
            UnifiedPolicyHead.Signals(0.72, 0.80, 0.68, 0.64, 0.76, 0.81),
        )
        UnifiedPolicyHead.stamp(
            mint, contributor,
            UnifiedPolicyHead.Signals(0.20, 0.15, 0.18, 0.22, 0.12, 0.25),
        )

        assertTrue(UnifiedPolicyHead.bindPosition6681(positionId, mint, owner))

        // A later evaluation for the same mint must not overwrite the already
        // frozen position entry features.
        UnifiedPolicyHead.stamp(
            mint, "MEME_GENERIC",
            UnifiedPolicyHead.Signals(0.01, 0.01, 0.01, 0.01, 0.01, 0.01),
        )

        assertTrue(UnifiedPolicyHead.recordOutcome6681(positionId, mint, owner, 25.0))
        assertEquals(globalBefore + 1L, UnifiedPolicyHead.trainedCount())
        assertEquals(ownerBefore + 1L, UnifiedPolicyHead.laneOwnHeadTrainedCount6605(owner))
        assertEquals(contributorBefore, UnifiedPolicyHead.laneOwnHeadTrainedCount6605(contributor))

        // Idempotent terminal delivery: the same position cannot train twice.
        assertFalse(UnifiedPolicyHead.recordOutcome6681(positionId, mint, owner, 25.0))
        assertEquals(globalBefore + 1L, UnifiedPolicyHead.trainedCount())
    }

    @Test
    fun `aate finality path binds and trains by position owner`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        assertTrue(src.contains("UnifiedPolicyHead.bindPosition6681(positionId, mint, lane)"))
        assertTrue(src.contains("UnifiedPolicyHead.recordOutcome6681(env.positionId, env.mint, env.lane, env.realizedReturnPct)"))
        assertFalse(src.contains("UnifiedPolicyHead.recordOutcome(env.mint, env.realizedReturnPct)"))
    }

    @Test
    fun `terminal learned veto authority is lane-own not global meme fallback`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
        val fn = src.substringAfter("fun laneHasOwnAuthoritativeHead(lane: String): Boolean")
            .substringBefore("\n    }", missingDelimiterValue = "")
        assertTrue(src.contains("V5.0.6681 §LANE_OWN_TERMINAL_AUTHORITY"))
        assertTrue(fn.contains("return h.trained >= AUTHORITY_AUTHORITATIVE"))
        assertFalse(src.contains("isMemeLane && trained >= MEME_GLOBAL_AUTHORITY_TRAINED_6604"))
    }

    @Test
    fun `causal model version resets historically contaminated persisted weights`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
        assertTrue(src.contains("MODEL_VERSION_V6681 = 6681"))
        assertTrue(src.contains("UNIFIED_POLICY_HEAD_CAUSAL_STATE_RESET_6681"))
        assertTrue(src.contains("boundPositions6681"))
    }
}
