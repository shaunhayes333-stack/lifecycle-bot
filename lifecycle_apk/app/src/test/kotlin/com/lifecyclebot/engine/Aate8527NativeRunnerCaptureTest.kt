package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPartialReceipt8527 as Receipt
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import java.math.BigInteger
import com.lifecyclebot.v3.scoring.MoonshotTraderAI as Moon
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI as Shit
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.io.File

/** Exercise the native traders, not just the shared trail helper. */
class Aate8527NativeRunnerCaptureTest {
    @Before fun reset() {
        Moon.clearAllPositions(); Shit.clearAllPositions(); Cpa.resetForTest()
        com.lifecyclebot.engine.truth.NativePartialProjection8527.resetForTest8527()
    }
    @After fun cleanup() { reset() }

    private fun shit(tag: String, ageMs: Long = 10_000L, peak: Double = 0.0, rungs: Int = 0): String {
        val mint = "native-shit8527-$tag-${System.nanoTime()}"
        Shit.restorePosition(Shit.ShitCoinPosition(
            mint = mint, symbol = "S8527", entryPrice = 1.0, entrySol = 1.0,
            entryTime = System.currentTimeMillis() - ageMs, marketCapUsd = 50_000.0, liquidityUsd = 50_000.0,
            isPaper = false, takeProfitPct = 100.0, stopLossPct = -15.0,
            launchPlatform = Shit.LaunchPlatform.PUMP_FUN,
            peakPnlPct = peak, highWaterMark = 1.0 + peak / 100.0,
            trailingStop = if (peak > 0.0) (1.0 + peak / 100.0) * 0.92 else 0.85,
            partialRungsTaken = rungs, firstTakeDone = rungs > 0,
        ), isPaper = false)
        return mint
    }

