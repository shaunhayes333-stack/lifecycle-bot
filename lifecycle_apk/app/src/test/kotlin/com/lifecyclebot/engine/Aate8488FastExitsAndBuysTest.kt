package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.BalanceProofWaitState
import com.lifecyclebot.engine.truth.BuyStageTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8488 — warm holding for every exit, faster proof waits, named queue causes, buy timing per step. */
class Aate8488FastExitsAndBuysTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun proofWaitPollsWithinSeconds() {
        assertEquals(1_000L, BalanceProofWaitState.backoffFor(0))
        assertEquals(2_000L, BalanceProofWaitState.backoffFor(1))
        assertEquals(8_000L, BalanceProofWaitState.backoffFor(4))
        assertEquals(8_000L, BalanceProofWaitState.backoffFor(40))
    }

    @Test fun buyQuoteLadderStopsOnQuotaAndTransport() {
        assertTrue(BuyStageTiming.quoteUnreachable("Jupiter GET 429"))
        assertTrue(BuyStageTiming.quoteUnreachable("Jupiter POST timeout"))
        assertTrue(BuyStageTiming.quoteUnreachable("Too Many Requests"))
        assertFalse(BuyStageTiming.quoteUnreachable("price impact 12% above guard"))
    }

    @Test fun buyStepsAreTimedFromTheFirstPhase() {
        val m = "Aate8488Mint${System.nanoTime()}"
        BuyStageTiming.onPhase(m, "BUY_QUOTE_TRY", 1_000L)
        BuyStageTiming.onPhase(m, "BUY_QUOTE_OK", 1_600L)
        BuyStageTiming.onPhase(m, "BUY_BROADCAST", 2_100L)
        BuyStageTiming.onPhase(m, "BUY_CONFIRMED", 3_000L)
        BuyStageTiming.onPhase(m, "BUY_VERIFIED_LANDED", 3_200L)
        val (n, _, max) = BuyStageTiming.startToBroadcast()
        assertTrue(n >= 1)
        assertTrue(max >= 1_100L)
        assertTrue(BuyStageTiming.statusLine().contains("QUOTE_TRY->QUOTE_OK"))
    }

    @Test fun everyExitReadsTheWarmHoldingFirst() {
        val planner = src("engine/ProcessorAmountPlanner.kt")
        assertTrue(planner.indexOf("StopLatencyWiring8153.provenHolding8153(ts.mint)") in 0 until planner.indexOf("SellBalanceProof8237.accounts8237("))
        val auth = src("engine/sell/SellAmountAuthority.kt").substringAfter("fun resolveForExit(")
        assertTrue(auth.indexOf("StopLatencyWiring8153.provenHolding8153(mint)") in 0 until auth.indexOf("val normal = resolve(mint, wallet)"))
        assertTrue(src("engine/Executor.kt").contains("if (emergency8094 || warm8488) null else wallet"))
        assertTrue(src("engine/sell/BalanceProofWaitState.kt").contains("ExitStageTiming7876.noteQueueCause8087(mint, \"BALANCE_PROOF_WAIT_\""))
        assertFalse(src("engine/sell/BalanceProofPoller.kt").substringAfter("private fun emergencyResell8144(")
            .substringBefore("val handoff").contains("bypassesQueue8144"))
    }
}
