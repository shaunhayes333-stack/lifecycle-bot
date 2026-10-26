package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.EvidenceShadow8344
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.ShadowFill8364
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8364 — entries (audit 8360 item 2 + the shadow-fill half of item 3): a LIVE 8114 admit needs a named setup that
 * fired; the adopter compares every arm at mean - 2 SE per signal (fill rate counted) and an M: band must beat the other
 * entry style by a margin; shadow fills are booked at the mark plus the lane's decision-to-fill chase.
 */
class Aate8364EntriesTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    private fun proof(mean: Double, se: Double, proven: Boolean = true) = EvidenceHold8336.Proof8336(60, mean, se, 2.0, proven)
    private val E = ExploreShadow8347.Entry8347.NOW
    private val P = ExploreShadow8347.Entry8347.PULLBACK
    private val R = ExploreShadow8347.Exit8347.RUNNER

    // ── 1. a live entry needs a fired setup ──

    @Test fun namedSetupMetadataDoesNotCreateASecondEntryAuthority() {
        val p=com.lifecyclebot.engine.cortex.LanePlaybook7907
        assertFalse(p.namedSetup8392(null));assertFalse(p.namedSetup8392("NO_TRIGGER"))
        assertFalse(p.namedSetup8392("matches_unrankable"))
        assertTrue(p.namedSetup8392("IGNITION_5M"))
        assertFalse(src("engine/truth/LiveEdgeGate7877.kt").contains("liveFiredSetupRefusal8364"))
    }

    @Test fun paperIsNeverRefusedByTheFiredSetupRule() {
        val s=src("engine/truth/LiveEdgeGate7877.kt")
        assertFalse(s.contains("noFiredSetupRefusal8364"))
        assertTrue(s.contains("if (paper) return cortex7950"))
    }

    @Test fun nichesNeedANamedFiredSetup() {
        val p = proof(10.0, 2.0)
        val s = ExploreShadow8347.Choice8351(P, R, "S", "IGNITION_5M", p)
        val m = ExploreShadow8347.Choice8351(E, R, "M", "MC_10K_100K", p)
        assertFalse("setup=null never enters an S: niche", ExploreShadow8347.inNiche8351(s, null, "MC_10K_100K"))
        assertTrue(ExploreShadow8347.inNiche8351(s, "ignition_5m", null))
        // superseded by V5.0.8370 — owner restored 8114 behaviour: an M: band niche is the band alone (no named setup needed).
        assertTrue("a band niche admits its band", ExploreShadow8347.inNiche8351(m, null, "MC_10K_100K"))
        assertTrue(ExploreShadow8347.inNiche8351(m, "NO_TRIGGER", "MC_10K_100K"))
        assertFalse("wrong band", ExploreShadow8347.inNiche8351(m, "BREAKOUT_RETEST", "MC_LT10K"))
        assertTrue(ExploreShadow8347.inNiche8351(m, "BREAKOUT_RETEST", "MC_10K_100K"))
        // against the coin's fired setups
        // superseded by V5.0.8370 — owner restored 8114 behaviour: the band decides an M: niche, fired setups or not.
        assertTrue(ExploreShadow8347.inNiche8364(m, null, "MC_10K_100K", emptyList()))
        assertTrue(ExploreShadow8347.inNiche8364(m, "BREAKOUT_RETEST", "MC_10K_100K", listOf("NO_TRIGGER")))
        assertTrue(ExploreShadow8347.inNiche8364(m, null, "MC_10K_100K", listOf("NO_TRIGGER", "BREAKOUT_RETEST")))
        assertFalse("wrong band", ExploreShadow8347.inNiche8364(m, null, "MC_LT10K", listOf("BREAKOUT_RETEST")))
        assertTrue(ExploreShadow8347.inNiche8364(s, null, null, listOf("IGNITION_5M")))
        assertFalse(ExploreShadow8347.inNiche8364(s, null, null, listOf("NO_TRIGGER")))
        val whole = s.copy(dim = null, value = null)
        assertTrue(ExploreShadow8347.inNiche8364(whole, null, null, emptyList()))
    }

    // ── 2. the honest adopter ──

    @Test fun everyArmIsComparedAtTwoSe() {
        assertEquals(6.0, ExploreShadow8347.lowerBound8364(10.0, 2.0), 1e-9)
        assertEquals(ExploreShadow8347.COHORT_SE_MULT_8351, 2.0, 0.0)
        // proven at 1.5 SE but not at 2 SE: not adoptable
        assertFalse(ExploreShadow8347.variantAdoptable8364(proof(5.0, 1.5)))
        assertTrue(ExploreShadow8347.variantAdoptable8364(proof(6.0, 1.5)))
        assertFalse(ExploreShadow8347.variantAdoptable8364(proof(9.0, 1.0, proven = false)))
        // a whole variant (lb 4 + 0.5) loses to a setup cohort with a better 2 SE bound (lb 5)
        val v = ExploreShadow8347.Choice8351(E, R, null, null, proof(8.0, 2.0))
        val c = ExploreShadow8347.Choice8351(E, R, "S", "IGNITION_5M", proof(10.0, 2.5))
        assertSame(c, ExploreShadow8347.pickBest8364(listOf(v, c), listOf(v, c)))
        assertNull(ExploreShadow8347.pickBest8364(emptyList(), emptyList()))
    }

    @Test fun aBandCohortMustBeatTheOtherEntryStyleByTheMargin() {
        val pull = ExploreShadow8347.Choice8351(P, R, null, null, proof(9.5, 2.0))          // lb 5.5 per signal
        val band6 = ExploreShadow8347.Choice8351(E, R, "M", "MC_10K_100K", proof(10.0, 2.0)) // lb 6.0: not +1 over 5.5
        assertFalse(ExploreShadow8347.mCohortAdmissible8364(band6, listOf(pull)))
        assertSame(pull, ExploreShadow8347.pickBest8364(listOf(pull, band6), listOf(pull)))
        val band7 = band6.copy(proof = proof(11.0, 2.0))                                   // lb 7.0 > 5.5 + 1
        assertTrue(ExploreShadow8347.mCohortAdmissible8364(band7, listOf(pull)))
        assertSame(band7, ExploreShadow8347.pickBest8364(listOf(pull, band7), listOf(pull)))
        // only a rival of the OTHER style binds: a NOW variant does not block a NOW band
        val nowV = ExploreShadow8347.Choice8351(E, R, null, null, proof(9.0, 2.0))         // lb 5.0 (+0.5)
        assertTrue(ExploreShadow8347.mCohortAdmissible8364(band6, listOf(nowV)))
        assertSame(band6, ExploreShadow8347.pickBest8364(listOf(nowV, band6), listOf(nowV)))
        // a proven-but-not-adoptable rival still counts as the bar the band must clear
        assertFalse(ExploreShadow8347.mCohortAdmissible8364(band6, listOf(pull.copy(proof = proof(9.5, 2.0)))))
        // setup cohorts are not subject to the band rule
        val setup = band6.copy(dim = "S", value = "IGNITION_5M")
        assertTrue(ExploreShadow8347.mCohortAdmissible8364(setup, listOf(pull)))
    }

    @Test fun armsAreComparedPerSignalWithUnfilledLegsCounted() {
        assertEquals(0.3, ExploreShadow8347.fillRate8364(30, 70), 1e-9)
        assertEquals(1.0, ExploreShadow8347.fillRate8364(0, 0), 1e-9)
        assertEquals(1.0, ExploreShadow8347.fillRate8364(40, 0), 1e-9)
        // a pullback arm with a big mean but a 30 % fill rate earns 3 per signal; buying NOW earns 4
        val pull = ExploreShadow8347.Choice8351(P, R, null, null, proof(14.0, 2.0), fillRate = 0.3)
        val now = ExploreShadow8347.Choice8351(E, R, null, null, proof(8.0, 2.0))
        assertEquals(3.0, ExploreShadow8347.perSignal8364(pull), 1e-9)
        assertEquals(4.0, ExploreShadow8347.perSignal8364(now), 1e-9)
        assertSame(now, ExploreShadow8347.pickBest8364(listOf(pull, now), listOf(pull, now)))
        // the band rule reads the rival per signal too: 6 > 3 + 1
        val band = ExploreShadow8347.Choice8351(E, R, "M", "MC_10K_100K", proof(10.0, 2.0))
        assertTrue(ExploreShadow8347.mCohortAdmissible8364(band, listOf(pull)))
        assertEquals(1.0, ExploreShadow8347.M_MARGIN_PT_8364, 0.0)
    }

    // ── 3. shadow fills priced like live ──

    @Test fun shadowFillPaysTheLaneChaseAndNoDoubleCountedExitSlippage() {
        assertEquals(2.9, ShadowFill8364.chasePct8364(false, Double.NaN), 1e-9)
        assertEquals(2.9, ShadowFill8364.chasePct8364(false, 0.4), 1e-9)       // fewer than 8 live fills: the model
        assertEquals(1.2, ShadowFill8364.chasePct8364(true, 1.2), 1e-9)        // measured
        assertEquals(0.0, ShadowFill8364.chasePct8364(true, -3.0), 1e-9)       // a cheaper fill is never credited
        assertEquals(30.0, ShadowFill8364.chasePct8364(true, 80.0), 1e-9)
        assertEquals(1.029, ShadowFill8364.entryPx8364(1.0, 2.9), 1e-12)
        // the sell-side slippage is inside NetEdge8030.costPct8030: nothing extra is charged
        assertEquals(0.0, ShadowFill8364.EXIT_SLIP_EXTRA_PCT_8364, 0.0)
        assertEquals(1.0, ShadowFill8364.exitPx8364(1.0), 1e-12)
        assertEquals(10.0, ShadowFill8364.exitGrossPct8364(10.0), 1e-9)
        assertEquals(8.9, ShadowFill8364.exitGrossPct8364(10.0, 1.0), 1e-9)
        // a signal at 1.00 that marks 1.10: +10 % at the signal mark, +6.9 % from the chased fill, net of a 5 % round trip
        val entry = ShadowFill8364.entryPx8364(1.0, 2.9)
        val g = (1.10 / entry - 1.0) * 100.0
        assertEquals(6.9, g, 0.01)
        assertEquals(g - 5.0, EvidenceShadow8344.netPct8344(0.0, 1.0, ShadowFill8364.exitGrossPct8364(g), 5.0), 1e-9)
    }

    // ── 4. source pins ──

    @Test fun hookSitesArePinned() {
        val gate=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("canonicalRefusal8392(ts, l, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertTrue(gate.contains("FdgGate8365.inContext8365(ts, l, basis)"))
        assertTrue(gate.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs)"))
        assertTrue(gate.contains("if (paper && final == null) Rehearsal8339.notePaperAdmit8342"))
        assertFalse(gate.contains("entryRefusal8114"))
    }
}
