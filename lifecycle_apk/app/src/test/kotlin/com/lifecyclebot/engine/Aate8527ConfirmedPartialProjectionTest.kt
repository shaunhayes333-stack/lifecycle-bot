package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPartialReceipt8527 as Receipt
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI as Shit
import com.lifecyclebot.v3.scoring.MoonshotTraderAI as Moon
import com.lifecyclebot.v3.scoring.QualityTraderAI as Quality
import com.lifecyclebot.v3.scoring.BlueChipTraderAI as Blue
import com.lifecyclebot.v3.scoring.ShitCoinExpress as Express
import com.lifecyclebot.v3.scoring.DipHunterAI as Dip
import com.lifecyclebot.v3.scoring.CashGenerationAI as CashGen
import com.lifecyclebot.v3.scoring.ProjectSniperAI as Sniper
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.io.File
import java.math.BigInteger

class Aate8527ConfirmedPartialProjectionTest {
    @Before fun reset() {
        Cpa.resetForTest()
        com.lifecyclebot.engine.truth.NativePartialProjection8527.resetForTest8527()
        Shit.clearAllPositions(); Shit.resetDaily(); Moon.clearAllPositions(); Moon.resetDaily(); Quality.clearAllPositions()
        Blue.clearAllPositions(); Express.clearAllRides(); Dip.clearAllDips(); Dip.resetDaily(); Dip.importState("{}")
        CashGen.clearAllPositions(); Sniper.clearAllMissions()
    }
    @After fun cleanup() { reset() }

