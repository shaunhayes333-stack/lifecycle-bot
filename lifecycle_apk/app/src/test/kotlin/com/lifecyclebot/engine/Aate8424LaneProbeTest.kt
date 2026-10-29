package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.LaneRealisedProbe8424
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8424 §A_LOSING_LANE_SIZES_DOWN_IT_DOES_NOT_HALT — 8422: MOONSHOT shut by three closes (n3/-21.3%), 749 refusals in
 * 8.5 minutes, no path back. A shut lane now probes one small-stake position at a time; a proven-losing lane once per two hours.
 */
class Aate8424LaneProbeTest {
    @After fun reset() { EntryEconomics8329.resetForTests8329() }

    private val three = listOf(-21.0, -13.7, -29.2)
    private val t0 = 1_800_000_000_000L

    @Test fun threeClosesShutTheLaneButProveNothing() {
        assertTrue("the 8295 shut rule is unchanged", EntryEconomics8329.laneShut8329(3.0, -21.3))
        assertFalse("three closes are not a proven record", LaneRealisedProbe8424.provenLosing8424(three))
        val s = LaneRealisedProbe8424.stats8424(three)
        assertEquals(3.0, s[0], 0.0)
        assertEquals(-21.3, s[1], 0.05)
        assertTrue(s[2] > 4.0)   // a three-sample SE of meme returns is points, not fractions
        // ten clear losses prove it; ten mixed closes whose mean + 2 SE straddles zero do not
        assertTrue(LaneRealisedProbe8424.provenLosing8424(List(10) { -20.0 + (it % 3) }))
        assertFalse(LaneRealisedProbe8424.provenLosing8424(List(10) { if (it % 2 == 0) -30.0 else 25.0 }))
        assertFalse(LaneRealisedProbe8424.provenLosing8424(List(9) { -40.0 }))
        assertEquals(10, LaneRealisedProbe8424.PROVEN_MIN_N_8424)
    }

    @Test fun aShutLaneProbesOneAtATimeAndAProvenLaneWaitsItsGap() {
        // thin shut, slot free: probe
        assertEquals(LaneRealisedProbe8424.Verdict8424.PROBE, LaneRealisedProbe8424.verdict8424(three, 0, -1L, -1L))
        // a live position already open in the lane, or another coin's fresh claim: occupied
        assertEquals(LaneRealisedProbe8424.Verdict8424.OCCUPIED, LaneRealisedProbe8424.verdict8424(three, 1, -1L, -1L))
        assertEquals(LaneRealisedProbe8424.Verdict8424.OCCUPIED, LaneRealisedProbe8424.verdict8424(three, 0, 10_000L, -1L))
        assertEquals("an expired claim frees the slot", LaneRealisedProbe8424.Verdict8424.PROBE,
            LaneRealisedProbe8424.verdict8424(three, 0, LaneRealisedProbe8424.CLAIM_MS_8424, -1L))
        // proven losing: one probe per gap, measured from the last close after the shut
        val proven = List(12) { -25.0 + (it % 4) }
        assertEquals(LaneRealisedProbe8424.Verdict8424.PROBE, LaneRealisedProbe8424.verdict8424(proven, 0, -1L, -1L))
        assertEquals(LaneRealisedProbe8424.Verdict8424.PROVEN_WAIT, LaneRealisedProbe8424.verdict8424(proven, 0, -1L, 60L * 60_000L))
        assertEquals(LaneRealisedProbe8424.Verdict8424.PROBE, LaneRealisedProbe8424.verdict8424(proven, 0, -1L, LaneRealisedProbe8424.PROVEN_PROBE_GAP_MS_8424))
        // a thin shut never waits the gap
        assertEquals(LaneRealisedProbe8424.Verdict8424.PROBE, LaneRealisedProbe8424.verdict8424(three, 0, -1L, 60L * 60_000L))
    }

