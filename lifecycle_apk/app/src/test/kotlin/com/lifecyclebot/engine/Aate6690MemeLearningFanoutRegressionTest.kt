package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6690 — regression lock for Meme Trader self-learning fanout.
 *
 * The Meme desk spans multiple specialist sources. A later isolation patch must
 * never reduce the learning cohort to a small source subset while those lanes
 * are still allowed to trade. Paper outcomes must also carry realized PnL into
 * FluidLearningAI so expectancy can adapt instead of seeing every close as 0%.
 */
class Aate6690MemeLearningFanoutRegressionTest {

    private val subscribers = File(
        "src/main/kotlin/com/lifecyclebot/engine/CanonicalSubscribers.kt"
    ).readText()

    @Test
    fun `paper FluidLearning receives realized pnl magnitude`() {
        assertTrue(subscribers.contains("TradeEnvironment.PAPER -> FluidLearningAI.recordPaperTrade("))
        assertTrue(subscribers.contains("pnlPct = outcome.realizedPnlPct ?: 0.0"))
    }

    @Test
    fun `full meme specialist desk remains learner eligible`() {
        val cohort = subscribers.substringAfter("private val MEME_LEARNING_SOURCES = setOf(")
            .substringBefore("\n    )")

        listOf(
            "TradeSource.V3",
            "TradeSource.TREASURY",
            "TradeSource.BLUECHIP",
            "TradeSource.SHITCOIN",
            "TradeSource.MOONSHOT",
            "TradeSource.MANIP",
            "TradeSource.EXPRESS",
            "TradeSource.COPYTRADE",
            "TradeSource.CYCLIC",
        ).forEach { source -> assertTrue("missing $source", cohort.contains(source)) }

        assertFalse(cohort.contains("TradeSource.MARKETS"))
        assertFalse(cohort.contains("TradeSource.UNKNOWN"))
        assertFalse(cohort.contains("TradeSource.MANUAL"))
    }

    @Test
    fun `canonical stream drives real learners not readiness counters only`() {
        assertTrue(subscribers.contains("MetaCognitionAI.onCanonicalOutcome(outcome)"))
        assertTrue(subscribers.contains("AdaptiveLearningEngine.onCanonicalOutcome(outcome)"))
        assertTrue(subscribers.contains("BehaviorLearning.onCanonicalOutcome(outcome)"))
        assertTrue(subscribers.contains("RunTracker30D.onCanonicalOutcome(outcome)"))
    }

    @Test
    fun `specialist layer votes use the same meme desk definition`() {
        val voteBlock = subscribers.substringAfter("// UNIVERSAL MEME LAYER VOTE CLOSEOUT.")
            .substringBefore("// Generic readiness recorder")
        assertTrue(voteBlock.contains("if (!isMemeLearningOutcome(outcome)) return@subscribe"))
    }
}
