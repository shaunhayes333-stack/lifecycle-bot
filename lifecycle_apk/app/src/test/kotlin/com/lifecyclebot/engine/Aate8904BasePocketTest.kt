package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BasePocket8904
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8904 — the base pocket: off-pump.fun coins in BASE_START with a $20k-$100k pool, one record at the hour, each coin
 * at its worst meme-lane read; it admits only once proven (30+ coins, mean above zero by two standard errors).
 */
class Aate8904BasePocketTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pump = "8KdKRqDWzDsrSrxo4mGA8e48KbG2JK9XgnVEFVXfpump"

    @Test fun thePocketIsTheBaseOffPumpFunAtTwentyToAHundredK() {
        assertTrue(BasePocket8904.inPocket8904(other, "BASE_START", 50_000.0))
        assertFalse(BasePocket8904.inPocket8904(pump, "BASE_START", 50_000.0))
        assertFalse(BasePocket8904.inPocket8904(other, "DUMPING", 50_000.0))
        assertFalse(BasePocket8904.inPocket8904(other, "BASE_START", 19_999.0))
        assertFalse(BasePocket8904.inPocket8904(other, "BASE_START", 100_000.0))
        val row = JSONObject("{\"t\":1795136269192,\"horizonMs\":3600000,\"mint\":\"$other\",\"lane\":\"EXPRESS\",\"stage\":\"BASE_START\",\"liq\":45000,\"net\":18.0}")
        assertNotNull(BasePocket8904.rowOf8904(row))
        assertNull("a five-minute read is not the hour", BasePocket8904.rowOf8904(JSONObject(row.toString()).put("horizonMs", 300000)))
        assertNull(BasePocket8904.rowOf8904(JSONObject(row.toString()).put("net", JSONObject.NULL)))
    }

    @Test fun eachCoinOnceAtItsWorstLaneReadAndOnlyProvenAdmits() {
        BasePocket8904.resetForTest8904()
        BasePocket8904.addForTest8904("EXPRESS", "C0", 40.0, 1_000L)
        BasePocket8904.addForTest8904("MOONSHOT", "C0", -10.0, 2_000L)
        BasePocket8904.addForTest8904("CRYPTO_CORE", "C1", 50.0, 1_000L)
        val r = BasePocket8904.record8904()
        assertEquals("one coin, the crypto lane not filed", 1.0, r[0], 0.0)
        assertEquals("its worst meme-lane read", -10.0, r[1], 1e-9)
        assertNull(BasePocket8904.evidenceOf8904("EXPRESS", r))
        BasePocket8904.resetForTest8904()
        // the 20-21 Nov rows: 49 coins, +13.3 % +- 8.6 -> unproven; a tight paying record -> proven
        for (i in 0 until 29) BasePocket8904.addForTest8904("CORE", "P$i", 20.0, i * 3_600_000L)
        assertNull("29 coins", BasePocket8904.evidenceOf8904("CORE", BasePocket8904.record8904()))
        BasePocket8904.addForTest8904("CORE", "P29", 22.0, 30 * 3_600_000L)
        val e = BasePocket8904.evidenceOf8904("CORE", BasePocket8904.record8904())
        assertNotNull(e); assertEquals(BasePocket8904.KIND_8904, e!!.kind); assertEquals(30, e.n)
        assertNull("not a meme lane", BasePocket8904.evidenceOf8904("CRYPTO_CORE", BasePocket8904.record8904()))
        assertTrue(BasePocket8904.statusLine8904().startsWith("basePocket8904[PROVEN n30/"))
        BasePocket8904.resetForTest8904()
    }

    @Test fun wiredAfterTheAgreementPocketAndAnsweringThePriors() {
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val chain = adm.substringAfter("marketCell8723 = PayingCell8723.evidence8723(ts, lane, nowMs)").substringBefore("regimeCold8546 =")
        assertTrue(chain.indexOf("?: BasePocket8904.evidence8904(ts, lane, nowMs)") in chain.indexOf("AgreementPocket8874.evidence8874(") until chain.indexOf("LlmBands8875.evidence8875("))
        assertTrue(LaneAuthority8577.answersPriors8874(BasePocket8904.KIND_8904))
        assertTrue(src("engine/truth/ResearchRows8877.kt").contains("BasePocket8904.learnLine8904(line)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("BasePocket8904.statusLine8904()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 92. Audit of 21 Nov, and the base pocket (8904)"))
    }
}
