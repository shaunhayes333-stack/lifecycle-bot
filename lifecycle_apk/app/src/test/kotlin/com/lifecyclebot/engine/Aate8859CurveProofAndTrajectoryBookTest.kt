package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveContinuity8859
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.TrajectoryGrades8851
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8859 — the trajectory book gives up its least-used cell (not its oldest) and counts what it gives up; every horizon
 * that closes without a read is counted under its cause; and the curve's own proof of a quiet stretch is measured.
 */
class Aate8859CurveProofAndTrajectoryBookTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val t = TrajectoryGrades8851

    @After fun tearDown() { CurveContinuity8859.resetForTest8859() }

    /** One observed 1-minute read of [net] in the cell (LANE, STAGE, [setup], BAND). */
    private fun read(book: TrajectoryGrades8851.Book, setup: String, net: Double = 1.0) =
        book.observe("LANE", "STAGE", setup, "BAND", mutableSetOf(), 1_000L, 1_000L, 1_000L + t.ONE_MIN, 1_000L + t.ONE_MIN, true, net, false) { 60_000L }

    @Test fun atTheCapTheLeastUsedCellIsGivenUpNotTheOldest() {
        val book = TrajectoryGrades8851.Book()
        repeat(3) { read(book, "FIRST") }                       // the oldest cell, and an established one
        for (i in 1..4_095) read(book, "S$i")                   // the book is now at its 4,096-cell cap
        read(book, "FIRST")                                     // still in use
        read(book, "NEW")                                       // a 4,097th cell: one must go
        val rows = JSONObject(book.encode()).getJSONObject("rows")
        assertEquals("the established cell kept all four reads", 4, rows.getJSONArray("LANE|STAGE|FIRST|BAND|${t.ONE_MIN}").getInt(0))
        assertFalse("the cell no read had reached for the longest went", rows.has("LANE|STAGE|S1|BAND|${t.ONE_MIN}"))
        assertTrue(rows.has("LANE|STAGE|S2|BAND|${t.ONE_MIN}") && rows.has("LANE|STAGE|NEW|BAND|${t.ONE_MIN}"))
        assertEquals(1L, book.evictedCells); assertEquals(1L, book.evictedReads)
        assertTrue(book.line().contains("cells=4096 evicted8859=1/reads1]"))
        val restored = TrajectoryGrades8851.Book().also { it.restore(book.encode()) }
        assertEquals(1L, restored.evictedCells); assertEquals(1L, restored.evictedReads); assertEquals(book.observed, restored.observed)
    }

    @Test fun aClosedHorizonIsCountedUnderTheFirstRequirementItsMarkFailed() {
        val f = ForwardReturnLabeler7731
        assertEquals("ENTRY_UNFILLED", f.trajectoryMissCause8859(false, false, false, false, false, false))
        assertEquals("MARK_NOT_LIVE", f.trajectoryMissCause8859(true, false, true, true, true, true))
        assertEquals("BASIS_INELIGIBLE", f.trajectoryMissCause8859(true, true, false, true, true, true))
        assertEquals("BASIS_LATE", f.trajectoryMissCause8859(true, true, true, false, true, true))
        assertEquals("IMPLAUSIBLE", f.trajectoryMissCause8859(true, true, true, true, false, true))
        assertEquals("VENUE_UNSCOPED", f.trajectoryMissCause8859(true, true, true, true, true, false))
        assertEquals("NET_UNREADABLE", f.trajectoryMissCause8859(true, true, true, true, true, true))
        assertEquals(0, f.trajectoryDoneMask8859(emptySet()))
        assertEquals(0b101, f.trajectoryDoneMask8859(setOf(t.ONE_MIN, t.FIFTEEN_MIN)))
        assertEquals(0b111111, f.trajectoryDoneMask8859(t.HORIZONS.toSet()))
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        val grade = labeler.substringAfter("private fun gradeTrajectory8851").substringBefore("private fun bookEntryTarget8521")
        assertTrue(grade.indexOf("val doneBefore8859 = trajectoryDoneMask8859(o.trajectoryDone8851)") in 0 until grade.indexOf("trajectoryBook8851.observe("))
        assertTrue(grade.contains("if (cause == TRAJ_NO_MARK_8859) try { CurveContinuity8859.noteQuietRead8859(o.mint, o.atMs + h) }"))
        assertTrue(labeler.contains("CurveContinuity8859.noteUnfilled8859(o.mint, o.atMs + REBASE_MIN_MS_8076)"))
        assertTrue(labeler.contains("\${trajectoryBook8851.line()} \${trajectoryMissLine8859()}"))
    }

    @Test fun aTradeStatesTheReservesItStartedFrom() {
        // a buy of 1,000,000 tokens for 0.03 SOL that left 31.03 SOL / 1,036,000,000 tokens started from 31.00 / 1,037,000,000
        assertEquals(1_037_000_000.0, CurveContinuity8859.tokensBefore8859(1_036_000_000.0, 1_000_000.0, true), 0.0)
        assertEquals(31.00, CurveContinuity8859.solBefore8859(31.03, 0.03, true), 1e-12)
        // a sell puts the tokens back and takes SOL out
        assertEquals(1_036_000_000.0, CurveContinuity8859.tokensBefore8859(1_037_000_000.0, 1_000_000.0, false), 0.0)
        assertEquals(31.03, CurveContinuity8859.solBefore8859(31.00, 0.03, false), 1e-12)
        assertTrue(CurveContinuity8859.continuous8859(1_037_000_000.0, 1_036_000_000.0, 1_000_000.0, true))
        assertFalse("a trade this process never received moved the curve in between",
            CurveContinuity8859.continuous8859(1_037_000_000.0, 1_030_000_000.0, 1_000_000.0, true))
        assertFalse(CurveContinuity8859.continuous8859(1_037_000_000.0, 1_036_000_000.0, 0.0, true))
        assertTrue(CurveContinuity8859.TOKEN_TOLERANCE_8859 < 1.0 && CurveContinuity8859.MIN_QUIET_MS_8859 == 60_000L)
    }

    @Test fun twoPrintsThatJoinProveTheCurveStoodBetweenThem() {
        val m = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"; val t0 = 1_795_000_000_000L
        CurveContinuity8859.onFrame8859(m, t0, 31.00, 1_037_000_000.0, 1.0, 35_000_000.0, true)                // the coin's first print here
        assertNull("one print proves nothing", CurveContinuity8859.standingAt8859(m, t0 + 10_000L))
        // 90 s later a buy that starts exactly where the last print ended: the curve stood at 31 / 1.037e9 for those 90 s
        CurveContinuity8859.onFrame8859(m, t0 + 90_000L, 31.03, 1_036_000_000.0, 0.03, 1_000_000.0, true)
        assertEquals(31.00 / 1_037_000_000.0, CurveContinuity8859.standingAt8859(m, t0 + 5_000L)!!, 1e-18)
        assertEquals(31.00 / 1_037_000_000.0, CurveContinuity8859.standingAt8859(m, t0 + 89_999L)!!, 1e-18)
        assertNull("the stretch ends where the next print lands", CurveContinuity8859.standingAt8859(m, t0 + 90_000L))
        // 30 s later, joined but short: already covered by the mark age and the entry window, so not kept
        CurveContinuity8859.onFrame8859(m, t0 + 120_000L, 31.06, 1_035_000_000.0, 0.03, 1_000_000.0, true)
        assertNull(CurveContinuity8859.standingAt8859(m, t0 + 100_000L))
        // 5 min later a print that does NOT start where the last ended: a trade was missed, nothing is proven for the gap
        CurveContinuity8859.onFrame8859(m, t0 + 420_000L, 33.00, 975_000_000.0, 0.5, 15_000_000.0, true)
        assertNull(CurveContinuity8859.standingAt8859(m, t0 + 300_000L))
        val line = CurveContinuity8859.statusLine8859()
        assertTrue(line, line.contains("printPairs=3 continuous=2(66%) broken=1 solSideAgrees=100%") && line.contains("quietStretches>=60s=1 "))
        assertTrue(line.endsWith("measured only: no label, fill or decision reads it]"))
    }

    @Test fun aQuestionAboutTheTimeAfterTheNewestPrintWaitsForTheNextOne() {
        val m = "MintBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBpump"; val t0 = 1_795_000_000_000L
        CurveContinuity8859.onFrame8859(m, t0, 31.00, 1_037_000_000.0, 1.0, 35_000_000.0, true)
        CurveContinuity8859.noteQuietRead8859(m, t0 + 60_000L)          // a 1-minute read closed with no mark: nothing proves it yet
        CurveContinuity8859.noteUnfilled8859(m, t0 + 5_000L)
        CurveContinuity8859.noteQuietRead8859("NeverSeenOnTheCurveStream", t0)
        assertTrue(CurveContinuity8859.statusLine8859().contains("wouldPrice{unfilledEntries=0/1 quietReads=0/2 askedOffCurve=1}"))
        CurveContinuity8859.onFrame8859(m, t0 + 200_000L, 30.97, 1_038_000_000.0, 0.03, 1_000_000.0, false)   // a sell that joins
        assertTrue(CurveContinuity8859.statusLine8859().contains("wouldPrice{unfilledEntries=1/1 quietReads=1/2 askedOffCurve=1}"))
        // asked after the fact, inside a kept stretch: answered at once
        CurveContinuity8859.noteUnfilled8859(m, t0 + 100_000L)
        assertTrue(CurveContinuity8859.statusLine8859().contains("unfilledEntries=2/2"))
    }

    @Test fun everyDecodedPrintIsCheckedBeforeTheCareFilterAndNothingReadsTheProof() {
        val tape = src("network/HeliusCurveTape8354.kt").substringAfter("fun onTradeEvent8354")
        val hook = tape.indexOf("CurveContinuity8859.onFrame8859(mint, PumpSwapTape8307.eventMs8307(ev.timestampSec, nowMs),")
        assertTrue("after the duplicate is refused", hook > tape.indexOf("duplicateSignedLaunch8841(mint, sig, ev.isBuy, priceSol)"))
        assertTrue("before the care filter", hook in 0 until tape.indexOf("if (!cares8354(mint, nowMs)) {"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Curve continuity (§8859): "))
        // measured only: the standing price is read by no production code
        val readers = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "CurveContinuity8859.kt" }
            .count { it.readText().contains("standingAt8859") }
        assertEquals(0, readers)
    }
}
