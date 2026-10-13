package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.ExactStrategyPerformance7429
import com.lifecyclebot.engine.truth.PositionExcursion8082
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8082 — blockchain finality → exact strategy learning; observed excursion; per-lane Cortex entry reads. */
class Aate8082CausalPipelineTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun fin(m: String, dq: String, pi: String = "") = CanonicalTradeFinalizedBus6450.economicFinality8082(m, dq, pi)

    @Test fun typedFinalityFromRealProducerVocabulary() {
        assertEquals("LIVE_FINALIZED", fin("live", "confirmed_signature", "confirmed_signature"))
        assertEquals("LIVE_FINALIZED", fin("live", "DLN_REVERSE_FINALIZED_SOURCE_SOL_DELTA", "ROUTE_VERIFIED_SELLABLE"))
        assertEquals("LIVE_RECONCILED", fin("live", "CONFIRMED_WALLET_RETURN_DELTA", "ROUTE_VERIFIED_SELLABLE"))
        assertEquals("PAPER_SIMULATED", fin("paper", "canonical_paper_fill", "canonical_paper_fill"))
        // legacy compound durable rows are read, not upgraded
        assertEquals("LIVE_FINALIZED", fin("live", "confirmed_signature:confirmed_signature"))
        // false / missing proofs stay out
        assertEquals("LIVE_UNVERIFIED", fin("live", "", ""))
        assertEquals("LIVE_UNVERIFIED", fin("live", "canonical_paper_fill"))
        assertEquals("PAPER_UNVERIFIED", fin("paper", "confirmed_signature"))
        assertEquals("UNVERIFIED", fin("unknown", "confirmed_signature"))
        assertTrue(ExactStrategyPerformance7429.terminalEconomicsEligible8081("LIVE", fin("live", "confirmed_signature")))
        assertFalse(ExactStrategyPerformance7429.terminalEconomicsEligible8081("LIVE", fin("live", "")))
    }

    @Test fun publisherReconcilerAndPersistenceShareTheTypedFinality() {
        val b = src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        assertTrue(b.contains("proofState = event.economicFinality,"))
        assertFalse(b.contains("proofState = \"\${event.dataQuality}:\${event.priceIntegrity}\""))
        assertTrue(src("engine/truth/FinalizedLearningReconciler7423.kt").contains("proofState = rich?.economicFinality"))
        assertTrue(src("engine/truth/CanonicalFinalityPersistence6486.kt").contains("finality8082"))
        val s = src("engine/sell/SellFinalizationCoordinator.kt")
        assertTrue(s.contains("economicFinality = if (sellSig.isNotBlank() && actualConsumedRaw.signum() > 0) \"LIVE_FINALIZED\" else \"LIVE_UNVERIFIED\""))
        assertTrue(s.contains("economicEventId = idKey,"))
    }

    @Test fun exactStrategyCountsEachPositionOnce() {
        ExactStrategyPerformance7429.clearSeenForTest8082()
        assertTrue(ExactStrategyPerformance7429.claimOnce8082("pos-1"))
        assertFalse(ExactStrategyPerformance7429.claimOnce8082("pos-1"))
        assertTrue(ExactStrategyPerformance7429.claimOnce8082("pos-2"))
    }

    @Test fun excursionIsObservedOrUnknownNeverFabricated() {
        PositionExcursion8082.clearForTest()
        assertTrue(PositionExcursion8082.mfeFor("never").isNaN())
        PositionExcursion8082.observe("p", 10.0, "T", 1_000L)
        PositionExcursion8082.observe("p", 250.0, "T", 2_000L)
        PositionExcursion8082.observe("p", -12.0, "T", 3_000L)
        val e = PositionExcursion8082.forClose("p")!!
        assertEquals(250.0, e.mfePct, 1e-9); assertEquals(-12.0, e.maePct, 1e-9); assertEquals(2_000L, e.mfeAtMs)
        val n = src("engine/truth/EntryStrategySnapshot6450.kt")
        assertFalse(n.contains("env.mfePct.takeIf { it != 0.0 } ?: maxOf(0.0, env.realizedReturnPct)"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("val mfePct: Double = Double.NaN,"))
        assertTrue(src("engine/truth/PositionDisplayTruth8078.kt").contains("PositionExcursion8082.observe(pos.positionId, v.pnlPct"))
    }

    @Test fun cortexEntryReadsAreKeyedPerLane() {
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("entryReads[\"\$normalized|\$m|\$laneKey8082\"]"))
        assertTrue(c.contains("entryReads[\"\$normalized8082|\${if (paper) \"PAPER\" else \"LIVE\"}|\${canon(a.lane)}\"] = a"))
        assertTrue(c.contains("ENTRY_BIND_OWNER_LANE_UNASSESSED_8082"))
    }
}
