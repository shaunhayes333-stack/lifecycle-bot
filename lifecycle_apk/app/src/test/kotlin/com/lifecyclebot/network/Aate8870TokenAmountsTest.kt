package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

internal object TokenAmountFixture8870 {
    const val wallet = "Wallet8870"
    const val mint = "Mint8870"
    const val wsol = "So11111111111111111111111111111111111111112"
    const val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    fun raw(amount: String, decimals: Int) = JSONObject().put("amount", amount).put("decimals", decimals).put("uiAmount", JSONObject.NULL)
    fun text(amount: String) = JSONObject().put("uiAmountString", amount).put("uiAmount", JSONObject.NULL)
    fun numeric(amount: Double) = JSONObject().put("uiAmount", amount)
    fun row(amount: JSONObject, mint: String = this.mint) =
        JSONObject().put("owner", wallet).put("mint", mint).put("uiTokenAmount", amount)
    fun push(pre: List<JSONObject>, post: List<JSONObject>, preSol: Long = 2_000_000_000L, postSol: Long = 1_000_000_000L): JSONObject {
        val meta = JSONObject().put("err", JSONObject.NULL).put("fee", 0L)
            .put("preBalances", JSONArray().put(preSol)).put("postBalances", JSONArray().put(postSol))
            .put("preTokenBalances", JSONArray(pre)).put("postTokenBalances", JSONArray(post))
        val message = JSONObject().put("accountKeys", JSONArray().put(wallet))
        return JSONObject().put("signature", "sig8870").put("transaction",
            JSONObject().put("transaction", JSONObject().put("message", message)).put("meta", meta))
    }
    fun meta(push: JSONObject): JSONObject = push.getJSONObject("transaction").getJSONObject("meta")
}

class Aate8870TokenAmountsTest {
    private val f = TokenAmountFixture8870
    private val watched = listOf(f.wallet)

    @Test fun rawUnsignedAmountUsesItsStatedDecimalsAndKeepsExactPrecision() {
        val fullU64 = f.raw("18446744073709551615", 9)
        assertEquals(0, BigDecimal("18446744073.709551615").compareTo(SolanaTokenAmounts8870.amount8870(fullU64)))
        assertEquals(BigDecimal.ZERO, SolanaTokenAmounts8870.amount8870(f.raw("0", 0)))
        assertEquals(BigDecimal("7"), SolanaTokenAmounts8870.amount8870(f.raw("7", 0)))
        assertEquals(BigDecimal("1.25"), SolanaTokenAmounts8870.amount8870(f.text("1.25")))
        assertEquals(BigDecimal("1.25"), SolanaTokenAmounts8870.amount8870(f.numeric(1.25)))
        val high = f.push(listOf(f.row(f.raw("18446744073709551614", 6))), listOf(f.row(f.raw("18446744073709551615", 6))))
        assertEquals("a real one-base-unit increase must survive Double rounding of the holdings", 1,
            HeliusPushSwapParser7277.detectBuys(high, watched).size)
    }

    @Test fun nullableNumericFieldDoesNotDiscardAValidBuyOrPartialSell() {
        val buy = f.push(listOf(f.row(f.text("0"))), listOf(f.row(f.text("100"))))
        val buys = HeliusPushSwapParser7277.detectBuys(buy, watched)
        assertEquals(1, buys.size)
        assertEquals(1.0, buys.single().solSpent, 0.0)
        val sell = f.push(listOf(f.row(f.numeric(100.0))), listOf(f.row(f.raw("40000000", 6))), 1_000_000_000L, 2_000_000_000L)
        val sells = HeliusPushSwapParser7277.detectSells(sell, watched)
        assertEquals(1, sells.size)
        assertEquals(0.6, sells.single().soldFraction, 1e-12)
        val unchanged = f.push(listOf(f.row(f.numeric(100.0))), listOf(f.row(f.text("100"))), 1_000_000_000L, 2_000_000_000L)
        assertTrue("nullable uiAmount must not fabricate a 100% smart-money exit", HeliusPushSwapParser7277.detectSells(unchanged, watched).isEmpty())
    }

