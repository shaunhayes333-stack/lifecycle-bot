package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.9.1564 — mode-aware runtime fault tests. */
class RuntimeInvariantGuardianTest {
    private fun snapshot(mode: String, canonical: Int, walletHeld: Int, reconcilerChecked: Int): RuntimeStateSnapshot =
        RuntimeStateSnapshot(
            buildVersion = "test",
            buildTag = "test",
            runtimeGeneration = 1L,
            uiState = "RUNNING",
            runtimeState = "RUNNING",
            botLoopActive = true,
            scannerActive = true,
            sellReconcilerStarted = true,
            hostTrackerOpenCount = walletHeld,
            positionStoreOpenCount = canonical,
            paperOpenPositions = if (mode == "PAPER") canonical else 0,
            liveOpenPositions = if (mode == "LIVE") canonical else 0,
            walletHeldMints = walletHeld,
            canonicalOpenPositions = canonical,
            orphanPaperPositions = 0,
            orphanLivePositions = 0,
            reconcilerTotalChecked = reconcilerChecked,
            mode = mode,
            enabledTraders = "MEME",
            intake = 10,
            safety = 10,
            v3 = 10,
            laneEval = 10,
            fdg = 10,
            exec = 0,
            exit = 10,
            learningTrades = 0,
            uniqueClosedPositionIds = 0,
            staleSellLocks = 0,
            mainUpdateSkippedInactive = 0,
            anrHints = 0,
            apiHealth = emptyMap(),
            topBlockReasons = emptyMap(),
            activeMitigations = emptyList(),
            timestampMs = 1L,
        )

    @Test fun paper_canonical_opens_do_not_emit_wallet_reconciler_stalled() {
        val faults = InvariantGuardian.check(snapshot(mode = "PAPER", canonical = 17, walletHeld = 0, reconcilerChecked = 0))
        assertFalse(faults.any { it.code == InvariantGuardian.FaultCode.RECONCILER_STALLED })
    }

    @Test fun live_canonical_opens_still_emit_wallet_reconciler_stalled() {
        val faults = InvariantGuardian.check(snapshot(mode = "LIVE", canonical = 17, walletHeld = 17, reconcilerChecked = 0))
        assertTrue(faults.any { it.code == InvariantGuardian.FaultCode.RECONCILER_STALLED })
    }
}
