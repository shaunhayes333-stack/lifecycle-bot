package com.lifecyclebot.engine.cortex

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A global STRONG/REFUSE flip cannot substitute for observed lane-and-bucket economics. */
class Aate8527DirectionalInversionAuthorityTest {
    private fun rank(
        verdict: String, n: Int = 100, order: String = "edge", lane: String = "QUALITY",
        refuses: Int = 30, refuseNetLcb: Double = 2.0,
        strongs: Int = 30, strongNetUcb: Double = -2.0,
    ) = CortexRank8325.LaneRank8325(lane, "", "", verdict, n, order, refuses, refuseNetLcb, strongs, strongNetUcb)

    private val take = CortexOpportunityPolarity8522.Decision.TAKE_CANDIDATE
    private val refuse = CortexOpportunityPolarity8522.Decision.REFUSE
    private fun allows(rank: CortexRank8325.LaneRank8325?, action: CortexOpportunityPolarity8522.Decision) =
        CortexOpportunityPolarity8522.inverseRankVerified8527(rank, "QUALITY", action)

    @Test fun onlyOwnSupportedInvertedEdgeCohortsMayChangeTheOriginalVerdict() {
        val eligible = rank("INVERTED", CortexOpportunityPolarity8522.MIN_DIRECTION_ROWS_8527)
        assertTrue(allows(eligible, take))
        assertTrue(allows(eligible, refuse))
        assertFalse(allows(rank("INVERTED", 59), take))
        assertFalse(allows(rank("INVERTED", 200, "tailEv"), take))
        assertFalse(allows(rank("INVERTED", 200, "edge", "SHITCOIN"), take))
        assertFalse(allows(rank("INVERTED", refuses = 19), take))
        assertFalse(allows(rank("INVERTED", strongs = 19), refuse))
        assertFalse(allows(rank("INVERTED", refuseNetLcb = -1.0), take))
        assertFalse(allows(rank("INVERTED", refuseNetLcb = Double.NaN), take))
        assertFalse(allows(rank("INVERTED", strongNetUcb = 1.0), refuse))
        assertFalse(allows(rank("INVERTED", strongNetUcb = Double.NaN), refuse))
    }

    @Test fun correctlyRankedFlatThinOrUnknownLanesCannotBeGloballyFlipped() {
        for (state in listOf("RANKS", "FLAT", "THIN", "INSUFFICIENT")) {
            assertFalse(state, allows(rank(state, 200), take))
            assertFalse(state, allows(rank(state, 200), refuse))
        }
        assertFalse(allows(null, take))
        assertFalse(allows(rank("INVERTED"), CortexOpportunityPolarity8522.Decision.ABSTAIN))
    }

    @Test fun staleRankCacheNeverGrantsProspectiveInverseAuthority() {
        val cache = CortexRank8325.TtlCache8325<CortexRank8325.LaneRank8325>(30_000L)
        assertNull(cache.peekFresh8527(1_000L))
        val verdict = rank("INVERTED", 100)
        assertSame(verdict, cache.get(1_000L) { verdict })
        assertSame(verdict, cache.peekFresh8527(1_001L))
        assertNull(cache.peekFresh8527(31_000L))
        assertFalse(allows(cache.peekFresh8527(31_000L), take))
        cache.invalidate()
        assertNull(cache.peekFresh8527(1_001L))
        assertSame(verdict, cache.peek()) // Diagnostics may show stale value; live authority may not.
    }

    @Test fun missingForecastPairsCannotMasqueradeAsSixtyRankedOutcomes() {
        val rows = (0 until 100).map { i ->
            CortexGradeBook8144.RankRow8324(
                if (i < 10) (100 - i).toDouble() else Double.NaN,
                Double.NaN, (i + 10).toDouble(), CortexScoreboard7885.Bucket.REFUSE.ordinal, false,
            )
        }
        val ranked = CortexRank8325.rankLane8325("QUALITY", rows, false, null)!!
        assertEquals(10, ranked.rows)
        assertFalse(allows(ranked, take))
    }

    @Test fun profitableBottomFifthCannotAuthorizeLosingWholeRefuseBucket() {
        val rows = (0 until 100).map { i ->
            CortexGradeBook8144.RankRow8324(
                i.toDouble(), Double.NaN, if (i < 20) 1.0 else -20.0,
                if (i < 60) CortexScoreboard7885.Bucket.REFUSE.ordinal
                else CortexScoreboard7885.Bucket.STRONG.ordinal, false,
            )
        }
        val ranked = CortexRank8325.rankLane8325("QUALITY", rows, false, null)!!
        assertEquals("INVERTED", ranked.verdict)
        assertEquals(60, ranked.refuseCount8527)
        assertTrue(ranked.refuseNetLowerPct8527 < 0.0)
        assertFalse(allows(ranked, take))
    }

    @Test fun trueProfitableRefuseAndLosingStrongBucketsHaveIndependentProof() {
        val rows = (0 until 100).map { i ->
            CortexGradeBook8144.RankRow8324(
                i.toDouble(), Double.NaN, if (i < 50) 5.0 else -5.0,
                if (i < 50) CortexScoreboard7885.Bucket.REFUSE.ordinal
                else CortexScoreboard7885.Bucket.STRONG.ordinal, false,
            )
        }
        val ranked = CortexRank8325.rankLane8325("QUALITY", rows, false, null)!!
        assertEquals("INVERTED", ranked.verdict)
        assertEquals(100, ranked.rows)
        assertEquals(50, ranked.refuseCount8527)
        assertEquals(50, ranked.strongCount8527)
        assertTrue(ranked.refuseNetLowerPct8527 > 0.0)
        assertTrue(ranked.strongNetUpperPct8527 < 0.0)
        assertTrue(allows(ranked, take))
        assertTrue(allows(ranked, refuse))
    }

    @Test fun liveProjectionConsumesFreshCachedRankAndHonoursBothBucketGates() {
        val runtime = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(runtime.contains("rankCache8325.peekFresh8527(nowMs)?.get(lane)"))
        assertTrue(runtime.contains("nowMs, accountMode8077(), rankedEvidence8527"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CortexOpportunityPolarity8522.kt").readText()
        assertTrue(gate.contains("REFUSE_BUCKET_INVERSION_UNPROVEN_8527"))
        assertTrue(gate.contains("STRONG_BUCKET_INVERSION_UNPROVEN_8527"))
        assertTrue(gate.contains("inverseRankVerified8527(verifiedLaneRank8527, source.lane, Decision.TAKE_CANDIDATE)"))
        assertTrue(gate.contains("inverseRankVerified8527(verifiedLaneRank8527, source.lane, Decision.REFUSE)"))
    }
}
