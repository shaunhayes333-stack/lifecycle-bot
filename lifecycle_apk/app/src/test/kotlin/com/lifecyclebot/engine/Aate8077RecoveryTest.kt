package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.HeldQuoteBatch8077
import com.lifecyclebot.network.NetworkRetry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate8077RecoveryTest {
    private val qty = BigInteger("1000000000")
    private fun buy() = EconomicEventSchema6464.Buy(1, "paper", "p", "m", "M", "buy",
        0.001, 0.000001, qty, 0.12)
    private fun sell() = EconomicEventSchema6464.Sell(2, "paper", "p", "m", "M", "sell",
        false, qty, 0.001, 0.0011, 0.000001, 0.001099, 0.000099, 9.9,
        BigInteger.ZERO, 0.0, 0.132, 120.0)

    @Test fun historySnapshotRequestsAreBoundedAndRetainWorkArrivingDuringPublish() {
        val queue = java.util.ArrayDeque<Runnable>()
        val published = mutableListOf<String>()
        lateinit var work: com.lifecyclebot.engine.truth.CoalescedJournalWork8077
        work = com.lifecyclebot.engine.truth.CoalescedJournalWork8077(
            schedule = { queue.add(it) },
            publish = { kind ->
                published.add(kind)
                if (kind == "8191") work.request("late")
            },
        )
        repeat(8192) { work.request(it.toString()) }
        assertEquals(1, queue.size)
        assertTrue(published.isEmpty())
        queue.remove().run()
        assertEquals(listOf("8191"), published)
        assertEquals(1, queue.size)
        queue.remove().run()
        assertEquals(listOf("8191", "late"), published)
        assertTrue(queue.isEmpty())
    }

    @Test fun failedSnapshotDoesNotStrandLaterReceiptNotifications() {
        val queue = java.util.ArrayDeque<Runnable>()
        var calls = 0
        val work = com.lifecyclebot.engine.truth.CoalescedJournalWork8077(
            schedule = { queue.add(it) }, publish = { if (++calls == 1) error("replay failed") },
        )
        work.request("first")
        try { queue.remove().run(); fail("expected publish failure") } catch (_: IllegalStateException) { }
        work.request("second")
        assertEquals(1, queue.size)
        queue.remove().run()
        assertEquals(2, calls)
    }

    @Test fun sameLaneCanHaveQuickTradesAndLongHoldsWithoutScannerReclassification() {
        fun token(mint: String, style: String) = com.lifecyclebot.data.TokenState(mint = mint,
            position = com.lifecyclebot.data.Position(qtyToken = 1.0, entryPrice = 1.0,
                entryTime = 1L, tradingMode = "MOONSHOT", isPaperPosition = false,
                entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT"))
        val quick = token("quick", "QUICK_FLIP")
        val runner = token("runner", "DIAMOND_HANDS_RUNNER")
        assertEquals(false, TradeExitContract8077.longHold(quick))
        assertEquals(true, TradeExitContract8077.longHold(runner))
        quick.position.tradingMode = "BLUECHIP"
        assertEquals(false, TradeExitContract8077.longHold(quick))
        assertNull(LifecycleHold8065.defer8065(quick, "MANIPULATED_TAKE_PROFIT"))
        assertFalse(LifecycleHold8065.partialHeld8065(quick, "CHART_PARTIAL"))
        assertFalse(LifecycleHold8065.onMark8065(quick, 3.0, 1000L) { _, _, _ -> fail("quick trade cannot become a long hold at 2x") })
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decide8034(quick, "TAKE_PROFIT"))
        assertNull(TradeExitContract8077.longHoldStyle("UNKNOWN"))
        val tacticalLaneRunner = token("non-lifecycle-runner", "DIAMOND_HANDS_RUNNER")
        tacticalLaneRunner.position.tradingMode = "MANIPULATED"
        assertNull(LifecycleHold8065.defer8065(tacticalLaneRunner, "STOP_LOSS"))
    }

    @Test fun manipulatedBanksFirstMinuteWinnerAndKeepsPeakTrailArmed() {
        val p = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipulatedPosition(
            "m", "M", 1.0, 0.03, 0L, 14.0, -11.0, 60, 45.0, 80.0, true,
            highWaterMark = 1.12, peakPnlPct = 12.0)
        val ai = com.lifecyclebot.v3.scoring.ManipulatedTraderAI
        assertEquals(com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipExitSignal.TAKE_PROFIT, ai.tacticalExit8077(p, 25.0, 1.25, 20))
        assertEquals(com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipExitSignal.TRAILING_STOP, ai.tacticalExit8077(p, 0.0, 1.0, 30))
        assertEquals(com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipExitSignal.STOP_LOSS, ai.tacticalExit8077(p, -16.0, 0.84, 10))
        val unconfirmed = p.copy(highWaterMark = 1.0, peakPnlPct = 0.0)
        assertNull(ai.tacticalExit8077(unconfirmed, -4.0, 0.96, 20))
        assertEquals(com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipExitSignal.STOP_LOSS, ai.tacticalExit8077(unconfirmed, -4.0, 0.96, 61))
        assertEquals(0, p.partialRungsTaken)
        val pending = p.copy(takeProfitPct = 50.0, pendingPartialRung8077 = 0)
        // A request may be repeated while its sell has not landed; it is not a receipt.
        repeat(3) { ai.tacticalExit8077(pending, 25.0, 1.25, 20) }
        assertEquals(0, pending.partialRungsTaken)
        assertSame(pending, ai.confirmedPartial8077(pending, 0.0))
        val confirmed = ai.confirmedPartial8077(pending, 0.5)
        assertEquals(1, confirmed.partialRungsTaken)
        assertEquals(0.015, confirmed.entrySol, 1e-12)
        assertNull(confirmed.pendingPartialRung8077)
    }

    @Test fun coreYieldNeedsOwnProofAndPreservesItsOriginalSizingAndScore() {
        val o = SpecialistBrainBridge7542.Opinion("CORE", false, 35, 35, 0.0,
            "CORE_YIELD_CLEAR_SPECIALIST_OWNER", "MAINSTREAM_CRYPTO_SWING", "core", "core")
        assertSame(o, SpecialistBrainBridge7542.reviewCoreYield8077(o) { _, _ -> false })
        val rescued = SpecialistBrainBridge7542.reviewCoreYield8077(o) { rule, lane ->
            assertEquals("CORE", lane)
            assertEquals("NATIVE_${DecisionReasons8031.rule8031(o.reason)}", rule)
            true
        }
        assertTrue(rescued.eligible)
        assertEquals(o.score, rescued.score)
        assertEquals(o.requestedSizeSol, rescued.requestedSizeSol, 0.0)
        for (blocked in listOf(o.copy(reason = "LIQ_TOO_LOW"), o.copy(gradeable = false),
            o.copy(authoritative = false), o.copy(score = 0), o.copy(lane = "MOONSHOT"))) {
            assertSame(blocked, SpecialistBrainBridge7542.reviewCoreYield8077(blocked) { _, _ ->
                fail("ineligible refusal must not ask for an override"); true
            })
        }
    }

    @Test fun durableSellRetainsItsOriginalValuationProof() {
        val original = sell()
        assertEquals(original, EconomicEventSchema6464.decode6486(EconomicEventSchema6464.encode6486(original)))
        val legacy = JSONObject(EconomicEventSchema6464.encode6486(original))
        legacy.remove("exitPriceUsd"); legacy.remove("solUsdAtExit")
        val restored = EconomicEventSchema6464.decode6486(legacy.toString()) as EconomicEventSchema6464.Sell
        assertEquals(0.0, restored.exitPriceUsd, 0.0)
        assertEquals(0.0, restored.solUsdAtExit, 0.0)
    }

    @Test fun journalEntryFeeExcludesExitFeesWithEitherCompleteProof() {
        fun fee(buys: List<EconomicEventSchema6464.Buy>, sells: List<EconomicEventSchema6464.Sell>) =
            CanonicalPaperTransaction6486.historicalEntryFees8077(0.001, qty, 0.000002, 0.001, qty, buys, sells)
        assertEquals(0.000001, fee(listOf(buy()), listOf(sell()))!!, 1e-15)
        assertEquals(0.000001, fee(emptyList(), listOf(sell()))!!, 1e-15)
        assertNull(fee(emptyList(), emptyList()))
        assertNull(fee(emptyList(), listOf(sell().copy(allocatedCostBasisSol = Double.NaN))))
        assertNull(fee(emptyList(), listOf(sell().copy(exitFeesSol = Double.POSITIVE_INFINITY))))
        // Principal + entry fee + exit fee are charged exactly once.
        val cashChange = -buy().executedCostSol - fee(listOf(buy()), listOf(sell()))!! + sell().netProceedsSol
        assertEquals(0.000098, cashChange, 1e-15)
    }

    @Test fun stalledFirstQuoteCannotConsumeANewDeadlineForEveryHolding() {
        val pool = Executors.newFixedThreadPool(4)
        val entered = CountDownLatch(3)
        val interrupted = CountDownLatch(3)
        val ready = CountDownLatch(1)
        try {
            val requests = linkedMapOf<String, () -> Double?>()
            repeat(3) { i -> requests["stalled$i"] = {
                entered.countDown()
                try { CountDownLatch(1).await(); null }
                finally { interrupted.countDown() }
            } }
            requests["healthy"] = { ready.countDown(); 1.25 }
            val start = System.nanoTime()
            val result = HeldQuoteBatch8077.resolve(requests, pool, 500)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(0L, entered.count)
            assertEquals(0L, ready.count)
            assertTrue("batch took $elapsedMs ms", elapsedMs < 1200)
            assertEquals(1.25, result.getValue("healthy").price!!, 0.0)
            repeat(3) { assertTrue(result.getValue("stalled$it").timedOut) }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun manualWebsocketAttemptsObserveCooldownAndProbeFailure() {
        val retry = NetworkRetry("test", failureThreshold = 1, openDurationMs = 60_000)
        assertTrue(retry.allowManualAttempt8077())
        retry.recordFailure()
        assertFalse(retry.allowManualAttempt8077())
        assertTrue(retry.allowManualAttempt8077(System.currentTimeMillis() + 60_001))
        retry.recordFailure()
        assertFalse(retry.allowManualAttempt8077())
        retry.recordSuccess()
        assertTrue(retry.allowManualAttempt8077())
    }
}
