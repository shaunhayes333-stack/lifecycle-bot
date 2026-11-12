package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.MarketCohort8701
import com.lifecyclebot.perps.PriceMissBackoff8717
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8717 — the findings the 8712 audit left open: tickets spent on coins the door refuses, starved inputs, pooled cohorts. */
class Aate8717OpenFindingsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun admissionReadsTheBuyDoorsBlockAndTheBackoffBeforeATicket() {
        val allow = PreTradeHardGate.Verdict(true, "OK", "")
        val block = PreTradeHardGate.Verdict(false, "LP_LOCK_BELOW_REQUIRED_8522", "lp=12")
        val wait = PreTradeHardGate.Verdict(false, "DEFER_SAFETY_PROOF", "RUGCHECK_PENDING|x")
        assertNull(PreTradeHardGate.doorBlockOf8717(allow)); assertNull(PreTradeHardGate.doorBlockOf8717(null))
        assertEquals("LP_LOCK_BELOW_REQUIRED_8522", PreTradeHardGate.doorBlockOf8717(block))
        assertNull("a proof wait is not a block", PreTradeHardGate.doorBlockOf8717(wait))
        assertEquals("RUGCHECK_PENDING", PreTradeHardGate.proofWaitOf8705(wait))
        val admit = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "GENERIC_PRIOR_CLEARS_8136", false, null, 4.0, listOf("gross=9 cost=3"))
        val refused = CandidateAdmission8136.doorBlocked8717(admit, "LP_LOCK_BELOW_REQUIRED_8522")
        assertEquals(CandidateAdmission8136.Terminal8136.REFUSE, refused.terminal)
        assertEquals("PRETRADE_HARD_BLOCK_LP_LOCK_BELOW_REQUIRED_8522", refused.reason)
        assertTrue(com.lifecyclebot.engine.truth.LaneAuthority8577.binding8577(refused.reason))
        assertEquals(0L, ExecutionAttemptLease.buyBackoffLeftMs8717("noSuchMint8717", "CASHGEN"))
        val ca = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(ca.indexOf("doorBlockOf8717(door8717)") in 1 until ca.indexOf("buyBackoffLeftMs8717(ts.mint"))
        assertTrue(ca.indexOf("buyBackoffLeftMs8717(ts.mint") in 1 until ca.indexOf("proofWaitOf8705(door8717)"))
    }

    @Test fun anUnpriceableCryptoTokenWaitsBeforeItIsAskedAgain() {
        PriceMissBackoff8717.resetForTest()
        val t = 1_000_000L
        assertFalse(PriceMissBackoff8717.cooling("cg:x", t))
        PriceMissBackoff8717.noteMiss("cg:x", t)
        assertTrue(PriceMissBackoff8717.cooling("cg:x", t + 59_000L))
        assertFalse(PriceMissBackoff8717.cooling("cg:x", t + 60_000L))
        PriceMissBackoff8717.noteMiss("cg:x", t + 60_000L)
        assertTrue("the wait doubles", PriceMissBackoff8717.cooling("cg:x", t + 60_000L + 119_000L))
        assertEquals(PriceMissBackoff8717.MAX_MS, PriceMissBackoff8717.waitMs(40))
        PriceMissBackoff8717.notePriced("cg:x")
        assertFalse(PriceMissBackoff8717.cooling("cg:x", t + 61_000L))
        val ct = src("perps/CryptoAltTrader.kt")
        assertTrue(ct.indexOf("if (PriceMissBackoff8717.cooling(tok.canonicalIdentity6544)) continue") in 1 until
            ct.indexOf("DynamicAltTokenRegistry.refreshPriceForMintBlocking(tok.canonicalIdentity6544)"))
        PriceMissBackoff8717.resetForTest()
    }

    @Test fun aFreshLaunchIsItsOwnCohort() {
        val other = "3nN4GVY7eS9JwWiiTFwzJBMTCv6rD8a2gofADeq6Qm63"
        assertEquals("ORIGIN8701_OTHER_FRESH_POOL_5K_20K", MarketCohort8701.cohortAt8717(other, 14_000.0, 1.2))
        assertEquals("ORIGIN8701_OTHER_POOL_5K_20K", MarketCohort8701.cohortAt8717(other, 14_000.0, MarketCohort8701.FRESH_MIN_8717))
        assertEquals("unknown birth stays in the pooled cohort", MarketCohort8701.cohort8701(other, 14_000.0), MarketCohort8701.cohortAt8717(other, 14_000.0, Double.NaN))
        assertEquals("ORIGIN8701_PUMP_FRESH_POOL_20K_100K", MarketCohort8701.cohortAt8717("2uqXwpNMXpiDzrJMGu2NVyszzteAbUHVx1GtFpBupump", 30_000.0, 0.5))
        assertTrue(MarketCohort8701.statusLine8701().contains("OTHER_FRESH{"))
    }

    @Test fun aGatesAllLaneRecordCountsACoinOnce() {
        GateOutcome8418.resetForTests8418()
        val gate = "WATCH_FIRST_7994_CELL_NEGATIVE"
        for (lane in listOf("MOONSHOT", "EXPRESS", "SHITCOIN", "CORE")) GateOutcome8418.book8418(gate, lane, -9.0, false, "mintA", 1_000L)
        assertEquals("one coin, four lanes: one all-lanes label", 1.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        assertEquals(1.0, GateOutcome8418.stat8418(gate, "EXPRESS")!!.n, 0.0)
        GateOutcome8418.book8418(gate, "MOONSHOT", -9.0, false, "mintA", 1_000L + 15 * 60_000L)
        assertEquals("a later episode of the same coin counts again", 2.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        GateOutcome8418.resetForTests8418()
    }

    @Test fun candleReadsOwnTheGeckoHostAndTheBirthProofSaysWhyItFailed() {
        val g = com.lifecyclebot.network.GeckoHostBudget8382
        assertEquals(g.BURST_8382 - 1.0, g.BULK_RESERVE_8382, 0.0)
        val h = src("engine/truth/TokenBirthHydrator7441.kt")
        assertTrue(h.contains("noteRpcError8441(rpc, \"getTransaction\", e.optInt(\"code\", -1), e.optString(\"message\"))"))
        assertTrue(h.contains("\${walkOutcomes8435()} \${txMissLine8717()}"))
    }
}
