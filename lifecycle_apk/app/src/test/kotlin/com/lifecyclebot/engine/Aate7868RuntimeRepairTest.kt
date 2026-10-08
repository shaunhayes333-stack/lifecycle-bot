package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.TraderSizingBridge6444
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

class Aate7868RuntimeRepairTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun sealedSizeIsHonouredAgainstItsSealCapacity() {
        val now = 1_000_000L
        val seal = WalletCapacitySeal7868.Seal(sizeSol = 0.04272, walletSol = 0.0843, solUsd = 116.0, atMs = now - 5_000L)
        // Same capacity: the cap recomputed at execution no longer refuses the sealed size.
        assertNull(WalletCapacitySeal7868.decide(seal, 0.04272, 0.0843, 116.2, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        assertNull(WalletCapacitySeal7868.decide(seal, 0.04272, 0.0843, 116.2, 0.01, "SEALED_SIZE_BELOW_CURRENT_MINIMUM_7835", now))
        // Material wallet drop: explicit invalidation, reseal next cycle.
        assertEquals("SEALED_INTENT_INVALIDATED_CAPACITY_CHANGED_7868",
            WalletCapacitySeal7868.decide(seal, 0.04272, 0.06, 116.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        // Material SOL move.
        assertEquals("SEALED_INTENT_INVALIDATED_CAPACITY_CHANGED_7868",
            WalletCapacitySeal7868.decide(seal, 0.04272, 0.0843, 130.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        // Unfundable: still refused.
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835",
            WalletCapacitySeal7868.decide(seal.copy(walletSol = 0.05), 0.04272, 0.05, 116.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        // Different size than sealed / no seal / other refusals unchanged.
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835",
            WalletCapacitySeal7868.decide(seal, 0.05, 0.0843, 116.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835",
            WalletCapacitySeal7868.decide(null, 0.04272, 0.0843, 116.0, 0.01, "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", now))
        assertEquals("EXECUTION_SIZE_INVALID_7835",
            WalletCapacitySeal7868.decide(seal, 0.04272, 0.0843, 116.0, 0.01, "EXECUTION_SIZE_INVALID_7835", now))
        assertNull(WalletCapacitySeal7868.decide(seal, 0.04272, 0.0843, 116.0, 0.01, null, now))
        // A dynamic route-cap PASS is not proof that wallet minus reserve can pay.
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835",
            WalletCapacitySeal7868.decide(seal, 0.04272, 0.045, 116.0, 0.01, null, now))
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835",
            WalletCapacitySeal7868.decide(seal, 0.04272, Double.NaN, 116.0, 0.01, null, now))
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("WalletCapacitySeal7868.executionRefusal("))
        assertTrue(src("engine/FinalDecisionGate.kt").contains("WalletCapacitySeal7868.record(ts.mint, resolution.finalSizeSol, cash, solUsd)"))
    }

    @Test fun losingLaneGetsNamedOwnershipTerminalNotSealFailure() {
        assertTrue(src("engine/ExecutableOpenGate.kt").contains("FDG_ALLOW_SUPERSEDED_BY_OWNER_LANE_7868"))
        assertTrue(src("engine/TradeAuthorizer.kt").contains("ExecutableOpenGate.ownerLaneOfLiveIntent7868(mode7812, mint, candidateVersion7624, requestedBook.name)"))
    }

    @Test fun deferralsAreNotTerminalBuyFailures() {
        assertTrue(PipelineHealthCollector.isNonTerminalBuyDeferral7868("ENTRY_MARKET_SNAPSHOT_MISSING_DEFERRED"))
        assertTrue(PipelineHealthCollector.isNonTerminalBuyDeferral7868("TOKEN_MAP_PENDING_DEFERRED_7371"))
        assertFalse(PipelineHealthCollector.isNonTerminalBuyDeferral7868("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835"))
        assertFalse(PipelineHealthCollector.isNonTerminalBuyDeferral7868("PRETRADE_HARD_BLOCK"))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt")
            .contains("PipelineHealthCollector.onCanonicalBuyCommitted7863(pendingSameMint7133.positionId, false)"))
    }

    @Test fun openRowReceiptGapReconcilesOnlyWithinVariance() {
        val c = BigInteger.valueOf(1_000_000)
        assertTrue(CanonicalPositionAuthority6441.receiptVarianceOk7868(c, BigInteger.valueOf(1_050_000)))
        assertTrue(CanonicalPositionAuthority6441.receiptVarianceOk7868(c, BigInteger.valueOf(1_100_000)))
        assertFalse(CanonicalPositionAuthority6441.receiptVarianceOk7868(c, BigInteger.valueOf(1_100_001)))
        assertFalse(CanonicalPositionAuthority6441.receiptVarianceOk7868(c, c))
        assertFalse(CanonicalPositionAuthority6441.receiptVarianceOk7868(c, BigInteger.valueOf(900_000)))
    }

    @Test fun heldMintsCannotReenterDiscovery() {
        val reg = src("engine/GlobalTradeRegistry.kt")
        val promote = reg.substringAfter("fun promoteFromProbation(").substringBefore("watchlist[mint] = WatchlistEntry(")
        assertTrue(promote.contains("HeldPositionSupervisor7246.isHeld(mint)"))
        assertTrue(src("engine/HeldPositionSupervisor7246.kt").contains("reconcileDiscoveryResidencyThrottled7868()"))
    }

    @Test fun protectiveSellReadsTheSoldMintFirst() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("?: sellWalletBalances7868(wallet, ts)"))
        assertTrue(src("network/SolanaWallet.kt").contains("fun getSingleMintBalanceBounded7868("))
    }

