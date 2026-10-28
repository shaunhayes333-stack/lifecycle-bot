package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.engine.execution.TxParseHelper
import com.lifecyclebot.network.SignedTxLedger8085
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** V5.0.8418 — items 9/11/12: receipts settle positions, top-ups are two-phase, a sent attempt is never re-sent. */
class Aate8418SettlementTest {
    private val owner = "Owner8418111111111111111111111111111111111"
    private val other = "Other8418222222222222222222222222222222222"
    private val mint = "Mint84183333333333333333333333333333333333"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private fun buy(outcome: TradeVerifier.Outcome, raw: Long, spent: Long, fee: Long) = TradeVerifier.BuyResult(
        outcome, "sigBuy8418", mint, BigInteger.valueOf(raw), raw / 1e6, 6, spent, 0L, 0L, fee, null,
    )

    // ── item 9: receipt settlement ──

    @Test fun buyFillIsTheReceiptCostNotThePlannedSize() {
        val f = requireNotNull(Settlement8418.buyFill8418(buy(TradeVerifier.Outcome.LANDED, 2_500_000, 40_105_000L, 105_000L)))
        assertEquals(0.04, f.costSol, 1e-12)
        assertEquals(0.000105, f.feeSol, 1e-12)
        assertEquals(2.5, f.qtyUi, 1e-12)
        assertEquals(BigInteger.valueOf(2_500_000), f.rawQty)
    }

    @Test fun noReceiptMeansPendingNeverAnEstimate() {
        assertNull(Settlement8418.buyFill8418(null))
        assertNull(Settlement8418.buyFill8418(buy(TradeVerifier.Outcome.INCONCLUSIVE_PENDING, 2_500_000, 40_000_000L, 5_000L)))
        assertNull(Settlement8418.buyFill8418(buy(TradeVerifier.Outcome.FAILED_CONFIRMED, 0, 0L, 5_000L)))
        assertNull("a fee-only receipt is not a fill", Settlement8418.buyFill8418(buy(TradeVerifier.Outcome.LANDED, 2_500_000, 5_000L, 5_000L)))
        assertNull(Settlement8418.buyFill8418(buy(TradeVerifier.Outcome.LANDED, 0, 40_000_000L, 5_000L)))
        val pending = TradeVerifier.SellResult(TradeVerifier.Outcome.INCONCLUSIVE_PENDING, "s", mint, BigInteger.ZERO, 0.0, 6, 0L, false, null)
        assertNull(Settlement8418.sellProceedsSol8418(pending))
        val landed = TradeVerifier.SellResult(TradeVerifier.Outcome.LANDED, "s", mint, BigInteger.TEN, 1e-5, 6, 61_000_000L, true, null)
        assertEquals(0.061, Settlement8418.sellProceedsSol8418(landed)!!, 1e-12)
    }

    /** A getTransaction result: owner at [ownerIdx], lamports per account, owner token balances for [mint]. */
    private fun tx(keys: List<String>, pre: List<Long>, post: List<Long>, fee: Long, preTok: Long?, postTok: Long?,
                   usdcPre: Long? = null, usdcPost: Long? = null, err: Any? = null): JSONObject {
        val k = JSONArray(); keys.forEach { k.put(JSONObject().put("pubkey", it)) }
        fun bal(idx: Int, m: String, amt: Long, dec: Int) = JSONObject().put("accountIndex", idx).put("mint", m).put("owner", owner)
            .put("uiTokenAmount", JSONObject().put("amount", amt.toString()).put("decimals", dec))
        val preT = JSONArray(); val postT = JSONArray()
        if (preTok != null) preT.put(bal(2, mint, preTok, 6))
        if (postTok != null) postT.put(bal(2, mint, postTok, 6))
        if (usdcPre != null) preT.put(bal(3, usdc, usdcPre, 6))
        if (usdcPost != null) postT.put(bal(3, usdc, usdcPost, 6))
        val meta = JSONObject().put("err", err ?: JSONObject.NULL).put("fee", fee)
            .put("preBalances", JSONArray(pre)).put("postBalances", JSONArray(post))
            .put("preTokenBalances", preT).put("postTokenBalances", postT)
        return JSONObject().put("meta", meta).put("slot", 1L)
            .put("transaction", JSONObject().put("message", JSONObject().put("accountKeys", k)))
    }

