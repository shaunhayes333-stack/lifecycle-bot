package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8991 — a lane is mined at its own hold and at every longer horizon in the research file, by the same rule; a longer
 * horizon proves only on its own held reads (an unpriced read is no reading, V5.0.8992); the research file keeps about a week.
 */
class Aate8991PocketRangeTest {
    private val h4 = 14_400_000L
    private val h24 = 86_400_000L

    private fun row(lane: String, h: Long, mint: String, t: Long, net: Double?) = JSONObject().put("t", t).put("horizonMs", h).put("mint", mint)
        .put("lane", lane).put("stage", "BASE_START").put("setup", "NONE").put("v3", 40.0).put("pred", 12.0).put("liq", 25_000.0)
        .put("mcap", 50_000.0).put("net", net ?: JSONObject.NULL).put("reason", if (net == null) "NO_PRICE_JUP_8884:NATIVE_READY" else "JUP_8884:NATIVE_READY")

    @Test fun theHorizonsALaneIsMinedAt() {
        assertFalse("its own hold is not an extension", LanePocket8934.extended8991("MOONSHOT", 3_600_000L))
        assertTrue(LanePocket8934.extended8991("MOONSHOT", h4)); assertTrue(LanePocket8934.extended8991("moonshot", h24))
        assertFalse("BLUECHIP's own hold is 4 h", LanePocket8934.extended8991("BLUECHIP", h4))
        assertTrue(LanePocket8934.extended8991("BLUECHIP", h24))
        // superseded by V5.0.9012 — the crypto lanes' longer horizons are mined too (report only; was assertFalse)
        assertTrue(LanePocket8934.extended8991("CRYPTO_ALT", h4)); assertFalse(LanePocket8934.extended8991("MOONSHOT", 7_200_000L))
        assertEquals("MOONSHOT@4h", LanePocket8934.extKey8991("moonshot", h4)); assertEquals("CORE@24h", LanePocket8934.extKey8991("CORE", h24))
        assertTrue(LanePocket8934.wanted8991("{\"t\":1,\"horizonMs\":86400000,\"mint\":\"x\"}"))
        assertFalse(LanePocket8934.wanted8991("{\"t\":1,\"horizonMs\":900000,\"mint\":\"x\"}"))
    }

    @Test fun aLongerHorizonProvesOnlyOnItsOwnHeldReads() {
        LanePocket8934.resetForTest8934()
        // 40 coins that still pay four hours on, across both halves of the window
        for (i in 1..40) LanePocket8934.fileForTest8934(row("MOONSHOT", h4, "m4h$i", i.toLong(), 30.0))
        // 40 coins unpriced a day on: no reading, not filed (superseded by V5.0.8992 — each counted -100 % before)
        for (i in 1..40) LanePocket8934.fileForTest8934(row("MOONSHOT", h24, "m24h$i", i.toLong(), null))
        LanePocket8934.mineForTest8934()
        assertTrue(LanePocket8934.provenExtForTest8991("MOONSHOT", h4).isNotEmpty())
        assertTrue("unpriced reads prove nothing", LanePocket8934.provenExtForTest8991("MOONSHOT", h24).isEmpty())
        assertTrue("the hour book is not fed by longer reads", LanePocket8934.provenForTest8934("MOONSHOT").isEmpty())
        val line = LanePocket8934.statusLine8934()
        assertTrue(line, line.contains("extended8991{MOONSHOT@4h=40/proven"))
        assertTrue(line, line.contains("coins{-}"))
        LanePocket8934.resetForTest8934()
    }

    @Test fun wired() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LanePocket8934.kt").readText()
        assertTrue(p.contains("if (h8991 != horizon8934(lane)) { if (extended8991(lane, h8991)) fileExt8991(o, lane, h8991); return }"))
        assertTrue(p.contains("\"LANE8934:\${extKey8991(l,horizon)}:\${c.id}\""))
        val r = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchRows8877.kt").readText()
        assertTrue(r.contains("private const val MAX_LINES_8877 = 210_000")); assertTrue(r.contains("private const val KEEP_LINES_8877 = 180_000"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 176. A pocket's range is every horizon it proves at (8991)"))
    }
}
