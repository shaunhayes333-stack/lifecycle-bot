package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8623 — 14:12 dump (8622): the paper book's profit was lost-mark closes booked at stale marks, and the heap died of a
 * voter graph that held no power on any lane.
 */
class Aate8623HonestPaperAndHeapTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aLostMarkIsNotAPaperExitPriceAndProvesNoLane() {
        val paper = src("engine/truth/MemePaperTrader8544.kt")
        // the unsold remainder of a lost-mark close is valued flat, never at the stale last mark
        assertTrue(paper.contains("p.banked += sold * (if (why == LOST_MARK_8623) 0.0 else g); p.held -= sold"))
        // and the close is kept out of every lane record
        val note = paper.substringAfter("private fun noteLane8544(").substringBefore("private fun addStat8572(")
        assertTrue(note.indexOf("if (c.why == LOST_MARK_8623)") < note.indexOf("addStat8572(c.lane, c)"))
        assertEquals("P8623|", MemePaperTrader8544.PRICED_PREFIX_8623)
        // promotion reads only the priced record; the bench may still read the older record while the priced one is thin
        val proven = paper.substringAfter("fun laneProven8572(").substringBefore("fun laneProvenLosing8608(")
        assertTrue(proven.contains("pricedProof8623(lane) ?: return false"))
        assertFalse(proven.contains("PROOF_PREFIX_8572"))
        val leads = paper.substringAfter("fun laneLeads8616(").substringBefore("/** Pure (V5.0.8616)")
        assertTrue(leads.contains("pricedProof8623(lane) ?: return false"))
        val bench = paper.substringAfter("fun laneProvenLosing8608(").substringBefore("fun laneLeads8616(")
        assertTrue(bench.contains("pricedProof8623(lane) ?: return false"))
        assertFalse(bench.contains("PROOF_PREFIX_8572"))
    }

    @Test fun everyOpenPaperPositionAsksForItsOwnMark() {
        val paper = src("engine/truth/MemePaperTrader8544.kt")
        val tick = paper.substringAfter("fun tick8544(").substringBefore("private fun trigger8544(")
        assertTrue(tick.contains("wantMark8623(p.mint, nowMs)"))
        assertTrue(paper.contains("KeylessMarks8359.noteWanted8359(mint, until)"))
        assertTrue(paper.contains("ForwardReturnLabeler7731.noteShadowWanted8442(mint, until)"))
    }

    @Test fun theVoterGraphStaysOffInProductionAndDoesNotReloadTheOomCheckpoint() {
        // The inverted voter graph has no live authority and repeatedly exhausted the phone.
        // superseded by V5.0.8684 (owner: "the Cortex returned to use"): production runs the BOUNDED sampled graph by default;
        // the unbounded graph stays test-only and "off" still retires it.
        assertEquals("sampled", Cortex7885.graphMode8684(null))
        assertEquals("off", Cortex7885.graphMode8684("off"))
        assertFalse(Cortex7885.voterGraphRetired8623(Cortex7885.graphMode8684(null)))
        assertTrue(Cortex7885.voterGraphBounded8644(Cortex7885.graphMode8684(null)))
        assertTrue(Cortex7885.voterGraphRetired8623(Cortex7885.graphMode8684("off")))
        assertTrue(Cortex7885.voterGraphRetired8623(null))
        assertTrue(Cortex7885.voterGraphRetired8623("off"))
        assertFalse(Cortex7885.voterGraphRetired8623("on"))
        assertFalse(Cortex7885.voterGraphRetired8623("sampled"))
        assertFalse(Cortex7885.voterGraphBounded8644(null))
        assertFalse(Cortex7885.voterGraphBounded8644("off"))
        assertFalse(Cortex7885.voterGraphBounded8644("on"))
        assertTrue(Cortex7885.voterGraphBounded8644("sampled"))
        assertTrue(Cortex7885.sampleDue8644(0L, 1_000L))
        assertFalse(Cortex7885.sampleDue8644(1_000L, 2_000L))
        // 8648's global 30-second ceiling left only 120 reads/hour over all lanes.
        // The same bounded quota now permits one worker read every six seconds.
        assertFalse(Cortex7885.sampleDue8644(1_000L, 6_999L))
        assertTrue(Cortex7885.sampleDue8644(1_000L, 7_000L))
        val c = src("engine/cortex/Cortex7885.kt")
        val assess = c.substringAfter("fun assess(ts: TokenState, laneRaw: String").substringBefore("assessCache[key]?.let")
        assertTrue("scan release still runs first", assess.indexOf("noteScored8418") < assess.indexOf("if (VOTER_GRAPH_RETIRED_8623) return null"))
        val capture = c.substringAfter("fun capture(ts: TokenState, labelLane: String").substringBefore("val captureKey8397")
        assertTrue("the playbook tag still belongs to every decision", capture.contains("LanePlaybook7907.capture(ts, labelLane, labelLane, nowMs, snap, sel, timing)"))
        assertTrue(capture.contains("ForwardReturnLabeler7731.freezeTiming8528(ts.mint, labelLane, nowMs, sel, timing)"))
        assertTrue(capture.contains("VOTER_GRAPH_RETIRED_8623 || unsampled8644"))
        assertTrue("repeated decisions cannot multiply pending voter captures", capture.contains("claimCapture8644(key8644, hot8644.atMs, nowMs)"))
        // V5.0.8649: the old prewarm-requires-proven-OOS loop was circular.
        // Bounded prewarm restores frozen decisions without taking the capture budget.
        val schedule = c.substringAfter("private fun cachedOrSchedule(").substringBefore("private fun operatorSource8522(")
        assertFalse("a cold lane must be allowed to learn", schedule.contains("!provenOverStack8539(laneRaw)"))
        assertTrue(schedule.indexOf("reservePrewarm8649(nowMs)") < schedule.indexOf("reserveSample8644(nowMs)"))
        assertTrue(schedule.contains("val frozenPrewarm8649 = try { decisionSnapshots8522.capture(ts, nowMs) }"))
        assertTrue(schedule.contains("assess(frozenPrewarm8649, laneRaw, nowMs, nowMs, sampleReserved8644 = true)"))
        // V5.0.8649 — the sampled 331-voter graph must never run inline in the
        // ForwardReturnLabeler decision callback. Only the already-bounded worker
        // may read it after freezing the immutable decision input.
        assertFalse("a heavy voter assessment must not block a live decision", capture.contains("assess("))
        assertTrue(capture.contains("val coldSampleReserved8649 = BOUNDED_VOTER_GRAPH_8644"))
        assertTrue(capture.contains("reserveSample8644(nowMs)"))
        val prepare = c.substringAfter("val captureKey8397").substringBefore("private fun captureNow(")
        assertTrue("capture input is frozen before enqueue", prepare.contains("val frozen8273 = decisionSnapshots8522.capture(ts, nowMs)"))
        assertTrue("the cold capture uses the reserved bounded slot", prepare.contains("ticket8397, coldSampleReserved8649)"))
        assertTrue("the worker does the expensive assessment", prepare.contains("sampleReserved8644 = sampleReserved8649"))
        assertTrue("each sampled read attaches only once", prepare.contains("CORTEX_LABEL_SAMPLE_DUPLICATE_8649"))
        assertTrue(prepare.indexOf("submitCaptureKeyed8187(ticket8397, admitted) {") < prepare.indexOf("sampleReserved8644 = sampleReserved8649"))
        assertEquals("CORTEX_PENDING_8644", com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.PENDING)
        assertTrue("the pending row is not read", c.contains("if (VOTER_GRAPH_RETIRED_8623) CortexForwardEpoch8521.readStoredBooks8623("))
        val drain = c.substringAfter("fun deliverForwardOutcome8404(").substringBefore("val frozenEpoch8521")
        assertTrue("retired outbox must be ACKed durably before decompressing old graphs",
            drain.contains("if (VOTER_GRAPH_RETIRED_8623) {") &&
                drain.contains("VOTER_GRAPH_RETIRED_NO_MODEL_8659") &&
                drain.contains("LearningPersistence.saveTogetherVerified8386(mapOf(receipt to archived))"))
        assertTrue("retirement must freeze a small epoch witness rather than live voter state",
            c.substringAfter("fun freezeForwardOutcome8404(").substringBefore("fun deliverForwardOutcome8404(")
                .contains("if (VOTER_GRAPH_RETIRED_8623) return ForwardCheckpoint8401.pack("))
        val books = com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.readStoredBooks8623({ k -> "row:" + k }, { false })!!
        assertEquals(null, books.pending)
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("it.systemProperty(\"aate.cortexVoterGraph8623\", \"on\")"))
    }

    @Test fun theShadowReportCountsInPlace() {
        val s = src("engine/truth/ExploreShadow8347.kt")
        val cohort = s.substringAfter("private fun bestCohort8360(").substringBefore("private data class Report8401")
        assertTrue(cohort.contains("countSumSince8623(k, sinceMs)"))
        assertFalse(cohort.contains("netsSince("))
    }

    @Test fun anOriginalMissingForwardReceiptAcksOnlyWhenDurableAndNeverForgesLearning() {
        val missing = """{"id":"mint|MOONSHOT|123","disposition":"ORIGINAL_PREDICTION_UNAVAILABLE"}"""
        val learned = """{"id":"mint|MOONSHOT|123","disposition":"LEARNED"}"""
        val observed = """{"id":"mint|MOONSHOT|123","disposition":"OBSERVED_ONLY"}"""
        val receipt = "CORTEX_FORWARD_RECEIPT_8404|mint|MOONSHOT|123"
        var writes = 0
        val saver: (Map<String, String>) -> Boolean = { rows ->
            writes++
            assertEquals(mapOf(receipt to missing), rows)
            true
        }
        assertTrue(Cortex7885.durableUnpairedForward8649(receipt, missing, saver))
        assertEquals(1, writes)
        assertFalse(Cortex7885.durableUnpairedForward8649(receipt, learned, saver))
        assertFalse(Cortex7885.durableUnpairedForward8649(receipt, observed, saver))
        assertFalse(Cortex7885.durableUnpairedForward8649(receipt, "{bad JSON", saver))
        assertEquals("no model-bearing receipt was separately acknowledged", 1, writes)
        assertFalse(Cortex7885.durableUnpairedForward8649(receipt, missing) { false })
        assertFalse(Cortex7885.durableUnpairedForward8649(receipt, missing) { throw IllegalStateException("DB closed") })
    }
}
