package com.lifecyclebot.engine.cortex

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression: a global STRONG/REFUSE flip is NOT a substitute for lane-specific demonstrated inversion. */
class Aate8527DirectionalInversionAuthorityTest {
    private fun rank(verdict: String, n: Int = 100, order: String = "edge", lane: String = "QUALITY",
                     inverseLowerNet: Double = 2.0) =
        CortexRank8325.LaneRank8325(lane, "", "", verdict, n, order, inverseLowerNet)

    @Test fun aVerifiedInverseMustMatchItsOwnEdgeOrderingAndSupport() {
        val eligible = rank("INVERTED", CortexOpportunityPolarity8522.MIN_DIRECTION_ROWS_8527)
        assertTrue(CortexOpportunityPolarity8522.inverseRankVerified8527(eligible, "QUALITY"))
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(rank("INVERTED", 59), "QUALITY"))
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(rank("INVERTED", 200, "tailEv"), "QUALITY"))
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(rank("INVERTED", 200, "edge", "SHITCOIN"), "QUALITY"))
        // An inverted ranking whose "better" rejected tokens STILL lose net money is not a buy.
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(
            rank("INVERTED", 200, "edge", "QUALITY", -1.0), "QUALITY"))
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(
            rank("INVERTED", 200, "edge", "QUALITY", Double.NaN), "QUALITY"))
    }

    @Test fun correctlyRankedFlatThinOrUnknownLanesCannotBeInverted() {
        for (state in listOf("RANKS", "FLAT", "THIN", "INSUFFICIENT")) {
            assertFalse(state, CortexOpportunityPolarity8522.inverseRankVerified8527(rank(state, 200), "QUALITY"))
        }
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(null, "QUALITY"))
    }

    @Test fun staleRankCacheNeverGrantsAProspectiveOperatorOverride() {
        val cache = CortexRank8325.TtlCache8325<CortexRank8325.LaneRank8325>(30_000L)
        assertNull(cache.peekFresh8527(1_000L))
        val verdict = rank("INVERTED", 100)
        assertSame(verdict, cache.get(1_000L) { verdict })
        assertSame(verdict, cache.peekFresh8527(1_001L))
        assertNull(cache.peekFresh8527(31_000L))
        cache.invalidate()
        assertNull(cache.peekFresh8527(1_001L))
        assertSame(verdict, cache.peek()) // Historical diagnostic remains visible; not current trade authority.
        assertFalse(CortexOpportunityPolarity8522.inverseRankVerified8527(cache.peekFresh8527(31_000L), "QUALITY"))
    }

    @Test fun liveProjectionUsesFreshLaneRankAndAbstainsWhenItIsUnproven() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(source.contains("rankCache8325.peekFresh8527(nowMs)?.get(lane)"))
        assertTrue(source.contains("nowMs, accountMode8077(), rankedEvidence8527"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CortexOpportunityPolarity8522.kt").readText()
        assertTrue(gate.contains("LANE_INVERSION_UNPROVEN_8527"))
        assertTrue(gate.contains("inverseRankVerified8527(verifiedLaneRank8527, source.lane)"))
    }
}
