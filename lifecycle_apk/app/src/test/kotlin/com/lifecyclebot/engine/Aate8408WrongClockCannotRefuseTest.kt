package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneGradeHorizon8406
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8408 — a book filled on the 5-minute clock cannot refuse an hour-graded lane until its hour book exists. */
class Aate8408WrongClockCannotRefuseTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun five_minute_evidence_refuses_only_where_the_clock_is_right() {
        assertTrue(LaneGradeHorizon8406.fiveMinuteMayRefuse8408(hourGraded = false, hourGradedN = 0))      // TREASURY: its clock
        assertFalse(LaneGradeHorizon8406.fiveMinuteMayRefuse8408(hourGraded = true, hourGradedN = 0))      // QUALITY, no hour book yet
        assertFalse(LaneGradeHorizon8406.fiveMinuteMayRefuse8408(hourGraded = true, hourGradedN = 29))
        assertTrue(LaneGradeHorizon8406.fiveMinuteMayRefuse8408(hourGraded = true, hourGradedN = 30))      // the hour book exists
        // a 5-minute lane is never spared (its evidence is on the right clock)
        assertFalse(LaneGradeHorizon8406.wrongClockSpares8408("TREASURY", "TEST"))
        // an hour-graded lane with an empty hour book is spared, and the sparing is counted
        assertTrue(LaneGradeHorizon8406.wrongClockSpares8408("QUALITY", "TEST"))
        assertTrue(LaneGradeHorizon8406.hourBookLine8408().contains("TEST|QUALITY="))
    }

    @Test fun the_three_refusal_sites_consult_the_clock_and_the_hour_book_grows_from_the_hour_revision() {
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("laneProvenLosing7938(laneStat7938) && !runnerSetupFires7939(ts, l, nowMs) &&"))
        // superseded by V5.0.8418 — the 5-minute refusal keeps binding on hour-graded lanes until the hour book exists (one-sided
        // sparing withdrawn, review 8418: QUALITY -13.7% x274); the sites count it via refusalKept8418. Was: wrongClockSpares8408.
        assertTrue(gate.contains("""LaneGradeHorizon8406.refusalKept8418(l, "LANE_7938")"""))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("!LaneGradeHorizon8406.refusalKept8418(lane, \"PLAYBOOK_7907\")) why = null"))
        assertTrue("a refusal site is never spared now", LaneGradeHorizon8406.refusalKept8418("QUALITY", "TEST"))
        assertTrue(LaneGradeHorizon8406.refusalKept8418("TREASURY", "TEST"))
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt").substringAfter("private fun reviseToHour8337(")
        assertTrue(lab.substringBefore("private val exitRunSamples8006").contains("LaneGradeHorizon8406.noteHourGrade8408(o.lane)"))
        val phc = src("engine/PipelineHealthCollector.kt")
        assertTrue(phc.contains("LaneGradeHorizon8406.hourBookLine8408()"))
    }
}
