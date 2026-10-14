package com.lifecyclebot.engine

import com.lifecyclebot.engine.execution.TxParseHelper
import com.lifecyclebot.engine.truth.TradeCostMeasurement8090
import com.lifecyclebot.network.SignedTxLedger8085 as Ledger
import com.lifecyclebot.network.SolanaWallet
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class Aate8090EconomicRegressionTest {
    @Before fun reset() { Ledger.persister = null; Ledger.resetForTest() }
    @After fun clean() { Ledger.persister = null; Ledger.resetForTest() }

    @Test fun unknownDoesNotExpireByWallClockAndLateConfirmationResolvesIt() {
        Ledger.record("s", "bytes", "hash", Ledger.Intent("mint", "BUY"), nowMs = 1L)
        Ledger.transition("s", Ledger.State.OUTCOME_UNKNOWN, nowMs = 2L)
        assertNotNull(Ledger.unresolvedFor("mint", "BUY", nowMs = 86_400_000L))
        Ledger.transition("s", Ledger.State.CONFIRMED, nowMs = 86_400_001L)
        assertNull(Ledger.unresolvedFor("mint", "BUY"))
    }

    @Test fun reservationIsAtomicAtRecordBoundary() {
        Ledger.record("s1", "bytes", "hash", Ledger.Intent("mint", "SELL", 10L))
        try {
            Ledger.record("s2", "other", "hash", Ledger.Intent("mint", "SELL", 10L))
            fail("second signed identity must be refused")
        } catch (e: SolanaWallet.DuplicateOrderPrevented8085) { assertEquals("s1", e.existingSignature) }
        assertNull(Ledger.get("s2"))
    }

    @Test fun failedDurabilityCannotReturnBroadcastableIdentity() {
        Ledger.persister = { throw IllegalStateException("disk unavailable") }
        try {
            Ledger.record("s", "bytes", "hash", Ledger.Intent("mint", "BUY"), requireDurable = true)
            fail("durability failure must reach the caller before submission")
        } catch (e: IllegalStateException) { assertEquals("disk unavailable", e.message) }
        assertNotNull(Ledger.unresolvedFor("mint", "BUY"))
    }

    @Test fun submissionTimestampSurvivesRestart() {
        Ledger.record("s", "bytes", "hash", Ledger.Intent("mint", "BUY"), nowMs = 1L)
        Ledger.transition("s", Ledger.State.SUBMIT_ATTEMPTED, nowMs = 123L)
        val encoded = Ledger.encode(Ledger.get("s")!!)
        Ledger.resetForTest()
        Ledger.restore(listOf(encoded))
        assertEquals(123L, Ledger.get("s")!!.submittedAtMs8087)
    }

    private fun token(index: Int, raw: String, decimals: Int = 6) = JSONObject()
        .put("accountIndex", index).put("owner", "wallet").put("mint", "mint")
        .put("uiTokenAmount", JSONObject().put("amount", raw).put("decimals", decimals))

    private fun tx(pre: JSONArray, post: JSONArray) = JSONObject()
        .put("slot", 123L).put("blockTime", 1000L)
        .put("transaction", JSONObject().put("message", JSONObject().put("accountKeys", JSONArray(listOf("wallet", "ata1", "ata2")))))
        .put("meta", JSONObject().put("err", JSONObject.NULL).put("fee", 5000L)
            .put("preBalances", JSONArray(listOf(1_000_000_000L, 2L, 2L)))
            .put("postBalances", JSONArray(listOf(970_000_000L, 2L, 2L)))
            .put("preTokenBalances", pre).put("postTokenBalances", post))

    @Test fun multipleOwnerAccountsSumWithoutDoublePrecisionLoss() {
        val pre = JSONArray().put(token(1, "9007199254740993")).put(token(2, "7"))
        val post = JSONArray().put(token(1, "9007199254741003")).put(token(2, "10"))
        val r = TxParseHelper.parseTransaction8090("wallet", "sig", tx(pre, post))!!
        assertEquals(BigInteger("13"), r.tokenDeltas["mint"]!!.rawDelta)
        assertEquals(setOf("ata1", "ata2"), r.tokenAccounts)
        assertEquals(123L, r.slot)
        assertEquals(5000L, r.feeLamports)
        assertEquals(-30_000_000L, r.solDeltaLamports)
    }

    @Test fun closedTokenAccountKeepsHistoricalDebit() {
        val r = TxParseHelper.parseTransaction8090("wallet", "sig", tx(JSONArray().put(token(1, "33", 0)), JSONArray()))!!
        assertEquals(BigInteger("-33"), r.tokenDeltas["mint"]!!.rawDelta)
        assertEquals(0, r.tokenDeltas["mint"]!!.decimals)
        assertFalse(r.tokenDeltas["mint"]!!.postExisted)
    }

    @Test fun absentTokenMetadataCannotBecomeZeroInventory() {
        val input = tx(JSONArray(), JSONArray())
        input.getJSONObject("meta").remove("postTokenBalances")
        assertNull(TxParseHelper.parseTransaction8090("wallet", "sig", input))
        assertNull(TxParseHelper.parseTransaction8090("otherWallet", "sig", tx(JSONArray(), JSONArray())))
    }

    @Test fun missingErrorMetadataCannotClaimSettlement() {
        val input = tx(JSONArray(), JSONArray())
        input.getJSONObject("meta").remove("err")
        assertNull(TxParseHelper.parseTransaction8090("wallet", "sig", input))
    }

    @Test fun unpaidRunnerCannotCreateOrRetainMoonbag() {
        assertEquals(TailBag8034.Decision.SetAside,
            TailBag8034.decidePure8034("MOONSHOT", "TIME_EXIT", 900.0, null, 1000L))
        assertEquals(TailBag8034.Decision.Milestone(0, 1.0 / 3.0),
            TailBag8034.decidePure8034("MOONSHOT", "TIME_EXIT", 900.0, TailBag8034.Tail(1L, 1L, 0), 1000L))
        assertEquals(TailBag8034.Decision.Pass,
            TailBag8034.decidePure8034("MOONSHOT", "RAPID_CATASTROPHE_STOP", -25.0, TailBag8034.Tail(1L, 1L, 0), 1000L, true))
    }

    @Test fun exactIdentityIsPositionBoundAndSurvivesRestart() {
        val priorPaper = RuntimeModeAuthority.isPaper()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        val h = StrategyHypothesisEngine
        h.reset()
        try {
            val identity = "BREAKOUT_CONTINUATION>LAUNCH_LADDER_PROVEN>DIAMOND_HANDS_RUNNER>MOMENTUM"
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "runner8090", identity, 8090L)
            h.bindExecutedPosition7428("position8090", "runner8090", 8090L, "MOONSHOT", "PAPER")
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "runner8090", "REVERSAL>DIP>SWING>REVERSAL", 8091L)
            assertEquals(identity.split('>'), h.exactEntryIdentity8090("position8090", "runner8090", "MOONSHOT", 8090L))
            assertNull(h.exactEntryIdentity8090("position8090", "runner8090", "MOONSHOT", 8091L))
            val saved = h.exportState()
            h.importState(saved)
            assertEquals(identity.split('>'), h.exactEntryIdentity8090("position8090", "runner8090", "MOONSHOT", 8090L))
        } finally {
            h.reset()
            RuntimeModeAuthority.publishConfig(paperMode = priorPaper, autoTrade = true)
        }
    }

    @Test fun costUsesEachPartialSalesOwnSolExchangeRate() {
        val legs = listOf(
            TradeCostMeasurement8090.Sale(BigInteger("50"), 0.74, 3.0, 200.0),
            TradeCostMeasurement8090.Sale(BigInteger("50"), 1.99, 4.0, 100.0))
        val m = TradeCostMeasurement8090.measure(1.02, BigInteger("100"), 0, 2.0, 200.0, legs)!!
        assertEquals(100.0 * 0.04 / 1.02, m.costPct, 1e-9)
        assertEquals(2.0, m.buyPct, 1e-9)
    }

    @Test fun incompleteSaleCannotTrainRoundTripCost() {
        assertNull(TradeCostMeasurement8090.measure(1.02, BigInteger("100"), 0, 2.0, 200.0,
            listOf(TradeCostMeasurement8090.Sale(BigInteger("50"), 0.49, 2.0, 200.0))))
    }

    @Test fun negativeBuyCostAndMissingConversionAreExcluded() {
        val legs = listOf(TradeCostMeasurement8090.Sale(BigInteger("100"), 0.99, 2.0, 200.0))
        assertNull(TradeCostMeasurement8090.measure(0.0508, BigInteger("100"), 0, 2.0, 200.0, legs))
        assertNull(TradeCostMeasurement8090.measure(1.02, BigInteger("100"), 0, 2.0, 0.0, legs))
    }
}
