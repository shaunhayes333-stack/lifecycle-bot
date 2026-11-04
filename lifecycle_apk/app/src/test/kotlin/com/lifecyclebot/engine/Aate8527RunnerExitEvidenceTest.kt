package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import com.lifecyclebot.engine.truth.HeldExitQuote8189
import com.lifecyclebot.engine.truth.PositionExcursion8082
import com.lifecyclebot.network.ExitQuoteBudget7863
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.math.BigInteger
import java.net.SocketTimeoutException

/** Real exit-state regressions: retain proved peaks, reject late mark rollback, bound only unsigned quotes. */
class Aate8527RunnerExitEvidenceTest {
    @Before fun resetState() {
        Cpa.resetForTest()
        PositionExcursion8082.clearForTest()
        HeldExitQuote8189.resetForTest8189()
    }
    @After fun cleanupState() { resetState() }
    private val raw = BigInteger.valueOf(100_000_000L)

    private fun runner(tag: String): TokenState {
        val mint = "RunnerExit8527${tag}${System.nanoTime()}"
        val pid = "LIVE:$mint"
        assertEquals(Cpa.MutateResult.APPLIED,
            Cpa.openPosition("open:$pid", pid, mint, tag, "MOONSHOT", "8527",
                1.0, raw, 6, 0.0, false,
                entryPriceUsd = 1.0, entryPriceSource = "LIVE_FINALIZED"))
        val canonical = Cpa.getPosition(pid)!!
        return TokenState(mint = mint, position = Position(
            positionId = pid, qtyToken = 100.0, entryPrice = 1.0, entryTime = canonical.openedAtMs,
            costSol = 1.0, highestPrice = 6.0, peakGainPct = 500.0,
            isPaperPosition = false, tradingMode = "MOONSHOT", entryPriceSource = "LIVE_FINALIZED",
        )).apply { lastPrice = 5.0 }
    }

    @Test fun laterExecutableDrawdownDoesNotEraseThePositionsVerifiedRunnerHigh() {
        val ts = runner("drawdown")
        val now = ts.position.entryTime + 200L
        PositionExcursion8082.observe(ts.position.positionId, 500.0,
            "VERIFIED_HELD_QUANTITY_EXIT_ESTIMATE", now - 100L)
        ProfitExitTruth8163.reconcileMark8237(ts, raw.toLong(), 3_000_000_000L, 100.0, now)
        assertEquals(3.0, ts.lastPrice, 1e-9)
        assertEquals(6.0, ts.position.highestPrice, 1e-9)
        assertEquals(500.0, ts.position.peakGainPct, 1e-9)
        assertEquals("EXEC_QUOTE_8237", ts.lastPriceSource)
        // The price-based and verified-return trail now agree: the real 6x -> 3x drawdown breached.
        assertTrue(ts.lastPrice <= ts.position.highestPrice * 0.65)
        assertTrue(RunnerTrail8419.breached8419(ts.position.peakGainPct, 200.0))
        assertEquals(raw, Cpa.getPosition(ts.position.positionId)!!.remainingQtyRaw)
        assertEquals(0.0, Cpa.getPosition(ts.position.positionId)!!.realizedProceedsSol, 0.0)
    }

    @Test fun anAlreadyErasedHighCanBeRestoredOnlyFromVerifiedSamePositionHistory() {
        val ts = runner("restore")
        val now = ts.position.entryTime + 200L
        PositionExcursion8082.observe(ts.position.positionId, 500.0,
            "VERIFIED_HELD_QUANTITY_EXIT_ESTIMATE", now - 100L)
        ts.position.highestPrice = 1.5
        ProfitExitTruth8163.reconcileMark8237(ts, raw.toLong(), 3_000_000_000L, 100.0, now)
        assertEquals(6.0, ts.position.highestPrice, 1e-9)
        assertTrue(ts.lastPrice <= ts.position.highestPrice * 0.65)
    }

