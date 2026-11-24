package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchFollow8884
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8983 — a decision whose mark was lost is read at the hour (then 4 h and 24 h) and writes its row; a pocket counts an
 * unpriced read as a dead coin (-100 %), as the authority table already does for PAYS.
 */
class Aate8983LostMarkRowTest {
    private val f = ResearchFollow8884
    private val mint = "So11111111111111111111111111111111111111112"

    @Test fun aRowsNetForAPocket() {
        assertEquals(12.5, ResearchRows8877.rowNet8983(JSONObject().put("net", 12.5).put("reason", "JUP_8884:x")), 1e-9)
        assertEquals(-100.0, ResearchRows8877.rowNet8983(JSONObject().put("net", JSONObject.NULL).put("reason", "NO_PRICE_JUP_8884:LOSTMARK8983/x")), 1e-9)
        assertTrue("a late read is not a reading", ResearchRows8877.rowNet8983(JSONObject().put("net", JSONObject.NULL).put("reason", "LATE_READ_8884:x")).isNaN())
        assertTrue(ResearchRows8877.rowNet8983(JSONObject()).isNaN())
    }

    @Test fun aLostMarkWaitsForTheHourThenFourAndTwentyFour() {
        f.resetForTest8884()
        f.noteLostHour8983(1_000L, mint, "MOONSHOT", true, "BASE_START", "S", 12.0, 40.0, 30_000.0, 20_000.0, 3.0, 0.00001, "R")
        val w = f.pendingForTest8884().single()
        assertTrue(w.hour8983)
        assertEquals(3_600_000L, f.horizonOf8983(w))
        assertTrue(w.reason.startsWith(ResearchFollow8884.LOST_TAG_8983))
        assertEquals(4L * 3_600_000L, f.horizonOf8983(w.copy(hour8983 = false)))
        val back = f.decode8884(f.encode8884(w))!!
        assertTrue(back.hour8983)
        val old = JSONObject(f.encode8884(w)).apply { remove("h1") }.toString()
        assertFalse("a wait saved before 8983 is a 4 h wait", f.decode8884(old)!!.hour8983)
        assertTrue(f.statusLine8884().contains("lostHour8983{noted="))
        f.resetForTest8884()
    }

    @Test fun wired() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/engine/truth/$p").readText()
        val lab = src("ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("markLost(o); lostHour8983(o); return true }"))
        assertTrue(lab.contains("            markLost(o)\n            lostHour8983(o)"))
        for (p in listOf("LanePocket8934.kt", "BasePocket8904.kt", "AgreementPocket8874.kt"))
            assertTrue(p, src(p).contains("val net = ResearchRows8877.rowNet8983(o)   // V5.0.8983"))
        assertTrue(src("ResearchFollow8884.kt").contains("if (w.hour8983) again += w.carry8973(w.copy(hour8983 = false))"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 168. A dead coin is a research row (8983)"))
    }
}
