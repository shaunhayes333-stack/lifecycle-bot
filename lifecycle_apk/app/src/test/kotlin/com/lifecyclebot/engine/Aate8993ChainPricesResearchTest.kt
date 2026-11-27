package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AuthorityTable8887
import com.lifecyclebot.engine.truth.ResearchFollow8884
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8993 — a research read Jupiter could not price is held by the trading process, the chain is asked once, and its
 * answer finishes the row (net and gross against the decision's price); a coin the chain cannot answer is written unpriced.
 */
class Aate8993ChainPricesResearchTest {
    private val f = ResearchFollow8884
    private val mint = "So11111111111111111111111111111111111111112"
    private val now = 1_800_000_000_000L

    private fun unpriced(reason: String = "NO_PRICE_JUP_8884:LOSTMARK8983/NATIVE_READY") = ResearchRows8877.line8877(now - 900_000L, 900_000L, mint,   // a horizon no pocket files: the shared books stay clean
       
        "MOONSHOT", true, "BASE_START", "S", 12.0, 40.0, 50_000.0, 25_000.0, 6.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, reason)

    @Test fun theUnpricedRowCarriesTheDecisionsPrice() {
        val row = f.carry8993(unpriced(), 0.00002, 18.0, -9.0)
        val o = JSONObject(row)
        assertEquals(0.00002, o.getDouble("e8993"), 1e-12); assertEquals(18.0, o.getDouble("pk8993"), 1e-9); assertEquals(-9.0, o.getDouble("lo8993"), 1e-9)
        assertTrue(f.wantsChain8993(row))
        assertFalse("no decision price: nothing to finish", f.wantsChain8993(f.carry8993(unpriced(), Double.NaN, 1.0, 1.0)))
        assertFalse("a priced row is filed as it is", f.wantsChain8993(f.carry8993(unpriced("JUP_8884:NATIVE_READY"), 0.00002, 1.0, 1.0)))
        assertTrue("still unknown to a pocket until the chain answers", ResearchRows8877.rowNet8983(o).isNaN())
    }

    @Test fun theChainsPriceFinishesTheRow() {
        val row = f.carry8993(unpriced(), 0.00002, 18.0, -9.0)
        val done = JSONObject(f.finish8993(row, 0.00003)!!)   // +50 % gross, 6 % round trip
        assertEquals(50.0, done.getDouble("gross"), 1e-4); assertEquals(44.0, done.getDouble("net"), 1e-4)
        assertTrue("a sampled high is not held", done.isNull("heldPeakPct")); assertEquals(50.0, done.getDouble("peakPct"), 1e-4); assertEquals(-9.0, done.getDouble("minPct"), 1e-4)
        assertEquals("CHAIN_8993:LOSTMARK8983/NATIVE_READY", done.getString("reason"))
        assertEquals(900_000L, done.getLong("horizonMs")); assertEquals("MOONSHOT", done.getString("lane")); assertFalse(done.has("e8993"))
        assertEquals(44.0, ResearchRows8877.rowNet8983(done), 1e-4)
        val waiting = AuthorityTable8887.row8887(JSONObject(row))!!
        val priced = AuthorityTable8887.row8887(done)!!
        assertTrue("the unanswered read remains unknown", waiting.net.isNaN())
        assertEquals("NATIVE_READY", waiting.reason)
        assertEquals("chain and lost-mark provenance must not split the original cohort", waiting.reason, priced.reason)
        assertEquals(44.0, priced.net, 1e-4)
        assertEquals("the original decision reason is persisted independently of read provenance", "NATIVE_READY", done.getString("originalReason9043"))
        assertNull("a 101x ratio is not a move a coin made", f.finish8993(row, 0.00002 * 101.0))
        assertNull(f.finish8993(row, Double.NaN))
    }

    @Test fun oneAskThenTheAnswerOrTheClock() {
        f.resetForTest8993()
        val asked = ArrayList<String>()
        val row = f.carry8993(unpriced(), 0.00002, Double.NaN, Double.NaN)
        f.file8993(row, now) { m, _ -> asked += m }
        assertEquals(listOf(mint), asked)
        assertTrue(f.chainLine8993().contains("holding=1"))
        f.settle8993(now + 60_000L) { _, _ -> Double.NaN }                 // no answer yet, inside the wait: still held, not asked again
        assertTrue(f.chainLine8993().contains("holding=1")); assertEquals(1, asked.size)
        f.settle8993(now + 61_000L) { _, _ -> 0.00003 }                    // the chain answered
        assertTrue(f.chainLine8993(), f.chainLine8993().contains("priced=1 unpriced=0 implausible=0 holding=0"))
        f.file8993(row, now) { m, _ -> asked += m }
        f.settle8993(now + ResearchFollow8884.WAIT_MS_8993) { _, _ -> Double.NaN }   // never answered: written unpriced
        assertTrue(f.chainLine8993(), f.chainLine8993().contains("priced=1 unpriced=1 implausible=0 holding=0"))
        f.resetForTest8993()
    }

    @Test fun wired() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchFollow8884.kt").readText()
        assertTrue(src.contains("emit8978(if (r == null && !tooLate && !implausible) carry8993(row8993, w.entryUsd, peak8973, low8973) else row8993, nowMs)"))
        assertTrue(src.contains("try { settle8993(nowMs) } catch (_: Throwable) { failed.incrementAndGet() }"))
        assertTrue(src.contains("ChainConfirm8950.confirmMint8950(mint, listOf(\"PRICE\"), \"RESEARCH\", nowMs)"))
        assertTrue(f.statusLine8884().contains("chainRead8993[asked="))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 178. The chain prices what Jupiter cannot (8993)"))
    }
}
