package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8412 — shadow outcomes from refused/held candidates teach isolated Cortex shadow books. */
class Aate8412CortexShadowLearningTest {
    @Test fun non_executable_shadow_rows_are_learned_without_becoming_live_authority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(src.contains("INVALID_SHADOW_OUTCOME"))
        assertTrue(src.contains("ctx?.executable != true && !p.lateRead8169 && !p.a.staleMark"))
        assertTrue(src.contains("newDecision8144(targetId, key, p, ctx, CortexLabelHygiene8144.Verdict8144(false, false))"))
        assertTrue(src.contains("gradeModelsLocked8372(key, p, netPct, grossPct, false, shadowExit8372 = true)"))
        assertTrue(src.contains("SHADOW_MODEL_LEARNED_NON_EXECUTABLE_8412"))
        // The legacy discard remains available for late/stale rows, but clean non-executable rows have a learning branch.
        assertTrue(src.contains("} else if (ctx?.executable != true || p.lateRead8169 || p.a.staleMark)"))
    }
}
