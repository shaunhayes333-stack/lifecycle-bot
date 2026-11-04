package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryFloorEvidence8528
import org.junit.Assert.*
import org.junit.Test

class Aate8528FluidFloorTest {
    @Test fun netProfitableBandDoesNotPayFeesTwice() {
        assertTrue(EntryFloorEvidence8528.profitableNetBand(30, 4.0))
        assertFalse(EntryFloorEvidence8528.profitableNetBand(5, 100.0))
        assertFalse(EntryFloorEvidence8528.profitableNetBand(30, -2.0))
        assertFalse(EntryFloorEvidence8528.profitableNetBand(30, Double.NaN))
        assertEquals(20.0, EntryFloorEvidence8528.preserveProfitableBand(44.0, 20.0), 0.0)
    }
    @Test fun profitableLowerBandReopensEvenWhenHighScoresLose() {
        assertEquals(20, EntryFloorEvidence8528.nativeFloor(30, listOf(Triple(20, 30, 5.0), Triple(60, 40, -20.0))))
    }
    @Test fun highScoreLossCannotBanUntestedLowerScores() {
        assertEquals(15, EntryFloorEvidence8528.nativeFloor(15, listOf(Triple(80, 30, -20.0))))
        assertEquals(20, EntryFloorEvidence8528.nativeFloor(15, listOf(Triple(10, 30, -20.0), Triple(80, 30, -20.0))))
    }
    @Test fun evidenceTightensAndThenRelaxesWithoutAReset() {
        assertEquals(30, EntryFloorEvidence8528.nativeFloor(15, listOf(Triple(10, 30, -10.0), Triple(20, 30, -8.0))))
        assertEquals(10, EntryFloorEvidence8528.nativeFloor(15, listOf(Triple(10, 30, 5.0), Triple(20, 30, -8.0))))
        assertEquals(15, EntryFloorEvidence8528.nativeFloor(15, emptyList()))
    }
}
