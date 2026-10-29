package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexLabelFeed8291
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexVoters7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8291 — Cortex end-to-end audit against the 5.0.8286 live evidence (AUDIT_CORTEX_8291.md). */
class Aate8291CortexAuditTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 1_760_000_000_000L
    private val h60 = 5L * 60_000L
    private val grace = 150_000L

    // ── labels mature: the off-watch batch asks for observations inside their 5-minute window first ──

    @Test fun theOffWatchBatchAsksInsideTheFiveMinuteWindowFirst() {
        val f = CortexLabelFeed8291
        assertEquals(0, f.offWatchRank8291(h60 + 10_000L, false, false, h60, grace))
        assertEquals(0, f.offWatchRank8291(h60 + grace, false, false, h60, grace))
        assertEquals(1, f.offWatchRank8291(20_000L, false, true, h60, grace))
        assertEquals(2, f.offWatchRank8291(h60 / 2, false, false, h60, grace))
        assertEquals(3, f.offWatchRank8291(70_000L, false, false, h60, grace))      // the 1-minute slot
        assertEquals(3, f.offWatchRank8291(h60 + 10_000L, true, false, h60, grace)) // already labelled
        assertEquals(3, f.offWatchRank8291(h60 + grace + 1L, false, false, h60, grace))
        // A younger observation inside its window outranks an older one at its 1-minute read; within a rank, oldest first.
        val inWindow = f.offWatchKey8291(0, t0 + 3_600_000L)
        val oneMinute = f.offWatchKey8291(3, t0)
        assertTrue(inWindow < oneMinute)
        assertTrue(f.offWatchKey8291(0, t0) < f.offWatchKey8291(0, t0 + 1L))
        assertTrue(f.offWatchKey8291(2, t0) < f.offWatchKey8291(3, t0 - 3_600_000L))
        val fl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(fl.contains("CortexLabelFeed8291.offWatchRank8291(age, o.done60, first8291, H60_MS_7731, graceFor7946(H60_MS_7731))"))
        assertTrue(fl.contains("val due7737 = rotateDue7809(dueUnpriced7737.sortedBy { it.second }.map { it.first } + freshUnpriced7737, nowMs)"))
    }

    // ── voter budget: core voters inline, cheapest first, the refresh fills the cache ──

    @Test fun votersAreReadCheapestFirstAndCoverageIsMeasured() {
        val v = CortexVoters7885
        assertArrayEquals(intArrayOf(2, 0, 3, 1), v.cheapestFirst8291(doubleArrayOf(1.0, 15.0, 0.1, 3.0)))
        assertArrayEquals(intArrayOf(1, 0), v.cheapestFirst8291(doubleArrayOf(2.0, Double.NaN)))
        assertEquals(1.0, v.coverage8291(0, 291), 0.0)
        assertEquals(0.5, v.coverage8291(100, 200), 1e-12)
        assertEquals(1.0, v.coverage8291(3, 0), 0.0)
    }

    @Test fun aDroppedRefreshNeverPinsItsVoterForever() {
        val v = CortexVoters7885
        assertTrue(v.refreshClaim8291(null, t0))
        assertFalse("queued moments ago", v.refreshClaim8291(t0 - 1_000L, t0))
        assertTrue("a stale claim is released", v.refreshClaim8291(t0 - v.REFRESH_STALE_MS_8291, t0))
        assertTrue("a claim from the future is not trusted", v.refreshClaim8291(t0 + 5_000L, t0))
        val s = src("engine/cortex/CortexVoters7885.kt")
        assertFalse(s.contains("ThreadPoolExecutor.DiscardPolicy()"))
        assertTrue(s.contains("(r as? RefreshTask8291)?.let { refreshJobs8291.remove(it.key) }; refreshDropped8187.incrementAndGet()"))
        assertTrue(s.contains("if (core8291) { tally8291?.let { it.core++ }; return timedRead8187(i, ts, lane, nowMs, failures) }"))
        assertTrue(s.contains("for (i in readOrder8291(wall)) {"))
        assertTrue(s.contains("coverageLine8291()"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("CortexVoters7885.readAll(ts, lane, nowMs, voterFailures, coreVoters8291(lane, nowMs))"))
        assertTrue(cx.contains("(ledger.seats[\"${'$'}{ids[i]}|${'$'}lane\"]?.authority() ?: 0.0) > 0.0"))
    }

    // ── calibration: phased in from 20 grades, shrink-only before 100 ──

    @Test fun calibrationShrinksAnOverconfidentCortexBeforeAHundredGrades() {
        val c = CortexCalibration7901()
        repeat(10) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; c.learn("L", d, 0.0, d * 0.5) }
        assertEquals("under 20 grades: identity", 1.0, c.slope("L"), 0.0)
        assertFalse(c.measured8291("L"))
        repeat(50) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; c.learn("L", d, 0.0, d * 0.5) }
        val s = c.slope("L")
        assertTrue("phased toward 0.5: $s", s > 0.7 && s < 0.9)
        assertTrue(c.measured8291("L"))
        assertTrue(c.line().contains("L:"))
        // An early stretch (outcomes twice the deviation) is not trusted before 100 grades.
        val up = CortexCalibration7901()
        repeat(60) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; up.learn("U", d, 0.0, d * 2.0) }
        assertEquals(1.0, up.slope("U"), 1e-12)
        // At and above MIN_N the 8169 estimator is unchanged.
        assertEquals(CortexCalibration7901.centredSlope8169(150.0, 10.0, 200.0, 5.0, 150.0),
            CortexCalibration7901.earlySlope8291(150.0, 10.0, 200.0, 5.0, 150.0), 0.0)
        assertEquals(1.0, CortexCalibration7901.earlySlope8291(19.0, 1.0, 50.0, 1.0, 10.0), 0.0)
        assertEquals(1.0, CortexCalibration7901.earlySlope8291(50.0, 0.0, 0.0, 0.0, 0.0), 0.0)  // no spread in d
    }

    // ── label hygiene: a learned veto is a judgement of a buyable coin ──

    @Test fun learnedVetoesAreExecutableAndHardBlocksAreNot() {
        val h = CortexLabelHygiene8144
        assertTrue(h.executableDecision8144(false, "TOXIC_PATTERN_SHITCOIN"))
        assertTrue(h.executableDecision8144(false, "LEARNED_TOXIC_LANE_MOONSHOT"))
        assertTrue(h.executableDecision8144(false, "TOXIC_BUCKET_HARD_VETO_6249"))
        assertTrue(h.executableDecision8144(false, "PAPER_EV_BUCKET_HARD_BLOCK_6405"))
        assertFalse(h.executableDecision8144(false, "HARD_BLOCK_RUGCHECK_FAILED"))
        assertFalse(h.executableDecision8144(false, "LEARNED_RUG_SIGNATURE"))       // a physical word beside the marker
        assertFalse(h.executableDecision8144(false, "TOXIC_CONCENTRATION"))
        assertFalse(h.executableDecision8144(false, "C1_HARD_SAFETY"))
        // 8372: a native judgement NO is still a buyable counterfactual, not a physical block.
        assertTrue(h.executableDecision8144(false, "TOXIC_PATTERN_X", false))
        assertFalse(h.executableDecision8144(false, "LEARNED_X_NATIVE_MCAP_TOO_HIGH"))
        assertNull(h.unexecClass8291(false, "EDGE_GATE_SOFT_SCORE"))
        assertNull(h.unexecClass8291(false, "SCORE_TOO_LOW", false))
        assertFalse(h.executableDecision8144(false, null, false))
        assertEquals("NATIVE_UNVERIFIED", h.unexecClass8291(false, null, false))
        assertEquals("NATIVE_MANDATE", h.unexecClass8291(false, "OUTSIDE_NATIVE_CAP"))
        assertEquals("RUG", h.unexecClass8291(false, "HARD_BLOCK_RUGCHECK_FAILED".replace("HARD_BLOCK_", "")))
        assertEquals("ROUTE", h.unexecClass8291(false, "WATCH_PROBATION_ROUTE_UNKNOWN"))
        assertNull(h.unexecClass8291(true, "SAFETY_X"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("unexecWhy8291=["))
    }

    // ── setup authority answers to realised money ──

    @Test fun losingRealisedClosesTakeSetupAuthorityAway() {
        val cx = Cortex7885
        assertEquals("no realised record: no cap", 1.0, cx.setupRealisedCap8291(0.0, Double.NaN, 0.0, Double.NaN, 0.0), 0.0)
        assertEquals("the setup's own closes lost", 0.0, cx.setupRealisedCap8291(2.0, -20.0, 2.0, -20.0, 2.0), 0.0)
        // 5.0.8286: SHITCOIN closed -33.8% and -16.7% under other setups; LAUNCH_LADDER_PROVEN had none of its own.
        assertEquals(0.0, cx.setupRealisedCap8291(0.0, Double.NaN, 2.0, -25.25, 2.0), 0.0)
        assertEquals("losing on the mean with wins among them", cx.LANE_LOSING_CAP_8291, cx.setupRealisedCap8291(0.0, Double.NaN, 3.0, -4.0, 1.0), 0.0)
        assertTrue(cx.LANE_LOSING_CAP_8291 < 0.5)   // under the LiveEdgeGate7877.setupAdmits7955 overrule bar
        assertEquals("one close is not a record", 1.0, cx.setupRealisedCap8291(1.0, -30.0, 1.0, -30.0, 1.0), 0.0)
        assertEquals("winning lane", 1.0, cx.setupRealisedCap8291(0.0, Double.NaN, 4.0, 12.0, 1.0), 0.0)
        val lr = cx.laneRealised8291(listOf(50.0, -33.8, -16.7, Double.NaN))
        assertEquals(3.0, lr[0], 0.0); assertEquals((50.0 - 33.8 - 16.7) / 3.0, lr[1], 1e-9); assertEquals(2.0, lr[2], 0.0)
        assertEquals(10.0, cx.laneRealised8291(List(25) { -1.0 })[0], 0.0)   // the recent closes only
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("if (st == null) null else doubleArrayOf(setupAuthority8291(lane, setup, st), st.n)"))
        assertTrue(pb.contains("val f = setupAuthority8291(lane, setup, st)"))
        assertTrue(pb.contains("realisedCap8291"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("realisedByLane8291[lane] = laneRealised8291(gradeBook8144.realisedSeries(lane, null, null, mode))"))
    }

    // ── the hive is a prior, never the local record ──

    @Test fun hiveDataNeverOutweighsLocalGrades() {
        val pb = LanePlaybook7907
        assertEquals(10.0, pb.hiveWeight8291(0.0), 0.0)
        assertEquals(40.0, pb.hiveWeight8291(40.0), 0.0)
        assertEquals(60.0, pb.hiveWeight8291(800.0), 0.0)
        assertEquals(10.0 / 1000.0, pb.hiveScale8291(0.0, 1000.0), 1e-12)   // foreign data alone stays under GRAD_MIN_N_7955
        assertTrue(1000.0 * pb.hiveScale8291(0.0, 1000.0) < com.lifecyclebot.engine.cortex.CortexScoreboard7885.GRAD_MIN_N_7955)
        assertEquals(1.0, pb.hiveScale8291(30.0, 12.0), 0.0)
        assertEquals(0.0, pb.hiveScale8291(30.0, 0.0), 0.0)
        val cx = Cortex7885
        assertEquals(30.0 / 900.0, cx.hiveSeatScale8291(900.0), 1e-12)
        assertTrue(900.0 * cx.hiveSeatScale8291(900.0) < com.lifecyclebot.engine.cortex.CortexLedger7885.MIN_SCORED)
        assertEquals(1.0, cx.hiveSeatScale8291(20.0), 0.0)
        assertTrue(cx.hiveExportable8291(80, null))
        assertFalse("an inherited seat is not echoed back", cx.hiveExportable8291(45, 30))
        assertTrue(cx.hiveExportable8291(60, 30))
        val s = src("engine/cortex/Cortex7885.kt")
        assertTrue(s.contains("st.scored = (net[0] * ks).toInt()"))
        assertTrue(s.contains("if (!hiveExportable8291(st.scored, inheritedSeed8291[id])) continue"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("c.n += hive[0] * k; c.sum += hive[1] * k"))
    }
}
