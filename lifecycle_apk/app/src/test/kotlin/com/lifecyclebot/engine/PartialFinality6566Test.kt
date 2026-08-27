package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import org.junit.Assert.*
import org.junit.Test

class PartialFinality6566Test {
    @Test fun moonshot_check_only_proposes_rung_and_applied_receipt_advances_it() {
        val mint = "MOON_PART_6566_${System.nanoTime()}"
        MoonshotTraderAI.addPosition(
            MoonshotTraderAI.MoonshotPosition(
                mint = mint, symbol = "M6566", entryPrice = 1.0, entrySol = 1.0,
                entryTime = System.currentTimeMillis(), takeProfitPct = 200.0,
                stopLossPct = -15.0, marketCapUsd = 100_000.0, liquidityUsd = 50_000.0,
                entryScore = 80.0, spaceMode = MoonshotTraderAI.SpaceMode.LUNAR,
                isPaperMode = true,
            )
        )
        assertEquals(MoonshotTraderAI.ExitSignal.PARTIAL_TAKE, MoonshotTraderAI.checkExit(mint, 1.25))
        val proposed = MoonshotTraderAI.getActivePositions().first { it.mint == mint }
        assertEquals(0, proposed.partialRungsTaken)
        assertFalse(proposed.firstTakeDone)

        MoonshotTraderAI.onPartialSell(mint, 0.50)
        val applied = MoonshotTraderAI.getActivePositions().first { it.mint == mint }
        assertEquals(1, applied.partialRungsTaken)
        assertTrue(applied.firstTakeDone)
        assertEquals(0.5, applied.entrySol, 1e-9)
        MoonshotTraderAI.evictGhost(mint)
    }
}
