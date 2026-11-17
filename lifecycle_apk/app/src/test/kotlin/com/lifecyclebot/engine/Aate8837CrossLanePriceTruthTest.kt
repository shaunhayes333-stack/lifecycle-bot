package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LlmAnalyst8746
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.Mind8746
import com.lifecyclebot.engine.truth.SignalTape8828
import com.lifecyclebot.engine.cortex.CortexVoters7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8837 — cross-lane models, causal signal fills, unified price history and free history before paid. */
class Aate8837CrossLanePriceTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun latestMindAndTreeReadingsAreScopedToTheirOriginalLane() {
        val mint = "8837_same_mint"
        val a = Mind8746.features8746(18.0, 5.0, Double.NaN, 0.9)
        val b = Mind8746.features8746(-12.0, -4.0, Double.NaN, 0.2)
        val now = 1_000_000L
        Mind8746.note8746(mint, " moonshot ", a, 22.0, now)
        Mind8746.note8746(mint, "EXPRESS", b, -14.0, now + 10L)

        assertEquals(22.0, Mind8746.mindFor8746(mint, "MOONSHOT", now + 10L)!!, 0.0)
        assertEquals(-14.0, Mind8746.mindFor8746(mint, "express", now + 10L)!!, 0.0)
        assertEquals(18.0, Mind8746.treeFor8746(mint, "MOONSHOT", now + 10L)!!, 0.0)
        assertEquals(-12.0, Mind8746.treeFor8746(mint, "EXPRESS", now + 10L)!!, 0.0)
        assertEquals(0.9, Mind8746.llmFor8746(mint, "MOONSHOT", now + 10L)!!, 1e-9)
        assertEquals(0.2, Mind8746.llmFor8746(mint, "EXPRESS", now + 10L)!!, 1e-9)
        assertTrue(Mind8746.spreadFor8746(mint, "MOONSHOT", now + 10L)!! > 0.0)
        assertNull("no borrowing another lane's model prediction", Mind8746.mindFor8746(mint, "SHITCOIN", now + 10L))

