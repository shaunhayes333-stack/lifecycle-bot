package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8992 — a read Jupiter could not price is no reading: no pocket files it (it was scored -100 % since 8983 and buried
 * every pocket); it is counted. The shipped seed is read whether the build left it gzip or plain.
 */
class Aate8992UnpricedIsUnknownTest {
    private fun row(mint: String, t: Long, net: Double?) = JSONObject().put("t", t).put("horizonMs", 3_600_000L).put("mint", mint)
        .put("lane", "MOONSHOT").put("stage", "BASE_START").put("setup", "NONE").put("v3", 40.0).put("pred", 12.0).put("liq", 25_000.0)
        .put("mcap", 50_000.0).put("net", net ?: JSONObject.NULL).put("reason", if (net == null) "NO_PRICE_JUP_8884:LOSTMARK8983/x" else "NATIVE_READY")

    @Test fun anUnpricedReadIsNotFiled() {
        val before = ResearchRows8877.unpricedLine8992()
        assertTrue(ResearchRows8877.rowNet8983(JSONObject().put("net", JSONObject.NULL).put("reason", "NO_PRICE_JUP_8884:x")).isNaN())
        assertEquals(7.0, ResearchRows8877.rowNet8983(JSONObject().put("net", 7.0)), 1e-9)
        val after = ResearchRows8877.unpricedLine8992()
        assertTrue(after.startsWith("researchUnpriced8992[notFiled=")); assertTrue(after.endsWith("rule=UNKNOWN_NOT_MINUS_100]"))
        assertTrue("the unpriced read is counted", before != after)
    }

    @Test fun aPocketProvesOnItsPricedCoinsWhateverJupiterMissed() {
        LanePocket8934.resetForTest8934()
        for (i in 1..40) LanePocket8934.fileForTest8934(row("p$i", i.toLong(), 30.0))
        for (i in 1..120) LanePocket8934.fileForTest8934(row("u$i", i.toLong(), null))   // three unpriced for every priced coin
        LanePocket8934.mineForTest8934()
        assertTrue(LanePocket8934.provenForTest8934("MOONSHOT").isNotEmpty())
        assertTrue(LanePocket8934.statusLine8934().contains("coins{MOONSHOT=40}"))
        LanePocket8934.resetForTest8934()
    }

    @Test fun theSeedReaderGoesByTheStreamsOwnBytes() {
        val plain = "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n".toByteArray()
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
        assertEquals(3, ResearchRows8877.forEachSeedLine8990(open = { plain.inputStream() }) { })
        assertEquals(3, ResearchRows8877.forEachSeedLine8990(open = { gz.inputStream() }) { })
        assertTrue(File("src/main/assets/research_seed_8990.bin").isFile)
    }

    @Test fun wired() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchRows8877.kt").readText()
        assertTrue(!r.contains("UNPRICED_NET_8983 ="))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("ResearchRows8877.unpricedLine8992()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 177. An unpriced read is not a loss (8992)"))
    }
}