    @Test fun anotherAccountsMovementInTheSameTxNeverReachesTheOwnersPnl() {
        // The owner sold 1.0 token for +0.2 SOL; another account in the same tx moved 5 SOL. A whole-wallet balance
        // diff taken around the send would also have counted any concurrent transfer; the receipt counts neither.
        val t = tx(listOf(owner, other, "ata", "usdcAta"), listOf(1_000_000_000L, 9_000_000_000L, 2_039_280L, 0L),
            listOf(1_200_000_000L, 4_000_000_000L, 2_039_280L, 0L), 5_000L, 1_000_000L, 0L)
        val p = requireNotNull(TradeVerifier.parseTransactionForOwner(t, owner, mint))
        assertEquals(200_000_000L, TradeVerifier.sellSolReceived7928(p.solBefore, p.solAfter, p.closedRentLamports))
        val all = requireNotNull(TxParseHelper.parseTransaction8090(owner, "sigClose", t))
        assertEquals(0.2, Settlement8418.closeProceedsSol8418(all, usdc, 150.0)!!, 1e-12)
    }

    @Test fun ownerMissingFromTheReceiptIsNoReceiptNotAZeroCostFill() {
        val t = tx(listOf(other, "x", "ata", "usdcAta"), listOf(1L, 1L, 0L, 0L), listOf(1L, 1L, 2_039_280L, 0L), 5_000L, null, 2_500_000L)
        assertNull("0 -> 0 lamports was booked as a landed buy at zero cost", TradeVerifier.parseTransactionForOwner(t, owner, mint))
        val noFee = tx(listOf(owner, other, "ata", "usdcAta"), listOf(1L, 1L, 0L, 0L), listOf(1L, 1L, 0L, 0L), 5_000L, null, 1L)
        noFee.getJSONObject("meta").remove("fee")
        assertNull(TradeVerifier.parseTransactionForOwner(noFee, owner, mint))
    }

    @Test fun closeProceedsNeedTheirOwnReceiptAndAnObservedSolPrice() {
        val withUsdc = tx(listOf(owner, other, "ata", "usdcAta"), listOf(1_000_000_000L, 0L, 0L, 0L),
            listOf(999_995_000L, 0L, 0L, 0L), 5_000L, null, null, usdcPre = 0L, usdcPost = 15_000_000L)
        val p = requireNotNull(TxParseHelper.parseTransaction8090(owner, "sigUsdc", withUsdc))
        assertEquals(15.0 / 150.0 - 0.000005, Settlement8418.closeProceedsSol8418(p, usdc, 150.0)!!, 1e-12)
        assertNull("no SOL/USD observed: pending, never a stand-in price", Settlement8418.closeProceedsSol8418(p, usdc, 0.0))
        assertNull(Settlement8418.closeProceedsSol8418(null, usdc, 150.0))
        val failed = tx(listOf(owner, other, "ata", "usdcAta"), listOf(1_000_000_000L, 0L, 0L, 0L),
            listOf(999_995_000L, 0L, 0L, 0L), 5_000L, null, null, err = JSONObject().put("InstructionError", 1))
        assertNull(Settlement8418.closeProceedsSol8418(TxParseHelper.parseTransaction8090(owner, "sigFail", failed), usdc, 150.0))
    }

    // ── item 11: two-phase top-up ──

    @Test fun topUpChangesSizeEntryAndBasisOnlyFromTheConfirmedFill() {
        val pos = Position(qtyToken = 1_000.0, entryPrice = 0.01, costSol = 0.05, entryTime = 1L)
        val fill = Settlement8418.Fill8418("sigTop", BigInteger.valueOf(500_000_000L), 6, 500.0, 0.04, 0.0001)
        val after = Settlement8418.appliedTopUp8418(pos, fill, 200.0, 0.02, 99L)
        assertEquals(1_500.0, after.qtyToken, 1e-9)
        assertEquals(0.09, after.costSol, 1e-12)
        assertEquals(0.04, after.topUpCostSol, 1e-12)
        assertEquals(1, after.topUpCount)
        // (1000 x $0.01 + 0.04 SOL x $200) / 1500
        assertEquals((10.0 + 8.0) / 1_500.0, after.entryPrice, 1e-12)
        assertEquals(99L, after.lastTopUpTime)
        val noUsd = Settlement8418.appliedTopUp8418(pos, fill, 0.0, 0.02, 99L)
        assertEquals("no SOL/USD: the prior entry stays, nothing guessed", 0.01, noUsd.entryPrice, 0.0)
        assertEquals(1_500.0, noUsd.qtyToken, 1e-9)
    }