        // Out-of-order concurrent evaluations cannot roll a newer lane assessment back.
        Mind8746.note8746(mint, "MOONSHOT", b, -99.0, now - 1L)
        assertEquals(22.0, Mind8746.mindFor8746(mint, "MOONSHOT", now + 10L)!!, 0.0)
        assertNull("an aged assessment cannot become new evidence", Mind8746.mindFor8746(mint, "MOONSHOT", now + 600_001L))
        // The features retained by a voter are a snapshot, not a mutable buffer.
        a[1] = 999.0
        assertEquals(18.0, Mind8746.treeFor8746(mint, "MOONSHOT", now + 10L)!!, 0.0)
    }

    @Test fun asyncLlmAnswerCannotBeReusedForAnotherStrategyLane() {
        val cache = mapOf(LlmAnalyst8746.answerKey8837("sameMint", "MOONSHOT") to (2_000L to 0.83))
        assertEquals(0.83, LlmAnalyst8746.cached8837(cache, "sameMint", "moonshot", 2_100L)!!, 0.0)
        assertNull(LlmAnalyst8746.cached8837(cache, "sameMint", "EXPRESS", 2_100L))
        assertNull(LlmAnalyst8746.cached8837(cache, "differentMint", "MOONSHOT", 2_100L))
        assertNull(LlmAnalyst8746.cached8837(cache, "sameMint", "MOONSHOT", 2_000L + 15 * 60_000L + 1L))
        assertFalse("an LLM request includes the lane", LlmAnalyst8746.answerKey8837("sameMint", "EXPRESS") == cache.keys.single())
        assertEquals(5L, LlmAnalyst8746.targetMinutes8837("TREASURY"))
        assertEquals(15L, LlmAnalyst8746.targetMinutes8837("MOONSHOT"))
        val llmSource = src("engine/truth/LlmAnalyst8746.kt")
        assertTrue(llmSource.contains("${'$'}{targetMinutes8837(r.lane)} minutes from this decision"))
        assertTrue("an answer cannot be timestamped as if its old features were observed at completion",
            llmSource.contains("answers[key] = nowMs to p"))
    }

    @Test fun openingBuyersCannotTriggerAtAnUnexecutablePriceAfterADeadMinute() {
        val tape = SignalTape8828()
        tape.onPrint(1_000L, 1e-7, true, 0.5, 11, true)
        tape.onPrint(5_000L, 1e-7, true, 0.5, 12, true)
        tape.onPrint(9_000L, 1e-7, true, 0.5, 13, true)
        assertTrue("the opening bid was 61 seconds ago, not an entry now",
            tape.onPrint(71_000L, 1.5e-7, true, 0.5, 14, true).isEmpty())
        assertFalse("cannot retroactively fire S001 on the next bar", "S001" in
            tape.onPrint(81_000L, 1.6e-7, true, 0.5, 15, true))
    }

    @Test fun allCortexForecasterVotesPassTheAssessedLaneAndEdgeWritesThatLane() {
        val edge = src("engine/truth/EdgeModel8537.kt")
        assertTrue(edge.contains("Mind8746.note8746(mint, raw.lane, f, mind, now)"))
        assertTrue(edge.contains("LlmAnalyst8746.prob8746(mint, raw.lane, now)"))
        val voters = src("engine/cortex/CortexVotersWide8004.kt")
        for (method in listOf("mindFor8746", "treeFor8746", "llmFor8746", "spreadFor8746"))
            assertTrue("$method must read the assessment lane", voters.contains("Mind8746.$method(ts.mint, lane, now)"))
        for (id in listOf("MIND_STACKED_PRED", "TREE_RANKER_PRED", "LLM_ANALYST_P_UP", "BRAIN_DISAGREEMENT_SSI"))
            assertTrue("$id must be lane-keyed even in Cortex's slow/memo cache", id in CortexVoters7885.LANE_DEPENDENT_8169)
    }

    @Test fun freeHistoricalBarsPrecedeUnresolvableOrRateLimitedPaidVenues() {
        val c = src("engine/truth/HeliusSwapCandles7819.kt").substringAfter("fun request7819(").substringBefore("private fun heliusAfterFree8418(")
        assertTrue("free history must be attempted before an unknown market return",
            c.indexOf("if (!heliusAfterFree8418(ts, nowMs, priority)) return") in 0 until c.indexOf("val marketAddress = activeMarketAddress8269(ts)"))
        assertTrue("free history must be attempted before paid cooldown",
            c.indexOf("if (!heliusAfterFree8418(ts, nowMs, priority)) return") < c.indexOf("if (nowMs < next)"))

        val mark = src("engine/truth/ShadowMark8360.kt")
        assertTrue("shadow entry and shadow exit must resolve the same priced observation",
            mark.contains("return observedTimed8372(ts, mint, nowMs)?.let { it.priceUsd to it.source }"))
    }

    @Test fun observedAmmVenueBeatsPumpSuffixWhenMigrationFlagIsLate() {
        val mint = "exampleMintpump"
        assertEquals("explicit PumpSwap venue is NOT the bonding curve", 1.0,
            LearnerColumns8798.venueOf(mint, "PUMPSWAP", "", "", migrated = false), 0.0)
        assertEquals("explicit Raydium venue is NOT the bonding curve", 3.0,
            LearnerColumns8798.venueOf(mint, "RAYDIUM_CPMM", "", "", migrated = false), 0.0)
        assertEquals("explicit Raydium venue outranks even a stale graduated flag", 3.0,
            LearnerColumns8798.venueOf(mint, "RAYDIUM_CPMM", "", "", migrated = true), 0.0)
        assertEquals("explicit Meteora venue is NOT the bonding curve", 4.0,
            LearnerColumns8798.venueOf(mint, "METEORA_DLMM", "", "", migrated = false), 0.0)
        assertEquals(0.0, LearnerColumns8798.venueOf(mint, "UNKNOWN", "UNKNOWN", "", migrated = false), 0.0)
    }
}
