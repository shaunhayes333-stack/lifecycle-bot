package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.Entry8114Gate8326
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

    @Test fun liveAdmitWithoutAFiredNamedSetupIsRefused() {
        val lane = "MOONSHOT"
        val why = "LIVE_NO_FIRED_SETUP_8364_MOONSHOT"
        // superseded by V5.0.8370 — owner restored 8114 behaviour ("No fired-setup rule"): the classification below is
        // telemetry (counted as LIVE_NO_FIRED_SETUP_8364_<lane>), never a binding live refusal.
        assertFalse(src("engine/truth/Entry8114Mode8326.kt").contains("Entry8114Gate8326.liveFiredSetup" + "Refusal8364("))
        assertEquals(why, Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, null, emptyList()))
        assertEquals(why, Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, "NO_TRIGGER", listOf("NO_TRIGGER")))
        assertEquals(why, Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, "DATA_INCOMPLETE", emptyList()))
        assertEquals(why, Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, "UNK", listOf("UNK", "NONE")))
        // a named setup that did not fire on this coin is not a fired setup
        assertEquals(why, Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, "IGNITION_5M", emptyList()))
        assertNull(Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, "IGNITION_5M", listOf("ignition_5m")))
        // another named setup that fired admits
        assertNull(Entry8114Gate8326.noFiredSetupRefusal8364(lane, false, null, listOf("NO_TRIGGER", "BREAKOUT_RETEST")))
        assertEquals("BREAKOUT_RETEST", Entry8114Gate8326.firedSetup8364("DATA_INCOMPLETE", listOf("BREAKOUT_RETEST")))
        assertEquals("IGNITION_5M", Entry8114Gate8326.firedSetup8364("IGNITION_5M", listOf("BREAKOUT_RETEST", "IGNITION_5M")))
        assertFalse(Entry8114Gate8326.namedSetup8364(null))
        assertFalse(Entry8114Gate8326.namedSetup8364("  "))
        assertFalse(Entry8114Gate8326.namedSetup8364("matches_unrankable"))
    }

    @Test fun paperIsNeverRefusedByTheFiredSetupRule() {
        assertNull(Entry8114Gate8326.noFiredSetupRefusal8364("MOONSHOT", true, null, emptyList()))
        assertNull(Entry8114Gate8326.noFiredSetupRefusal8364("SHITCOIN", true, "NO_TRIGGER", emptyList()))
        // the shadow stays wide (8360): every non-safety no opens
        assertEquals(100, ExploreShadow8347.REJECT_SAMPLE_PCT_8347)
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
        val mode = src("engine/truth/Entry8114Mode8326.kt")
        // superseded by V5.0.8370 — owner restored 8114 behaviour ("No fired-setup rule"): the hook counts, it never refuses
        // (was "(if (paper8336) null else try { Entry8114Gate8326.liveFiredSetupRefusal8364(...) } ...)" in the verdict chain).
        val hook = "if (!paper8336) try { Entry8114Gate8326.noteFiredSetup8370(ts, l, old.setup, nowMs) } catch (_: Throwable) {}"
        assertTrue("live only, counted", mode.contains(hook))
        assertFalse("not in the verdict chain", mode.contains("Entry8114Gate8326.liveFiredSetup" + "Refusal8364("))
        assertTrue("before the niche and the timing wait", mode.indexOf(hook) < mode.indexOf("ExploreShadow8347.nicheRefusal8351(ts, l,") &&
            mode.indexOf("val final0_8347 = finalVerdict8326(old.reason, now, realised)") < mode.indexOf(hook))
        val gate = src("engine/truth/Entry8114Gate8326.kt")
        assertTrue(gate.contains("LanePlaybook7907.matchedSetups8083(ts, lane, nowMs)") && gate.contains("const val NO_FIRED_SETUP_PREFIX_8364 = \"LIVE_NO_FIRED_SETUP_8364_\""))
        val ex = src("engine/truth/ExploreShadow8347.kt")
        assertTrue("ranked per signal at 2 SE", ex.contains("return pickBest8364(out, rivals8364)"))
        assertTrue("variants at the same 2 SE", ex.contains("if (variantAdoptable8364(it)) out += c"))
        assertTrue("expired legs counted", ex.indexOf("unfilled8364(t, leg, nowMs) }   // never filled: no trade, counted unfilled") > 0 &&
            ex.contains("else if (age >= CONFIRM_WAIT_MS_8347) { leg.dead = true; t.force8355 = true; unfilled8364(t, leg, nowMs) }"))
        assertTrue("open fills chased", ex.contains("val fillPx8364 = ShadowFill8364.laneEntryPx8364(px, lane)") &&
            ex.contains("it.active = true; it.entryPx = fillPx8364; it.entryAtMs = nowMs"))
        assertTrue("NOW arm chased", ex.contains("leg.entryPx = ShadowFill8364.laneEntryPx8364(t.signalPx, t.lane)"))
        assertTrue("niche reads fired setups", ex.contains("if (inNiche8364(c, setup, band, fired)) return null"))
        assertTrue("fill rate on the status line", ex.contains("legsUnfilled8364=") && ex.contains(" fill\${\"%.0f\".format(c.fillRate * 100.0)}%"))
        val ev = src("engine/truth/EvidenceShadow8344.kt")
        assertTrue(ev.contains("val fillPx8364 = ShadowFill8364.laneEntryPx8364(px, lane)") && ev.contains("Pos8344(mint, lane, fillPx8364, nowMs,"))
        val fill = src("engine/truth/ShadowFill8364.kt")
        assertTrue("measured chase only from live fills", fill.contains("EntryChase7961.laneLatencyMs8094(lane)") && fill.contains("EntryChase7961.lanePenaltyPct7961(lane)"))
    }
}
