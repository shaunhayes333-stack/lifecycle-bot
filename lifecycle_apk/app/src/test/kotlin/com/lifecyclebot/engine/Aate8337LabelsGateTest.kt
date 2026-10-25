package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexFlatMark8322
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8337 — owner fix block, labels and the 8114 gate: FIX 2 (a flat mark is a lost mark, never a -cost label), FIX 6
 * (historical 8114 runner tickets are honoured, subject to current downstream live safety), FIX 7 (inLegacy8326 reads 8114's
 * evidence: discovery-age cells, uncapped full-hive setup records), FIX 8 (chart BUY in 8114 mode goes through the 8114
 * decision), 10d (curve witness only within 5 s, else the mark is held, never dropped), 10e (runner lanes grade on the hour).
 */
class Aate8337LabelsGateTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun flatMarkIsLostNeverBookedAsACostLabel() {
        // the flat test itself (8322): no price other than the entry ever seen
        assertTrue(CortexFlatMark8322.flatMark8322(0.0, 0.0, 0.0))
        assertFalse(CortexFlatMark8322.flatMark8322(0.0, 0.4, 0.0))
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        val sixty = labeler.substringAfter("private fun bookSixty7944(").substringBefore("// ── V5.0.7997 — PROGRESSIVE GRADING ──")
        val flatTest = sixty.indexOf("val flat8322 = com.lifecyclebot.engine.cortex.CortexFlatMark8322.flatMark8322(gross0, o.peakPct, o.minPct7962)")
        val booked = sixty.indexOf("book(o, 60, net, gross, w8324)")
        assertTrue(flatTest in 0 until booked)                                      // the flat test runs BEFORE the 60 slot is booked
        assertTrue(sixty.contains("markLost(o)                       // counted as lost mark, never as a -cost label"))
        assertTrue(sixty.contains("o.bookedNet7997 = Double.NaN"))
        // the shape / cell / specialist hooks are skipped on a flat mark; the Cortex still receives the flag (observed only there)
        val shape = sixty.indexOf("TradeShapeLearner7883.onLabel60(o.mint, o.lane, net, gross)")
        assertTrue(shape > sixty.indexOf("} else {") && shape > flatTest)
        val skip = sixty.indexOf("if (flat8322) return")
        assertTrue(skip > 0)
        assertTrue(skip > sixty.indexOf("Cortex7885.onLabel(o.mint, o.lane, 60, net, gross, fam8080, flat8322 = flat8322, witness8324 = w8324)"))
        assertTrue(skip < sixty.indexOf("CellAllocator7962.onLabel7962(o.cell, o.lane, o.setup7955, net)"))
        assertTrue(skip < sixty.indexOf("SpecialistMiner7972.onLabel7972(o.lane, o.mint, o.feats7972, net, gross, o.atMs)"))
    }

    @Test fun eightOneOneFourGatePreservesHistoricalRunnerTicketEvidence() {
        // The newer 8117 early-run predicate remains available to the CURRENT
        // strategy; it must not replace the original 8113/8114 runner tickets.
        assertTrue(LiveEdgeGate7877.earlyRun8117(runupFromLowPct = 85.0, chg5mPct = 40.0))
        assertFalse(LiveEdgeGate7877.earlyRun8117(runupFromLowPct = 1_238.0, chg5mPct = 30.0))
        assertFalse(LiveEdgeGate7877.earlyRun8117(runupFromLowPct = 90.0, chg5mPct = 140.0))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("internal fun earlyRun8117(runupFromLowPct: Double, chg5mPct: Double): Boolean ="))
        val gate = src("engine/truth/Entry8114Gate8326.kt")
        // 8113's liveRefusal used BOTH an active RunnerPlay ticket and a
        // lane-proven RunnerGrab; the chase veto used that same condition.
        assertTrue(gate.contains("RunnerPlay8018.ticketActive8018(ts.mint, nowMs)"))
        assertTrue(gate.contains("RunnerGrab7967.grab7967(ts.mint, nowMs)"))
        assertTrue(gate.contains("!runnerForming8114(ts, nowMs) &&"))
        // 8114 watch-first explicitly cleared a verified RunnerTicket8073.
        assertTrue(gate.contains("RunnerTicket8073.isTicket8073(ts.mint, nowMs)"))
        val watch = gate.substringAfter("private fun watchFirst8114(").substringBefore("private fun core8114(")
        assertTrue(watch.contains("RunnerTicket8073.isTicket8073(ts.mint, nowMs)"))
        assertTrue(watch.contains("TailHunter7996.ticket7996(ts, l, nowMs)"))      // the tail ticket (8114) stays
    }

    @Test fun legacyScopeKeysTheCellOnDiscoveryAge() {
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        val read = labeler.substringAfter("fun cellStatFor(ts: TokenState, lane: String").substringBefore("return executable8187(")
        assertTrue(read.contains("val ageMs = if (Entry8114Mode8326.legacyScope8326()) (if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L) else cellAgeMs8208(ts, nowMs)"))
        // the 8114 age is time since discovery; the 8208 key is the birth-witness age (unknown without a witness)
        val now = 1_000_000_000L
        val ts = com.lifecyclebot.data.TokenState(mint = "LegacyAge8337" + System.nanoTime(), symbol = "X")
        ts.addedToWatchlistAt = now - 60_000L
        assertEquals(-1L, ForwardReturnLabeler7731.cellAgeMs8208(ts, now))
        assertEquals(60_000L, if (ts.addedToWatchlistAt > 0L) now - ts.addedToWatchlistAt else -1L)
        assertEquals("AGE_UNKNOWN", ForwardReturnLabeler7731.ageBand(-1L))
    }

    @Test fun legacySetupRecordIsUncappedWithTheWholeHive() {
        val local = CortexLedger7885.labelStat8187()
        local.add(5_000.0, true); local.add(4_000.0, true); local.add(-20.0, false)
        val sum = local.sum; val n = local.n
        // the current (8187) record reads through the plausibility cap; the 8114 record reads sum / n, never suspect
        assertTrue(local.cap8187.isFinite())
        val legacy = LanePlaybook7907.uncappedStat8337(local, null)
        assertFalse(legacy.cap8187.isFinite())
        assertFalse(legacy.suspect8187())
        assertEquals(sum / n, legacy.mean(), 1e-9)
        assertEquals(sum / n, legacy.predictMean8322(), 1e-9)
        assertEquals(n, legacy.n, 1e-12)
        assertEquals(local.wins, legacy.wins, 1e-12)
        assertEquals(local.runners, legacy.runners, 1e-12)
        // the hive rides at FULL weight (8114): 800 network labels add 800, not the 8291 clamp of 10..60
        val hive = doubleArrayOf(800.0, 8_000.0, 400_000.0, 500.0, 40.0, 0.0)
        val merged = LanePlaybook7907.uncappedStat8337(local, hive)
        assertEquals(n + 800.0, merged.n, 1e-9)
        assertEquals(sum + 8_000.0, merged.sum, 1e-9)
        assertEquals(local.wins + 500.0, merged.wins, 1e-9)
        assertEquals(60.0, LanePlaybook7907.hiveWeight8291(800.0), 1e-12)          // what the current read lends at most
        val hiveOnly = LanePlaybook7907.uncappedStat8337(null, hive)
        assertEquals(800.0, hiveOnly.n, 1e-12)
        assertEquals(10.0, hiveOnly.mean(), 1e-9)
        // Raw tail remains unmodified, but a negative live setup has less executable authority.
        val rec = com.lifecyclebot.engine.cortex.Cortex7885.executableCopy8187(merged, 7.0, 0.0, Double.NaN)
        val governed = com.lifecyclebot.engine.cortex.LaneRealisedGovernor8295.governedCopy8295(
            rec, doubleArrayOf(2.0, -15.0), doubleArrayOf(4.0, -11.0))
        assertTrue(governed.n < merged.n)
        assertTrue(governed.mean() < merged.mean())
        assertEquals(sum + 8_000.0, merged.sum, 1e-9) // absolutely no label value rewritten
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue("legacy raw evidence must feed executable/realised feedback", pb.contains("val raw = (if (com.lifecyclebot.engine.truth.Entry8114Mode8326.legacyScope8326()) legacyStat8326(lane, setup)") &&
            pb.contains("LaneRealisedGovernor8295.governedCopy8295(exec, rr, laneRealised8295(lane))"))
        assertFalse(pb.contains("if (com.lifecyclebot.engine.truth.Entry8114Mode8326.legacyScope8326()) return raw"))
        val legacyFn = pb.substringAfter("private fun legacyStat8326(").substringBefore("internal fun uncappedStat8337(")
        assertFalse(legacyFn.contains("hiveScale8291"))
        assertFalse(legacyFn.contains("labelStat8187"))
    }

    @Test fun curveWitnessComparesOnlyWithinFiveSecondsAndHoldsOtherwise() {
        val f = ForwardReturnLabeler7731
        assertEquals(5_000L, f.CURVE_WITNESS_SYNC_MS_8337)
        assertEquals(0, f.curveWitness8337(1.0, 1.2, 10_000L, 10_000L))             // agrees: the mark stands
        assertEquals(0, f.curveWitness8337(1.0, 0.0, 10_000L, 10_000L))             // no curve read: the mark stands
        assertEquals(1, f.curveWitness8337(1.0, 3.0, 10_000L, 12_000L))             // 2 s apart and 3x off: contradicted
        assertEquals(1, f.curveWitness8337(1.0, 3.0, 12_000L, 7_000L))              // 5 s apart (either way): still compared
        assertEquals(2, f.curveWitness8337(1.0, 3.0, 10_000L, 20_000L))             // 10 s apart: held, not dropped
        assertEquals(2, f.curveWitness8337(3.0, 1.0, 40_000L, 10_000L))             // an old read disagreeing: held
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        val tick = labeler.substring(labeler.indexOf("fun tick(priceFor0:"))
        val mutation = tick.indexOf("o.rebased8076 = true")
        val held = tick.indexOf("if (curveHolds8337(o.mint, px, nowMs, mark8386.observedAtMs)) {")
        assertTrue(held in 0 until mutation)
        assertTrue(held > tick.indexOf("if (curveContradicts8165(o.mint, px, nowMs, mark8386.observedAtMs)) {"))
        val heldBlock = tick.substring(held, tick.indexOf("\n            }", held))   // the if-block's own closing brace, past the inline try/catch
        assertTrue(heldBlock.contains("continue"))
        assertFalse(heldBlock.contains("pending.remove"))                            // never dropped
        // the entry mark is compared on its own time
        assertTrue(labeler.contains("if (curveContradicts8165(ts.mint, px, nowMs, if (tsFresh) ts.lastPriceUpdate else nowMs)) {"))
        assertTrue(labeler.contains("CurveReserves7951.readingAtMs8173(mint)"))
    }

    @Test fun runnerLanesGradeOnTheHourSlot() {
        for (l in listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "PROJECT_SNIPER")) assertTrue(l, ForwardReturnLabeler7731.runnerSlot8337(l))
        assertFalse(ForwardReturnLabeler7731.runnerSlot8337("CASHGEN"))
        assertFalse(ForwardReturnLabeler7731.runnerSlot8337("QUALITY"))
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        val twoForty = labeler.substringAfter("private fun bookTwoForty7944(").substringBefore("private val exitRunSamples8006")
        // one-line slot selection: the hour's label revises a runner lane's 5-minute slot before the 240 slot is booked
        assertTrue(twoForty.contains("if (runnerSlot8337(o.lane)) reviseToHour8337(o, net0, gross0, net, gross)"))
        assertTrue(twoForty.indexOf("reviseToHour8337(") < twoForty.indexOf("book(o, 240, net, gross)"))
        val revise = labeler.substringAfter("private fun reviseToHour8337(").substringBefore("private fun bookTwoForty7944(").ifBlank {
            labeler.substringAfter("private fun reviseToHour8337(")
        }
        assertTrue(revise.contains("revise7997(o, oldNet, net, o.bookedGross7997, gross"))
        assertTrue(revise.contains("LanePlaybook7907.reviseLabel7997(o.mint, o.lane, oldLong8080, long8080.first"))
        assertTrue(revise.contains("kotlin.math.abs(net - oldNet) < 0.5) return"))   // a checkpoint that already moved it is not revised twice
    }

    @Test fun chartBuyInEightOneOneFourModeGoesThroughTheDecision() {
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("if (Entry8114Mode8326.liveActive8326(lane, paper)) return LiveEdgeGate7877.liveRefusal(ts, lane, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertFalse(plan.contains("if (Entry8114Mode8326.liveActive8326(lane, paper)) return null"))
        val chart = plan.substringAfter("if (LiveEdgeGate7877.chartAdmits7950(ts, lane, nowMs)) {").substringBefore("}")
        assertTrue(chart.contains("LiveEdgeGate7877.liveRefusal("))
        assertTrue(chart.contains("chartPlanRefusal8136(ts, lane, paper, nowMs, candidateVersion, candidateAdmission)"))
    }
}
