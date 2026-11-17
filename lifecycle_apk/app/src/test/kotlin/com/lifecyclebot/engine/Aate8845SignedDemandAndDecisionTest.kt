package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.MemeStrategies8546
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 8845/8846: no phantom five-minute flow and no inferred wallet identities. */
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

    @Test fun historicalProfitableSetupCannotReplaceItsWeakCurrentExecutableMove8846() {
        val setup = CandidateAdmission8136.Evidence8136(
            CandidateAdmission8136.KIND_SETUP, "B_SETUP", 40, 25.0, 3.0)
        val c = CandidateAdmission8136.Candidate8136(
            mint = "PROOF8846pump", lane = "SHITCOIN", selectedSetup = "A_SETUP",
            priceAgeMs = 5_000L, expectedGrossPct = 30.0, costPct = 6.0,
            downsidePct = 15.0, setups = listOf(
                CandidateAdmission8136.Evidence8136(
                    CandidateAdmission8136.KIND_SETUP, "A_SETUP", 40, -12.0, 2.0), setup),
            expectedGrossBySetup = mapOf("A_SETUP" to 30.0, "B_SETUP" to 8.0),
            genericScore = 12.0, genericFloor = 30.0,
        )
        val result = CandidateAdmission8136.decide8136(c)
        assertEquals("B_SETUP", result.chosenSetup)
        assertEquals("EXECUTION_ECONOMICS_8136", result.reason)
        assertEquals(2.0, result.expectedNetPct, 1e-8)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(expectedGrossBySetup = emptyMap())).terminal)
    }

}
