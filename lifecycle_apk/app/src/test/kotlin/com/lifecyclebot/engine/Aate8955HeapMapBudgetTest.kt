package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8955 — the memory map always reports: holder tracing has a time budget, and every stage is logged. */
class Aate8955HeapMapBudgetTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun holderTracingStopsOnItsBudgetAndEveryStageIsLogged() {
        assertEquals(180_000L, HeapMapCore8866.HOLDER_BUDGET_MS_8955)
        val core = src("engine/HeapMapCore8866.kt")
        assertTrue(core.contains("if (System.currentTimeMillis() - t0 >= holderBudgetMs8955) { holderBudgetHitAt8955 = level + 1; break }"))
        assertTrue("the class, name and object passes run before the budget is read",
            core.indexOf("pass(2, sampleEvery); passes++") < core.indexOf("if (System.currentTimeMillis() - t0 >= holderBudgetMs8955)"))
        assertTrue(core.contains("\" holderBudget8955=stoppedAtLevel\$holderBudgetHitAt8955\""))
        val map = src("engine/HeapMap8866.kt")
        assertTrue(map.contains("HEAP_MAP_PHASE_8866 dumping usedMb="))
        assertTrue(map.contains("HEAP_MAP_PHASE_8866 dumped dumpMs="))
        assertTrue(map.contains("\"HEAP_MAP_PHASE_8866 \$it +\${System.currentTimeMillis() - t0}ms\""))
        assertTrue(File("../../ci/runtime_device_checks_8418.sh").readText().contains("hm_detail+=\"; last stage: \${lastphase:-none logged}\""))
    }
}
