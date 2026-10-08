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

    @Test fun forensicParentsAreModeScoped() {
        val forensic = src("engine/ForensicReconciler6377.kt")
        val parent = forensic.substringAfter("private fun canonicalParentMints7868(")
            .substringBefore("fun runAll(")
        assertTrue(parent.contains("paperMode: Boolean"))
        assertTrue(parent.contains("it.mode.equals(if (paperMode)"))
        assertTrue(forensic.contains("canonicalParentMints7868(paperMode)"))
    }

    @Test fun learningMustNotAcknowledgeMissingPositionBinding() {
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        val method = bridge.substringAfter("private fun deliverToStrategyHypothesis(")
            .substringBefore("private fun deliverToExactStrategyPerformance7429(")
        assertTrue(method.contains("hasPositionBinding7877(env.positionId)"))
        assertTrue(method.contains("HYPOTHESIS_EXACT_BINDING_AWAIT_RETRY_7877"))
        assertTrue(method.contains("NO_PROVEN_ENTRY_HYPOTHESIS_BINDING_7877"))
    }

    @Test fun sameCandidateElectionIsAtomicUnderConcurrentCallers() {
        LaneExecutionCoordinator.resetForTests()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val startingGate = java.util.concurrent.CountDownLatch(1)
            val work = (0 until 8).map { n ->
                pool.submit<java.lang.String> {
                    startingGate.await()
                    LaneExecutionCoordinator.elect(
                        mint = "atomic-7877", lanes = listOf("MOONSHOT", "SHITCOIN"),
                        preferred = if (n % 2 == 0) "SHITCOIN" else "MOONSHOT",
                        candidateVersion = 7877L, runtimeGeneration = 877L,
                    ).electionId
                }
            }
            startingGate.countDown()
            val electionIds = work.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals("one mint/version must have exactly one winner", 1, electionIds.distinct().size)
            assertTrue(LaneExecutionCoordinator.currentElection6600(
                "atomic-7877", candidateVersion = 7877L, runtimeGeneration = 877L
            ) != null)
        } finally {
            pool.shutdownNow()
            LaneExecutionCoordinator.resetForTests()
        }
    }

    @Test fun emergencyEscalationPreemptsOnlyCompletedSofterRetry() {
        val close = src("engine/sell/CloseLease.kt")
        val acquire = close.substringAfter("fun acquire(mint: String")
            .substringBefore("fun recordRetry(")
        assertTrue(acquire.contains("val escalation7807 = emergency7807"))
        assertTrue(acquire.contains("!existing.inFlight"))
        assertTrue(acquire.contains("escalation7807 ||"))
        assertTrue(acquire.contains("EMERGENCY_RETRY_BACKOFF_PREEMPTED_7877"))
    }

    @Test fun ticketRefusedAtExecutorTerminatesByName() {
        assertTrue(src("engine/Executor.kt").contains("ToolkitSignalSheet.recordDeskStage(lane, \"EXEC_REFUSED\", attemptId)"))
    }
}
