package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8891 — owner: "proceed with the required changes". On 20 Nov 8 lanes decided DQKmfa at 11:00:36; EXPRESS's hour
 * read saw the +767 % run, the other 7 read +0.78 % (the price minutes after the decision) — 37 of 214 rows in the 2x+
 * moments of that export missed the run. Before any label rule changes (they teach the learners), each hour row now carries
 * which check refused the marks after its last accepted one. Measurement only: no learner reads research rows.
 */
class Aate8891LabelStallTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theDiagnosticsAreOneJsonObjectOnTheResearchRow() {
        val d = ForwardReturnLabeler7731.diagJson8891(intArrayOf(12, 40, 3, 0, 1, 7, 0), 9, "OBSERVED_ONLY_VENUE_MISMATCH_8783",
            "PoolA111111111111111111111111111111111", "DEXSCREENER_PAIR", "PoolB\"2222222222222222222222222222222", "JUPITER", 3540L, "TICK")
        val o = JSONObject(d)
        assertEquals(12, o.getJSONObject("skips").getInt("ELIGIBILITY"))
        assertEquals(7, o.getJSONObject("skips").getInt("JUMP"))
        assertEquals(ForwardReturnLabeler7731.SKIP_COUNT_8891, o.getJSONObject("skips").length())
        assertEquals(ForwardReturnLabeler7731.SKIP_COUNT_8891, ForwardReturnLabeler7731.SKIP_NAMES_8891.size)
        assertEquals("OBSERVED_ONLY_VENUE_MISMATCH_8783", o.getString("lastSkip"))
        assertEquals("a quote in a venue never breaks the row", "PoolB2222222222222222222222222222222", o.getString("skipVenue"))
        assertEquals(3540L, o.getLong("lastAcceptAgoS")); assertEquals(9, o.getInt("accepted"))
        val row = JSONObject(ResearchRows8877.line8877(1L, 3_600_000L, "M", "CORE", false, "S", "X", 1.0, 2.0, 3.0, 4.0, 5.0, -2.3,
            0.7, 0.78, 0.78, -0.39, "R", d))
        assertEquals("OBSERVED_ONLY_VENUE_MISMATCH_8783", row.getJSONObject("diag8891").getString("lastSkip"))
        assertEquals(-2.3, row.getDouble("net"), 1e-9)
        val plain = JSONObject(ResearchRows8877.line8877(1L, 3_600_000L, "M", "CORE", false, "S", "X", 1.0, 2.0, 3.0, 4.0, 5.0, -2.3,
            0.7, 0.78, 0.78, -0.39, "R"))
        assertFalse("a row without diagnostics is the 8877 row", plain.has("diag8891"))
        assertFalse("text that is not an object is never spliced in", JSONObject(ResearchRows8877.line8877(1L, 1L, "M", "L", true, "", "",
            1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, "", "nope")).has("diag8891"))
    }

    @Test fun everyRefusalOfAMarkIsCountedAndTheStaleHourIsOnTheBrief() {
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        for (k in listOf("SKIP_ELIGIBILITY_8891", "SKIP_CACHE_8891", "SKIP_COPY_8891", "SKIP_CURVE_CONTRADICTED_8891",
                "SKIP_CURVE_HELD_8891", "SKIP_JUMP_8891", "SKIP_INELIGIBLE_8891"))
            assertTrue(k, lab.contains("skip8891(o, $k,"))
        assertTrue(lab.contains("o.accepted8891++"))
        assertTrue(lab.contains("diag8891(o, System.currentTimeMillis())))"))
        assertTrue(lab.contains("if (o.lastPxAtMs > 0L && System.currentTimeMillis() - o.lastPxAtMs > MARK_MAX_AGE_MS_7731)"))
        ResearchRows8877.noteStale8891("OBSERVED_ONLY_VENUE_MISMATCH_8783")
        assertTrue(ResearchRows8877.statusLine8877().contains("staleHour8891{OBSERVED_ONLY_VENUE_MISMATCH_8783="))
        assertTrue(ResearchRows8877.statusLine8877().startsWith("research8877[written="))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 80. Why an hour read stopped moving (8891)"))
    }
}
