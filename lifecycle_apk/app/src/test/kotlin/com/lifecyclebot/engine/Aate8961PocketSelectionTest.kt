package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PocketHunt8961
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803
import org.junit.Assert.*
import org.junit.Test

class Aate8961PocketSelectionTest {
    @Test fun unresolvedForecastIsAReadRequestAndContradictoryOriginIsNotAPocket() {
        val cell = listOf("origin=OTHER", "pred=10+")
        assertEquals(PocketHunt8961.Match.NEEDS_READ,
            PocketHunt8961.match(cell, setOf("origin=OTHER", "pred=NA"), 1000, 2000))
        assertEquals(PocketHunt8961.Match.NONE,
            PocketHunt8961.match(cell, setOf("origin=PUMP", "pred=NA"), 1000, 2000))
        assertEquals(PocketHunt8961.Match.MATCHED,
            PocketHunt8961.match(cell, setOf("origin=OTHER", "pred=10+"), 1000, 2000))
    }

    @Test fun noKnownConditionOrStaleOrFutureSnapshotCannotNominateAPocket() {
        val cell = listOf("stage=BASE_START")
        assertEquals(PocketHunt8961.Match.NONE, PocketHunt8961.match(cell, setOf("stage=NA"), 1000, 2000))
        for (clock in listOf(0L, 1L, 200001L))
            assertEquals(PocketHunt8961.Match.NONE, PocketHunt8961.match(cell, setOf("stage=BASE_START"), clock, 200000))
    }

    @Test fun pocketPriorityKeepsOrdinaryDiscoveryAndIsBounded() {
        val order = listOf("a", "b", "c", "d", "e", "f")
        val matches = mapOf("b" to PocketHunt8961.Match.NEEDS_READ, "e" to PocketHunt8961.Match.MATCHED,
            "f" to PocketHunt8961.Match.MATCHED)
        assertEquals(listOf("e", "f", "a", "b", "c", "d"), PocketHunt8961.prioritize(order, matches, 2))
        assertEquals(order, PocketHunt8961.prioritize(order, emptyMap()))
        assertEquals(order, PocketHunt8961.prioritize(order, matches, 0))
    }

    @Test fun earlierEvidenceThatNoLongerPaysCannotHideTheLanePocket() {
        val prior = CandidateAdmission8136.Evidence8136("LAB", "weak", 40, 1.0, 2.0)
        val pocket = CandidateAdmission8136.Evidence8136("LANE_POCKET", "own", 50, 15.0, 4.0)
        assertEquals(pocket, CandidateAdmission8136.selectMarketEvidence8961(listOf(prior, pocket), 0.0, 3.0))
        val shifted = prior.copy(meanPct = 5.0)
        assertEquals(pocket, CandidateAdmission8136.selectMarketEvidence8961(listOf(shifted, pocket), 4.0, 3.0))
        assertEquals(shifted, CandidateAdmission8136.selectMarketEvidence8961(listOf(shifted, pocket), 0.0, 3.0))
        assertNull(CandidateAdmission8136.selectMarketEvidence8961(emptyList(), 0.0, 3.0))
    }

    @Test fun unknownCostsAndSuspectEvidenceCannotBecomeSupportedBySelection() {
        val gross = CandidateAdmission8136.Evidence8136("LAB", "gross", 50, 10.0, 1.0, netOfCost = false)
        val net = gross.copy(kind = "LANE_POCKET", id = "net", netOfCost = true)
        assertEquals(net, CandidateAdmission8136.selectMarketEvidence8961(listOf(gross, net), 0.0, Double.NaN))
        assertEquals(net, CandidateAdmission8136.selectMarketEvidence8961(listOf(net.copy(suspect8290 = true), net), 0.0, 3.0))
    }

    @Test fun profitableStrategyWinsOwnershipOverHigherScoreLosingStrategyOnlyForSameRevision() {
        val asset = "solana|pocket-selection-8961"
        CryptoStrategyCandidateBooks7803.ready(asset, "T", "MOMENTUM", 11, 99, 99, "ready")
        CryptoStrategyCandidateBooks7803.ready(asset, "T", "PULLBACK", 11, 70, 70, "ready")
        CryptoStrategyCandidateBooks7803.ready(asset, "T", "BREAKOUT", 10, 100, 100, "stale revision")
        try {
            val selected = CryptoStrategyCandidateBooks7803.bestReady(asset, 11) {
                PocketHunt8961.recordRank(it.strategy == "PULLBACK", it.strategy == "MOMENTUM")
            }
            assertEquals("PULLBACK", selected?.strategy)
            assertEquals("MOMENTUM", CryptoStrategyCandidateBooks7803.bestReady(asset, 11)?.strategy)
            assertNull(CryptoStrategyCandidateBooks7803.bestReady(asset, 12))
            assertEquals(-1, PocketHunt8961.recordRank(backed = true, losing = true))
        } finally {
            for (strategy in listOf("MOMENTUM", "PULLBACK", "BREAKOUT"))
                CryptoStrategyCandidateBooks7803.lose(asset, strategy, 0, "test complete")
        }
    }
}
