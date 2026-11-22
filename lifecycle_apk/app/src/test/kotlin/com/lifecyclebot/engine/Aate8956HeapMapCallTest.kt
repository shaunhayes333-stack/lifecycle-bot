package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8956 — 8955 did not compile: its new budget parameter followed the stage callback, so the trailing lambda bound it. */
class Aate8956HeapMapCallTest {
    @Test fun theStageCallbackStaysTheLastParameter() {
        val core = File("src/main/kotlin/com/lifecyclebot/engine/HeapMapCore8866.kt").readText()
        assertTrue(core.contains("holderBudgetMs8955: Long = HOLDER_BUDGET_MS_8955, progress: (String) -> Unit = {}): Result {"))
    }
}
