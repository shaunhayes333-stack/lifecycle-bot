package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceShadow8344
import org.junit.Assert.*
import org.junit.Test

/** 8344 — the shadow trader behind the live trader: give-back floor and net of banked slices, remainder and cost. */
class Aate8344EvidenceShadowTest {
    @Test fun giveBackFloorArmsAtTheLaneArmAndKeepsMostOfThePeak() {
        assertNull(EvidenceShadow8344.giveBackFloor8344(19.0, 20.0, 6.0))
        assertEquals(16.0, EvidenceShadow8344.giveBackFloor8344(40.0, 20.0, 6.0)!!, 1e-9)   // max(min(6, 20), 16)
        assertEquals(80.0, EvidenceShadow8344.giveBackFloor8344(200.0, 25.0, 6.0)!!, 1e-9)  // max(6, 80)
        assertEquals(10.0, EvidenceShadow8344.giveBackFloor8344(20.0, 20.0, 12.0)!!, 1e-9)  // max(min(12, 10), 8)
    }

    @Test fun netCountsBankedSlicesTheRemainderAndTheCost() {
        // +40 tier sold 60 % at +40 (banked 24 gross), remainder 40 % exits at +30 (12), cost 6 -> +30
        assertEquals(30.0, EvidenceShadow8344.netPct8344(24.0, 0.4, 30.0, 6.0), 1e-9)
        // straight stop at -10 with 6 % cost -> -16
        assertEquals(-16.0, EvidenceShadow8344.netPct8344(0.0, 1.0, -10.0, 6.0), 1e-9)
    }
}