    @Test fun concurrentAcceptedMarksCannotEraseTheHigherMfeOrItsSource() {
        val pid = "excursion-race8527-${System.nanoTime()}"
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { worker ->
                pool.submit {
                    start.await()
                    for (i in 1..250) {
                        val gain = (worker * 250 + i).toDouble()
                        PositionExcursion8082.observe(pid, gain, "VERIFIED_WORKER_$worker", 1_000L)
                        PositionExcursion8082.observe(pid, -gain, "VERIFIED_WORKER_$worker", 1_001L)
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            val e = PositionExcursion8082.forClose(pid)!!
            assertEquals(2_000.0, e.mfePct, 0.0)
            assertEquals(-2_000.0, e.maePct, 0.0)
            assertEquals(4_000, e.samples)
            assertEquals("VERIFIED_WORKER_7", e.source)
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test fun anUnsupportedUnitSpikeStillRebasesToTheExecutableQuote() {
        val ts = runner("phantom")
        ts.lastPrice = 21.0
        ts.position.highestPrice = 21.0
        ts.position.peakGainPct = 2_000.0
        // An unverified or paper observation is not live peak proof.
        PositionExcursion8082.observe(ts.position.positionId, 2_000.0,
            "PAPER_MARK_TEST", ts.position.entryTime + 100L)
        ProfitExitTruth8163.reconcileMark8237(ts, raw.toLong(), 1_035_000_000L, 100.0, ts.position.entryTime + 200L)
        assertEquals(1.035, ts.lastPrice, 1e-9)
        assertEquals(1.035, ts.position.highestPrice, 1e-9)
    }

    @Test fun aPeakBeforeEntryOrFromARescaledBasisCannotSurviveAsVerifiedHistory() {
        for (rescaled in listOf(false, true)) {
            val ts = runner(if (rescaled) "rescaled" else "outside")
            ts.position.priceBasisRescaled = rescaled
            PositionExcursion8082.observe(ts.position.positionId, 500.0, "VERIFIED_HELD_QUANTITY_EXIT_ESTIMATE",
                if (rescaled) ts.position.entryTime + 100L else ts.position.entryTime - 1L)
            ProfitExitTruth8163.reconcileMark8237(ts, raw.toLong(), 3_000_000_000L, 100.0, ts.position.entryTime + 200L)
            assertEquals(3.0, ts.position.highestPrice, 1e-9)
        }
    }

    @Test fun theQuoteCannotCorrectAnotherPositionOrAnAlreadySoldQuantity() {
        val ts = runner("identity")
        ts.position = ts.position.copy(positionId = "other-position")
        ProfitExitTruth8163.reconcileMark8237(ts, raw.toLong(), 3_000_000_000L, 100.0)
        assertEquals(5.0, ts.lastPrice, 0.0)
        val partial = runner("partial")
        val pid = partial.position.positionId
        assertEquals(Cpa.MutateResult.APPLIED,
            Cpa.partialSell("partial:$pid", pid, BigInteger.valueOf(40_000_000L), 0.8, 0.4, 0.0, false))
        ProfitExitTruth8163.reconcileMark8237(partial, raw.toLong(), 3_000_000_000L, 100.0)
        assertEquals("the old whole-holding quote cannot reprice the remaining slice", 5.0, partial.lastPrice, 0.0)
        assertEquals(BigInteger.valueOf(60_000_000L), Cpa.getPosition(pid)!!.remainingQtyRaw)
    }

    @Test fun aLateBackgroundQuoteCannotEraseTheNewerIdentifiedStopQuote() {
        val mint = "late-quote8527-${System.nanoTime()}"
        HeldExitQuote8189.rememberStopQuote8521(mint, "position8527", raw, 6, 0.048, 100.0, 2_000L, 0.0003)
        val newest = HeldExitQuote8189.last8189(mint)!!
        val late = HeldExitQuote8189.Quote8189(0.001, 1_000L, raw, 0.001)
        assertFalse(HeldExitQuote8189.publish8527(mint, late))
        assertEquals(newest, HeldExitQuote8189.last8189(mint))
        assertFalse("equal-age anonymous background evidence cannot erase position identity",
            HeldExitQuote8189.publish8527(mint, late.copy(observedAtMs = 2_000L)))
        val later = late.copy(observedAtMs = 2_001L, priceUsd = 0.035)
        assertTrue(HeldExitQuote8189.publish8527(mint, later))
        assertEquals(later, HeldExitQuote8189.last8189(mint))
    }

    @Test fun theWholeLadderBudgetCannotRestartAtASecondFallback() {
        assertNull(ExitQuoteBudget7863.remainingMs(0L))
        val outer = ExitQuoteBudget7863.begin(0L, budgetMs = 6_000L)
        try {
            assertEquals(2_000L, ExitQuoteBudget7863.remainingMs(0L))
            ExitQuoteBudget7863.run(4_000_000_000L) {
                assertEquals(100L, ExitQuoteBudget7863.remainingMs(5_900_000_000L))
                try { ExitQuoteBudget7863.remainingMs(6_000_000_000L); fail("outer deadline must expire") }
                catch (_: SocketTimeoutException) {}
            }
            assertEquals(500L, ExitQuoteBudget7863.remainingMs(5_500_000_000L))
        } finally { outer.close() }
        assertNull("simulation and signed finality inherit no quote timeout", ExitQuoteBudget7863.remainingMs(0L))
    }

    @Test fun quoteScopesCleanUpAfterEarlyReturnAndExceptionWithoutExtendingAnOuterScope() {
        fun earlyReturn(): Boolean {
            val scope = ExitQuoteBudget7863.begin(0L)
            try { return false } finally { scope.close() }
        }
        assertFalse(earlyReturn())
        assertNull(ExitQuoteBudget7863.remainingMs(0L))
        val outer = ExitQuoteBudget7863.begin(0L, 6_000L)
        try {
            val inner = ExitQuoteBudget7863.begin(1_000_000_000L, 1_000L)
            try {
                assertEquals(500L, ExitQuoteBudget7863.remainingMs(1_500_000_000L))
                throw IllegalStateException("quote provider failure")
            } catch (_: IllegalStateException) {
            } finally { inner.close(); inner.close() }
            assertEquals(2_000L, ExitQuoteBudget7863.remainingMs(2_000_000_000L))
        } finally { outer.close() }
        assertNull(ExitQuoteBudget7863.remainingMs(0L))
    }

    @Test fun quoteDeadlineIsLocalToTheActualQuoteThread() {
        val scope = ExitQuoteBudget7863.begin(0L)
        val other = java.util.concurrent.atomic.AtomicReference<Long?>(99L)
        try {
            val worker = Thread { other.set(ExitQuoteBudget7863.remainingMs(0L)) }
            worker.start()
            worker.join(1_000L)
            assertFalse(worker.isAlive)
            assertNull(other.get())
            assertEquals(2_000L, ExitQuoteBudget7863.remainingMs(0L))
        } finally { scope.close() }
    }

    @Test fun productionWiringUsesMonotonicCacheAndEndsBudgetBeforeExecution() {
        fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()
        val held = src("engine/truth/HeldExitQuote8189.kt")
        assertTrue(held.substringAfter("private fun quoteInto8189(").substringBefore("fun quoteNow8189(")
            .contains("if (!publish8527(mint, q)) return null"))
        assertTrue(held.substringAfter("fun rememberStopQuote8521(").substringBefore("fun take8189(")
            .contains("publish8527(mint, q)"))
        val ex = src("engine/Executor.kt")
        val ladder = ex.substringAfter("val quoteLadderDeadline7863 =").substringBefore("val jupiterQuoteUnavailable =")
        assertTrue(ladder.contains("ExitQuoteBudget7863.begin(budgetMs = 6_000L)"))
        assertTrue(ladder.contains("finally { quoteBudget8527.close() }"))
        assertTrue(ladder.indexOf("ExitQuoteBudget7863.begin(") < ladder.indexOf("jupiterLadder7228@ for"))
        assertTrue(ladder.indexOf("getQuoteWithSlippageGuard(") < ladder.indexOf("quoteBudget8527.close()"))
        assertFalse(ladder.contains("sendTransaction("))
        assertFalse(ladder.contains("simulateTransaction("))
        val truth = src("engine/ProfitExitTruth8163.kt")
        assertFalse(truth.substringAfter("fun reconcileMark8237(").contains("firstOpenForMint("))
    }
}
