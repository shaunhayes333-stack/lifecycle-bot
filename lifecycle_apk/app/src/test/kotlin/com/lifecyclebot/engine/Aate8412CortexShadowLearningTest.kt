package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8412 / 8420 — clean shadow exits (executable or not) train the Cortex models, marked shadow, never as authority. */
class Aate8412CortexShadowLearningTest {
    @Test fun non_executable_shadow_rows_are_learned_without_becoming_live_authority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val fn = src.substringAfter("fun onShadowOutcome8372(").substringBefore("/** Pure (V5.0.8420): a shadow exit trains")
        assertTrue(fn.contains("gradeModelsLocked8372(key8420, p8420, netPct, grossPct, false, shadowExit8372 = true)"))
        assertTrue(fn.contains("SHADOW_MODEL_LEARNED_NON_EXECUTABLE_8420"))
        assertTrue(fn.contains("outcomeBook8372.finishedShadow(shadowId, learned = true, trainedId = targetId8420)"))
        // the learnable test does not depend on executability; late/stale/non-finite stay report-only
        assertTrue(Cortex7885.shadowLearnable8420(false, false, 12.0, 14.0))
        assertFalse(Cortex7885.shadowLearnable8420(true, false, 12.0, 14.0))
        assertFalse(Cortex7885.shadowLearnable8420(false, true, 12.0, 14.0))
        assertFalse(Cortex7885.shadowLearnable8420(false, false, Double.NaN, 14.0))
    }
}
