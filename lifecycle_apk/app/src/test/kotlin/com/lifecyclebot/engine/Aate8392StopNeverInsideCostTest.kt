package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.truth.FieldManual7715
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8392 §STOP_NEVER_INSIDE_THE_ROUND_TRIP — behavioural: a desk's stop is the wider of its mandate and 1.5x the round
 * trip it pays; asset classes paying basis points keep their mandates; runner lanes already outside today's trip do not
 * move; a bigger ticket (lower cost) tightens the stop back toward the mandate on its own; the hold layer never trades a
 * stop inside the trip and a non-runner desk holds at least the window the Cortex grades.
 */
class Aate8392StopNeverInsideCostTest {
    private val trip = 9.28   // 5.0.8390 live measured median round trip at a 0.031 SOL ticket

    @Test fun memeDesksWidenToTheTripTheyPay() {
        assertEquals(5.0, FieldManual7715.mandateFor("TREASURY").invalidationPct, 0.0)
        assertEquals(1.5 * trip, FieldManual7715.stopPct8392("TREASURY", trip), 1e-9)
        assertEquals(1.5 * trip, FieldManual7715.stopPct8392("CASHGEN", trip), 1e-9)
        assertEquals(1.5 * trip, FieldManual7715.stopPct8392("QUALITY", trip), 1e-9)
        assertEquals(1.5 * trip, FieldManual7715.stopPct8392("EXPRESS", trip), 1e-9)
        assertEquals(1.5 * trip, FieldManual7715.stopPct8392("DIP_HUNTER", trip), 1e-9)
    }

    @Test fun runnerLanesAlreadyOutsideTheTripDoNotMove() {
        assertEquals(15.0, FieldManual7715.stopPct8392("MOONSHOT", trip), 0.0)
        assertEquals(15.0, FieldManual7715.stopPct8392("SHITCOIN", trip), 0.0)
        assertEquals(15.0, FieldManual7715.stopPct8392("MANIPULATED", trip), 0.0)
    }

    @Test fun assetClassAwareThroughTheCostItself() {
        assertEquals(3.0, FieldManual7715.stopPct8392("PERPS", 0.12), 0.0)
        assertEquals(1.5, FieldManual7715.stopPct8392("CARRY", 0.05), 0.0)
        assertEquals(0.8, FieldManual7715.stopPct8392("ARB", 0.02), 0.0)
        assertEquals(6.0, FieldManual7715.stopPct8392("CRYPTO_ALT", 1.2), 0.0)
    }

    @Test fun aBiggerTicketTightensBackTowardTheMandateAndTheCapHolds() {
        assertEquals(10.0, FieldManual7715.stopPct8392("QUALITY", 4.0), 0.0)        // 6 < 10: mandate
        assertEquals(10.5, FieldManual7715.stopPct8392("QUALITY", 7.0), 1e-9)
        assertEquals(FieldManual7715.STOP_CAP_PCT_8392, FieldManual7715.stopPct8392("QUALITY", 60.0), 0.0)
        assertEquals(10.0, FieldManual7715.stopPct8392("QUALITY", Double.NaN), 0.0)   // unknown cost: the mandate, never a guess
        assertEquals(10.0, FieldManual7715.stopPct8392("QUALITY", 0.0), 0.0)
    }

    @Test fun theHoldLayerNeverTradesAStopInsideTheTripAndHoldsTheGradedWindow() {
        val treasury = HoldingLogicLayer.getHoldParams("TREASURY")
        assertTrue("TREASURY stop ${treasury.stopLossPct}", treasury.stopLossPct <= -FieldManual7715.laneStopPct8392("TREASURY") + 1e-9)
        assertTrue(treasury.maxHoldTimeMs >= CortexLabelHygiene8144.executableHorizonMs8392(false))
        assertTrue(treasury.maxHoldTimeMs < HoldingLogicLayer.getHoldParams("STANDARD").maxHoldTimeMs)
        // runner mandates keep their specialist clocks
        assertEquals(15 * 60 * 1000L, HoldingLogicLayer.getHoldParams("MANIPULATED").maxHoldTimeMs)
        assertEquals(-20.0, HoldingLogicLayer.getHoldParams("MANIPULATED").stopLossPct, 0.0)
        // the live doctrine floor is the same number
        assertEquals(FieldManual7715.laneStopPct8392("QUALITY"), ExitDoctrine8169.doctrinePct8169("QUALITY"), 1e-9)
        assertEquals(60L * 60_000L, CortexLabelHygiene8144.executableHorizonMs8392(false))
        assertEquals(6L * 3_600_000L, CortexLabelHygiene8144.executableHorizonMs8392(true))
    }
}
