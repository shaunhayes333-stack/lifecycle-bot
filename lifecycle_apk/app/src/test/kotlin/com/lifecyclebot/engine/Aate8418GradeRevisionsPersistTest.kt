package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexOutcomeDisposition8395
import com.lifecyclebot.engine.cortex.CortexOutcomeProof8395
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.8418 — a grade record's revision count is saved with the Cortex grade book and survives a restart. */
class Aate8418GradeRevisionsPersistTest {
    private val at = 2_000_000L
    private val id = "rev8418|MOONSHOT|$at"

    private fun book() = CortexGradeBook8144().also { b -> b.runnerLane8322 = { l -> l == "MOONSHOT" } }

    private fun deliver(b: CortexGradeBook8144, minutes: Int, net: Double): CortexOutcomeDisposition8395 {
        val p = CortexOutcomeProof8395(id, minutes * 60_000L, at + minutes * 60_000L, net, net + 5.0)
        return b.labelOutcome8395(id, net, p.grossPct, net, true, p.horizonMs, p.observedAtMs, p.observedAtMs, p)
    }

    @Test fun revisionCountSurvivesARestartAndOldRowsLoad() {
        val live = book()
        live.addDecision(CortexGradeRecord8144('D', id, "rev8418|MOONSHOT", at, "MOONSHOT", "BASE", "TREND",
            "EARLY", "LOW", "SMALL", 2, 7.5, true, true, false))
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST, deliver(live, 5, 4.0))
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_REVISION, deliver(live, 60, 12.0))
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_REVISION, deliver(live, 240, 30.0))
        assertEquals(2, live.decision(id)!!.revisions8272)

        val encoded = live.encode()
        val restored = book().also { it.decode(encoded) }
        assertEquals(2, restored.decision(id)!!.revisions8272)
        assertEquals(30.0, restored.decision(id)!!.executableRevisedPct, 1e-4)
        // Counting continues from the restored value, not from 0.
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_REVISION, deliver(restored, 300, 33.0))
        assertEquals(3, restored.decision(id)!!.revisions8272)

        // A row saved before 8418 (46 fields, no count) still loads; its count reads 0 as it always did.
        val fs = '\u001F'
        val fields = encoded.split(fs)
        assertEquals(47, fields.size)
        val legacy = book().also { it.decode(fields.dropLast(1).joinToString(fs.toString())) }
        assertEquals(0, legacy.decision(id)!!.revisions8272)
        assertEquals(30.0, legacy.decision(id)!!.executableRevisedPct, 1e-4)
    }
}
