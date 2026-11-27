package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class Aate9048ReceiptAndDiscoveryTest {
    private val mint = "fixtureMint"
    private val pid = "LIVE:fixtureMint:1:2"
    private fun raw(n: Long) = BigInteger.valueOf(n)
    private fun buy(at: Long, key: String, qty: Long, cost: Double, price: Double = 0.000020632276, id: String = pid) =
        EconomicEventSchema6464.Buy(at, "live", id, mint, "FIX", key, cost, .0001, raw(qty), price, 6, 6, "MOONSHOT", "SOLANA_TOKEN")
    private fun part(at: Long, key: String, sold: Long, rem: Long, basis: Double, remCost: Double) =
        EconomicEventSchema6464.Sell(at, "live", pid, mint, "FIX", key, true, raw(sold), basis, .03, .001, .029, .029-basis, 0.0, raw(rem), remCost)
    @Test fun exactRecoveryPreservesIdentityTimeAndBasis() {
        val row = buy(1000L,"buy1",150474239734L,.042454)
        val p = ExactLiveReceiptRecovery9048.reconstruct(listOf(row),mint,row.filledQty,6,50000L)!!
        assertEquals(pid,p.positionId)
        assertEquals(1000L,p.openedAtMs)
        assertEquals(.042454,p.entryCostSol,1e-12)
        assertEquals(row.fillPrice,p.entryPriceUsd,1e-12)
        assertEquals(.0001,p.feesSol,1e-12)
        assertEquals(row.filledQty,p.remainingQtyRaw)
    }
    @Test fun partialBeforeTopUpIsNotForgotten() {
        val p = ExactLiveReceiptRecovery9048.reconstruct(
            listOf(buy(1000,"b1",1000,.04,.001),part(2000,"s1",500,500,.02,.02),buy(3000,"b2",500,.02,.002)),
            mint,raw(1000),6,5000)!!
        assertEquals(pid,p.positionId)
        assertEquals(raw(1500),p.originalQtyRaw)
        assertEquals(raw(1000),p.remainingQtyRaw)
        assertEquals(.06,p.entryCostSol,1e-10)
        assertEquals(.02,p.soldCostBasisSol,1e-10)
        assertEquals(.0015,p.entryPriceUsd,1e-10)
        assertEquals(1000L,p.openedAtMs)
    }
    @Test fun ambiguousOrSpentReceiptDoesNotReopen() {
        val b = buy(1000,"b1",1000,.04)
        assertNull(ExactLiveReceiptRecovery9048.reconstruct(listOf(b),mint,raw(999),6,5000))
        assertNull(ExactLiveReceiptRecovery9048.reconstruct(listOf(b),mint,raw(1000),9,5000))
        assertNull(ExactLiveReceiptRecovery9048.reconstruct(listOf(b,b),mint,raw(2000),6,5000))
        assertNull(ExactLiveReceiptRecovery9048.reconstruct(listOf(b,buy(2000,"b2",1000,.04,id="another")),mint,raw(1000),6,5000))
        assertNull(ExactLiveReceiptRecovery9048.reconstruct(listOf(b,part(2000,"s1",500,500,.02,.01)),mint,raw(500),6,5000))
        assertNull(ExactLiveReceiptRecovery9048.reconstruct(listOf(b,part(2000,"s1",1000,0,.04,0.0).copy(partial=false)),mint,raw(1000),6,5000))
    }
    @Test fun explorationIsBoundedAndDeduplicated() {
        val b = DiscoveryProbe9048(perMinute=2,divisor=1)
        assertTrue(b.permit("a",60000))
        assertFalse(b.permit("a",60001))
        assertTrue(b.permit("b",60001))
        assertFalse(b.permit("c",60002))
        assertTrue(b.permit("c",120000))
        assertFalse(b.permit("d",60000))
    }
}