    private fun lot(tag: String, mint: String = "partial8527-$tag-${System.nanoTime()}", lane: String = "SHITCOIN"): TokenState {
        val pid = "LIVE:$mint:$tag"
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$pid", pid, mint, tag, lane,
            "8527", 1.0, BigInteger.valueOf(100L), 0, 0.0, false,
            entryPriceUsd = 1.0, entryPriceSource = "LIVE_FINALIZED"))
        val c = Cpa.getPosition(pid)!!
        return TokenState(mint = mint, symbol = tag, position = Position(
            positionId = pid, qtyToken = 100.0, costSol = 1.0, entryPrice = 1.0,
            entryTime = c.openedAtMs, isPaperPosition = false, tradingMode = lane))
    }

    private fun native(ts: TokenState, paper: Boolean = false) {
        Shit.restorePosition(Shit.ShitCoinPosition(
            mint = ts.mint, symbol = ts.symbol, entryPrice = 1.0, entrySol = 1.0,
            entryTime = ts.position.entryTime, marketCapUsd = 50_000.0, liquidityUsd = 50_000.0,
            isPaper = paper, takeProfitPct = 100.0, stopLossPct = -15.0,
            launchPlatform = Shit.LaunchPlatform.PUMP_FUN), paper)
    }

    private fun fill(ts: TokenState, soldRaw: Long, proceeds: Double, key: String = "slice"): Receipt {
        val before = Cpa.getPosition(ts.position.positionId)!!
        val sold = BigInteger.valueOf(soldRaw)
        val cost = (before.entryCostSol - before.soldCostBasisSol) *
            sold.toDouble() / before.remainingQtyRaw.toDouble()
        val capture = Receipt.Capture8527()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("$key:${before.positionId}:${before.remainingQtyRaw}",
            before.positionId, sold, proceeds, cost, 0.0, false, receiptCapture8527 = capture))
        return requireNotNull(capture.receipt)
    }

    @Test fun actualRoundedSliceDrivesCostAndOnlyOneRungNotRequestedTwentyFivePercent() {
        val ts = lot("rounded"); native(ts)
        val apply = Shit.partialProjection8527(ts)
        assertFalse(apply(null))
        val actual = fill(ts, 33L, 0.40)
        assertEquals(0.33, actual.soldFraction, 1e-12)
        assertEquals(BigInteger.valueOf(67L), actual.remainingRaw)
        assertEquals(0.07, actual.realizedPnlSol, 1e-12)
        assertTrue(apply(actual))
        val p = Shit.getActivePositionsForMode(false).single()
        assertEquals(0.67, p.entrySol, 1e-12)
        assertEquals(1, p.partialRungsTaken)
        assertTrue(p.firstTakeDone)
        assertFalse(apply(actual))
        assertEquals(1, Shit.getActivePositionsForMode(false).single().partialRungsTaken)
    }

    @Test fun aPartialThatConsumesAllRawIsTerminalEvenWhenRuntimeModeWasCleared() {
        val ts = lot("full"); native(ts)
        val apply = Shit.partialProjection8527(ts)
        val actual = fill(ts, 100L, 1.10)
        assertTrue(actual.terminal)
        ts.position = Position() // Default paper mode must not redirect the completed LIVE callback.
        assertFalse(apply(actual)) // A receipt alone cannot skip the native terminal accounting owner.
        assertTrue(Shit.hasPosition(ts.mint))
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(actual, 1.10))
        assertTrue(apply(actual))
        assertFalse(Shit.hasPosition(ts.mint))
        assertFalse(apply(actual))
        assertEquals(Cpa.Lifecycle.CLOSED, Cpa.getPosition(actual.positionId)!!.lifecycle)
    }

    @Test fun delayedCallbackCannotAlterNewNativeGenerationEvenWithSameTimestamp() {
        val ts = lot("generation"); native(ts)
        val apply = Shit.partialProjection8527(ts)
        val actual = fill(ts, 25L, 0.30)
        val old = Shit.getActivePositionsForMode(false).single()
        Shit.restorePosition(old.copy(entrySol = 0.88), false) // different generation/reference, same entry time
        assertFalse(apply(actual))
        assertEquals(0.88, Shit.getActivePositionsForMode(false).single().entrySol, 0.0)
    }

    @Test fun oldCanonicalLotCannotProjectOntoAReentryOfTheSameMint() {
        val ts = lot("old"); native(ts)
        val apply = Shit.partialProjection8527(ts)
        val closed = fill(ts, 100L, 1.1)
        val newer = lot("new", ts.mint)
        native(newer)
        assertFalse(apply(closed))
        assertEquals(1.0, Shit.getActivePositionsForMode(false).single().entrySol, 0.0)
        assertEquals(newer.position.positionId, Cpa.openPositions().single().positionId)
    }

    @Test fun receiptMustMatchActualRawAndOlderCallbacksOnlyProjectTheLatestCanonicalRemainder() {
        val ts = lot("stale"); native(ts)
        val before = Cpa.getPosition(ts.position.positionId)!!
        val apply = Shit.partialProjection8527(ts)
        val first = fill(ts, 25L, 0.30)
        val after = Cpa.getPosition(ts.position.positionId)!!
        assertNull(Receipt.fromApplied8527(false, before, after, "not-applied"))
        assertNull(Receipt.fromApplied8527(true, before, after, "wrong-consumption", BigInteger.TEN))
        assertNull(Receipt.fromApplied8527(true, before, after.copy(positionId = "other-lot"), "wrong-lot"))
        assertNull(Receipt.fromApplied8527(true, before, after.copy(mode = "paper"), "wrong-mode"))
        assertNull(Receipt.fromApplied8527(true, before.copy(originalQtyRaw = BigInteger.ZERO),
            after.copy(originalQtyRaw = BigInteger.ZERO), "missing-original-raw"))
        val second = fill(ts, 25L, 0.30, "next")
        assertTrue(apply(first))
        assertEquals(0.50, Shit.getActivePositionsForMode(false).single().entrySol, 1e-12)
        assertTrue(apply(second))
        assertEquals(0.50, Shit.getActivePositionsForMode(false).single().entrySol, 1e-12)
        assertEquals(2, Shit.getActivePositionsForMode(false).single().partialRungsTaken)
        assertFalse(apply(first))
    }

    @Test fun simultaneousCapturesBothProjectTheirSequentialCanonicalFills() {
        val ts = lot("two-captures"); native(ts)
        val firstCallback = Shit.partialProjection8527(ts)
        val secondCallback = Shit.partialProjection8527(ts)
        assertTrue(firstCallback(fill(ts, 25L, 0.30, "first")))
        assertTrue(secondCallback(fill(ts, 25L, 0.30, "second")))
        val p = Shit.getActivePositionsForMode(false).single()
        assertEquals(0.50, p.entrySol, 1e-12)
        assertEquals(2, p.partialRungsTaken)
    }

    @Test fun authoritativeMutationFreezesBothActualReceiptsBeforeEitherCallerReadsThem() {
        val ts = lot("atomic-producer"); native(ts)
        val a = Shit.partialProjection8527(ts)
        val b = Shit.partialProjection8527(ts)
        val c0 = Cpa.getPosition(ts.position.positionId)!!
        val captureA = Receipt.Capture8527()
        val captureB = Receipt.Capture8527()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("producer-a", c0.positionId,
            BigInteger.valueOf(25L), 0.30, 0.25, 0.0, false, receiptCapture8527 = captureA))
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("producer-b", c0.positionId,
            BigInteger.valueOf(25L), 0.35, 0.25, 0.0, false, receiptCapture8527 = captureB))
        // Both returns are consumed only after C2 exists: neither is reconstructed from C0 -> C2.
        val first = requireNotNull(captureA.receipt)
        val second = requireNotNull(captureB.receipt)
        assertEquals(BigInteger.valueOf(100L), first.preRaw)
        assertEquals(BigInteger.valueOf(75L), first.remainingRaw)
        assertEquals(BigInteger.valueOf(75L), second.preRaw)
        assertEquals(BigInteger.valueOf(50L), second.remainingRaw)
        assertTrue(a(first))
        assertTrue(b(second))
        assertEquals(0.50, Shit.getActivePositionsForMode(false).single().entrySol, 1e-12)
        assertEquals(2, Shit.getActivePositionsForMode(false).single().partialRungsTaken)
        val duplicate = Receipt.Capture8527()
        assertEquals(Cpa.MutateResult.DUPLICATE, Cpa.partialSell("producer-b", c0.positionId,
            BigInteger.valueOf(25L), 0.35, 0.25, 0.0, false, receiptCapture8527 = duplicate))
        assertNull(duplicate.receipt)
    }

    @Test fun outOfOrderCallbacksBankCurrentEconomicsOnceWithoutRollingBackTheBook() {
        val ts = lot("out-of-order")
        Dip.restoreDip(Dip.DipPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            10.0, 50.0, 50_000.0, 1_000.0, false))
        val a = Dip.partialProjection8527(ts)
        val b = Dip.partialProjection8527(ts)
        val first = fill(ts, 25L, 0.30, "a")
        val second = fill(ts, 25L, 0.35, "b")
        assertTrue(b(second))
        assertEquals(0.50, Dip.getActiveDips().single().remainingFrac, 1e-12)
        assertEquals(0.15, Dip.getDailyPnlSol(), 1e-12)
        assertTrue(a(first))
        assertEquals(0.50, Dip.getActiveDips().single().remainingFrac, 1e-12)
        assertEquals(0.15, Dip.getDailyPnlSol(), 1e-12)
        assertFalse(b(second))
    }

    @Test fun canonicalMutationCannotRaceTheNativeProjectionWrite() {
        val ts = lot("atomic")
        data class Native(val remaining: Double)
        val book = java.util.concurrent.ConcurrentHashMap<String, Native>()
        book[ts.mint] = Native(1.0)
        val insideWrite = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val mutationStarted = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val apply = com.lifecyclebot.engine.truth.NativePartialProjection8527.capture8527(
                book, ts, { false }, { _, current ->
                    insideWrite.countDown()
                    assertTrue(releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    Native(current.remainingCostSol)
                })
            val first = fill(ts, 25L, 0.30, "first")
            val projection = pool.submit<Boolean> { apply(first) }
            assertTrue(insideWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val mutation = pool.submit<Receipt> {
                mutationStarted.countDown()
                fill(ts, 25L, 0.30, "second")
            }
            assertTrue(mutationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(mutation.isDone)
            assertEquals(BigInteger.valueOf(75L), Cpa.getPosition(ts.position.positionId)!!.remainingQtyRaw)
            releaseWrite.countDown()
            assertTrue(projection.get(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = mutation.get(5, java.util.concurrent.TimeUnit.SECONDS)
            // A new capture shares the same exact canonical lineage despite the prior native copy.
            val next = com.lifecyclebot.engine.truth.NativePartialProjection8527.capture8527(
                book, ts, { false }, { _, current -> Native(current.remainingCostSol) })
            assertTrue(next(second))
            assertEquals(0.50, book[ts.mint]!!.remaining, 1e-12)
        } finally {
            releaseWrite.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun asyncTerminalCompletionWaitsForSettlementThenDeliversOnceOutsideTheMutationLock() {
        val ts = lot("async-full", lane = "MOONSHOT")
        Moon.restorePosition(Moon.MoonshotPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            200.0, -20.0, 50_000.0, 50_000.0, 80.0, Moon.SpaceMode.LUNAR, false))
        val nativeProjection = Moon.partialProjection8527(ts)
        val callbacks = java.util.concurrent.atomic.AtomicInteger(0)
        val releasedToSettle = java.util.concurrent.CountDownLatch(1)
        val mutationApplied = java.util.concurrent.CountDownLatch(1)
        val accountingFinished = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val independent = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val delivery = Receipt.Delivery8527 { receipt ->
                // Publication must not hold CPA's lock while invoking a downstream callback.
                val independentWrite = independent.submit<Boolean> {
                    val another = "async-callback8527-${System.nanoTime()}"
                    Cpa.openPosition("open:$another", another, another, "other", "CORE", "8527",
                        1.0, BigInteger.ONE, 0, 0.0, false, entryPriceUsd = 1.0,
                        entryPriceSource = "LIVE_FINALIZED") == Cpa.MutateResult.APPLIED
                }
                assertTrue(independentWrite.get(5, java.util.concurrent.TimeUnit.SECONDS))
                callbacks.incrementAndGet()
                nativeProjection(receipt)
            }
            val capture = Receipt.Capture8527(delivery)
            val settledLater = executor.submit<Boolean> {
                assertTrue(releasedToSettle.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("async-terminal",
                    ts.position.positionId, BigInteger.valueOf(100L), 1.10, 1.0, 0.0, false,
                    receiptCapture8527 = capture))
                mutationApplied.countDown()
                assertTrue(accountingFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // The real terminal owner books counters/PnL/releases before completion can retire its metadata.
                assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(capture.receipt, 1.10))
                assertEquals(1, Moon.getDailyWins())
                assertEquals(0.10, Moon.getDailyPnlSol(), 0.0001)
                capture.publishSettled8527()
            }
            // The supervisor caller has stopped waiting, but no settlement or rung is fabricated.
            assertFalse(settledLater.isDone)
            assertNull(capture.receipt)
            assertEquals(0, callbacks.get())
            assertEquals(0, Moon.getActivePositionsForMode(false).single().partialRungsTaken)
            releasedToSettle.countDown()
            assertTrue(mutationApplied.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(capture.receipt)
            assertFalse(nativeProjection(capture.receipt)) // Cannot bypass native counters by publishing too early.
            assertEquals(0, Moon.getDailyWins())
            assertEquals(0, callbacks.get())
            assertTrue(Moon.hasPosition(ts.mint))
            accountingFinished.countDown()
            assertTrue(settledLater.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(Moon.hasPosition(ts.mint))
            assertEquals(1, callbacks.get())
            assertFalse(capture.publishSettled8527())
            assertFalse(delivery.deliver8527(capture.receipt))
            assertEquals(1, callbacks.get())
            assertEquals(1, Moon.getDailyWins())
            assertEquals(0.10, Moon.getDailyPnlSol(), 0.0001)
        } finally {
            releasedToSettle.countDown(); accountingFinished.countDown()
            executor.shutdownNow(); independent.shutdownNow()
        }
    }

    @Test fun livePartialLeavesTheSameMintPaperProjectionUntouched() {
        val ts = lot("mode"); native(ts); native(ts, paper = true)
        val apply = Shit.partialProjection8527(ts)
        assertTrue(apply(fill(ts, 33L, 0.40)))
        assertEquals(0.67, Shit.getActivePositionsForMode(false).single().entrySol, 1e-12)
        assertEquals(1.0, Shit.getActivePositionsForMode(true).single().entrySol, 0.0)
        assertEquals(0, Shit.getActivePositionsForMode(true).single().partialRungsTaken)
    }

    @Test fun moonshotUsesTheRequestedModesRungAndUpdatesItsRestoreAliasExactly() {
        val ts = lot("moon", lane = "MOONSHOT")
        val p = Moon.MoonshotPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            200.0, -20.0, 50_000.0, 50_000.0, 80.0, Moon.SpaceMode.LUNAR, false)
        Moon.restorePosition(p)
        Moon.restorePosition(p.copy(isPaperMode = true, partialRungsTaken = 3))
        assertEquals(0.10, Moon.getPartialSellPct(ts.mint, false), 0.0)
        assertEquals(0.12, Moon.getPartialSellPct(ts.mint, true), 0.0)
        val apply = Moon.partialProjection8527(ts)
        assertTrue(apply(fill(ts, 33L, 0.40)))
        val live = Moon.getActivePositionsForMode(false).single()
        assertEquals(0.67, live.entrySol, 1e-12)
        assertEquals(0.33, live.partialSellPct, 1e-12)
        assertEquals(1.0, Moon.getActivePositionsForMode(true).single().entrySol, 0.0)
        assertFalse(apply(null))
    }

    @Test fun moonshotFractionCatchesUpFromAnEarlierGenericCanonicalPartial() {
        val ts = lot("moon-generic", lane = "MOONSHOT")
        Moon.restorePosition(Moon.MoonshotPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            200.0, -20.0, 50_000.0, 50_000.0, 80.0, Moon.SpaceMode.LUNAR, false))
        fill(ts, 25L, 0.30, "generic") // earlier generic partial did not update the native fraction
        val apply = Moon.partialProjection8527(ts)
        assertTrue(apply(fill(ts, 25L, 0.30, "native")))
        val p = Moon.getActivePositionsForMode(false).single()
        assertEquals(0.50, p.entrySol, 1e-12)
        assertEquals(0.50, p.partialSellPct, 1e-12)
    }

    @Test fun qualityBluechipAndExpressProposalsStayRetryableUntilConfirmed() {
        val q = lot("quality", lane = "QUALITY")
        Quality.restorePosition(Quality.QualityPosition(q.mint, q.symbol, 1.0, q.position.entryTime,
            1.0, 50_000.0, 100.0, -15.0), false)
        val b = lot("blue", lane = "BLUECHIP")
        Blue.restorePosition(Blue.BlueChipPosition(b.mint, b.symbol, 1.0, 1.0, b.position.entryTime,
            50_000.0, 50_000.0, false, 100.0, -15.0), false)
        val e = lot("express", lane = "EXPRESS")
        Express.restoreRide(Express.ExpressRide(e.mint, e.symbol, 1.0, 1.0, e.position.entryTime,
            5.0, 70.0, false))
        repeat(3) {
            assertEquals(Quality.ExitSignal.PARTIAL_TAKE, Quality.checkExit(q.mint, 1.31, isPaper = false))
            assertEquals(Blue.ExitSignal.PARTIAL_TAKE, Blue.checkExit(b.mint, 1.31, isPaper = false))
            assertEquals(Express.ExitSignal.TAKE_PROFIT_30, Express.checkExit(e.mint, 1.31, 5.0))
        }
        val qa = Quality.partialProjection8527(q)
        val ba = Blue.partialProjection8527(b)
        val ea = Express.partialProjection8527(e)
        assertTrue(qa(fill(q, 20L, 0.25)))
        assertTrue(ba(fill(b, 20L, 0.25)))
        assertTrue(ea(fill(e, 20L, 0.25)))
        assertEquals(Quality.ExitSignal.HOLD, Quality.checkExit(q.mint, 1.31, isPaper = false))
        assertEquals(Blue.ExitSignal.HOLD, Blue.checkExit(b.mint, 1.31, isPaper = false))
        assertEquals(Express.ExitSignal.HOLD, Express.checkExit(e.mint, 1.31, 5.0))
    }

    @Test fun invalidMarkDoesNotInventZeroPnlOrOverwriteARealPeak() {
        val b = lot("invalid-blue", lane = "BLUECHIP")
        val blue = Blue.BlueChipPosition(b.mint, b.symbol, 1.0, 1.0, b.position.entryTime,
            50_000.0, 50_000.0, false, 100.0, -15.0, highWaterMark = 6.0, peakPnlPct = 500.0)
        Blue.restorePosition(blue, false)
        val e = lot("invalid-express", lane = "EXPRESS")
        val ride = Express.ExpressRide(e.mint, e.symbol, 1.0, 1.0, e.position.entryTime,
            5.0, 70.0, false, highWaterMark = 6.0, peakPnlPct = 500.0)
        Express.restoreRide(ride)
        val d = lot("invalid-dip", lane = "DIP_HUNTER")
        Dip.restoreDip(Dip.DipPosition(d.mint, d.symbol, 1.0, 1.0, d.position.entryTime,
            10.0, 50.0, 50_000.0, 1_000.0, false, recoveryHighPct = 500.0))
        for (bad in listOf(Double.NaN, 0.0, Double.POSITIVE_INFINITY)) {
            assertEquals(Blue.ExitSignal.HOLD, Blue.checkExit(b.mint, bad, false))
            assertEquals(Express.ExitSignal.HOLD, Express.checkExit(e.mint, bad, 5.0))
            assertEquals(Dip.DipExitSignal.HOLD, Dip.checkExit(d.mint, bad, 1_000.0))
        }
        assertEquals(500.0, blue.peakPnlPct, 0.0)
        assertEquals(1.0, blue.lastSeenPrice, 0.0)
        assertEquals(500.0, ride.peakPnlPct, 0.0)
        assertEquals(Dip.DipExitSignal.DEATH_SPIRAL, Dip.checkExit(d.mint, Double.NaN, 400.0))
        assertEquals(Express.ExitSignal.STOP_LOSS, Express.checkExit(e.mint, 0.80, 5.0))
    }

    @Test fun dipBanksActualCanonicalNetOnceAndKeepsItsActualRemainder() {
        val ts = lot("dip", lane = "DIP_HUNTER")
        Dip.restoreDip(Dip.DipPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            10.0, 50.0, 50_000.0, 1_000.0, false))
        val triggerPrice = 1.0 + (Dip.getFluidRecoveryTarget() + 1.0) / 100.0
        repeat(2) {
            assertEquals(Dip.DipExitSignal.PARTIAL_TAKE, Dip.checkExit(ts.mint, triggerPrice, 1_000.0))
            assertFalse(Dip.getActiveDips().single().recoveryBanked)
        }
        val apply = Dip.partialProjection8527(ts)
        val actual = fill(ts, 33L, 0.40)
        assertTrue(apply(actual))
        assertTrue(Dip.getActiveDips().single().recoveryBanked)
        assertEquals(0.67, Dip.getActiveDips().single().remainingFrac, 1e-12)
        assertEquals(0.07, Dip.getDailyPnlSol(), 1e-12)
        assertFalse(apply(actual))
        assertEquals(0.07, Dip.getDailyPnlSol(), 1e-12)
    }

    @Test fun rejectedOrQueuedFullCloseCannotEraseDipOwnershipOrBankAnUnsettledMark() {
        val ts = lot("dip-full-proof", lane = "DIP_HUNTER")
        Dip.restoreDip(Dip.DipPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            10.0, 50.0, 50_000.0, 1_000.0, false))
        repeat(2) { Dip.closeDip(ts.mint, 1.10, Dip.DipExitSignal.RECOVERY_TARGET) }
        assertTrue(Dip.hasDip(ts.mint))
        assertEquals(0.0, Dip.getDailyPnlSol(), 0.0)
        fill(ts, 100L, 1.10, "actually-closed")
        Dip.closeDip(ts.mint, 1.10, Dip.DipExitSignal.RECOVERY_TARGET)
        assertFalse(Dip.hasDip(ts.mint))
        assertEquals(0.10, Dip.getDailyPnlSol(), 1e-12)
        Dip.closeDip(ts.mint, 1.10, Dip.DipExitSignal.RECOVERY_TARGET)
        assertEquals(0.10, Dip.getDailyPnlSol(), 1e-12)
    }

    @Test fun dipFinalSliceUsesExactNetWithoutLosingEarlierPartialsOrMisclassifyingTheWholeLot() {
        val ts = lot("dip-final-accounting", lane = "DIP_HUNTER")
        Dip.restoreDip(Dip.DipPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            10.0, 50.0, 50_000.0, 1_000.0, false))
        val firstProjection = Dip.partialProjection8527(ts)
        assertTrue(firstProjection(fill(ts, 25L, 0.35, "profitable-first")))
        assertEquals(0.10, Dip.getDailyPnlSol(), 1e-12)
        val finalProjection = Dip.partialProjection8527(ts)
        val capture = Receipt.Capture8527()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("losing-final", ts.position.positionId,
            BigInteger.valueOf(75L), 0.702, 0.75, 0.002, false, receiptCapture8527 = capture))
        val finalSlice = requireNotNull(capture.receipt)
        assertEquals(-0.05, finalSlice.realizedPnlSol, 1e-12)
        assertFalse(finalProjection(finalSlice))
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(finalSlice, 2.0))
        // The deliberately different mark must not manufacture +0.75 SOL; the canonical whole lot won +0.05.
        assertEquals(0.05, Dip.getDailyPnlSol(), 1e-12)
        assertEquals(1, Dip.getStats().dailyWins)
        assertEquals(0, Dip.getStats().dailyLosses)
        assertTrue(finalProjection(finalSlice))
        assertEquals(0.05, Dip.getDailyPnlSol(), 1e-12)
        assertFalse(finalProjection(finalSlice))
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(finalSlice, 2.0))
        assertEquals(0.05, Dip.getDailyPnlSol(), 1e-12)
        assertEquals(1, Dip.getStats().dailyWins)
    }

    @Test fun subCentiSolSlicesAndPersistenceKeepActualLamports() {
        val ts = lot("small-dip", lane = "DIP_HUNTER")
        Dip.importState("""{"dailyPnlSolBps":-2,"dipBalanceBps":100}""")
        assertEquals(-0.02, Dip.getDailyPnlSol(), 1e-12)
        Dip.restoreDip(Dip.DipPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            10.0, 50.0, 50_000.0, 1_000.0, false))
        val apply = Dip.partialProjection8527(ts)
        assertTrue(apply(fill(ts, 25L, 0.251, "small-a")))
        assertEquals(-0.019, Dip.getDailyPnlSol(), 1e-12)
        val again = Dip.partialProjection8527(ts)
        assertTrue(again(fill(ts, 25L, 0.257, "small-b")))
        assertEquals(-0.012, Dip.getDailyPnlSol(), 1e-12)
        val saved = Dip.exportState()
        Dip.resetDaily()
        assertEquals(0.0, Dip.getDailyPnlSol(), 0.0)
        Dip.importState(saved)
        assertEquals(-0.012, Dip.getDailyPnlSol(), 1e-12)
        val state = org.json.JSONObject(saved)
        assertEquals(-12_000_000L, state.getLong("dailyPnlLamports8527"))
        assertEquals(1_003_200_000L, state.getLong("dipBalanceLamports8527"))
    }


    @Test fun terminalPartialClosesCashGenerationOnlyForTheConfirmedAccount() {
        val savedCounters = CashGen::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            when (val counter = field.get(CashGen)) {
                is java.util.concurrent.atomic.AtomicLong -> Triple(counter, counter.get(), true)
                is java.util.concurrent.atomic.AtomicInteger -> Triple(counter, counter.get().toLong(), false)
                else -> null
            }
        }
        try {
            val ts = lot("cashgen-terminal", lane = "TREASURY")
            val live = CashGen.TreasuryPosition(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
                1.10, 0.90, 1.0, 0.0, false)
            CashGen.restorePosition(live, false)
            CashGen.restorePosition(live.copy(isPaper = true), true)
            val receipt = fill(ts, 100L, 1.10)
            assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
            assertNull(CashGen.getActivePosition(ts.mint, false))
            assertNotNull(CashGen.getActivePosition(ts.mint, true))
            assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
            assertNotNull(CashGen.getActivePosition(ts.mint, true))
        } finally {
            savedCounters.forEach { (counter, value, long) ->
                if (long) (counter as java.util.concurrent.atomic.AtomicLong).set(value)
                else (counter as java.util.concurrent.atomic.AtomicInteger).set(value.toInt())
            }
        }
    }

    private fun sniper(ts: TokenState): Sniper.SniperMission =
        Sniper.SniperMission(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            30, 50_000.0, 50_000.0, 75.0, 1.0)

    private fun sniperExit() = Sniper.ExitSignal(true, 100, "TAKE_PROFIT", Sniper.SniperRank.PENDING)

    @Test fun terminalPartialFinishesTheExistingSniperOwnerExactlyOnce() {
        // The production owner updates singleton counters; preserve the preceding suite's ledger.
        val kills = Sniper::class.java.getDeclaredField("dailyKills").apply { isAccessible = true }
            .get(Sniper) as java.util.concurrent.atomic.AtomicInteger
        val pnl = Sniper::class.java.getDeclaredField("dailyPnlLamports8527").apply { isAccessible = true }
            .get(Sniper) as java.util.concurrent.atomic.AtomicLong
        val beforeKills = kills.get()
        val beforePnl = pnl.get()
        try {
            val ts = lot("sniper-terminal", lane = "PROJECT_SNIPER")
            Sniper.restoreMission(sniper(ts))
            val receipt = fill(ts, 100L, 1.10)
            assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
            assertFalse(Sniper.hasMission(ts.mint))
            assertEquals(beforeKills + 1, Sniper.getDailyStats().kills)
            assertEquals(beforePnl + 100_000_000L, pnl.get())
            assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
            assertEquals(beforeKills + 1, Sniper.getDailyStats().kills)
            assertEquals(beforePnl + 100_000_000L, pnl.get())
        } finally {
            kills.set(beforeKills); pnl.set(beforePnl)
        }
    }

    @Test fun anotherLanesTerminalReceiptCannotCompleteASniperMission() {
        val ts = lot("sniper-wrong-owner", lane = "SHITCOIN")
        val mission = sniper(ts)
        Sniper.restoreMission(mission)
        val receipt = fill(ts, 100L, 1.10)
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
        assertSame(mission, Sniper.getMission(ts.mint))
        Sniper.completeMission(ts.mint, 1.10, sniperExit(), canonicalSlice8527 = receipt)
        assertSame(mission, Sniper.getMission(ts.mint))
    }

    @Test fun sniperReceiptCannotRemoveAReplacementGenerationOrOppositeModeLiability() {
        val ts = lot("sniper-generation", lane = "PROJECT_SNIPER")
        val expected = sniper(ts)
        Sniper.restoreMission(expected)
        val receipt = fill(ts, 100L, 1.10)
        val replacement = expected.copy() // Deliberately equal value; generation identity must still differ.
        Sniper.restoreMission(replacement)
        Sniper.completeMission(ts.mint, 1.10, sniperExit(),
            canonicalSlice8527 = receipt, expectedMission8527 = expected)
        assertSame(replacement, Sniper.getMission(ts.mint))

        // Prove the opposite-mode liability with an actually funded paper account.
        // CPA correctly rejects an unfunded PAPER open; it must not relax that invariant.
        Cpa.setPaperCash(1.0, "sniper_opposite_mode_liability_fixture_8527")
        val paperId = "PAPER:other:${ts.mint}"
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$paperId", paperId, ts.mint,
            ts.symbol, "PROJECT_SNIPER", "8527", 1.0, BigInteger.valueOf(100L), 0, 0.0, true,
            entryPriceUsd = 1.0, entryPriceSource = "PAPER"))
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
        assertSame(replacement, Sniper.getMission(ts.mint))
        assertEquals(BigInteger.valueOf(100L), Cpa.getPosition(paperId)!!.remainingQtyRaw)
        assertTrue(Cpa.getPosition(paperId)!!.mode == "paper")
    }

    @Test fun expressTerminalClaimBindsCapturedGenerationAndReceiptModeAtomically() {
        val ts = lot("express-terminal-generation", lane = "EXPRESS")
        val expected = Express.ExpressRide(ts.mint, ts.symbol, 1.0, 1.0, ts.position.entryTime,
            5.0, 75.0, false)
        Express.restoreRide(expected)
        val receipt = fill(ts, 100L, 1.10)
        val replacement = expected.copy() // Equality is not sufficient for an ownership claim.
        Express.restoreRide(replacement)
        Express.exitRide(ts.mint, 1.10, Express.ExitSignal.TAKE_PROFIT_30,
            canonicalSlice8527 = receipt, expectedRide8527 = expected)
        assertSame(replacement, Express.getActiveRides().single())

        val paper = replacement.copy(isPaper = true)
        Express.restoreRide(paper)
        Express.exitRide(ts.mint, 1.10, Express.ExitSignal.TAKE_PROFIT_30,
            canonicalSlice8527 = receipt, expectedRide8527 = paper)
        assertSame(paper, Express.getActiveRides().single())
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 1.10))
        assertSame(paper, Express.getActiveRides().single())
    }

    @Test fun terminalPartialPreservesAllNineExistingNativeOwnersAndOptimizationMetadata() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/NativeTerminalClose8527.kt").readText()
        for (owner in listOf("CashGen.closePosition", "Shit.closePosition", "Moon.closePosition",
            "Quality.closePosition", "Blue.closePosition", "Manip.closePosition", "Express.exitRide",
            "Dip.closeDip", "Sniper.completeMission", "SellOptimizationAI.closePosition")) {
            assertTrue(owner, source.contains(owner))
        }
        assertTrue(source.contains("canonicalSlice8527 = receipt, expectedRide8527 = expectedRide8527"))
        assertTrue(source.contains("canonicalSlice8527 = receipt, expectedMission8527 = expectedMission8527"))
        val express = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue(express.contains("activeRides[mint] !== candidate8527"))
        val sniper = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue(sniper.contains("current8527 === candidate8527"))
        assertTrue(sniper.contains("matching8527.mode != canonicalSlice8527.mode"))
    }

    @Test fun executorAndEveryNativeCallerUseTheSameCanonicalReceipt() {
        val root = "src/main/kotlin/com/lifecyclebot/"
        val executor = File(root + "engine/Executor.kt").readText()
        assertTrue(executor.contains("settled7835.remainingRaw.signum() == 0"))
        assertTrue(executor.contains("confirmedConsumedRaw8527 = settled7835.actualConsumedRaw"))
        val wrapper = executor.substringAfter("fun requestPartialSellConfirmed6566(")
            .substringBefore("/** V5.0.8337")
        assertFalse(wrapper.contains("getPosition("))
        assertFalse(wrapper.contains("fromApplied8527("))
        assertTrue(executor.contains("canonicalSlice8527 = settled7835.canonicalSlice8527"))
        assertTrue(executor.contains("canonicalSlice8527 = partial6510.canonicalSlice8527"))
        assertTrue(executor.contains("requestSellCore7948(ts, reason, wallet, walletSol, receiptCapture8527)"))
        assertTrue(executor.contains("liveSellInScope7314(ts, reason, wallet, walletSol, identity, receiptCapture8527)"))
        assertTrue(executor.contains("return result8527.copy(canonicalSlice8527 = capture8527.receipt)"))
        assertTrue(executor.contains("entryPrice = trade.entryPriceSnapshot.takeIf"))
        assertTrue(executor.contains("it.positionId == outcomePositionId8527"))
        val cpa = File(root + "engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(cpa.contains("receiptCapture8527?.recordApplied8527(pos, appliedPosition8527, idempotencyKey)"))
        for (path in listOf("engine/sell/SellFinalizationCoordinator.kt",
            "engine/truth/CanonicalPaperTerminalBridge6469.kt", "engine/truth/ExecutorCanonicalMirror6442.kt")) {
            assertTrue(path, File(root + path).readText().contains("receiptCapture8527 = capture8527"))
        }
        val service = File(root + "engine/BotService.kt").readText()
        assertEquals(10, service.split("val applyPartial8527 = ").size - 1)
        assertFalse(service.contains(".onPartialSell("))
        assertFalse(service.contains("val profitSol = (ts.position.costSol * chunkPct)"))
        assertTrue(executor.contains("TradeSliceEconomics8527.read(tradeWithMint)"))
        assertTrue(executor.contains("featuresAtEntry = features + frozenFeatures8527"))
        assertFalse(executor.contains("AdaptiveLearningEngine.learnPaperTrade7835(features)"))
        assertFalse(executor.contains("AdaptiveLearningEngine.learnFromTrade(features, environment ="))
        assertEquals(10, service.split("onConfirmedSlice8527 = applyPartial8527,").size - 1)
        assertFalse(service.contains("applyPartial8527(partialReceipt6566.canonicalSlice8527)"))
        assertTrue(wrapper.contains("if (!receipt.applied || slice8527 == null) return receipt.copy(applied = false)"))
        assertTrue(executor.contains("CanonicalPartialReceipt8527.Capture8527(delivery8527)"))
        val finalizer = File(root + "engine/sell/SellFinalizationCoordinator.kt").readText()
        assertFalse(finalizer.contains("publishSettled8527()"))
        val live = executor.substringAfter("private fun liveSellInScope7314(")
        assertTrue(live.indexOf("DipHunterAI.closeDip(tradeId.mint, exitPrice, dipEx") <
            live.indexOf("nativeCompletion8527.publishSettled8527()"))
        assertTrue(live.contains("canonicalSlice8527 = nativeCompletion8527.receipt"))
        val request = executor.substringAfter("private fun requestPartialSellWithDelivery8527(")
            .substringBefore("private fun isNonRouteSellWait")
        assertTrue(request.contains("NativeTerminalClose8527.close8527(partial6510.canonicalSlice8527, currentPrice)"))
        assertTrue(request.indexOf("NativeTerminalClose8527.close8527(settled7835.canonicalSlice8527, currentPrice)") <
            request.indexOf("partialCompletion8527.publishSettled8527()"))
        val paperBridge = File(root + "engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        assertTrue(paperBridge.contains("preRemainingRaw = capture8527.receipt?.preRaw ?: preRemainingRaw"))
        assertTrue(paperBridge.contains("postRemainingRaw = capture8527.receipt?.remainingRaw ?: preRemainingRaw"))
        assertTrue(service.contains("getPartialSellPct(ts.mint, ts.position.isPaperPosition)"))
        assertTrue(service.contains("!blueSellResult8527.settled8521() || !specialistCloseConfirmed8521(ts.mint, closingLot8527)"))
        assertTrue(service.contains("!dipSellResult8527.settled8521() || !specialistCloseConfirmed8521(ts.mint, closingLot8527)"))
        assertTrue(File(root + "v3/scoring/DipHunterAI.kt").readText()
            .contains("SpecialistCloseProjection7858.mayClose(mint, pos.isPaper, pos.entryTime)"))
        for (name in listOf("QualityTraderAI", "BlueChipTraderAI", "ShitCoinExpress")) {
            val source = File(root + "v3/scoring/$name.kt").readText()
            val exit = source.substringAfter("fun checkExit(").substringBefore("\n    fun ")
            assertFalse(name, exit.contains("partialRungsTaken += 1"))
        }
    }

    @Test fun partialReturnUsesConsumedBasisAndCountsNetFeesOnlyOnce() {
        val trade = com.lifecyclebot.data.Trade("PARTIAL_SELL", "live", 0.33, 1.0, 1L,
            pnlSol = 0.09, netPnlSol = 0.07, feeSol = 0.02, positionId = "exact",
            soldCostBasisSol = 0.33, entryCostSol = 1.0, postCostSol = 0.67,
            canonicalConsumedRaw = BigInteger.valueOf(33L), proofState = "LIVE_FINALIZED")
        val e = com.lifecyclebot.engine.truth.TradeSliceEconomics8527.read(trade)
        assertEquals(0.33, e.costBasisSol!!, 0.0)
        assertEquals(0.07, e.netPnlSol, 0.0)
        assertEquals(0.07 / 0.33 * 100.0, e.returnPct!!, 1e-12)
        assertNotEquals(0.07 / 0.67 * 100.0, e.returnPct!!, 1e-12)
        val legacyPartial = com.lifecyclebot.engine.truth.TradeSliceEconomics8527.read(
            trade.copy(soldCostBasisSol = 0.0, entryCostSol = 1.0))
        assertEquals(0.33, legacyPartial.costBasisSol!!, 0.0)
        assertEquals(0.07 / 0.33 * 100.0, legacyPartial.returnPct!!, 1e-12)
        val unknownPartial = com.lifecyclebot.engine.truth.TradeSliceEconomics8527.read(
            trade.copy(soldCostBasisSol = 0.0, sol = 0.0, entryCostSol = 1.0))
        assertNull(unknownPartial.costBasisSol)
        assertNull(unknownPartial.returnPct)
        val breakEven = com.lifecyclebot.engine.truth.TradeSliceEconomics8527.read(
            trade.copy(pnlSol = 0.02, netPnlSol = 0.0, feeSol = 0.02))
        assertEquals(0.0, breakEven.netPnlSol, 0.0)
        assertEquals(0.0, breakEven.returnPct!!, 0.0)
    }

    @Test fun scannerTrainingUsesFrozenEntryBirthSourceAndLiquidityNotExitWatchlistAge() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(source.contains("CanonicalTokenBirthTime7440.peekBirthMs8418(ts.mint, entryNow8527)"))
        assertEquals(2, source.split("val scannerEntry8527 = pos.entryPolicySnapshot").size - 1)
        assertFalse(source.contains("val tokenAgeHours = (System.currentTimeMillis() - ts.addedToWatchlistAt)"))
        assertFalse(source.contains("val tokenAgeHours2 = (System.currentTimeMillis() - ts.addedToWatchlistAt)"))
    }
}
