package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceHold8336
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** 8336 — no live meme entry until the lane's own paper record proves (n >= 30, mean - SE > 2 %, PF >= 1.3). */
class Aate8336EvidenceHoldTest {
    @After fun back() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)
        EvidenceHold8336.enabled8336 = true
    }

    @Test fun thinOrLosingRecordsDoNotProve() {
        assertFalse(EvidenceHold8336.proof8336(List(29) { 20.0 }, List(29) { 0.01 }).proven)          // n < 30
        assertFalse(EvidenceHold8336.proof8336(List(40) { -3.0 }, List(40) { -0.001 }).proven)        // losing
        val coinFlip = List(40) { if (it % 2 == 0) 10.0 else -9.0 }
        assertFalse(EvidenceHold8336.proof8336(coinFlip, coinFlip.map { it / 1000.0 }).proven)       // mean - SE under the margin
    }

    @Test fun aClearPositiveRecordProves() {
        val r = List(40) { if (it % 4 == 0) -8.0 else 15.0 }   // 75 % wins, mean ~ +9.25 %
        val p = EvidenceHold8336.proof8336(r, r.map { it / 1000.0 })
        assertTrue(p.toString(), p.proven)
        assertTrue(p.pf >= EvidenceHold8336.MIN_PF_8336)
    }

    @Test fun paperIsNeverHeldAndTheSwitchTurnsTheHoldOff() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        assertNull(EvidenceHold8336.refusal8336("SHITCOIN", paper = true))
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        EvidenceHold8336.enabled8336 = false
        assertNull(EvidenceHold8336.refusal8336("SHITCOIN", paper = false))
    }
}
