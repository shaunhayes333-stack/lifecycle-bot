package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
import com.lifecyclebot.engine.truth.FillMarketReference8239
import com.lifecyclebot.engine.truth.HeldQuoteEvidence8239
import com.lifecyclebot.engine.truth.PositionExcursion8082
import com.lifecyclebot.engine.truth.QuoteFillCost8239
import com.lifecyclebot.engine.truth.RunnerEvidence8239
import java.io.File
import java.math.BigInteger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Decision economics and historical evidence are exercised without network access or a funded wallet. */
class Aate8239ExecutionQualityTest {
    private fun raw(n: Long) = BigInteger.valueOf(n)
    private fun source(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun heldQuotesRequireTheSamePositionQuantityDecimalsModeAndVenue() {
        val h = HeldQuoteEvidence8239.Holding("mint", "position-a", "LIVE", raw(1_000), raw(800), 6, "RAYDIUM:pool-a")
        assertTrue(HeldQuoteEvidence8239.matches(h, h.copy()))
        for (changed in listOf(h.copy(positionId = "position-b"), h.copy(rawQty = raw(700)),
            h.copy(canonicalRaw = raw(900)), h.copy(decimals = 9), h.copy(mode = "PAPER"),
            h.copy(mint = "other"), h.copy(venueContext = "PUMPSWAP:pool-b"))) {
            assertFalse("a cached quote cannot cross holding identity: $changed", HeldQuoteEvidence8239.matches(h, changed))
        }
        assertFalse(HeldQuoteEvidence8239.matches(h.copy(positionId = ""), h.copy(positionId = "")))
        assertFalse(HeldQuoteEvidence8239.matches(h.copy(rawQty = raw(2_000)), h.copy(rawQty = raw(2_000))))
    }

    @Test fun reserveModelsRetainTheirOriginalClockAndCannotBecomeRoutedQuotes() {
        val q = HeldQuoteEvidence8239.Observation(0.01, 0.03, raw(800), 6,
            HeldQuoteEvidence8239.Kind.ROUTED_QUOTE, "JUPITER", "pool", 100_000L, 100_000L)
        assertTrue(HeldQuoteEvidence8239.fresh(q, 103_999L))
        assertFalse(HeldQuoteEvidence8239.fresh(q, 104_001L))
        val model = q.copy(kind = HeldQuoteEvidence8239.Kind.RESERVE_MODEL, observedAtMs = 81_000L,
            requestedAtMs = 100_000L, feeAssumptionPct = 1.25)
        assertTrue(HeldQuoteEvidence8239.fresh(model, 100_000L))
        assertFalse("a new request does not refresh old reserves", HeldQuoteEvidence8239.fresh(model.copy(requestedAtMs = 105_000L), 105_000L))
        assertNotEquals(q.source, model.source)
        assertFalse(HeldQuoteEvidence8239.fresh(q.copy(observedAtMs = 100_001L), 100_000L))
        assertFalse(HeldQuoteEvidence8239.fresh(q.copy(outSol = Double.NaN), 100_000L))
    }

    @Test fun aRejectedQuoteTaskReleasesItsKeyAndDoesNotRunOnTheCaller() {
        val pool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1), ThreadPoolExecutor.AbortPolicy())
        val keys = ConcurrentHashMap.newKeySet<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        try {
            assertTrue(HeldQuoteEvidence8239.enqueue(pool, keys, "first") { entered.countDown(); release.await(2, TimeUnit.SECONDS) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse("duplicate coalesces", HeldQuoteEvidence8239.enqueue(pool, keys, "first") { fail("duplicate ran") })
            assertTrue(HeldQuoteEvidence8239.enqueue(pool, keys, "second") { drained.countDown() })
            assertFalse(HeldQuoteEvidence8239.enqueue(pool, keys, "retryable") { fail("rejected task ran on caller") })
            assertFalse("rejection must not strand the mint forever", keys.contains("retryable"))
            release.countDown()
            assertTrue(drained.await(1, TimeUnit.SECONDS))
            val retried = CountDownLatch(1)
            assertTrue(HeldQuoteEvidence8239.enqueue(pool, keys, "retryable") { retried.countDown() })
            assertTrue(retried.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown(); pool.shutdown()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
        assertTrue(keys.isEmpty())
    }

    @Test fun exactSliceCostIsIndependentOfMarksAndCannotOversell() {
        assertEquals(0.0075, ProfitExitTruth8163.sliceCost(0.03, raw(1_000), raw(250))!!, 1e-12)
        assertNull(ProfitExitTruth8163.sliceCost(0.03, raw(1_000), raw(1_001)))
        assertNull(ProfitExitTruth8163.sliceCost(0.0, raw(1_000), raw(250)))
        assertNull(ProfitExitTruth8163.sliceCost(0.03, raw(1_000), BigInteger.ZERO))
        assertNull(com.lifecyclebot.engine.truth.HeldExitQuote8189.heldQty8189(raw(1_000), BigInteger.ZERO, true))
        assertFalse(ProfitExitTruth8163.clearsQuoted8239(null, 40_000_000, 500, null, 0, 0.0002))
        assertFalse(ProfitExitTruth8163.clearsQuoted8239(0.03, 0, 500, null, 0, 0.0002))
    }

    @Test fun routeOutputsChargeOnlyTheIncrementalQuoteToWalletShortfall() {
        assertEquals(2.0, QuoteFillCost8239.shortfallPct(0.03, 0.0294)!!, 1e-10)
        assertEquals(-1.0, QuoteFillCost8239.shortfallPct(0.03, 0.0303)!!, 1e-10)
        assertNull(QuoteFillCost8239.shortfallPct(0.0, 0.03))
        assertNull(QuoteFillCost8239.shortfallPct(0.03, Double.NaN))
        // 0.0105 * 98% = 0.01029. The measured total already paid the fixed fee.
        assertTrue(ProfitExitTruth8163.clearsQuoted8239(0.01, 10_500_000, 500, 2.0, 5, 0.0002))
        assertFalse("charging the fixed fee twice would refuse this valid margin",
            ProfitExitTruth8163.clears(0.01, 10_500_000, 200, 0.0002))
        // Fees outside the quote may exceed the transaction's price-slippage cap.
        assertFalse(ProfitExitTruth8163.clearsQuoted8239(0.01, 10_500_000, 50, 5.0, 5, 0.0002))
        assertFalse(ProfitExitTruth8163.clearsQuoted8239(0.01, 10_500_000, -1, 2.0, 5, 0.0002))
    }

    @Test fun nativeAndJupiterOutputsShareProfitEconomicsAndUnknownNativeOutputDefers() {
        val slice = ProfitExitTruth8163.sliceCost(0.03, raw(1_000), raw(500))
        assertTrue(ProfitExitTruth8163.clearsQuoted8239(slice, 18_000_000, 500, null, 0, 0.000205))
        assertFalse(ProfitExitTruth8163.clearsQuoted8239(slice, 14_000_000, 500, null, 0, 0.000205))
        val profit = source("engine/ProfitExitTruth8163.kt")
        assertTrue(profit.contains("clearsRoute8239(ts, quote.inAmount, quote.outAmount, slip)"))
        assertTrue(profit.contains("clearsRoute8239(ts, raw, outLamports, slip, route)"))
        val executor = source("engine/Executor.kt")
        assertTrue(executor.contains("ProfitExitTruth8163.basisPreflight8239(ts, reason, sellUnits)"))
        assertTrue(executor.contains("built7311.outLamports, raySlip7311, \"RAYDIUM\", built7311.observedAtMs8239"))
        assertTrue(executor.contains("PUMP_PROFIT_ROUTE_OUTPUT_UNPROVEN_8239"))
        for (reason in listOf("strict_sl", "emergency_rug", "owner_manual_sell")) assertFalse(ProfitExitTruth8163.profitTaking(reason))
    }

    @Test fun fillCostReferencesMustPrecedeTheActualChainFillAndHaveIndependentProvenance() {
        val m = FillMarketReference8239.Mark("mint", 0.01, 100.0, 100_000L, "LOCKED_VENUE_POOL_7392", 6)
        assertTrue(FillMarketReference8239.eligible(m, 101_999L))
        assertFalse(FillMarketReference8239.eligible(m, 102_001L))
        assertFalse("a later candle cannot price an earlier fill", FillMarketReference8239.eligible(m, 99_999L))
        for (src in listOf("JUPITER_HELD_SELL_QUOTE_8189", "HELD_VENUE_RESERVE_MODEL_8239", "LIVE_PROOF_COST_BASIS", "REPAIRED_CORROBORATED")) {
            assertFalse(src, FillMarketReference8239.eligible(m.copy(source = src), 101_000L))
        }
        assertFalse(FillMarketReference8239.eligible(m.copy(decimals = -1), 101_000L))
        assertFalse(FillMarketReference8239.eligible(m.copy(solUsd = Double.NaN), 101_000L))
    }

    @Test fun immutableFillReferencesSurviveSerializationWithSignatureQuantityAndOriginalTime() {
        val mark = FillMarketReference8239.Mark("mint", 0.01, 105.0, 100_000L, "LOCKED_VENUE_POOL_7392", 6)
        val fill = FillMarketReference8239.Fill("signature8239", "mint", "SELL", "9007199254740993", 101_000L, mark)
        val encoded = FillMarketReference8239.encode(fill)
        assertEquals(fill, FillMarketReference8239.decode(JSONObject(encoded.toString())))
        assertNull(FillMarketReference8239.decode(JSONObject(encoded.toString()).put("sig", "")))
        assertNull(FillMarketReference8239.decode(JSONObject(encoded.toString()).put("fill", 99_000L)))
        assertNull(FillMarketReference8239.decode(JSONObject(encoded.toString()).put("raw", "0")))
    }

    @Test fun runnerHoldingNeedsAnObservedPeakClockModeAndPosition() {
        assertEquals(RunnerEvidence8239.Hold.HELD, RunnerEvidence8239.hold(2_000, 1_000, 3_000, "LIVE", "p"))
        assertEquals(RunnerEvidence8239.Hold.OUTSIDE, RunnerEvidence8239.hold(4_000, 1_000, 3_000, "LIVE", "p"))
        assertEquals(RunnerEvidence8239.Hold.OUTSIDE, RunnerEvidence8239.hold(900, 1_000, 3_000, "LIVE", "p"))
        assertEquals(RunnerEvidence8239.Hold.UNKNOWN, RunnerEvidence8239.hold(0, 1_000, 3_000, "LIVE", "p"))
        assertEquals(RunnerEvidence8239.Hold.UNKNOWN, RunnerEvidence8239.hold(2_000, 1_000, 3_000, "", "p"))
        assertEquals(RunnerEvidence8239.Hold.UNKNOWN, RunnerEvidence8239.hold(2_000, 1_000, 3_000, "LIVE", ""))
        assertTrue(com.lifecyclebot.engine.truth.RunnerCapture8017.bankedShare8017(30.0, 500.0, Double.NaN).isNaN())
    }

    @Test fun restoredHeldFlagsWithoutProvenanceAreUnknownAndKnownAttributionRoundTrips() {
        val legacy = AdmissionRunnerTrace8144.fromJson8144(JSONObject().put("mint", "mint").put("peakPct", 500.0).put("held", true))
        assertEquals(AdmissionRunnerTrace8144.Miss8144.DATA_GAP, AdmissionRunnerTrace8144.classify8144(legacy).miss)
        val known = legacy.copy(peakAtMs8239 = 2_000, holdingMode8239 = "LIVE", holdingPositionId8239 = "p8239",
            holdingEntryAtMs8239 = 1_000, holdingCloseAtMs8239 = 3_000, holdingEntryPx8239 = 0.01, bankedPct8239 = 22.0)
        val back = AdmissionRunnerTrace8144.fromJson8144(AdmissionRunnerTrace8144.toJson8144(known))
        assertEquals(known, back)
        assertEquals(AdmissionRunnerTrace8144.Miss8144.CAPTURED, AdmissionRunnerTrace8144.classify8144(back).miss)
        assertEquals(AdmissionRunnerTrace8144.Miss8144.DATA_GAP,
            AdmissionRunnerTrace8144.classify8144(back.copy(peakAtMs8239 = 4_000)).miss)
    }

    @Test fun aRealHistoricalPeakSurvivesALaterLowerObservation() {
        val pid = "peak8239-decline"
        PositionExcursion8082.clearForTest()
        PositionExcursion8082.observe(pid, 250.0, "LOCKED_VENUE_POOL_7392", 100_000L)
        PositionExcursion8082.observe(pid, -12.0, "EXEC_QUOTE_8237", 105_000L)
        val e = PositionExcursion8082.forClose(pid)!!
        assertEquals(250.0, e.mfePct, 0.0)
        assertEquals(100_000L, e.mfeAtMs)
        assertEquals(-12.0, e.maePct, 0.0)
        assertFalse(RunnerEvidence8239.invalidates(e.mfeAtMs, 105_000L))
        PositionExcursion8082.release(pid)
    }

    @Test fun aProvenInvalidObservationRemovesOnlyItsOwnExcursion() {
        val pid = "peak8239-invalid"
        PositionExcursion8082.clearForTest()
        PositionExcursion8082.observe(pid, 900.0, "LOCKED_VENUE_POOL_7392", 100_000L)
        PositionExcursion8082.observe(pid, -10.0, "LOCKED_VENUE_POOL_7392", 105_000L)
        assertFalse(PositionExcursion8082.invalidateObservation8239(pid, "mint8239", 99_000L, "WRONG_DECIMALS_PROVEN"))
        assertTrue(PositionExcursion8082.invalidateObservation8239(pid, "mint8239", 100_000L, "WRONG_DECIMALS_PROVEN"))
        val e = PositionExcursion8082.forClose(pid)!!
        assertTrue(e.mfePct.isNaN())
        assertEquals(-10.0, e.maePct, 0.0)
        assertEquals(1L, e.revision8239)
        PositionExcursion8082.release(pid)
    }

    @Test fun provenEntryRepairReexpressesAbsolutePeaksWithoutChangingObservationTimes() {
        val pid = "peak8239-rebase"
        PositionExcursion8082.clearForTest()
        PositionExcursion8082.observe(pid, 400.0, "LOCKED_VENUE_POOL_7392", 100_000L)
        assertTrue(PositionExcursion8082.rebaseEntry8239(pid, 1.0, 2.0))
        val e = PositionExcursion8082.forClose(pid)!!
        assertEquals(150.0, e.mfePct, 1e-9) // Absolute observed price 5 now has entry 2.
        assertEquals(100_000L, e.mfeAtMs)
        PositionExcursion8082.release(pid)
    }

    @Test fun invalidRunnerOpportunityStaysInvalidAfterRestartAndDoesNotReverseConfirmedCash() {
        val b = RunnerRungBook8153()
        val key = "mint8239|1000"
        b.noteOpportunity8153(key, "RUNNER", 0.1, 400.0, 2_000L, "p8239", "LIVE")
        assertTrue(b.confirm8153(key, "RUNNER", 1, "sig8239", 0.3, 0.05))
        val before = b.encode8153()
        assertFalse(b.invalidateOpportunity8239("p8239", 3_000L))
        assertTrue(b.invalidateOpportunity8239("p8239", 2_000L))
        val restored = RunnerRungBook8153()
        restored.restore8153(b.encode8153())
        restored.restore8153(before) // stale max-merge must not revive corrected opportunity.
        assertTrue(restored.statusLine8153(), restored.statusLine8153().contains("grossOppSol=0.0000"))
        assertEquals(0.25, restored.bankedNetSol8153(key), 1e-12)
        assertEquals(1, restored.confirmedTier8153(key, "RUNNER"))
        assertFalse("the actual fill still remains idempotent", restored.confirm8153(key, "RUNNER", 1, "sig8239", 0.3, 0.05))
    }

    @Test fun entryBasisCorrectionCanPersistALowerOpportunityAndClosedHistoryRetainsItsProof() {
        val b = RunnerRungBook8153()
        val key = "mint8239b|1000"
        b.noteOpportunity8153(key, "RUNNER", 0.1, 400.0, 2_000L, "p8239b", "LIVE")
        val old = b.encode8153()
        assertTrue(b.rebaseOpportunity8239("p8239b", 1.0, 2.0, 0.1))
        val restored = RunnerRungBook8153()
        restored.restore8153(b.encode8153()); restored.restore8153(old)
        assertTrue(restored.statusLine8153(), restored.statusLine8153().contains("grossOppSol=0.1500"))
        assertTrue(restored.retire8153(key))
        val closed = RunnerRungBook8153()
        closed.restore8153(restored.encode8153())
        assertTrue(closed.invalidateOpportunity8239("p8239b", 2_000L))
        assertTrue(closed.statusLine8153(), closed.statusLine8153().contains("grossOppSol=0.0000"))
    }

    @Test fun legacyRunnerOpportunityWithoutHoldingProofDoesNotInflateBankingCoverage() {
        val b = RunnerRungBook8153()
        b.restore8153("#closed;4.0;0.2;3\nmint8239|1000;0;1;RUNNER;1;0.3;0.1;1.5\n")
        assertTrue(b.statusLine8153(), b.statusLine8153().contains("grossOppSol=0.0000"))
        assertTrue(b.statusLine8153(), b.statusLine8153().contains("unprovenHistoricalOppSol=5.5000"))
        assertEquals(0.2, b.bankedNetSol8153("mint8239|1000"), 1e-12)
    }
}
