package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Test

class Aate8076CortexScoreIntegrityTest {
    @Test fun corruptNegativeSseCannotCreateSuperhumanSkillOrAuthority() {
        val seat = CortexLedger7885.Seat(2)
        seat.scored = CortexLedger7885.MIN_SCORED + 200
        seat.sseBase = 12.0
        seat.sseModel = -999.0
        assertEquals(0.0, seat.skill(), 0.0)
        assertEquals(0.0, seat.authority(), 0.0)
    }

    @Test fun validOutOfSampleSkillRemainsUnchanged() {
        val seat = CortexLedger7885.Seat(2)
        seat.scored = CortexLedger7885.MIN_SCORED
        seat.sseBase = 100.0
        seat.sseModel = 25.0
        assertEquals(0.75, seat.skill(), 1e-9)
    }
}