    @Test fun staleSealedSnapshotIsRevalidatedWithinBand() {
        assertTrue(SealedEntryContinuity7863.withinRevalidationBand7868(1.0, 1.10))
        assertTrue(SealedEntryContinuity7863.withinRevalidationBand7868(1.0, 0.86))
        assertFalse(SealedEntryContinuity7863.withinRevalidationBand7868(1.0, 1.20))
        assertFalse(SealedEntryContinuity7863.withinRevalidationBand7868(0.0, 1.0))
    }

    @Test fun signedSwapAwaitingProofIsInFlightUntilDispatchedTtl() {
        val entry = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertTrue(entry.contains("nowMs7809 - atMs < DISPATCHED_TTL_MS_7313"))
    }

    @Test fun liveReconcilerKeepsLegacyPaperRegistryOutOfScope() {
        val r = src("engine/ForensicReconciler6377.kt")
        assertTrue(r.contains("registry=LEGACY_PAPER_OUT_OF_SCOPE_IN_LIVE"))
        assertTrue(r.contains("it.quarantinedLivePositions7454()"))
    }

    @Test fun sizingMemoKeyIsDeterministic() {
        val a = TraderSizingBridge6444.sizingMemoKey7868("MOONSHOT", "mint", false, 0.1, 0.0843, null)
        val b = TraderSizingBridge6444.sizingMemoKey7868("MOONSHOT", "mint", false, 0.1, 0.0843, null)
        val c = TraderSizingBridge6444.sizingMemoKey7868("MOONSHOT", "mint", false, 0.1, 0.0900, null)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test fun missingElectionReleaseIsANoOp() {
        val lec = src("engine/LaneExecutionCoordinator.kt")
        val block = lec.substringAfter("if (current == null) {\n            // V5.0.7868").substringBefore("return false")
        assertFalse(block.contains("ChokeReliefBus.launch"))
    }

    @Test fun forensicSellParityRequiresSameMintParent() {
        val forensic = src("engine/ForensicReconciler6377.kt")
        assertTrue(forensic.contains("val journalBoughtMints = buys.mapTo(HashSet()) { it.mint }"))
        assertTrue(forensic.contains("it.mint !in parented7868 && it.mint !in journalBoughtMints"))
        assertTrue(forensic.contains("val ok = unparentedSells7868 == 0"))
    }

    @Test fun releaseCannotRemoveNewerSpecialistCandidateVersion() {
        val coordinator = src("engine/LaneExecutionCoordinator.kt")
        val release = coordinator.substringAfter("fun releaseIfPrimary(").substringBefore("fun resetForTests()")
        assertTrue(release.contains("e.key.candidateVersion == candidateVersion"))
    }

    @Test fun canonicalDuplicatePositionBindingIsSerialized() {
        val learner = src("engine/StrategyHypothesisEngine.kt")
        val binding = learner.substringAfter("fun bindExecutedPosition7428(")
            .substringBefore("fun releasePosition7809(")
        assertTrue(binding.contains("return synchronized(pendingByPosition7428) { try {"))
        assertTrue(binding.contains("pendingByDecision7428.remove(decisionKey7428"))
        assertTrue(binding.contains("pendingByPosition7428[positionId] = applied"))
    }

    @Test fun ticketRefusedAtExecutorTerminatesByName() {
        assertTrue(src("engine/Executor.kt").contains("ToolkitSignalSheet.recordDeskStage(lane, \"EXEC_REFUSED\", attemptId)"))
    }
}
