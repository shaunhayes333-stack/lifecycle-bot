package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6678 — trade-quality / win-rate sentience regression locks.
 *
 * Prevents a repeat of V5.0.6677 where provider connectivity was restored but
 * entry-side LLM hooks remained orphaned and the auto-tuner asked for a syntax
 * LlmParameterTuner could never parse.
 */
class Aate6678TradeQualitySentienceLoopTest {

    private val sentience = File("src/main/kotlin/com/lifecyclebot/engine/SentienceHooks.kt").readText()
    private val narrative = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MemeNarrativeAI.kt").readText()

    @Test
    fun `meme entry review primes and consumes the LLM vote`() {
        val requestBlock = sentience
            .substringAfter("fun requestLlmMemeBuy(")
            .substringBefore("// ─────────────────────────────────────────────────────────────────────────\n    // 4.")
        assertTrue(requestBlock.contains("preTradeVeto(symbol, score, confidence, reason)"))
        assertTrue(sentience.contains("fun entryQualityScoreBias6678(symbol: String): Int"))
        assertTrue(sentience.contains("\"VETO\" -> -12"))
        assertTrue(sentience.contains("\"ALLOW\" -> 2"))

        assertTrue(narrative.contains("SentienceHooks.requestLlmMemeBuy("))
        assertTrue(narrative.contains("SentienceHooks.entryQualityScoreBias6678(symbol)"))
        assertTrue(narrative.contains("LLM_ENTRY_SCORE_SHAPED_6678"))
    }

    @Test
    fun `entry LLM remains bounded and cannot directly execute`() {
        val biasBlock = sentience
            .substringAfter("fun entryQualityScoreBias6678")
            .substringBefore("private fun ask")
        assertFalse(biasBlock.contains("Executor"))
        assertFalse(biasBlock.contains("paperBuy"))
        assertFalse(biasBlock.contains("liveBuy"))
        assertTrue(narrative.contains("coerceIn(-10, 12)"))
    }

    @Test
    fun `auto tune uses real evidence and exact tuner parser contract`() {
        val tuneBlock = sentience
            .substringAfter("fun maybeAutoTune(context: android.content.Context)")
            .substringBefore("// ─── Diagnostics")

        assertTrue(sentience.contains("AUTOTUNE_INTERVAL_MS = 5L * 60L * 1000L"))
        assertTrue(sentience.contains("AUTOTUNE_MIN_NEW_OUTCOMES_6678 = 10L"))
        assertTrue(sentience.contains("canonicalOutcomesSinceTune6678.incrementAndGet()"))
        assertTrue(sentience.contains("QualityLadder.statusLine()"))
        assertTrue(sentience.contains("SsiPilotCouncil.statusLine()"))
        assertTrue(sentience.contains("LaneBucketPivot.statusLine()"))
        assertTrue(sentience.contains("UnifiedPolicyHead.trainedCount()"))

        assertTrue(tuneBlock.contains("<<TUNE>>"))
        assertTrue(tuneBlock.contains("<<ENDTUNE>>"))
        assertTrue(tuneBlock.contains("LlmParameterTuner.extractAndApply(context, reply)"))
        assertTrue(tuneBlock.contains("Improve ENTRY QUALITY / WIN RATE only"))
        assertTrue(tuneBlock.contains("Do not optimize throughput"))
    }

    @Test
    fun `auto tune quality key set cannot disable lanes`() {
        val context = sentience
            .substringAfter("val qualityKeys = listOf(")
            .substringBefore(").filter")
        assertTrue(context.contains("minDiscoveryScore"))
        assertTrue(context.contains("minLiquidityUsd"))
        assertTrue(context.contains("sentimentBlockThreshold"))
        assertTrue(context.contains("behaviorAggressionLevel"))
        assertTrue(context.contains("aggressiveWhaleThreshold"))
        assertFalse(context.contains("disable"))
        assertFalse(context.contains("paperMode"))
    }
}
