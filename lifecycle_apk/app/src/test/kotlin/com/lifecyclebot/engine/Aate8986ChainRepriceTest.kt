package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.ChainPrice8963
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.network.PoolVirtualQuote8952
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8986 — a chain read is a price on every venue it can prove: a PumpSwap vault read carries its pool account (its
 * virtual quote reserves, pump_amm IDL @245), a concentrated pool's decoded state price is written, a decision's price may
 * be re-read inside its own staleness bar, and the provider's impact field does not refuse a quote its own amounts contradict.
 */
class Aate8986ChainRepriceTest {
    private val wsol = OnChainDepth8038.WSOL
    private val disc = byteArrayOf(241.toByte(), 154.toByte(), 109, 4, 17, 177.toByte(), 109, 188.toByte())

    private fun pool(size: Int, virtual: BigInteger?): ByteArray {
        val d = ByteArray(size); disc.copyInto(d, 0)
        if (virtual != null) {
            val be = virtual.toByteArray()   // big-endian two's complement
            val fill: Byte = if (virtual.signum() < 0) (-1).toByte() else 0
            for (i in 0 until 16) d[245 + i] = if (i < be.size) be[be.size - 1 - i] else fill
        }
        return d
    }
    private fun vault(mint: String, ui: String, dec: Int) = JSONObject().put("data", JSONObject().put("parsed",
        JSONObject().put("info", JSONObject().put("mint", mint).put("tokenAmount", JSONObject().put("uiAmountString", ui).put("decimals", dec)))))
    private fun acct(data: ByteArray) = JSONObject().put("data", JSONArray().put(java.util.Base64.getEncoder().encodeToString(data)).put("base64"))

    @Test fun thePoolAccountNamesItsVirtualQuote() {
        assertEquals(BigInteger("5000000000"), OnChainDepth8038.pumpVirtualQuote8986(pool(301, BigInteger("5000000000"))))
        assertEquals(BigInteger("-123456789"), OnChainDepth8038.pumpVirtualQuote8986(pool(301, BigInteger("-123456789"))))
        assertEquals("a pool written before the field existed reads 0", BigInteger.ZERO, OnChainDepth8038.pumpVirtualQuote8986(pool(243, null)))
        assertNull("not a Pool account", OnChainDepth8038.pumpVirtualQuote8986(ByteArray(301)))
        assertEquals(listOf("vA", "vB", "P"), OnChainDepth8038.readKeys8418("vA" to "vB", "P", OnChainDepth8038.PUMP_AMM))
    }

    @Test fun aPumpSwapReadPricesOnItsEffectiveReservesFromTheSameRead() {
        PoolVirtualQuote8952.resetForTest8952()
        val mint = "CpMint8986aaaaaaaaaaaaaaaaaaaaaaaaaaaapump"
        val p = "Poo18986aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertFalse(PoolVirtualQuote8952.known8952(p))
        assertEquals(OnChainDepth8038.Accept8418.CONSTANT_PRODUCT,
            OnChainDepth8038.accept8418(mint, p, OnChainDepth8038.PUMP_AMM, vault(mint, "500000", 6), vault(wsol, "25", 9), acct(pool(301, BigInteger("5000000000"))), 5_000L))
        assertTrue("no swap event was heard: the account itself named it", PoolVirtualQuote8952.known8952(p))
        assertEquals(30.0, OnChainDepth8038.freshRead8198(mint, 90_000L, 6_000L)!!.quoteSol, 1e-9)   // 25 in the vault + 5 virtual
        assertTrue(OnChainDepth8038.line8986().startsWith("chainReprice8986[pumpSwapPoolAccountVirtualQuote="))
        PoolVirtualQuote8952.resetForTest8952()
    }

    @Test fun aDecisionsPriceIsRereadInsideItsBar() {
        val c = ChainConfirm8950
        assertEquals(10_000L, c.gapFor8986(ChainConfirm8950.Input8950.PRICE_DEPTH, "evidence"))
        assertEquals(10_000L, c.gapFor8986(ChainConfirm8950.Input8950.PRICE_DEPTH, "DECISION"))
        assertEquals(20_000L, c.gapFor8986(ChainConfirm8950.Input8950.PRICE_DEPTH, "STAGE"))
        assertEquals(20_000L, c.gapFor8986(ChainConfirm8950.Input8950.HOLDERS, "DECISION"))
        assertTrue(c.due8950(1_000L, 11_000L, 10_000L)); assertFalse(c.due8950(1_000L, 10_999L, 10_000L))
        assertFalse("other inputs keep 20 s", c.due8950(1_000L, 11_000L))
        assertTrue(c.PRICE_GAP_MS_8986 < com.lifecyclebot.engine.truth.PriceAge8549.LIVE_HOT_STALE_MS_8549)
    }

    @Test fun theQuotesOwnAmountsAnswerItsImpactField() {
        val q = SizingQuoteRecheck8153
        val seal = SizingQuoteRecheck8153.SealedEconomics8153(sizeSol = 0.05, expectedGrossPct = 30.0, firstTargetPct = 30.0, costPct = 4.0,
            impactOneWayPct = 0.1, priceUsd = 1.0, atMs = 1_000L)
        val lam = 50_000_000L
        // the field says 2.0 % (budget 1.6 %), no executable price to compare: refused as before
        assertEquals("PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153", q.recheck8153(seal, 0.05, lam, 2.0, 1.0, 2_000L))
        // the quote itself pays 0.4 % over the sealed price: the field is contradicted, the buy goes on to the net-EV check
        assertNull(q.recheck8153(seal, 0.05, lam, 2.0, 1.0, 2_000L, executionPriceUsd = 1.004))
        // the quote really pays 2.5 % over the sealed price: refused
        assertEquals("PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153", q.recheck8153(seal, 0.05, lam, 2.0, 1.0, 2_000L, executionPriceUsd = 1.025))
        assertTrue(q.impactContradicted8986(1.0, 1.015, 1.6)); assertFalse(q.impactContradicted8986(1.0, 1.017, 1.6))
        assertFalse(q.impactContradicted8986(0.0, 1.0, 1.6)); assertFalse(q.impactContradicted8986(1.0, Double.NaN, 1.6))
        assertTrue(q.statusLine8153().contains("impactFieldContradictedByQuote8986="))
    }

    @Test fun wired() {
        val d = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(d.contains("ChainPrice8963.publish8963(mint, it.solPerCoin * solUsd(), ChainPrice8963.STATE_SOURCE_8986 + venueName8418(owner8418), observedAt, pool = p)"))
        assertTrue(d.contains("com.lifecyclebot.network.PoolVirtualQuote8952.note8952(p, it.toDouble(), observedAt)"))
        assertEquals("CHAIN_STATE_READ_8986_", ChainPrice8963.STATE_SOURCE_8986)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/ChainConfirm8950.kt").readText().contains("val due = synchronized(lastAsk) { due8950(lastAsk[key] ?: 0L, nowMs, gapFor8986(input, normalized)) }"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("OnChainDepth8038.line8986()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 171. A chain read is a price on every venue it can prove (8986)"))
    }
}
