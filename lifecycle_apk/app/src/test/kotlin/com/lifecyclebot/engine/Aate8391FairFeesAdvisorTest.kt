package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class Aate8391FairFeesAdvisorTest {
    @Test fun feeIsOnePercentTotalNotPerWallet() {
        // superseded by V5.0.8418 — owner: performance fee 1% of realised net profit (was 5%)
        val result = PerformanceFees8391.assess(PerformanceFees8391.State(), 1_000_000_000)
        assertEquals(10_000_000L, result.fee)
        assertEquals(5_000_000L, result.fee / 2)
        assertEquals(0.01, PerformanceFees8391.RATE, 0.0)
    }
    @Test fun losingAndRecoveringToPriorHighWaterPaysNothing() {
        val gain = PerformanceFees8391.assess(PerformanceFees8391.State(), 1_000_000_000)
        val loss = PerformanceFees8391.assess(gain.state, -500_000_000)
        assertEquals(0L, loss.fee)
        val recovery = PerformanceFees8391.assess(loss.state, 500_000_000)
        assertEquals(0L, recovery.fee)
        val newHigh = PerformanceFees8391.assess(recovery.state, 100_000_000)
        assertEquals(1_000_000L, newHigh.fee) // superseded by V5.0.8418 — owner: performance fee 1% of realised net profit (was 5%)
    }
    @Test fun bootstrapLossMustBeRecoveredBeforeAnyFee() {
        val loss = PerformanceFees8391.assess(PerformanceFees8391.State(), -100_000_000)
        assertEquals(0L, loss.fee)
        val win = PerformanceFees8391.assess(loss.state, 150_000_000)
        assertEquals(500_000L, win.fee) // superseded by V5.0.8418 — owner: performance fee 1% of realised net profit (was 5%)
    }
    @Test fun onlyWholePositionEconomicsCountNotEarlyPartialProfit() {
        // +100 partial, -120 final: whole position is -20, not a charge on the early gain.
        assertEquals(0L, PerformanceFees8391.assess(PerformanceFees8391.State(), -20_000_000).fee)
    }
    @Test fun noCrossAccountHighWaterDependency() {
        val walletA = PerformanceFees8391.assess(PerformanceFees8391.State(), -500L)
        val walletB = PerformanceFees8391.assess(PerformanceFees8391.State(), 1000L)
        assertEquals(0L, walletA.fee); assertEquals(10L, walletB.fee) // superseded by V5.0.8418 — owner: performance fee 1% of realised net profit (was 5%)
    }
    @Test fun solConversionIsFiniteAndLamportExact() {
        assertEquals(1L, PerformanceFees8391.lamports(0.000000001))
        assertEquals(-10L, PerformanceFees8391.lamports(-0.00000001))
        assertThrows(IllegalArgumentException::class.java) { PerformanceFees8391.lamports(Double.NaN) }
        assertThrows(ArithmeticException::class.java) { PerformanceFees8391.assess(PerformanceFees8391.State(Long.MAX_VALUE,Long.MAX_VALUE),1) }
    }
    private fun suggestion(id:String, at:Long, delta:Double) = SelfHealingAdvisor.Suggestion(id,at,"exitScoreThreshold",delta,"observed","expected","med")
    @Test fun newerGenerationSupersedesOldProposal() {
        AdvisorInbox.clear()
        val old = suggestion("old",1,-1.0); val fresh = suggestion("new",2,1.0)
        AdvisorInbox.addAll(listOf(old)); AdvisorInbox.addAll(listOf(fresh))
        assertEquals(listOf(fresh),AdvisorInbox.pending())
        assertFalse(AdvisorInbox.isCurrent8391(old)); assertTrue(AdvisorInbox.isCurrent8391(fresh))
    }
    @Test fun sameGenerationContradictionsStayForReview() {
        AdvisorInbox.clear()
        AdvisorInbox.addAll(listOf(suggestion("a",3,1.0),suggestion("b",3,-1.0)))
        assertEquals(2,AdvisorInbox.pending().size)
        assertTrue(SelfHealingAdvisor.batchPlan8373(AdvisorInbox.pending().map{it.key}).all{it!=null})
    }
    @Test fun identicalSuggestionsNeverStack() {
        AdvisorInbox.clear()
        AdvisorInbox.addAll(listOf(suggestion("a",3,1.0),suggestion("b",3,1.0)))
        assertEquals(1,AdvisorInbox.pending().size)
        val id=AdvisorInbox.pending().single().id
        AdvisorInbox.markApplied(id)
        AdvisorInbox.addAll(listOf(suggestion("c",3,1.0)))
        assertTrue(AdvisorInbox.pending().isEmpty())
    }
    @Test fun successfulReviewResolvesSameGenerationConflict() {
        AdvisorInbox.clear()
        AdvisorInbox.addAll(listOf(suggestion("a",3,1.0),suggestion("b",3,-1.0)))
        AdvisorInbox.markApplied("a")
        assertTrue(AdvisorInbox.pending().isEmpty())
    }
    @Test fun tinyFeesAccumulateWithoutDroppingRoundingDust() {
        var state = PerformanceFees8391.State()
        var total=0L
        repeat(100) { val a=PerformanceFees8391.assess(state,1L); state=a.state; total+=a.fee }
        assertEquals(1L,total) // superseded by V5.0.8418 — owner: performance fee 1% of realised net profit (was 5%)
    }
    @Test fun matureCleanCostsAdaptWithoutAPermanentOnePointOneMultiplier() {
        val cost=com.lifecyclebot.engine.truth.CostLedger7962
        assertEquals(1.5,cost.adaptiveMargin8391(List(29){3.0},3.0),0.0)
        val early=cost.adaptiveMargin8391(List(30){3.0},3.0)
        val mature=cost.adaptiveMargin8391(List(120){3.0},3.0)
        // superseded by V5.0.8418 — the adaptive margin may only RAISE the owner's 1.5x (DIRECTIVE_LEDGER #28); clean costs no
        // longer lower it toward 1.0 (was: early<1.5, mature<early, entryClears8030(4.0, 3.0, early)).
        assertEquals(1.5,early,0.0); assertEquals(1.5,mature,0.0)
        assertFalse(com.lifecyclebot.engine.truth.NetEdge8030.entryClears8030(4.0,3.0,early))
        assertTrue(com.lifecyclebot.engine.truth.NetEdge8030.entryClears8030(4.5,3.0,mature))
        assertFalse(com.lifecyclebot.engine.truth.NetEdge8030.entryClears8030(2.9,3.0,mature))
        assertTrue(cost.adaptiveMargin8391(List(30){12.0},3.0)>1.5)
    }
    @Test fun quotesUseTotalFeesAndNeverTreatMissingDataAsFree() {
        assertEquals(0.5,Costs8369.quotedFeePct8391(org.json.JSONObject("{\"feeBps\":50,\"platformFee\":{\"feeBps\":10}}"),true)!!,0.0)
        assertNull(Costs8369.quotedFeePct8391(org.json.JSONObject(),true))
        assertEquals(0.0,Costs8369.quotedFeePct8391(org.json.JSONObject("{\"platformFee\":null}"),false)!!,0.0)
        assertEquals(0.5,Costs8369.routeFee8391("JUPITER","missing","BUY",100),0.0)
        val sol="So11111111111111111111111111111111111111112"
        Costs8369.noteJupiterQuote8391(sol,"mint8391",org.json.JSONObject("{\"feeBps\":10}"),true,100)
        assertEquals(0.1,Costs8369.routeFee8391("JUPITER","mint8391","BUY",101),0.0)
        assertEquals(0.5,Costs8369.routeFee8391("JUPITER","mint8391","SELL",101),0.0)
        assertEquals(0.5,Costs8369.routeFee8391("JUPITER","mint8391","BUY",60101),0.0)
    }
}
