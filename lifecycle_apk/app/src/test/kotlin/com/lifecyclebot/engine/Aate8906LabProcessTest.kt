package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8906 — the lab process: offline exports on their own heap. The app does nothing else in it; the trading process
 * falls back to its own export when the lab does not answer.
 */
class Aate8906LabProcessTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun onlyTheLabProcessNameIsTheLab() {
        assertTrue(LabProcess8906.isLabName8906("com.lifecyclebot.aate:lab8906", "com.lifecyclebot.aate"))
        assertFalse(LabProcess8906.isLabName8906("com.lifecyclebot.aate", "com.lifecyclebot.aate"))
        assertFalse(LabProcess8906.isLabName8906("com.lifecyclebot.aate:other", "com.lifecyclebot.aate"))
    }

    @Test fun theMarkerSaysExportedNoneOrFailed() {
        assertEquals(true to null, LabProcess8906.readMarker8906("NONE"))
        assertEquals(false to null, LabProcess8906.readMarker8906("FAIL"))
        assertEquals(false to null, LabProcess8906.readMarker8906(""))
        assertEquals("a path that is not there is not handled", false to null, LabProcess8906.readMarker8906("/nonexistent/lab8906.jsonl"))
        val f = File.createTempFile("lab8906", ".jsonl")
        try { assertEquals(true to f, LabProcess8906.readMarker8906(f.absolutePath + "\n")) } finally { f.delete() }
        assertNull(LabProcess8906.readMarker8906("NONE").second)
    }

    @Test fun theAppStopsAtOnceInTheLabAndTheSaveUsesIt() {
        val app = src("AATEApp.kt")
        val guard = app.indexOf("if (com.lifecyclebot.engine.LabProcess8906.isLabProcess8906(this)) return")
        assertTrue(guard > 0)
        assertTrue("before every sweep, crash mark, reset and guard", guard < app.indexOf("HeapMap8866.sweep8866(this)"))
        assertTrue(guard < app.indexOf("LearningResetSweep7781.applyPendingAtBoot(this)"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".engine.LabProcess8906\"") && manifest.contains("android:process=\":lab8906\""))
        assertTrue(src("ui/ReportCopyDialog8198.kt").contains("com.lifecyclebot.engine.LabProcess8906.launchHourExport8906(app)?.let { file ->"))
        val lab = src("engine/LabProcess8906.kt")
        assertTrue("the fallback is the export as before", lab.contains("return com.lifecyclebot.engine.truth.LaunchHour8628.export(app.cacheDir, System.currentTimeMillis())"))
        assertTrue(src("engine/truth/LaunchHour8628.kt").contains(".put(\"unitMismatchRejected8642\", unitMismatchRejected8906)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 94. The lab process, stage 1 (8906)"))
    }
}
