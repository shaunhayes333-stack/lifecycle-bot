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
        val flatTest = sixty.indexOf("var flat8322 = com.lifecyclebot.engine.cortex.CortexFlatMark8322.flatMark8322(gross0, o.peakPct, o.minPct7962)")
        val booked = sixty.indexOf("book(o, 60, net, gross, w8324)")
        assertTrue(flatTest in 0 until booked)                                      // the flat test runs BEFORE the 60 slot is booked
        assertTrue(sixty.contains("markLost(o)                       // counted as lost mark, never as a -cost label"))
        assertTrue(sixty.contains("o.bookedNet7997 = Double.NaN"))
        // the shape / cell / specialist hooks are skipped on a flat mark; the Cortex still receives the flag (observed only there)
        val shape = sixty.indexOf("TradeShapeLearner7883.onLabel60(o.mint, o.lane, net, gross)")
        assertTrue(shape > sixty.indexOf("} else {") && shape > flatTest)
        val skip = sixty.indexOf("if (flat8322) return")
        assertTrue(skip > 0)
        assertTrue(skip > sixty.indexOf("stageForwardOutcome8404(o, net, gross, fam8080, flat8322, w8324)"))
        // superseded by V5.0.8418 — the label carries its decision id (LearnerAttribution8418: one booking per decision)
        assertTrue(skip < sixty.indexOf("CellAllocator7962.onLabel7962(o.cell, o.lane, o.setup7955, net, \"\${o.mint}|\${o.lane}|\${o.atMs}\")"))
        assertTrue(skip < sixty.indexOf("SpecialistMiner7972.onLabel7972(o.lane, o.mint, o.feats7972, net, gross, o.atMs)"))
    }

    @Test fun canonicalGatePreservesCurrentRunnerAndLockoutEvidence() {
        val g=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("com.lifecyclebot.engine.cortex.Cortex7885.entryRefusal(ts, lane, paper)"))
        assertTrue(g.contains("com.lifecyclebot.engine.RebuyLockout8019.refusal8019(ts.mint, nowMs)"))
        assertFalse(g.contains("entryRefusal8114"))
    }

    @Test fun cellUsesCanonicalTokenAgeWithoutASecondClock() {
        val s=src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(s.contains("val ageMs = cellAgeMs8208(ts, nowMs)"))
        assertFalse(s.contains("legacyScope8326"))
    }

    @Test fun oneSetupEvidenceReadPreservesHiveLimitsAndRealisedFeedback() {
        val s=src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(s.contains("val raw = statRaw8187(lane, setup) ?: return null"))
        assertTrue(s.contains("LaneRealisedGovernor8295.governedCopy8295(exec, rr, laneRealised8295(lane))"))
        assertTrue(s.contains("hiveScale8291"))
        assertFalse(s.contains("legacyStat8326"));assertFalse(s.contains("uncappedStat8337"))
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
        // V5.0.8406 — the hour slot belongs to every lane whose mandate is >= 20% (QUALITY 20, DIP 25); TREASURY (12%) keeps 5 min.
        assertTrue(ForwardReturnLabeler7731.runnerSlot8337("QUALITY"))
        assertFalse(ForwardReturnLabeler7731.runnerSlot8337("TREASURY"))
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

    @Test fun chartBuyUsesCanonicalPlanAdmission() {
        val s=src("engine/truth/TradePlan7739.kt")
        assertTrue(s.contains("return chartPlanRefusal8136(ts, lane, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertFalse(s.contains("Entry8114"))
    }
}
