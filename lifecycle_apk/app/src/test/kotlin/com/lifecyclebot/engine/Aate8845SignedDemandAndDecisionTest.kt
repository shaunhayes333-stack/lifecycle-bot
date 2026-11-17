package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.MemeStrategies8546
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 8845: no phantom 5m flow, no borrowed strategy economics, and no thin evidence. */
class Aate8845SignedDemandAndDecisionTest {
    private val now = 1_800_000L

    @Before fun resetBuyerFlow() = BuyerFlow8666.clearForTest()

    @Test fun signedBuyersTriggerPaperResearchBeforeFiveMinuteTapeExists() {
        fun buy(who: String, secAgo: Long, amount: Double) =
            BuyerFlow8666.note("freshPump", who, amount, true, now - secAgo * 1000L, "tx-$who", now)
        buy("earlyA", 26, 1.0)
        buy("earlyB", 22, 1.0)
        buy("nowA", 8, 2.0)
        buy("nowB", 5, 2.0)
        buy("nowC", 1, 2.0)
        val f = BuyerFlow8666.read("freshPump", now)
        assertNotNull(f)
        assertTrue(f!!.sustainedDemand)
        assertNotNull(MemeStrategies8546.signedDemandForming8845(f, 2.0))
        assertEquals(listOf("freshPump"), BuyerFlow8666.activeMints8845(now))
        assertNull(MemeStrategies8546.signedDemandForming8845(f, 20.0))
    }

    @Test fun signedWalletlessDustSingleWhaleAndStaleDoNotProduceBuyerSetup() {
        BuyerFlow8666.note("unidentified", "", 6.0, true, now, "signedButNoWallet", now)
        assertNull(MemeStrategies8546.signedDemandForming8845(BuyerFlow8666.read("unidentified", now), 1.0))
        BuyerFlow8666.note("old", "seller", 2.0, false, now - 29_000L, "oldSwap", now)
        assertFalse("old" in BuyerFlow8666.activeMints8845(now))
        assertTrue("unidentified" in BuyerFlow8666.activeMints8845(now))
        assertFalse("old" in BuyerFlow8666.activeMints8845(now + 10_000L))
    }

    private fun setup(n: Int = 50, mean: Double = 24.0, se: Double = 4.0, id: String = "RECLAIM") =
        CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se)

    @Test fun sameSetupProvenNetOwnsItsGrossButHeuristicStillMustClearQuotedMargin() {
        val proof = setup()
        assertEquals(25.0, CandidateAdmission8136.grossFromSetupProof8845(5.0, "RECLAIM", proof, 5.0), 1e-8)
        assertEquals(25.0, CandidateAdmission8136.grossFromSetupProof8845(Double.NaN, "RECLAIM", proof, 5.0), 1e-8)
        assertEquals(70.0, CandidateAdmission8136.grossFromSetupProof8845(70.0, "RECLAIM", proof, 5.0), 1e-8)
        assertEquals(5.0, CandidateAdmission8136.grossFromSetupProof8845(5.0, "OTHER", proof, 5.0), 1e-8)
        assertEquals(5.0, CandidateAdmission8136.grossFromSetupProof8845(5.0, "RECLAIM", setup(n = 5), 5.0), 1e-8)
        assertEquals(5.0, CandidateAdmission8136.grossFromSetupProof8845(5.0, "RECLAIM", proof.copy(suspect8290 = true), 5.0), 1e-8)
        assertEquals(5.0, CandidateAdmission8136.grossFromSetupProof8845(5.0, "RECLAIM", proof.copy(kind = CandidateAdmission8136.KIND_CELL), 5.0), 1e-8)
        assertTrue(CandidateAdmission8136.grossFromSetupProof8845(Double.NaN, "RECLAIM", proof, Double.NaN).isNaN())
    }
}
