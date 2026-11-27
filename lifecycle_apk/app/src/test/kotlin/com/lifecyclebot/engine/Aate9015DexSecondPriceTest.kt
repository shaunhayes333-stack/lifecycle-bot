package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchFollow8884
import com.lifecyclebot.engine.truth.AuthorityTable8887
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9015 — the 4 h / 24 h research reads ask DexScreener for what Jupiter did not price or did not answer for; its
 * price counts only from a pool a holder could sell into, and the row names its source.
 */
class Aate9015DexSecondPriceTest {
    @Test fun aDexPriceCountsOnlyFromAPoolAHolderCouldSellInto() {
        val f = ResearchFollow8884
        assertTrue(f.dexUsable9015(0.0012, 5_000.0))
        assertTrue("the row's own tradeable bar", f.dexUsable9015(0.0012, ResearchFollow8884.MIN_POOL_USD_8923))
        assertFalse("a last print in an empty pool is not a price", f.dexUsable9015(0.0012, 40.0))
        assertFalse("an unread pool is not a deep one", f.dexUsable9015(0.0012, null))
        assertFalse(f.dexUsable9015(null, 5_000.0)); assertFalse(f.dexUsable9015(0.0, 5_000.0)); assertFalse(f.dexUsable9015(Double.NaN, 5_000.0))
        assertEquals("DEX_9015:", ResearchFollow8884.DEX_PREFIX_9015)
        assertTrue(f.dexLine9015().startsWith("dexSecondPrice9015[asked="))
        assertTrue(f.statusLine8884().contains("dexSecondPrice9015["))
    }

    @Test fun itIsAskedAfterJupiterAndTheRowNamesItsSource() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchFollow8884.kt").readText()
        val pass = s.substringAfter("private fun pass8884(").substringBefore("private fun fetch8884(")
        assertTrue(pass.contains("val dex9015 = try { dexPrices9015(mints.filter { priceOf8884(body, it) == null }) }"))
        assertTrue("an unanswered Jupiter read is no longer left pending when DexScreener priced it",
            pass.contains("if (!tooLate && body == null && w.mint !in dex9015) continue"))
        assertTrue(pass.contains("val usd = jup9015 ?: dex9015[w.mint]"))
        assertTrue(pass.contains("else -> (if (jup9015 != null) \"JUP_8884:\" else DEX_PREFIX_9015) + w.reason"))
        assertTrue("the plausibility check still judges it", pass.indexOf("val usd = jup9015 ?: dex9015[w.mint]") < pass.indexOf("plausible8923(w.entryUsd, usd)"))
        assertTrue("an unpriced row stays unpriced", pass.contains("r == null -> \"NO_PRICE_JUP_8884:\" + w.reason"))
        val fn = s.substringAfter("private fun dexPrices9015(").substringBefore("fun dexLine9015()")
        assertTrue(fn.contains("batchPriceFetchTyped8087(chunk, DEX_SLOT_WAIT_MS_9015, heldMarkTraffic = false)"))
        assertTrue(fn.contains("poolsOf8285(m).filter { it.priceUsd == px }.maxOfOrNull { it.liquidityUsd }"))
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/AuthorityTable8887.kt").readText()
        assertTrue("the authority uses the same quality and original-reason reader as the pockets",
            a.contains("val read = ResearchEvidence9043.read9043(o)") && a.contains("read.net, read.originalReason"))
        val reason = "LPSTATE8725_LP_BLOCKED:LT30/NOT_DLMM_cpamdpZC"
        val source = ResearchFollow8884.DEX_PREFIX_9015 + "LOSTMARK8983/" + reason
        val row = JSONObject().put("t", 1L).put("horizonMs", 14_400_000L).put("lane", "MOONSHOT")
            .put("net", 12.0).put("reason", source)
        val read = AuthorityTable8887.row8887(row)!!
        assertEquals(reason, read.reason)
        assertEquals(12.0, read.net, 0.0)
        assertEquals("the priced source remains available for audit", source, row.getString("reason"))
    }
}