    @Test fun unknownAmountsAreNotZeroAndOneUnreadAccountPoisonsOnlyItsOwnerMintTotal() {
        val malformed = listOf(JSONObject(), f.text("NaN"), f.text("-1"), f.raw("-1", 6), f.raw("18446744073709551616", 0), f.raw("100", -1))
        for (ui in malformed) {
            assertNull(ui.toString(), SolanaTokenAmounts8870.amount8870(ui))
            val sell = f.push(listOf(f.row(f.numeric(100.0))), listOf(f.row(ui)), 1_000_000_000L, 2_000_000_000L)
            assertTrue(ui.toString(), HeliusPushSwapParser7277.detectSells(sell, watched).isEmpty())
            val buy = f.push(listOf(f.row(ui)), listOf(f.row(f.numeric(100.0))))
            assertTrue(ui.toString(), HeliusPushSwapParser7277.detectBuys(buy, watched).isEmpty())
        }
        val partial = f.push(listOf(f.row(f.numeric(100.0)), f.row(f.numeric(100.0))),
            listOf(f.row(f.numeric(100.0)), f.row(JSONObject())), 1_000_000_000L, 2_000_000_000L)
        assertTrue(HeliusPushSwapParser7277.detectSells(partial, watched).isEmpty())
        val otherMint = f.push(listOf(f.row(JSONObject(), "OtherMint")), listOf(f.row(f.raw("100000000", 6))))
        assertEquals("an unrelated unread mint cannot suppress a separately proven buy", 1, HeliusPushSwapParser7277.detectBuys(otherMint, watched).size)
    }

    @Test fun actualZeroAndNewOrClosedAccountsRemainObservableButAbsentArraysDoNotBecomeBalances() {
        val newAccount = f.push(emptyList(), listOf(f.row(f.raw("100000000", 6))))
        assertEquals(1, HeliusPushSwapParser7277.detectBuys(newAccount, watched).size)
        for (post in listOf(emptyList(), listOf(f.row(f.raw("0", 6))))) {
            val closed = f.push(listOf(f.row(f.raw("100000000", 6))), post, 1_000_000_000L, 2_000_000_000L)
            assertEquals(1.0, HeliusPushSwapParser7277.detectSells(closed, watched).single().soldFraction, 0.0)
        }
        f.meta(newAccount).remove("preTokenBalances")
        assertTrue(HeliusPushSwapParser7277.detectBuys(newAccount, watched).isEmpty())
        val missingPost = f.push(listOf(f.row(f.numeric(100.0))), emptyList(), 1_000_000_000L, 2_000_000_000L)
        f.meta(missingPost).remove("postTokenBalances")
        assertTrue(HeliusPushSwapParser7277.detectSells(missingPost, watched).isEmpty())
    }

    @Test fun wrappedSolUsesNineDecimalsAndOwnerAccountsAreSummedBeforeTheFraction() {
        val buy = f.push(listOf(f.row(f.raw("2000000000", 9), f.wsol)),
            listOf(f.row(f.raw("0", 9), f.wsol), f.row(f.raw("100", 0))), 1_000_000_000L, 1_000_000_000L)
        assertEquals(2.0, HeliusPushSwapParser7277.detectBuys(buy, watched).single().solSpent, 0.0)
        val sell = f.push(listOf(f.row(f.raw("100000000", 6)), f.row(f.raw("50000000", 6))),
            listOf(f.row(f.raw("100000000", 6)), f.row(f.raw("40000000", 6))), 1_000_000_000L, 2_000_000_000L)
        assertEquals(10.0 / 150.0, HeliusPushSwapParser7277.detectSells(sell, watched).single().soldFraction, 1e-12)
    }

    @Test fun anyNonNullTransactionErrorRejectsSignalsForBothDirections() {
        for (error in listOf("BlockhashNotFound", JSONObject().put("InstructionError", JSONArray().put(0).put("Custom")))) {
            val buy = f.push(emptyList(), listOf(f.row(f.numeric(100.0))))
            f.meta(buy).put("err", error)
            assertTrue(HeliusPushSwapParser7277.detectBuys(buy, watched).isEmpty())
            val sell = f.push(listOf(f.row(f.numeric(100.0))), emptyList(), 1_000_000_000L, 2_000_000_000L)
            f.meta(sell).put("err", error)
            assertTrue(HeliusPushSwapParser7277.detectSells(sell, watched).isEmpty())
        }
    }
}
