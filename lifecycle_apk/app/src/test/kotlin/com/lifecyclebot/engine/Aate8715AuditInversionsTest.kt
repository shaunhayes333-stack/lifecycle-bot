package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8715 — the 8712 audit (edge dataset: 72,220 labelled decisions; capture 00:45). Admission ignored the one learned
 * signal that ranks outcomes, lane authority overrode 461 measured-losing refusals, labels read below -100 %, and the brief
 * called a runner-carried mean "doors SEPARATE winners from losers".
 */
class Aate8715AuditInversionsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun verdict(p: Double, authority: Boolean, refuses: Boolean) =
        EdgeModel8537.Verdict(p, authority, "SHITCOIN", 900L, 0, Double.NaN, Double.NaN, -8.0, refuses8715 = refuses)

    @Test fun theModelRefusesOnItsOwnRefusalRecord() {
        // V5.0.8750 (supersedes the 4-argument rule): 30+ coins each side, the refused losing after one SE, and the gap to the
        // kept coins beyond two standard errors of the difference
        assertTrue(EdgeModel8537.refusalAuthority8715(30, -10.0, 1.0, 30, -2.4, 1.0))
        assertFalse("too few refused", EdgeModel8537.refusalAuthority8715(29, -10.0, 1.0, 30, -2.4, 1.0))
        assertFalse("too few kept", EdgeModel8537.refusalAuthority8715(30, -10.0, 1.0, 29, -2.4, 1.0))
        assertFalse("not proven losing", EdgeModel8537.refusalAuthority8715(200, -1.0, 2.0, 200, 5.0, 1.0))
        assertFalse("no worse than what it keeps", EdgeModel8537.refusalAuthority8715(200, -10.0, 1.0, 200, -12.0, 1.0))
        // the 8748 report's PROJECT_SNIPER: -16.9 % ± 2.1 refused vs -16.1 % kept is not a separation
        assertFalse("inside the noise", EdgeModel8537.refusalAuthority8715(400, -16.9, 2.1, 400, -16.1, 1.5))
        val o = EdgeModel8537.Oos()
        repeat(80) { o.add(EdgeModel8537.target8567(-20.0), -15.0) }      // predicted to lose, lost
        o.add(EdgeModel8537.target8567(-20.0), 7_000.0)                    // one runner cannot acquit the cohort
        repeat(30) { o.add(EdgeModel8537.target8567(5.0), 0.0) }           // kept: flat
        assertEquals(81, o.refN); assertEquals(30, o.keepN)
        assertEquals((80 * -15.0 + EdgeModel8537.REFUSAL_CLIP_PCT_8715) / 81, o.refMean, 1e-9)
        assertTrue(EdgeModel8537.refusalAuthority8715(o.refN, o.refMean, o.refSe, o.keepN, o.keepMean, o.keepSe))
        // the calls: a refusal needs either authority; a positive pick still needs the picks to have paid
        assertEquals(EdgeModel8537.Call.NEGATIVE, EdgeModel8537.callOf(verdict(-3.0, authority = false, refuses = true)))
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(verdict(-3.0, authority = false, refuses = false)))
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(verdict(9.0, authority = false, refuses = true)))
        assertEquals(EdgeModel8537.Call.POSITIVE, EdgeModel8537.callOf(verdict(9.0, authority = true, refuses = false)))
    }

    @Test fun aRefusalOnlyAuthorityBindsLiveAndLeavesPaperTrading() {
        val base = CandidateAdmission8136.Candidate8136("m8715", "SHITCOIN", null,
            expectedGrossPct = 20.0, costPct = 4.5, genericScore = 80.0, genericFloor = 15.0)
        val v = verdict(-12.0, authority = false, refuses = true)
        assertEquals("paper keeps trading it", null, CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = v), null, mutableListOf()))
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
            CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = v, live = true), null, mutableListOf()))
        // with the picks proven too, it refuses in both books as before
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
            CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = verdict(-12.0, authority = true, refuses = false)), null, mutableListOf()))
        assertTrue(LaneAuthority8577.binding8577(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537))
    }

    @Test fun aLearnedDoorBindsOnceItsRecordProtects() {
        GateOutcome8418.resetForTests8418()
        val door = "WATCH_FIRST_7994_CELL_NEGATIVE"
        val key = com.lifecyclebot.engine.truth.FdgGate8365.gateKey8392(door)   // the record a door's labels are filed under
        assertFalse("no record: advisory", LaneAuthority8577.binding8577(door))
        repeat(39) { GateOutcome8418.book8418(key, "QUALITY", -9.0 + (it % 3), false) }
        assertFalse("39 graded: still advisory", LaneAuthority8577.binding8577(door))
        GateOutcome8418.book8418(key, "QUALITY", -9.0, false)
        assertTrue("40 graded, proven losing: binds", LaneAuthority8577.binding8577(door))
        // a door whose refused coins do not lose stays advisory, and one that refuses winners is not protecting
        repeat(60) { GateOutcome8418.book8418(com.lifecyclebot.engine.truth.FdgGate8365.gateKey8392("CHASING_SPIKE_8065"), "EXPRESS", if (it % 2 == 0) 12.0 else -10.0, false) }
        assertFalse(LaneAuthority8577.binding8577("CHASING_SPIKE_8065"))
        assertFalse(GateOutcome8418.protects8715(null))
        GateOutcome8418.resetForTests8418()
        // the overridden door is filed on the coin's forward label
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("ForwardReturnLabeler7731.noteOverriddenDoor8715(ts.mint, l, doors8577!!, nowMs)"))
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("GateOutcome8418.book8418(o.advisoryGate8715, o.lane, net, gross >= RUNNER_PCT_7731, o.mint)"))
        assertTrue(lab.contains(".put(\"advisoryGate\", o.advisoryGate8715)"))
    }

    @Test fun aLabelNeverLosesMoreThanTheStake() {
        assertEquals(-100.0, ForwardReturnLabeler7731.netPct(1.0, 0.02, 60.0), 0.0)
        assertEquals(7.5, ForwardReturnLabeler7731.netPct(1.0, 1.10, 2.5), 1e-9)
        assertEquals(-12.5, ForwardReturnLabeler7731.netPct(1.0, 0.90, 2.5), 1e-9)
    }

    @Test fun theBriefDoesNotCallARunnerCarriedMeanSeparation() {
        val b = src("engine/DiagnosisBrief8438.kt")
        assertTrue(b.contains("adm.meanNet60Pct - ref.meanNet60Pct > gapNoise8715"))
        assertTrue(b.contains("!admTailOnly8715 &&"))
        assertFalse(b.contains("adm.n60 >= 30 && ref.n60 >= 30 && adm.meanNet60Pct > ref.meanNet60Pct"))
    }
}
