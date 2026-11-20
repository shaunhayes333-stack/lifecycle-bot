package com.lifecyclebot.engine

import com.lifecyclebot.ui.ReportCopyDialog8198
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8892 — what the CPU is doing: on the owner's tap after a report save, 30 s of ART's sampling profile (method names
 * and times only), saved to Downloads/AATE and deleted from the app. Measurement only.
 */
class Aate8892CpuSampleTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theSampleIsARareTapNeverAutomaticAndLeavesNothingBehind() {
        assertEquals(30L, CpuSample8892.SECONDS_8892)
        assertEquals("one stack sample of every thread every 20 ms (the pause stays short)", 20_000, CpuSample8892.INTERVAL_US_8892)
        assertFalse(CpuSample8892.isRunning8892())
        val s = src("engine/CpuSample8892.kt")
        assertTrue(s.contains("android.os.Debug.startMethodTracingSampling(f.absolutePath, BUFFER_BYTES_8892, INTERVAL_US_8892)"))
        assertTrue("stopped whatever happens", s.contains("try { Thread.sleep(SECONDS_8892 * 1000L) } finally { android.os.Debug.stopMethodTracing() }"))
        assertTrue("deleted whatever happens", s.contains("try { file?.delete() } catch (_: Throwable) {}"))
        assertTrue("swept at start", src("AATEApp.kt").contains("com.lifecyclebot.engine.CpuSample8892.sweep8892(this)"))
        val dialog = src("ui/ReportCopyDialog8198.kt")
        assertTrue("only on the owner's tap, beside the memory map", dialog.contains("com.lifecyclebot.engine.CpuSample8892.start8892(app, save = { file, name -> saveDataset(app, file, name) })"))
        assertTrue(dialog.contains(".setNegativeButton(\"Not now\", null)"))
        val offer = ReportCopyDialog8198.heapMapOffer8866(300L, 512L)
        assertTrue(offer, offer.contains("CPU sample: 30 s") && offer.contains("method names and times only"))
        assertTrue(offer, offer.contains("never leaves the app and is deleted"))
        assertTrue(CpuSample8892.statusLine8892().startsWith("cpuSample8892[none taken"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("CpuSample8892.statusLine8892()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 81. What the CPU is doing (8892)"))
    }
}