    private fun boundShit(mint: String): TokenState {
        val p = Shit.getActivePositionsForMode(false).single { it.mint == mint }
        val pid = "LIVE:$mint:fixture8527"
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$pid", pid, mint, p.symbol, "SHITCOIN",
            "8527", p.entrySol, BigInteger.valueOf(100L), 0, 0.0, false,
            entryPriceUsd = p.entryPrice, entryPriceSource = "LIVE_FINALIZED"))
        return TokenState(mint = mint, position = Position(positionId = pid, qtyToken = 100.0,
            entryPrice = p.entryPrice, entryTime = Cpa.getPosition(pid)!!.openedAtMs,
            costSol = p.entrySol, isPaperPosition = false))
    }

    private fun confirmQuarter(mint: String) {
        val ts = boundShit(mint)
        val apply = Shit.partialProjection8527(ts)
        val before = Cpa.getPosition(ts.position.positionId)!!
        val capture = Receipt.Capture8527()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("partial:${before.positionId}", before.positionId,
            BigInteger.valueOf(25L), 0.30, 0.25, 0.0, false, receiptCapture8527 = capture))
        assertTrue(apply(capture.receipt))
    }

    private fun moon(tag: String, peak: Double = 0.0, rungs: Int = 0): String {
        val mint = "native-moon8527-$tag-${System.nanoTime()}"
        Moon.restorePosition(Moon.MoonshotPosition(
            mint = mint, symbol = "M8527", entryPrice = 1.0, entrySol = 1.0,
            entryTime = System.currentTimeMillis() - 10_000L, takeProfitPct = 200.0, stopLossPct = -20.0,
            marketCapUsd = 50_000.0, liquidityUsd = 50_000.0, entryScore = 80.0,
            spaceMode = Moon.SpaceMode.LUNAR, isPaperMode = false,
            peakPnlPct = peak, highWaterMark = 1.0 + peak / 100.0,
            trailingStop = if (peak > 0.0) (1.0 + peak / 100.0) * 0.92 else 0.80,
            partialRungsTaken = rungs, firstTakeDone = rungs > 0,
        ))
        return mint
    }

    @Test fun earlyLaunchUpsideIsObservedAndItsRungStaysRetryableUntilTheReceipt() {
        val mint = shit("early")
        repeat(3) {
            assertEquals(Shit.ExitSignal.PARTIAL_TAKE, Shit.checkExit(mint, 1.30, isPaper = false))
            val pending = Shit.getActivePositionsForMode(false).single { it.mint == mint }
            assertEquals(0, pending.partialRungsTaken)
            assertFalse(pending.firstTakeDone)
            assertEquals(30.0, pending.peakPnlPct, 1e-9)
            assertEquals(1.0, pending.entrySol, 0.0)
        }
        confirmQuarter(mint)
        val confirmed = Shit.getActivePositionsForMode(false).single { it.mint == mint }
        assertEquals(1, confirmed.partialRungsTaken)
        assertTrue(confirmed.firstTakeDone)
        assertEquals(0.75, confirmed.entrySol, 1e-9)
        assertEquals(Shit.ExitSignal.HOLD, Shit.checkExit(mint, 1.30, isPaper = false))
    }

    @Test fun invalidPartialInputsCannotAdvanceASpecialistRung() {
        val mint = shit("invalid")
        val ts = boundShit(mint)
        val apply = Shit.partialProjection8527(ts)
        val unchanged = Cpa.getPosition(ts.position.positionId)!!
        assertFalse(apply(null))
        assertFalse(apply(Receipt.fromApplied8527(true, unchanged, unchanged, "not-a-fill")))
        val p = Shit.getActivePositionsForMode(false).single { it.mint == mint }
        assertEquals(0, p.partialRungsTaken)
        assertFalse(p.firstTakeDone)
        assertEquals(1.0, p.entrySol, 0.0)
    }

    @Test fun firstMinuteSettlementNeverMasksAnAlreadySeenWinnerOrTheHardFloor() {
        val neverGreen = shit("settle")
        assertEquals(Shit.ExitSignal.HOLD, Shit.checkExit(neverGreen, 0.96, isPaper = false))
        assertEquals(Shit.ExitSignal.STOP_LOSS, Shit.checkExit(neverGreen, 0.84, isPaper = false))
        val winner = shit("winner", peak = 500.0, rungs = 7)
        assertEquals(Shit.ExitSignal.TRAILING_STOP, Shit.checkExit(winner, 3.80, isPaper = false))
    }

    @Test fun promotedShitcoinUsesTheOwnedRunnerTrailInsteadOfAnEightPercentLegacyTrail() {
        val mint = shit("trail", ageMs = 120_000L, peak = 500.0, rungs = 7)
        assertEquals(Shit.ExitSignal.HOLD, Shit.checkExit(mint, 5.0, isPaper = false))
        val p = Shit.getActivePositionsForMode(false).single { it.mint == mint }
        assertEquals(3.90, p.trailingStop, 1e-9)
        assertEquals(Shit.ExitSignal.TRAILING_STOP, Shit.checkExit(mint, 3.89, isPaper = false))
    }

    @Test fun unpromotedShitcoinStillUsesItsFastContract() {
        val mint = shit("fast", ageMs = 120_000L, peak = 30.0, rungs = 7)
        assertFalse(RunnerExitProfile7277.runnerExits8468("SHITCOIN", 30.0))
        // Its original scalp give-back remains: no unconditional 35% runner widening below promotion.
        assertEquals(Shit.ExitSignal.TRAILING_STOP, Shit.checkExit(mint, 1.10, isPaper = false))
    }

    @Test fun nativeMoonshotUsesTheSameRunnerTrailAndKeepsItsHardStop() {
        val mint = moon("trail", peak = 500.0, rungs = 7)
        assertEquals(Moon.ExitSignal.HOLD, Moon.checkExit(mint, 5.0, isPaper = false))
        val p = Moon.getActivePositionsForMode(false).single { it.mint == mint }
        assertEquals(3.90, p.trailingStop, 1e-9)
        assertEquals(Moon.ExitSignal.TRAILING_STOP, Moon.checkExit(mint, 3.89, isPaper = false))
        val loss = moon("loss")
        assertEquals(Moon.ExitSignal.HOLD, Moon.checkExit(loss, 0.94, isPaper = false))
        assertEquals(Moon.ExitSignal.STOP_LOSS, Moon.checkExit(loss, 0.79, isPaper = false))
    }

    @Test fun partialProgressIsAccountScoped() {
        val mint = shit("live")
        val live = Shit.getActivePositionsForMode(false).single { it.mint == mint }
        Shit.restorePosition(live.copy(isPaper = true), isPaper = true)
        confirmQuarter(mint)
        assertEquals(1, Shit.getActivePositionsForMode(false).single { it.mint == mint }.partialRungsTaken)
        assertEquals(0, Shit.getActivePositionsForMode(true).single { it.mint == mint }.partialRungsTaken)
        assertEquals(1.0, Shit.getActivePositionsForMode(true).single { it.mint == mint }.entrySol, 0.0)
    }

    @Test fun callersOnlyConfirmTheNativeRungAfterTheAppliedReceipt() {
        val service = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        for (region in listOf(
            service.substringAfter("val exitSignal = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.checkExit(").substringBefore("// Full exit for all other signals"),
            service.substringAfter("// ── ShitCoinTraderAI delegation").substringBefore("// ── MoonshotTraderAI delegation"),
        )) {
            assertTrue(region.indexOf("if (!partialReceipt6566.applied)") >= 0)
            assertTrue(region.indexOf("onConfirmedSlice8527 = applyPartial8527,") >= 0)
            assertTrue(region.indexOf("onConfirmedSlice8527 = applyPartial8527,") <
                region.indexOf("if (!partialReceipt6566.applied)"))
        }
        val native = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertFalse(native.substringAfter("fun checkExit(").substringBefore("// LEARNING").contains("pos.partialRungsTaken +="))
        assertTrue(native.contains("holdSeconds = _holdSec, lane = \"SHITCOIN\""))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
            .contains("holdSeconds = _holdSec, lane = \"MOONSHOT\""))
    }
}
