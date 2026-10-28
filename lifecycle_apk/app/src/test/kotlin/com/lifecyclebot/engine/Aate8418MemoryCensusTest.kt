package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236
import com.lifecyclebot.engine.truth.MarkPriceFreshnessTelemetry6832
import com.lifecyclebot.engine.truth.TokenMetricsAuthority7069
import com.lifecyclebot.v3.scoring.OrderbookImbalancePulseAI
import com.lifecyclebot.v3.scoring.ProjectSniperAI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — the stores behind 5.0.8416's 160 MB of unattributed heap: each is bounded at write (or by its census trim)
 * and registered in MemoryGuard7977's census.
 */
class Aate8418MemoryCensusTest {
    private val now = System.currentTimeMillis()
    private val stale = now - 3L * 3_600_000L

    @Test fun orderbookPulseKeepsAtMostTwoThousandMintsAndTheNewestStillReads() {
        for (i in 0 until 2_500) OrderbookImbalancePulseAI.pushTick("pulse8418_$i", 50.0)
        Thread.sleep(5)   // the newest entry is strictly newer than the burst, so an oldest-first trim cannot take it
        OrderbookImbalancePulseAI.pushTick("pulse8418_last", 50.0)
        OrderbookImbalancePulseAI.pushTick("pulse8418_last", 70.0)
        assertTrue(OrderbookImbalancePulseAI.count8418() <= 2_000)
        assertEquals(20.0, OrderbookImbalancePulseAI.getRecentPulse("pulse8418_last"), 1e-9)
    }

    @Test fun chartInsightsAndAggregatedSignalsAreBoundedPerMint() {
        for (i in 0 until 2_500) {
            SuperBrainEnhancements.recordChartInsight("ci8418_$i", "S$i", "BREAKOUT", "1m", 60.0, 1.0)
            SuperBrainEnhancements.recordSignal("ci8418_$i", "S$i", "test", "BULLISH")
        }
        Thread.sleep(5)
        SuperBrainEnhancements.recordSignal("ci8418_last", "L", "test", "BULLISH")
        assertTrue(SuperBrainEnhancements.count8418() <= 4_000)
        assertNotNull(SuperBrainEnhancements.getAggregatedSignal("ci8418_last"))
    }

    @Test fun expiredLlmNarrativesAndExitAdviceLeaveOnceOverTheCap() {
        for (i in 0 until 1_200) {
            AsyncGeminiNarrativeCache6478.putForTest8418("N$i|X", AsyncGeminiNarrativeCache6478.Entry(false, null, stale))
            AsyncGeminiExitAdviceCache6479.putForTest8418("m$i|p0|h0", AsyncGeminiExitAdviceCache6479.Entry(null, stale))
        }
        AsyncGeminiNarrativeCache6478.putForTest8418("FRESH8418|FRESH8418", AsyncGeminiNarrativeCache6478.Entry(true, null, now))
        AsyncGeminiNarrativeCache6478.bound8418(now)
        AsyncGeminiExitAdviceCache6479.bound8418(now)
        assertTrue(AsyncGeminiNarrativeCache6478.count8418() < 1_200)
        assertTrue(AsyncGeminiExitAdviceCache6479.count8418() < 1_200)
        assertEquals(true, AsyncGeminiNarrativeCache6478.peekExact7925("FRESH8418", "FRESH8418")?.quickScam)

        val a = GeminiCopilot.NarrativeAnalysis(false, 0.0, "NONE", "MEME", 0.5, emptyList(), emptyList(), "r", "WATCH")
        for (i in 0 until 700) GeminiCopilot.putNarrativeForTest8418("gc8418_$i", a, stale)
        GeminiCopilot.bound8418(now)
        assertTrue(GeminiCopilot.count8418() <= 500)
    }

    @Test fun llmVotesOutsideTheirWindowAreDroppedAndAFreshVoteStillReads() {
        for (i in 0 until 2_100) SentienceHooks.putVoteForTest8418("V8418_$i", true, stale)
        SentienceHooks.putVoteForTest8418("VFRESH8418", false, now)
        SentienceHooks.bound8418(now)
        assertTrue(SentienceHooks.count8418() < 2_100)
        assertEquals("VETO", SentienceHooks.llmVote("VFRESH8418"))
    }

    @Test fun markFreshnessTelemetryKeepsAtMostFourThousandMints() {
        for (i in 0 until 4_500) MarkPriceFreshnessTelemetry6832.observe("fresh8418_$i", 1.0 + i)
        assertTrue(MarkPriceFreshnessTelemetry6832.count8418() <= 4_000)
    }

    @Test fun quoteRepairAndThrottleStampsPastTheirWindowsLeave() {
        for (i in 0 until 4_200) {
            MarkIdentityRepairAuthority7236.stampAttemptForTest8418("q8418_$i", stale)
            BirdeyeWhaleFeeder.stampForTest8418("b8418_$i", stale)
            ProjectSniperAI.stampForTest8418("p8418_$i", stale)
            TokenMetricsAuthority7069.stampForTest8418("t8418_$i", stale)
        }
        MarkIdentityRepairAuthority7236.bound8418(now)
        BirdeyeWhaleFeeder.bound8418(now)
        ProjectSniperAI.bound8418(now)
        TokenMetricsAuthority7069.bound8418(now)
        assertTrue(MarkIdentityRepairAuthority7236.count8418() < 4_200)
        assertTrue(BirdeyeWhaleFeeder.count8418() < 4_200)
        assertTrue(ProjectSniperAI.count8418() < 4_200)
        assertTrue(TokenMetricsAuthority7069.count8418() < 4_200)
    }

    @Test fun everyNewStoreIsInTheCensusWithATrimOrAnExemptKind() {
        val stores = MemoryGuard7977.registeredStores8144()
        for (n in listOf("orderbookPulse8418", "chartInsights8418", "llmNarratives8418", "llmExitAdvice8418", "llmVotes8418",
                "markFreshness8418", "execQuoteRepairs8418", "throttleStamps8418", "noTradeObservations8418", "serviceStamps8418")) {
            assertTrue(n, n in stores)
        }
        assertTrue(MemoryGuard7977.untrimmedStores8309().isEmpty())
    }
}
