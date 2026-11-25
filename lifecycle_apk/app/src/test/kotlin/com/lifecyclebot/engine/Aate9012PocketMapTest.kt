package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.9012 — the whole pocket map: for every lane and horizon the miner reads, how many cells it holds, how many prove
 * (and how many of those are its own, not a market pocket proven in three or more lanes), how many lose on both halves, and
 * the unproven cell nearest its bar with the test it fails. The crypto lanes' 4 h / 24 h reads are mined too (report only).
 * The harvest ledger also keeps how long the big wins were held.
 */
class Aate9012PocketMapTest {
    private fun coin(i: Int, pred: Double, net: Double, atMs: Long, stage: String = "BASE_START") =
        LanePocket8934.Coin8934(atMs, LanePocket8934.features8934("m$i", stage, "NONE", 30.0, pred, 25_000.0, 15_000.0), net)

    @Test fun theSurveyComesFromTheSamePassAsTheProvenCells() {
        val coins = ArrayList<LanePocket8934.Coin8934>()
        for (i in 0 until 40) coins += coin(i, 12.0, 30.0 + (i % 5), i * 3_000L)
        for (i in 40 until 120) coins += coin(i, -3.0, -10.0 - (i % 7), (i - 40) * 1_500L)
        val (cells, s) = LanePocket8934.mineAll9012(coins, 60_000L)
        assertEquals("mine8934 is the same proven list", LanePocket8934.mine8934(coins, 60_000L), cells)
        assertEquals(cells.size, s.proven)
        assertTrue(s.cells >= s.proven + s.losing && s.proven > 0)
        assertTrue("the losing forecast cell is counted", s.losing > 0)
        // nothing to read
        val (none, empty) = LanePocket8934.mineAll9012(coins.take(29), 45_000L)
        assertTrue(none.isEmpty()); assertEquals(0, empty.cells); assertEquals(null, empty.nearest)
    }

    @Test fun aLaneWithNothingProvenNamesItsNearestCellAndTheTestItFails() {
        // paid only in its first half: positive overall, not proven
        val firstHalfOnly = (0 until 40).map { coin(it, 12.0, if (it < 20) 60.0 else -20.0, it * 1_000L) }
        val (cells, s) = LanePocket8934.mineAll9012(firstHalfOnly, 20_000L)
        assertTrue(cells.isEmpty()); assertEquals(0, s.proven)
        assertNotNull(s.nearest); assertEquals("A_HALF_NOT_POSITIVE", s.nearestWhy)
        val entry = LanePocket8934.mapEntry9012("QUALITY", s, 0)
        assertTrue(entry, entry.startsWith("QUALITY:k${s.cells}/p0/l") && entry.contains("~[") && entry.endsWith("A_HALF_NOT_POSITIVE]"))
        // a proven lane shows its own count and no nearest cell
        val good = (0 until 40).map { coin(it, 12.0, 30.0 + (it % 5), it * 3_000L) }
        val (pc, ps) = LanePocket8934.mineAll9012(good, 60_000L)
        val e2 = LanePocket8934.mapEntry9012("MOONSHOT", ps, 2)
        assertTrue(e2, pc.isNotEmpty() && e2.startsWith("MOONSHOT:k${ps.cells}/p${ps.proven}(own2)/l") && !e2.contains("~["))
    }

    @Test fun aCellProvenInThreeLanesIsOneMarketPocket() {
        fun c(id: String) = LanePocket8934.Cell8934(id.split("&"), 40, 30.0, 2.0, 25.0, 3.0, 0.1)
        val proven = mapOf("A" to listOf(c("stage=BASE_START"), c("setup=X")), "B" to listOf(c("stage=BASE_START")),
            "C" to listOf(c("stage=BASE_START"), c("setup=Y")), "D" to listOf(c("setup=Y")))
        assertEquals(setOf("stage=BASE_START"), LanePocket8934.sharedIds9012(proven))
        assertEquals(setOf("stage=BASE_START", "setup=Y"), LanePocket8934.sharedIds9012(proven, minLanes = 2))
        assertTrue(LanePocket8934.sharedIds9012(emptyMap()).isEmpty())
    }

    @Test fun theCryptoLanesLongerReadsAreMinedAndTheMapIsInTheReport() {
        val h4 = 4L * 3_600_000L
        assertTrue(LanePocket8934.extended8991("CRYPTO_EXPRESS", h4)); assertFalse(LanePocket8934.extended8991("CRYPTO_EXPRESS", 3_600_000L))
        LanePocket8934.resetForTest8934()
        for (i in 1..40) LanePocket8934.fileForTest8934(JSONObject().put("t", i.toLong()).put("horizonMs", h4).put("mint", "c4h$i")
            .put("lane", "CRYPTO_EXPRESS").put("stage", "UNKNOWN").put("setup", "NONE").put("v3", 40.0).put("pred", -1.0).put("liq", 3_000.0)
            .put("mcap", 8_000.0).put("net", 25.0 + (i % 4)))
        LanePocket8934.mineForTest8934()
        assertTrue("mined at 4 h without a hunt band", LanePocket8934.provenExtForTest8991("CRYPTO_EXPRESS", h4).isNotEmpty())
        val line = LanePocket8934.statusLine8934()
        assertTrue(line, line.startsWith("pocketMap9012{CRYPTO_EXPRESS@4h:k"))
        assertTrue(line, line.contains("lanePocket8934[ready="))
        assertTrue(LanePocket8934.pocketMapLine9012().startsWith("pocketMap9012{CRYPTO_EXPRESS@4h:k") && LanePocket8934.pocketMapLine9012().contains("sharedPockets=0"))
        val p = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LanePocket8934.kt").readText()
        assertTrue("no crypto lane is served a pocket", p.contains("l.startsWith(\"CRYPTO_\")) return null"))
        LanePocket8934.resetForTest8934()
    }

    @Test fun theHarvestLedgerKeepsHowLongTheBigWinsWereHeld() {
        val b = DegenHarvest9011.Book9011()
        assertTrue(b.add(400.0, 250.0, "RUNNER_DOCTRINE_TRAIL_8630", true, 12L * 60_000L))
        assertTrue(b.add(300.0, 90.0, "TAKE_PROFIT", false, 4L * 60_000L))
        assertTrue(b.add(250.0, 60.0, "TAKE_PROFIT", false))          // hold unknown: not averaged
        assertTrue(b.line(), b.line().contains("meanHoldMin=8 "))
        val r = DegenHarvest9011.Book9011().also { it.decode(b.encode()) }
        assertEquals(b.line(), r.line())
        // a ledger written by 9011 (eight fields) still loads
        val old = DegenHarvest9011.Book9011().also { it.decode("T\t2\t1\t0\t1\t340.0\t700.0\t1.2\nE\tTAKE_PROFIT\t2\t0") }
        assertEquals(2L, old.reached); assertEquals(0L, old.holds)
    }
}
