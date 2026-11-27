package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AuthorityTable8887
import com.lifecyclebot.engine.truth.FdgGate8365
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8887 — plan W2: every hour the device's own decision reads of the last 48 h (1 h / 4 h / 24 h), graded per lane on
 * each door, admitted / refused, stage, setup and the window's own quintiles of V3, forecast, cap and pool; ex-top-3 mean,
 * median and halves; deflated across the cells graded; one table.
 */
class Aate8887AuthorityTableTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    private fun row(t: Long, net: Double, lane: String = "CORE", admitted: Boolean = true, reason: String = "", v3: Double = Double.NaN) =
        AuthorityTable8887.Row8887(t, h, lane, admitted, "EXPANDING", "BASE_BREAK", Double.NaN, v3, Double.NaN, Double.NaN, net, reason)

    @Test fun aResearchLineIsReadByItsHeadThenParsedOnce() {
        val line = com.lifecyclebot.engine.truth.ResearchRows8877.line8877(now - 2 * h, h, "M", "core", false, "EXPANDING", "BASE",
            1.0, 60.0, 50_000.0, 9_000.0, 3.0, -4.0, -1.0, 5.0, 2.0, -9.0, "LIVE_STAGE_LOSES_8626_X")
        assertEquals(now - 2 * h to h, AuthorityTable8887.head8887(line))
        assertNull(AuthorityTable8887.head8887("{\"other\":1}"))
        val r = AuthorityTable8887.row8887(JSONObject(line))!!
        assertEquals("CORE", r.lane); assertFalse(r.admitted); assertEquals(-4.0, r.net, 0.0); assertEquals(60.0, r.v3, 0.0)
        assertTrue(AuthorityTable8887.inWindow8887(r, now))
        assertFalse("decided more than 48 h ago", AuthorityTable8887.inWindow8887(r.copy(atMs = now - 49 * h), now))
        assertFalse("its read is not in yet (the embargo)", AuthorityTable8887.inWindow8887(r.copy(atMs = now - h / 2), now))
    }

    @Test fun laterReadsKeepTheirDeadCoinsAndDropTheirLateOnes() {
        fun later(reason: String, net: Any) = JSONObject().put("t", now - 30 * h).put("horizonMs", 24 * h).put("lane", "MOONSHOT")
            .put("admitted", true).put("net", net).put("reason", reason)
        val priced = AuthorityTable8887.row8887(later("JUP_8884:ADMIT", 12.0))!!
        assertEquals("ADMIT", priced.reason); assertEquals(12.0, priced.net, 0.0)
        val dead = AuthorityTable8887.row8887(later("NO_PRICE_JUP_8884:ADMIT", JSONObject.NULL))!!
        assertTrue("an unroutable coin stays in as unpriced", dead.net.isNaN()); assertEquals("ADMIT", dead.reason)
        assertNull("a late read is not the horizon's read", AuthorityTable8887.row8887(later("LATE_READ_8884:ADMIT", 50.0)))
        for (horizon in listOf(h, 24 * h)) {
            val unknown = AuthorityTable8887.row8887(later("ADMIT", JSONObject.NULL).put("horizonMs", horizon))!!
            assertTrue("an explicit missing outcome stays in its original cohort at either horizon", unknown.net.isNaN())
            assertEquals("ADMIT", unknown.reason)
            assertEquals(horizon, unknown.horizonMs)
        }
        assertNull("a malformed row with no outcome field is not an explicit unpriced read",
            AuthorityTable8887.row8887(later("ADMIT", JSONObject.NULL).apply { remove("net") }))
    }

    @Test fun theVerdictNeedsTheMarginTheHalvesAndRowsTheUnpricedCannotFlatter() {
        val steady = (0 until 30).map { if (it % 2 == 0) 8.0 else 12.0 }
        val pays = AuthorityTable8887.grade8887(h, "CORE", "lane", "ALL", steady, 1.0)
        assertEquals(AuthorityTable8887.Verdict8887.PAYS, pays.verdict)
        assertEquals(10.0, pays.mean, 1e-12); assertEquals(10.0, pays.median, 1e-12)
        assertEquals((15 * 8.0 + 12 * 12.0) / 27.0, pays.exTop3, 1e-12)
        assertEquals("a wide family raises the margin", AuthorityTable8887.Verdict8887.UNPROVEN,
            AuthorityTable8887.grade8887(h, "CORE", "lane", "ALL", steady, 30.0).verdict)
        val lateOnly = List(15) { -5.0 } + List(15) { 30.0 }
        assertEquals("a cell paid by its later half only is not proven", AuthorityTable8887.Verdict8887.UNPROVEN,
            AuthorityTable8887.grade8887(h, "CORE", "lane", "ALL", lateOnly, 1.0).verdict)
        val withDead = List(20) { 10.0 } + List(10) { Double.NaN }
        val d = AuthorityTable8887.grade8887(h, "MOONSHOT", "lane", "ALL", withDead, 1.0)
        assertEquals("ten dead coins counted as total losses: not PAYS", AuthorityTable8887.Verdict8887.UNPROVEN, d.verdict)
        assertEquals(10, d.unpriced); assertEquals(10.0, d.mean, 0.0)
        val losing = (0 until 25).map { if (it % 2 == 0) -8.0 else -12.0 }
        assertEquals(AuthorityTable8887.Verdict8887.LOSES, AuthorityTable8887.grade8887(h, "CORE", "door", "X", losing, 1.0).verdict)
        assertEquals("under 20 rows nothing is proven", AuthorityTable8887.Verdict8887.UNPROVEN,
            AuthorityTable8887.grade8887(h, "CORE", "door", "X", losing.take(19), 1.0).verdict)
        assertEquals(-100.0, AuthorityTable8887.TOTAL_LOSS_8887, 0.0)
    }

    @Test fun theTableGradesEveryCellOfEachLaneOnTheWindowsOwnBands() {
        val rows = (0 until 25).map { i -> row(now - 40 * h + i * 60_000L, if (i % 2 == 0) -8.0 else -12.0, admitted = false,
            reason = "LIVE_STAGE_LOSES_8626_X", v3 = (i + 1).toDouble()) }
        val (cells, cuts) = AuthorityTable8887.table8887(h, rows)
        val door = FdgGate8365.gateKey8392("LIVE_STAGE_LOSES_8626_X")
        val doorCell = cells.single { it.factor == "door" }
        assertEquals(door, doorCell.value); assertEquals(25, doorCell.n)
        assertNotNull(cells.single { it.factor == "lane" && it.value == "ALL" })
        assertNotNull(cells.single { it.factor == "admitted" && it.value == "N" })
        assertEquals(listOf(6.0, 11.0, 16.0, 21.0), cuts.getValue("CORE|v3").toList())
        assertEquals(setOf("Q1", "Q2", "Q3", "Q4", "Q5"), cells.filter { it.factor == "v3" }.map { it.value }.toSet())
        assertEquals("Q1", AuthorityTable8887.band8887(1.0, cuts["CORE|v3"]))
        assertEquals("Q5", AuthorityTable8887.band8887(25.0, cuts["CORE|v3"]))
        assertNull(AuthorityTable8887.band8887(Double.NaN, cuts["CORE|v3"]))
        assertNull("no band without five readings", AuthorityTable8887.band8887(1.0, null))
        // five cells hold 20+ rows (lane, admitted, door, stage, setup): the margin is the Šidák z for five
        val z5 = com.lifecyclebot.perps.CryptoTrend8886.familyZ8886(5)
        assertEquals(AuthorityTable8887.Verdict8887.LOSES, doorCell.verdict)
        assertTrue(doorCell.mean + z5 * doorCell.se < 0.0)
        assertNull("before the first pass there is no verdict", AuthorityTable8887.verdict8887(h, "CORE", "door", door))
        assertNull(AuthorityTable8887.bandOf8887(h, "CORE", "v3", 1.0))
    }

    @Test fun theTableIsStartedPublishedAndSavedWithTheReport() {
        assertTrue(src("engine/truth/ResearchRows8877.kt").contains("try { AuthorityTable8887.ensureStarted8887() } catch (_: Throwable) {}"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("com.lifecyclebot.engine.truth.AuthorityTable8887.statusLine8887()"))
        assertTrue(src("ui/ReportCopyDialog8198.kt").contains("saveDataset(app, it, \"aate_authority_8887_\${System.currentTimeMillis()}.json\")"))
        val t = src("engine/truth/AuthorityTable8887.kt")
        assertTrue(t.contains("JobCost8813.measureBackground8817(\"authorityTable8887\")"))
        assertTrue(t.contains("if (h !in HORIZONS_8887 || t < nowMs - WINDOW_MS_8887 || t + h > nowMs) return@forEachLine"))
        assertTrue(AuthorityTable8887.statusLine8887().startsWith("authority8887["))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 76. The hourly authority table (8887)"))
    }
}
