package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8016StrongClearsWatchFirstTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aProvenStrongReadClearsWatchFirst() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        val wf = g.substringAfter("private fun watchFirst7994(").substringBefore("private val planSkips7995")
        assertTrue(wf.contains("Cortex7885.overrulesEdgeRefusal(ts, l, why)"))
        assertTrue(wf.indexOf("overrulesEdgeRefusal(ts, l, why)") < wf.indexOf("watched7994.incrementAndGet()"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("refusal.contains(\"CELL_NEGATIVE\") || refusal.contains(\"BAND_NEGATIVE\")) && fraction < 1.0"))
    }

    @Test fun theRefusalReasonSurvivesARestart() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("o.reason7967.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '),"))
        assertTrue(f.contains("if (f.size >= 32) o.reason7967 = f[31]"))
    }
}