    @Test fun theSlotIsClaimedByTheAdmittedCoinNotByEveryCoinEvaluated() {
        LaneRealisedProbe8424.resetForTests8424()
        val closes = listOf(Triple(t0 - 60_000L, -0.004, -21.0), Triple(t0 - 50_000L, -0.003, -13.7), Triple(t0 - 40_000L, -0.005, -29.2))
        val lane = "ZZ_PROBE_8424"
        // evaluations read the door; none of them takes the slot
        assertNull(LaneRealisedProbe8424.probe8424(lane, "mintA", closes, t0 - 30_000L, t0))
        assertNull("a second coin evaluated is not blocked by the first coin's evaluation", LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 1_000L))
        // the FDG admits mintA live: the slot is its
        LaneRealisedProbe8424.claim8424(lane, "mintA", t0 + 2_000L)
        assertNull("the admitted coin re-asked inside its claim keeps it", LaneRealisedProbe8424.probe8424(lane, "mintA", closes, t0 - 30_000L, t0 + 5_000L))
        val other = LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 6_000L)
        assertNotNull(other)
        assertTrue(other!!, other.startsWith(LaneRealisedProbe8424.OCCUPIED_8424))
        assertTrue("the refusal still reads as the lane-losing family (evidence shadow opens on it)", other.contains("LANE_REALISED_LOSING"))
        assertNotNull("a read-only ask (blank mint) sees the slot taken", LaneRealisedProbe8424.probe8424(lane, "", closes, t0 - 30_000L, t0 + 7_000L))
        assertNull("after the claim expires another coin may probe", LaneRealisedProbe8424.probe8424(lane, "mintB", closes, t0 - 30_000L, t0 + 2_000L + LaneRealisedProbe8424.CLAIM_MS_8424 + 1L))
        LaneRealisedProbe8424.claim8424(lane, "mintA", t0 + 3_000L)   // the same admission re-noted: not a second probe
        val line = LaneRealisedProbe8424.statusLine8424(t0 + 4_000L)
        assertTrue(line, line.startsWith("laneProbe8424[admitted=1 occupied=2"))
        LaneRealisedProbe8424.resetForTests8424()
    }

    @Test fun aProbeTradesAtTheSmallStakeBecauseALosingRecordNeverMatures() {
        val rets = three
        val sol = listOf(-0.004, -0.003, -0.005)
        assertEquals("no realised proof: the label share is capped to zero -> route-minimum stake (#36)", 0.0,
            LiveRiskPolicy7807.realisedProofKellyFrac8188(rets, sol), 0.0)
        val proven = List(12) { -25.0 + (it % 4) }
        assertEquals(0.0, LiveRiskPolicy7807.realisedProofKellyFrac8188(proven, proven.map { it / 5_000.0 }), 0.0)
    }

    @Test fun wiring() {
        val eco = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryEconomics8329.kt").readText()
        assertTrue(eco.contains("val probe8424 = LaneRealisedProbe8424.probe8424(l, mint, closes, s.sinceMs, nowMs) ?: return null"))
        assertTrue(eco.contains("fun laneRealisedRefusal8329(lane: String, nowMs: Long = System.currentTimeMillis(), mint: String = \"\"): String?"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(gate.contains("EntryEconomics8329.laneRealisedRefusal8329(l, nowMs, ts.mint)"))
        assertTrue("the slot is claimed on the live admission only", gate.contains("if (!paper && final == null) try { LaneRealisedProbe8424.claim8424(l, ts.mint, nowMs) } catch (_: Throwable) {}"))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("tailRefuted8295(c.laneRealised8325) && !c.laneProbe8424) return LIVE_LANE_LOSING_8325"))
        assertTrue(adm.contains("laneProbe8424 = live8290 && (try { EntryEconomics8329.laneRealisedRefusal8329(lane, nowMs, ts.mint) == null } catch (_: Throwable) { false })"))
        // the shut rule, the re-open rule and the shadow-only measurement of a losing lane are untouched
        assertTrue(eco.contains("if (reopens8329(s.sinceMs, closes)) {"))
        val reh = File("src/main/kotlin/com/lifecyclebot/engine/truth/Rehearsal8339.kt").readText()
        assertTrue(reh.contains("if (shadowOnly && reason == CandidateAdmission8136.LIVE_LANE_LOSING_8325) return false"))
    }
}

/** V5.0.8428 — the runner-trace summary names the gate that stopped a qualified lane (8422: wrongBy{QUALIFIED_LANE_STOPPED=65}). */
class Aate8428RunnerTraceKeyTest {
    @Test fun aStoppedQualifiedLaneNamesItsDoor() {
        val t = com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
        assertEquals("QUALIFIED_LANE_STOPPED:CANDIDATE_ADMISSION_8136_LIVE_LANE_REALISED_LOSING_8325",
            t.wrongKey8428("QUALIFIED_LANE_STOPPED:CANDIDATE_ADMISSION_8136_LIVE_LANE_REALISED_LOSING_8325@MOONSHOT extra"))
        assertEquals("ELECTION_SHADOWED:FDG_TIMING_ADVERSE_8136", t.wrongKey8428("ELECTION_SHADOWED:FDG_TIMING_ADVERSE_8136:detail"))
        assertEquals("QUALIFIED_LANE_STOPPED", t.wrongKey8428("QUALIFIED_LANE_STOPPED:"))
        assertEquals("GENERIC_PRIOR_OVER_QUALIFIED", t.wrongKey8428("GENERIC_PRIOR_OVER_QUALIFIED:whatever"))
        assertEquals("ADMITTED>NO_BUY_ATTEMPT", t.wrongKey8428("ADMITTED>NO_BUY_ATTEMPT"))
    }
}
