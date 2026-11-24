package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8877 — plan W8, first step: every decision's 60-minute read, with its peak, held peak and dip, is written as a research
 * row to its own file and exported; no learner reads it and the edge dataset's line window is not spent on it.
 */
class Aate8877ResearchRowsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aResearchRowIsTheDecisionsFactsBesideItsHour() {
        val o = JSONObject(ResearchRows8877.line8877(1_700_000_000_000L, 3_600_000L, "Mint\"X", "MOONSHOT", false, "EXPANDING", "IGNITION_5M",
            18.5, 31.0, 12_000.0, 30_000.0, 3.2, 412.0, 418.0, 960.0, 700.0, -12.0, "SETUP_RECORD_LOSING_8136"))
        assertEquals(3_600_000L, o.getLong("horizonMs"))
        assertEquals("MintX", o.getString("mint"))
        assertFalse(o.getBoolean("admitted"))
        assertEquals(960.0, o.getDouble("peakPct"), 1e-6)
        assertEquals(700.0, o.getDouble("heldPeakPct"), 1e-6)
        assertEquals("SETUP_RECORD_LOSING_8136", o.getString("reason"))
        val unread = JSONObject(ResearchRows8877.line8877(1L, 3_600_000L, "M", "CORE", true, "", "", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, 5.0, 8.0, 8.0, 0.0, 0.0, ""))
        assertTrue("an unread fact is null, never a number", unread.isNull("pred") && unread.isNull("v3") && unread.isNull("mcap"))
        assertFalse("it is never a training row of the edge dataset", ResearchRows8877.FILE_8877 == "edge_dataset_8537.jsonl")
    }

    @Test fun theHourReadWritesItAndTheReportCarriesIt() {
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        val fn = lab.substringAfter("private fun bookTwoForty7944(").substringBefore("private fun flatMarkNow8342(")
        val write = fn.indexOf("ResearchRows8877.note8877(ResearchRows8877.line8877(o.atMs, H240_MS_7731, o.mint, o.lane, o.admitted,")
        assertTrue("after the lost-mark return", write > fn.indexOf("if (flatMarkNow8342(o, gross0)) { flatMarks8322.incrementAndGet(); markLost(o); lostHour8983(o); return true }"))
        assertTrue("before the hour books", write in 0 until fn.indexOf("book(o, 240, net, gross)"))
        assertTrue(lab.contains("it.v38877 = it.edge8537?.raw?.v3 ?: Double.NaN"))
        assertTrue(src("ui/ReportCopyDialog8198.kt").contains("saveDataset(app, it, \"aate_edge_research_8877_\${System.currentTimeMillis()}.jsonl\")"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("ResearchRows8877.statusLine8877()"))
        assertTrue(ResearchRows8877.statusLine8877().startsWith("research8877[written="))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 66. Every decision is read at the hour (8877)"))
    }
}
