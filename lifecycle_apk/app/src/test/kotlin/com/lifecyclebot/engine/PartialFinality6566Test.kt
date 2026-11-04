package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPartialReceipt8527
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import java.math.BigInteger
import org.junit.Before
import org.junit.After
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import org.junit.Assert.*
import org.junit.Test

class PartialFinality6566Test {
    @Before fun reset() {
        Cpa.resetForTest(); MoonshotTraderAI.clearAllPositions()
        com.lifecyclebot.engine.truth.NativePartialProjection8527.resetForTest8527()
    }
    @After fun cleanup() { reset() }
    @Test fun moonshot_check_only_proposes_rung_and_applied_receipt_advances_it() {
        val mint = "MOON_PART_6566_${System.nanoTime()}"
        MoonshotTraderAI.addPosition(
            MoonshotTraderAI.MoonshotPosition(
                mint = mint, symbol = "M6566", entryPrice = 1.0, entrySol = 1.0,
                entryTime = System.currentTimeMillis(), takeProfitPct = 200.0,
                stopLossPct = -15.0, marketCapUsd = 100_000.0, liquidityUsd = 50_000.0,
                entryScore = 80.0, spaceMode = MoonshotTraderAI.SpaceMode.LUNAR,
                isPaperMode = false,
            )
        )
        assertEquals(MoonshotTraderAI.ExitSignal.PARTIAL_TAKE, MoonshotTraderAI.checkExit(mint, 1.25, isPaper = false))
        val proposed = MoonshotTraderAI.getActivePositions().first { it.mint == mint }
        assertEquals(0, proposed.partialRungsTaken)
        assertFalse(proposed.firstTakeDone)

        val pid = "LIVE:$mint"
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$pid", pid, mint, "M6566", "MOONSHOT",
            "test", 1.0, BigInteger.valueOf(100L), 0, 0.0, false,
            entryPriceUsd = 1.0, entryPriceSource = "LIVE_FINALIZED"))
        val before = Cpa.getPosition(pid)!!
        val ts = TokenState(mint = mint, position = Position(positionId = pid, qtyToken = 100.0,
            entryPrice = 1.0, entryTime = before.openedAtMs, costSol = 1.0, isPaperPosition = false))
        val project = MoonshotTraderAI.partialProjection8527(ts)
        val capture = CanonicalPartialReceipt8527.Capture8527()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("slice:$pid", pid, BigInteger.valueOf(50L),
            0.60, 0.50, 0.0, false, receiptCapture8527 = capture))
        assertTrue(project(capture.receipt))
        assertFalse(project(capture.receipt))
        val applied = MoonshotTraderAI.getActivePositions().first { it.mint == mint }
        assertEquals(1, applied.partialRungsTaken)
        assertTrue(applied.firstTakeDone)
        assertEquals(0.5, applied.entrySol, 1e-9)
        MoonshotTraderAI.evictGhost(mint)
    }
}
