package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainHistoryMark8541
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8541 — owner: "fix the data issues ... everything is available on the block chain". Labels a provider could not
 * price are read from the coin's own pool on chain: now (the vault read, chain rung of the off-watch batch) and for a
 * window that closed while the app was down (the vault balances after the first swap inside the window). Near-decision
 * coins may read their on-chain swap history for 1 m bars, inside the candles' own credit share.
 */
class Aate8541ChainDataTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private val mint = "So1anaCoin1111111111111111111111111111pump"
    private val wsol = "So11111111111111111111111111111111111111112"
    private val vaultCoin = "VauLtCoin111111111111111111111111111111111"
    private val vaultSol = "VauLtSo1111111111111111111111111111111111111"

    private fun bal(idx: Int, m: String, ui: String) = JSONObject().put("accountIndex", idx).put("mint", m)
        .put("uiTokenAmount", JSONObject().put("uiAmountString", ui).put("decimals", 6))

    private fun parsedTx(err: Any? = null) = JSONObject()
        .put("transaction", JSONObject().put("message", JSONObject().put("accountKeys", JSONArray()
            .put(JSONObject().put("pubkey", "Signer11111111111111111111111111111111111"))
            .put(JSONObject().put("pubkey", vaultCoin))
            .put(JSONObject().put("pubkey", vaultSol)))))
        .put("meta", JSONObject().put("err", err ?: JSONObject.NULL)
            .put("postTokenBalances", JSONArray().put(bal(1, mint, "1000000")).put(bal(2, wsol, "50"))))

    @Test fun aSwapsPostVaultBalancesPriceTheCoinAtItsBlockTime() {
        val m = ChainHistoryMark8541.markOf8541(mint, parsedTx(), vaultCoin to vaultSol, 1_000_000L, 200.0, 2_000_000L)
        assertNotNull(m)
        assertEquals("50 SOL / 1,000,000 coins x 200 USD", 0.01, m!!.usd, 1e-12)
        assertEquals(1_000_000L, m.observedAtMs)
        assertEquals(ChainHistoryMark8541.SOURCE_8541, m.source)
        assertTrue(m.known())
        assertNull("a failed transaction is no state", ChainHistoryMark8541.markOf8541(mint, parsedTx(JSONObject().put("x", 1)), vaultCoin to vaultSol, 1_000_000L, 200.0, 2_000_000L))
        assertNull("another coin's vaults are no read", ChainHistoryMark8541.markOf8541("Other1111111111111111111111111111111111111", parsedTx(), vaultCoin to vaultSol, 1_000_000L, 200.0, 2_000_000L))
        assertNull("a block after the read is impossible", ChainHistoryMark8541.markOf8541(mint, parsedTx(), vaultCoin to vaultSol, 3_000_000L, 200.0, 2_000_000L))
    }

    @Test fun plainKeyAnswersAppendTheLoadedAddresses() {
        val tx = JSONObject()
            .put("transaction", JSONObject().put("message", JSONObject().put("accountKeys", JSONArray().put("A").put("B"))))
            .put("meta", JSONObject().put("loadedAddresses", JSONObject().put("writable", JSONArray().put("C")).put("readonly", JSONArray().put("D"))))
        assertEquals(listOf("A", "B", "C", "D"), ChainHistoryMark8541.accountKeys8541(tx))
        assertEquals(listOf("Signer11111111111111111111111111111111111", vaultCoin, vaultSol), ChainHistoryMark8541.accountKeys8541(parsedTx()))
    }

    @Test fun theEarliestSwapInsideTheWindowIsRead() {
        val sigs = listOf("s4" to 400L, "s3" to 300L, "bad" to -1L, "s2" to 200L, "s1" to 100L)
        assertEquals("s2" to 200L, ChainHistoryMark8541.firstInWindow8541(sigs, 150L, 350L))
        assertNull("no swap in the window is no mark", ChainHistoryMark8541.firstInWindow8541(sigs, 410L, 500L))
        assertNull("a failed row never matches", ChainHistoryMark8541.firstInWindow8541(listOf("bad" to -1L), -5L, 5L))
    }

    @Test fun theSolRateOfThePastNeedsBothSidesOfTheDowntimeToAgree() {
        assertEquals(200.0, ChainHistoryMark8541.solUsdAcross8541(200.0, 1_000L, 202.0, 10_000L, 2_000L), 0.0)
        assertEquals(202.0, ChainHistoryMark8541.solUsdAcross8541(200.0, 1_000L, 202.0, 10_000L, 9_000L), 0.0)
        assertEquals(0.02, ChainHistoryMark8541.MAX_SOL_DRIFT_8541, 0.0)
        assertTrue("moved 5 %: unknown", ChainHistoryMark8541.solUsdAcross8541(200.0, 1_000L, 210.0, 10_000L, 2_000L).isNaN())
        assertTrue("no prior observation: unknown", ChainHistoryMark8541.solUsdAcross8541(Double.NaN, 0L, 200.0, 10_000L, 2_000L).isNaN())
        assertTrue("no fresh rate: unknown", ChainHistoryMark8541.solUsdAcross8541(200.0, 1_000L, 0.0, 10_000L, 2_000L).isNaN())
    }

    @Test fun outsideTheAppOrOutsideTheDowntimeNothingIsAsked() {
        val r: ChainHistoryMark8541.Read8541 = ChainHistoryMark8541.read8541(mint, 1_000L, 2_000L, 10_000L, 11_000L)
        assertFalse("past the chain window nothing waits", ChainHistoryMark8541.read8541(mint, 1_000L, 2_000L, 10_000L, 10_000L + ChainHistoryMark8541.CHAIN_WINDOW_MS_8541 + 1L).waiting)
        assertNull(r.mark); assertFalse("unit tests have no app context: never waits", r.waiting)
        assertFalse(ChainHistoryMark8541.read8541(mint, 1_000L, 20_000L, 10_000L, 11_000L).waiting)
        assertFalse(ChainHistoryMark8541.read8541("not-a-mint", 1_000L, 2_000L, 10_000L, 11_000L).waiting)
        assertTrue(ChainHistoryMark8541.statusLine8541().startsWith("chainHistory8541[jobs="))
    }

    @Test fun theLabelerAsksTheChainFirstAndKeepsTheCandleFilter() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val chain = l.indexOf("val chain8541 = ChainHistoryMark8541.read8541(o.mint, o.atMs + horizon, deadline, bootMs8430, nowMs)")
        assertTrue(chain > 0)
        assertTrue(l.contains("recovered = chain8541.mark?.takeIf(recoverable8435)"))
        assertTrue(chain < l.indexOf("val downtime8430 = DowntimeRecovery8430.candleMark8430("))
        assertTrue(l.contains("if (recovered == null && chainWaiting8541) return false"))
        // the off-watch chain rung (live vault read) after Jupiter, before the batch is closed
        val rung = l.indexOf("OnChainDepth8038.chainPriceUsd8540(m, CHAIN_MARK_MAX_AGE_MS_8540)")
        assertTrue(rung > l.indexOf("FORWARD_LABEL_OFFWATCH_JUP_PRICED_8405"))
        assertTrue(rung < l.indexOf("for (m in got.keys) noteCause8535(m, OffWatchSchedule8535.Cause.PRICED)"))
        // superseded by V5.0.8543 — the unpriced mints are handed to one background drainer, not requested on the batch thread
        // superseded by V5.0.8572 — "requestMints8543(ask8543)": the ask carries the rung's own 20 s freshness.
        assertTrue(l.contains("if (ask8543.isNotEmpty()) try { OnChainDepth8038.requestMints8543(ask8543, CHAIN_MARK_MAX_AGE_MS_8540) } catch (_: Throwable) {}"))
        val d = src("engine/truth/OnChainDepth8038.kt")
        assertTrue("concentrated venues never price", d.substringAfter("internal fun historyPools8541(").substringBefore("private fun poolsForMint8540(").contains("if (concentrated8418(o)) null else p to v"))
        val w = src("engine/WalletManager.kt")
        assertTrue("the pre-boot rate is captured once", w.contains("if (bootPriorSol8541 == null) bootPriorSol8541 = p.getFloat(KEY_SOL_PRICE_7042, 0f).toDouble()"))
        assertTrue(src("engine/HeliusCadence8361.kt").contains("fun signaturesTimed8541("))
    }

    @Test fun nearDecisionCoinsReadTheirOnChainSwapHistoryWithoutThePriorityLease() {
        assertTrue(HeliusSwapCandles7819.heliusCandlesAllowed8453(HeliusSwapCandles7819.PRIORITY_NEAR_DECISION_8284))
        assertFalse(HeliusSwapCandles7819.heliusCandlesAllowed8453(HeliusSwapCandles7819.PRIORITY_HANDOFF_8284))
        assertFalse(HeliusSwapCandles7819.heliusCandlesAllowed8453(0))
        val c = src("engine/truth/HeliusSwapCandles7819.kt")
        assertFalse(c.contains("200.0, priority >= PRIORITY_NEAR_DECISION_8284, nowMs)"))
        assertTrue(c.contains("200.0, priority >= PRIORITY_HELD_8284, nowMs)"))
    }
}
