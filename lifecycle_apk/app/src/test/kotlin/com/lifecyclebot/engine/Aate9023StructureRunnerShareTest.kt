package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.GateOutcome8418
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9023 — the structural-risk judgement (stage RUG_PRONE) binds only while its record shows it is not refusing the
 * runners: a losing mean with 5 %+ of the refused coins running is a door that costs the big winners.
 */
class Aate9023StructureRunnerShareTest {
    @Test fun aLosingMeanAloneNoLongerBinds() {
        assertTrue("losing and no runners among what it refused: it protects", GateOutcome8418.structureBinds9023(100.0, -12.0, 2.0, 2.0))
        assertFalse("losing on the mean, 11 % of what it refused ran", GateOutcome8418.structureBinds9023(100.0, -12.0, 2.0, 11.0))
        assertFalse("exactly at the share", GateOutcome8418.structureBinds9023(100.0, -12.0, 2.0, 5.0))
        assertFalse("not proven losing: released as before", GateOutcome8418.structureBinds9023(100.0, 3.1, 4.0, 0.0))
        assertFalse(GateOutcome8418.structureBinds9023(0.0, -5.0, 1.0, 0.0))
        assertEquals(0.05, GateOutcome8418.STRUCTURE_RUNNER_SHARE_9023, 0.0)
    }

    @Test fun theSafetyDoorIsNotThisJudgement() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/GateOutcome8418.kt").readText()
        assertTrue(g.contains("val binds = structureBinds9023(st.n, st.mean(), st.se(), st.runners)"))
        assertTrue("a thin record still binds", g.contains("if (st.n < 40.0) return true"))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue("safety is refused before structure is asked", adm.indexOf("\"SAFETY_REFUSED_8136\", true, c.selectedSetup)") in 0 until adm.indexOf("c.structuralRisk?.let {"))
    }
}
