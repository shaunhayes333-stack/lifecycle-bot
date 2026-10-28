package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8412 — shadow outcomes from refused/held candidates taught Cortex.
 * superseded by V5.0.8418 — 8412's branch graded the LIVE ledger / board / calibration / grade book from non-executable
 * shadow rows (gradeModelsLocked8372 with shadowExit8372 = true, SHADOW_MODEL_LEARNED_NON_EXECUTABLE_8412). Every shadow
 * outcome now books only the isolated shadow ledger (CortexShadowLedger8418); see Aate8418ShadowIsolationTest.
 */
class Aate8412CortexShadowLearningTest {
    @Test fun non_executable_shadow_rows_are_learned_without_becoming_live_authority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(src.contains("INVALID_SHADOW_OUTCOME"))
        val body = src.substringAfter("fun onShadowOutcome8372(").substringBefore("fun onShadowUnavailable8372(")
        assertTrue(body.contains("shadowOutcomeIsolated8418(shadowLedger8418, canon(lane)"))
        assertTrue(body.contains("outcomeBook8372.finishedShadow(shadowId, learned = false)"))
        assertFalse(body.contains("gradeModelsLocked8372("))
        assertFalse(body.contains("reviseModelsLocked8372("))
        assertFalse(body.contains("gradeBook8144.shadowOutcome8372("))
        assertFalse(body.contains("LanePlaybook7907.onShadowTerminal8372("))
    }
}
