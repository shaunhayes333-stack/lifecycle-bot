package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8069 — the Cortex panel on the Learning screen. */
class Aate8069CortexPanelTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun panelReadsTheCortexOffTheUiThread() {
        val a = src("ui/LearningCounterActivity.kt")
        assertTrue(a.contains("renderCortex8069()"))
        // The snapshot is taken on the IO handler, never inside renderAll (the Cortex locks its books to read them).
        val prewarm = a.substringAfter("private val backgroundPrewarm").substringBefore("private val refreshRunnable")
        assertTrue(prewarm.contains("Cortex7885.ui8069()"))
        assertTrue(prewarm.contains("LanePlaybook7907.setupRows8069(14)"))
        assertTrue(!a.substringAfter("private fun renderCortex8069()").contains("Cortex7885.ui8069()"))
    }

    @Test fun dataAccessorsExist() {
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("fun ui8069(): Ui8069?"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("fun setupRows8069(limit: Int = 16): List<SetupRow8069>"))
    }
}
