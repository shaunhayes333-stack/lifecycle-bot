package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8415 — hour-graded lanes make their one Cortex model contribution at the hour, not on the 5-minute read. */
class Aate8415CortexHourGradeTest {
    @Test fun only_clean_hour_lane_labels_defer() {
        assertTrue(Cortex7885.deferHourRule8415(hourLane = true, accepted = true, authority = true, lateFirstPrice = false, lateRead = false))
        assertFalse("5-minute lanes grade at 5 minutes", Cortex7885.deferHourRule8415(false, true, true, false, false))
        assertFalse("unexecutable / repeat stays observed-only", Cortex7885.deferHourRule8415(true, true, false, false, false))
        assertFalse("rejected value stays observed-only", Cortex7885.deferHourRule8415(true, false, true, false, false))
        assertFalse("late first price stays observed-only", Cortex7885.deferHourRule8415(true, true, true, true, false))
        assertFalse("late voter read stays observed-only", Cortex7885.deferHourRule8415(true, true, true, false, true))
    }

    @Test fun fifteen_minute_checkpoint_is_not_the_hour() {
        assertFalse(Cortex7885.hourGradeDue8415(15L * 60_000L))
        assertTrue(Cortex7885.hourGradeDue8415(60L * 60_000L))
        assertTrue(Cortex7885.hourGradeDue8415(4L * 3_600_000L))
    }

    @Test fun wiring_grades_once_at_the_hour() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val onLabel = src.substringAfter("fun onLabel(mint: String").substringBefore("/** Every gradeable YES and NO teaches discovery")
        assertTrue(onLabel.indexOf("CORTEX_GRADE_DEFERRED_TO_HOUR_8415") in 0 until onLabel.indexOf("gradeDecision8372(key, p, net8187"))
        // a flat 5-minute mark no longer disqualifies an hour-lane coin: deferral is decided before the flat filing
        assertTrue(onLabel.indexOf("hourDeferred8415(") < onLabel.indexOf("CortexFlatMark8322.observedOnly8322("))
        val revise = src.substringAfter("fun reviseLabel8006(").substringBefore("/** Replace the original weighted contribution")
        assertTrue(revise.contains("CORTEX_HOUR_GRADE_NOT_DUE_8415"))
        assertTrue(revise.contains("gradeDecision8372(k, original, newNet, newGross, witnessed)"))
        assertTrue(revise.contains("awaitsHourGrade8417(id, record)"))
        assertTrue(revise.indexOf("deferred8415") < revise.indexOf("reviseGradeOnly8324("))
    }
}
