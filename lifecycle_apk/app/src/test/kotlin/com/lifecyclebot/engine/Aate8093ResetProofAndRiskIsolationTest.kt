package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8093: fresh learning must not erase economic identity or mix paper into LIVE performance. */
class Aate8093ResetProofAndRiskIsolationTest {
    private fun source(name: String): String =
        File("src/main/kotlin/com/lifecyclebot/$name").readText()

    @Test fun canonicalEntrySnapshotHasResetProofPersistence() {
        val s = source("engine/truth/EntryStrategySnapshot6450.kt")
        assertTrue(s.contains("LearningPersistence.saveImmutableExecutionEvidence8093("))
        assertTrue(s.contains("LearningPersistence.loadImmutableExecutionEvidence8093(positionId)"))
        val store = source("engine/LearningPersistence.kt")
        assertTrue(store.contains("canonical_entry_evidence_8093"))
        assertTrue(store.contains("putString(positionId, payload).commit()"))
        val reset = source("engine/truth/LearningResetSweep7781.kt")
        assertFalse(reset.contains("\"canonical_entry_evidence_8093\""))
    }

    @Test fun liveMemeMaturityIsNotUnlockedByPaperProofTrades() {
        val tracker = source("engine/RunTracker30D.kt")
        assertTrue(tracker.contains("fun getLiveLaneStats(lane: String)"))
        assertTrue(tracker.contains("fun getPaperLaneStats(lane: String)"))
        assertTrue(tracker.contains("outcome.environment == com.lifecyclebot.engine.TradeEnvironment.LIVE"))
        assertTrue(tracker.contains("outcome.environment == com.lifecyclebot.engine.TradeEnvironment.PAPER"))
        assertTrue(tracker.contains("closedAt < proofEpoch8093"))
        assertTrue(tracker.contains("RUN_TRACKER_MIXED_JOURNAL_SYNC_SUPPRESSED_8093"))
        assertTrue(tracker.contains("putLong(\"proofEpoch8093\", proofEpoch8093)"))
        assertTrue(tracker.contains("if (RuntimeModeAuthority.isPaper()) getPaperLaneStats(lane) else getLiveLaneStats(lane)"))
        val fluid = source("v3/scoring/FluidLearningAI.kt")
        assertTrue(fluid.contains("RunTracker30D.getLiveLaneStats(\"MEME\")"))
        assertTrue(fluid.contains("totalTrades >= EARLY_BOOTSTRAP_TRADES) return false"))
    }

    @Test fun cleanProofResetKeepsLiveChainRiskHistory() {
        val reset = source("engine/truth/LearningResetSweep7781.kt")
        assertTrue(reset.contains("RunTracker30D.reset()"))
        assertTrue(reset.contains("KillSwitch.reanchorExplicitProofRun8093(app)"))
        val risk = source("engine/KillSwitch.kt")
        val start = risk.indexOf("fun reanchorExplicitProofRun8093(")
        val end = risk.indexOf("fun reset(context:", start)
        assertTrue(start > 0 && end > start)
        val body = risk.substring(start, end)
        assertTrue(body.contains("peakBalance = equity"))
        assertTrue(body.contains("dailyStartBalance = equity"))
        assertFalse(body.contains("consecutiveLosses = 0"))
        assertFalse(body.contains("lastProvenLossAt8081 = 0"))
        assertFalse(body.contains("isKilled = false"))
    }

    @Test fun equityReanchorDoesNotManufactureADrawdown() {
        assertTrue(CapitalDrawdown7948.reanchorExplicitRun8093(0.3074, 100000L))
        assertEquals(0.3074, CapitalDrawdown7948.currentPeak7948().peakSol, 1e-8)
        assertEquals(0.0, CapitalDrawdown7948.peekPct7948(0.3074, 100001L), 1e-8)
        assertTrue(CapitalDrawdown7948.peekPct7948(0.3000, 100001L) > 0.0)
        assertFalse(CapitalDrawdown7948.reanchorExplicitRun8093(Double.NaN, 100002L))
    }
}
