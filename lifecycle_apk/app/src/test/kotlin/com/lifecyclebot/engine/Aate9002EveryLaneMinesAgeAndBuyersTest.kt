package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9002 — the research row carries the coin's age at the decision, and every lane's pocket miner reads age and the
 * 60 s buyer count as features. A value that was not read adds no feature, so older rows mine exactly as before.
 */
class Aate9002EveryLaneMinesAgeAndBuyersTest {
    private fun row(age: Double, flow: String = "") = ResearchRows8877.line8877(1L, 3_600_000L, "MINTpump", "MOONSHOT", true, "BASE_START", "S",
        1.0, 20.0, 15_000.0, 9_000.0, 2.8, 30.0, 32.8, 60.0, 60.0, -5.0, "R", "", flow, age)

    @Test fun theRowCarriesTheAgeOnlyWhenItWasKnown() {
        assertEquals(3.5, JSONObject(row(3.5)).getDouble("age"), 1e-9)
        assertFalse(JSONObject(row(Double.NaN)).has("age"))
        assertFalse(row(Double.NaN).contains("\"age\""))
        val both = JSONObject(row(3.5, "{\"buyers60\":42}"))
        assertEquals(3.5 to 42.0, LanePocket8934.rowExtras9002(both))
        val none = LanePocket8934.rowExtras9002(JSONObject(row(Double.NaN)))
        assertTrue(none.first.isNaN() && none.second.isNaN())
    }

    @Test fun ageAndBuyersAreFeaturesAndAnUnreadValueAddsNone() {
        val old = LanePocket8934.features8934("MINTpump", "BASE_START", "S", 20.0, 1.0, 9_000.0, 15_000.0)
        assertEquals(7, old.size)
        val now = LanePocket8934.features8934("MINTpump", "BASE_START", "S", 20.0, 1.0, 9_000.0, 15_000.0, 3.5, 42.0)
        assertEquals(old + listOf("age=UNDER_15M", "buyers=30-80"), now)
        assertEquals("9 alone and 36 pairs", 45, LanePocket8934.cellsOf8934(now).size)
        assertEquals(listOf("age=OVER_24H"), LanePocket8934.moreFeatures9002(5_000.0, Double.NaN))
        assertEquals(listOf("buyers=<10"), LanePocket8934.moreFeatures9002(Double.NaN, 0.0))
        assertEquals(listOf("age=15M_1H", "buyers=80+"), LanePocket8934.moreFeatures9002(15.0, 80.0))
    }

    @Test fun wired() {
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("it.age9002 = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) ?: Double.NaN } catch (_: Throwable) { Double.NaN }"))
        assertTrue(lab.contains("diag8891(o, System.currentTimeMillis()), o.flow8924, o.age9002))"))
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LanePocket8934.kt").readText()
        assertEquals(2, p.split("mcap, rowExtras9002(o).first, rowExtras9002(o).second)").size - 1)
        assertTrue(p.contains("features8934(ts.mint, stage, setup, raw.v3, predPct ?: Double.NaN, liq, ts.lastMcap, raw.ageMin, buyers9002).toSet()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 187. Every lane mines where its winners form (9002)"))
    }
}
