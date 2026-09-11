package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728
import com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6728 — §ADAPTIVE_VETO_CONSENSUS.
 *
 * Locks in the mechanism that hardens the collective advisory posture
 * into a real hard-veto when >=3 independent evidence families agree. This is the
 * escalation the operator flagged as missing in the 6727 dump: the
 * learning/risk brains detect the toxic state but publish as advisories
 * that BUY/WAIT overrides ignore.
 */
class Aate6728AdaptiveConsensusTest {

    @Before
    fun reset() {
        AdaptiveVetoConsensusAuthority6728.resetForTest6728()
    }

    @Test
    fun `single signal below quorum does not trigger hard veto`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertFalse("single signal must not escalate", v.hardVeto)
        assertEquals(1, v.quorum)
    }

    @Test
    fun `two signals below quorum still do not trigger hard veto`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertFalse("two signals below quorum of 3 must not escalate", v.hardVeto)
        assertEquals("LLM and Sentience are correlated advisor evidence", 1, v.quorum)
    }

    @Test
    fun `three raw correlated signals do not form independent quorum`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LOSING_STREAK_COHORT)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertFalse("correlated advisories must not escalate to a hard veto", v.hardVeto)
        assertEquals(2, v.quorum)
        assertFalse(AdaptiveVetoConsensusAuthority6728.isHardVeto())
    }

    @Test
    fun `clear pulls signal back below quorum`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LOSING_STREAK_COHORT)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.BRAIN_CONSENSUS_SOFT_BLOCK)
        assertTrue(AdaptiveVetoConsensusAuthority6728.isHardVeto())
        AdaptiveVetoConsensusAuthority6728.clear(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.clear(Signal.SENTIENCE_VETO_ADVISORY)
        assertFalse("clear must pull below quorum", AdaptiveVetoConsensusAuthority6728.isHardVeto())
    }

    @Test
    fun `diagnostic line contains verdict and active signals`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.PERFORMANCE_BELOW_50_TARGET)
        val line = AdaptiveVetoConsensusAuthority6728.diagnosticLine()
        assertTrue(line.contains("hardVeto="))
        assertTrue(line.contains("quorum="))
        assertTrue(line.contains("PERFORMANCE_BELOW_50_TARGET"))
    }
}
