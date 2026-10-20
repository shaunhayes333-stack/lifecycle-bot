package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.TxMetaSellFinalizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Captured finalized RPC responses, not invented transaction or price fixtures. */
class Aate8253RowleySettlementReplayTest {
    private val mint = "9o3iDgNPnZTMF8GcXKxzFTxRqme9mJw5WF7RnXtMpump"
    private val owner = "BpDDptyWCTkNzPUUGRJJKbiPRS5skRho1J6shur4uUrG"
    private fun transaction(name: String): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/forensics/rowley-8253/$name.json")!!.bufferedReader().use { it.readText() }
    ).getJSONObject("result")

    @Test fun originalBuyPartialAndClosePassTheLiveDecoderAndFinalityRules() {
        val buy = TradeVerifier.parseTransactionForOwner(transaction("buy"), owner, mint)!!
        val partial = TradeVerifier.parseTransactionForOwner(transaction("partial"), owner, mint)!!
        val close = TradeVerifier.parseTransactionForOwner(transaction("close"), owner, mint)!!
        assertNull(buy.metaErr)
        assertNull(partial.metaErr)
        assertNull(close.metaErr)
        assertEquals(6, buy.decimals)
        assertEquals(BigInteger("158329976511"), buy.rawAfter)
        assertEquals(buy.rawAfter, partial.rawBefore)
        assertEquals(BigInteger("94997985906"), partial.rawBefore - partial.rawAfter)
        assertEquals(BigInteger("63331990605"), partial.rawAfter)
        assertEquals(partial.rawAfter, close.rawBefore)
        assertEquals(BigInteger.ZERO, close.rawAfter)
        // The token account remains open: its rent is still locked, not trading loss or proceeds.
        assertEquals(1_513_840L, buy.ataRentLamports)
        assertEquals(0L, close.closedRentLamports)
        val spent = TradeVerifier.buySolSpent7928(buy.solBefore, buy.solAfter, buy.ataRentLamports)
        val partialNet = TradeVerifier.sellSolReceived7928(partial.solBefore, partial.solAfter, partial.closedRentLamports)
        val closeNet = TradeVerifier.sellSolReceived7928(close.solBefore, close.solAfter, close.closedRentLamports)
        assertEquals(29_891_786L, spent)
        assertEquals(17_153_559L, partialNet)
        assertEquals(11_358_235L, closeNet)
        assertEquals(-1_379_992L, partialNet + closeNet - spent)
        for ((name, proof, net, expected) in listOf(
            Leg("partial", partial, partialNet, TxMetaSellFinalizer.FinalState.PARTIAL_SELL),
            Leg("close", close, closeNet, TxMetaSellFinalizer.FinalState.CLEARED),
        )) {
            val tx = transaction(name)
            val result = TxMetaSellFinalizer.finalize(mint,
                tx.getJSONObject("transaction").getJSONArray("signatures").getString(0), proof.rawBefore,
                proof.rawBefore, proof.rawAfter, proof.rawAfter, net, tx.getLong("slot"))
            assertEquals(expected, result.finalState)
            assertEquals(proof.rawBefore - proof.rawAfter, result.actualConsumedRaw)
            assertEquals(proof.rawAfter, result.remainingRaw)
        }
    }

    private data class Leg(val name: String, val proof: TradeVerifier.TxParse, val net: Long,
                           val expected: TxMetaSellFinalizer.FinalState)
}