    @Test fun executorTopUpIsWiredReceiptFirst() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val body = src.substringAfter("private fun liveTopUp(").substringBefore("// ── buy ──")
        val receipt = body.indexOf("Settlement8418.buyFill8418(")
        val mutate = body.indexOf("ts.position = Settlement8418.appliedTopUp8418(")
        assertTrue(receipt > 0 && mutate > receipt)
        assertTrue("the wallet-wide token poll is gone", !body.contains("cur - preTopUpQty"))
        assertTrue("planned sol is no longer the booked cost", !body.contains("costSol        = pos.costSol + sol"))
    }

    // ── item 12: ownership + no duplicate order for one attempt ──

    @Test fun anAttemptWhoseBuyIsUnresolvedOrConfirmedIsSpent() {
        SignedTxLedger8085.resetForTest()
        try {
            SignedTxLedger8085.record("sigA8418", "b64", "bh", SignedTxLedger8085.Intent(mint, "BUY", intentId = "att8418"))
            assertEquals("sigA8418", SignedTxLedger8085.attemptSpent8418("att8418")?.signature)
            SignedTxLedger8085.transition("sigA8418", SignedTxLedger8085.State.CONFIRMED)
            assertNull("unresolved-only check lets a confirmed attempt re-send", SignedTxLedger8085.unresolvedAttempt8198("att8418"))
            assertEquals("sigA8418", SignedTxLedger8085.attemptSpent8418("att8418")?.signature)
            assertNull(SignedTxLedger8085.attemptSpent8418(""))
            SignedTxLedger8085.record("sigB8418", "b64", "bh", SignedTxLedger8085.Intent(mint + "x", "BUY", intentId = "att8418b"))
            SignedTxLedger8085.transition("sigB8418", SignedTxLedger8085.State.FAILED_ON_CHAIN, "err")
            assertNull("a chain-failed attempt may be retried", SignedTxLedger8085.attemptSpent8418("att8418b"))
        } finally { SignedTxLedger8085.resetForTest() }
    }

    @Test fun ticketRetryEndsASpentAttemptInsteadOfHoldingItForAResend() {
        SignedTxLedger8085.resetForTest()
        TicketRetry8327.resetForTests8327()
        try {
            val r = "LIVE_BUY_NOT_OPENED_7948:TOKEN_MAP_ROUTE_PENDING_7371"
            assertNull("control: a transient stop is held", TicketRetry8327.retryOrTerminal8327("QUALITY", "free8418", r, 1_000L))
            SignedTxLedger8085.record("sigC8418", "b64", "bh", SignedTxLedger8085.Intent(mint, "BUY", intentId = "spent8418"))
            SignedTxLedger8085.transition("sigC8418", SignedTxLedger8085.State.CONFIRMED)
            assertEquals("${r}_ATTEMPT_SPENT_NO_RETRY_8418", TicketRetry8327.retryOrTerminal8327("QUALITY", "spent8418", r, 1_000L))
        } finally { SignedTxLedger8085.resetForTest(); TicketRetry8327.resetForTests8327() }
    }

    @Test fun aSignedSellCarriesItsPositionAcrossARestart() {
        SignedTxLedger8085.resetForTest()
        val prior = SignedTxLedger8085.liveOwnerResolver8418
        try {
            SignedTxLedger8085.liveOwnerResolver8418 = { m -> if (m == mint) "LIVE:pos8418" else "" }
            val e = SignedTxLedger8085.record("sigS8418", "b64", "bh", SignedTxLedger8085.Intent(mint, "SELL", 7L, route = "JUPITER"))
            assertEquals("LIVE:pos8418", e.positionId)
            val top = SignedTxLedger8085.record("sigT8418", "b64", "bh",
                SignedTxLedger8085.Intent(mint, "BUY", positionId = "LIVE:pos8418", intentId = Settlement8418.topUpAttemptId8418("LIVE:pos8418", mint, 2)))
            val lines = listOf(SignedTxLedger8085.encode(e), SignedTxLedger8085.encode(top))
            SignedTxLedger8085.resetForTest()
            val recovered = mutableListOf<SignedTxLedger8085.Entry>()
            assertEquals(2, SignedTxLedger8085.restore(lines) { recovered.add(it) })
            assertEquals(setOf("sigS8418", "sigT8418"), recovered.map { it.signature }.toSet())
            assertEquals("LIVE:pos8418", SignedTxLedger8085.get("sigS8418")!!.positionId)
            assertEquals("TOPUP_8418:LIVE:pos8418:2", SignedTxLedger8085.get("sigT8418")!!.intentId)
            assertNotNull("the restored top-up still blocks a second order for the same add",
                SignedTxLedger8085.attemptSpent8418("TOPUP_8418:LIVE:pos8418:2"))
            assertNotNull("and any second sell for the mint", SignedTxLedger8085.unresolvedFor(mint, "SELL"))
        } finally {
            SignedTxLedger8085.liveOwnerResolver8418 = prior
            SignedTxLedger8085.resetForTest()
        }
    }
}
