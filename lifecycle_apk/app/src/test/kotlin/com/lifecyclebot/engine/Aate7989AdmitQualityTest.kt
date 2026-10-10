package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartReader7950
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7989AdmitQualityTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun fifteenSecondReadsNeverAdmit() {
        val fine = ChartReader7950.Read(null, 30, 0.9, false, 1L, 0L, colorBuy7968 = true, fine7982 = true)
        assertFalse(ChartReader7950.buySignal(fine))
        assertTrue(src("engine/chart/ChartReader7950.kt").contains("if (r.fine7982) return false"))
    }

    @Test fun grabRecordPersists() {
        val rg = src("engine/RunnerGrab7967.kt")
        assertTrue(rg.contains("\"runner_grab_7989.txt\""))
        assertTrue(rg.contains("ensureRecord7989()\n        gradeDue(nowMs)"))
        assertTrue(src("engine/truth/LearningResetSweep7781.kt").contains("\"runner_grab_7989.txt\""))
    }
}
