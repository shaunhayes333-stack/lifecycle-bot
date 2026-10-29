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

    @Test fun v8399LaneBarIsFifteenClosesAtPf12ForTheProfitableLanesOnly() {
        // V5.0.8399 — CORE / MOONSHOT / PROJECT_SNIPER prove on 15 closes at PF 1.2; every other lane keeps 30 / 1.3; the margin is shared.
        assertEquals(setOf("CORE", "MOONSHOT", "PROJECT_SNIPER"), EvidenceHold8336.FAST_BAR_LANES_8399)
        // V5.0.8420 — the operator's 8399 fast bar is restored (the 8418 retirement is superseded).
        assertEquals(EvidenceHold8336.FAST_MIN_N_8399, EvidenceHold8336.minN8399("CORE")); assertEquals(EvidenceHold8336.MIN_N_8336, EvidenceHold8336.minN8399("SHITCOIN"))
        assertEquals(EvidenceHold8336.FAST_MIN_PF_8399, EvidenceHold8336.minPf8399("MOONSHOT"), 0.0); assertEquals(EvidenceHold8336.MIN_PF_8336, EvidenceHold8336.minPf8399(null), 0.0)
        val rets = List(16) { if (it % 4 == 3) -5.0 else 8.0 }                 // 16 closes, mean +4.75, SE ~1.45, PF 96/20 = 4.8
        val sol = rets.map { it / 100.0 }
        assertTrue("16 paper closes prove CORE on the fast bar", EvidenceHold8336.proof8336(rets, sol, "CORE").proven)
        assertFalse("the same record does not prove a 30-close lane", EvidenceHold8336.proof8336(rets, sol, "SHITCOIN").proven)
        assertFalse("nor an un-laned read", EvidenceHold8336.proof8336(rets, sol).proven)
    }
}
