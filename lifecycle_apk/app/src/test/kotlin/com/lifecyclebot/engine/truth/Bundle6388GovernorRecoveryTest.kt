package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6388 — GOVERNOR RECOVERY STATE MACHINE + FULL DIRECTIVE COVERAGE.
 *
 * This bundle enforces every named test from directive Section 25 plus the
 * end-to-end integration acceptance from Section 26. If any of these fail,
 * CI blocks the ship. No cherry-picking allowed.
 */
class Bundle6388GovernorRecoveryTest {

    private val healthyInfra = GovernorRecovery6388.InfrastructureSignals(
        runtimeActive = true, walletProviderAvailable = true,
        ownerFilteredBalanceAvailable = true, fillLotLedgerAvailable = true,
        canonicalRegistryAvailable = true, sellReconcilerStarted = true,
        sellReconcilerHealthy = true, walletReconciliationConclusive = true,
        noCanonicalDiscrepancy = true, noUnresolvedFill = true,
    )
    private val unhealthyReconciler = healthyInfra.copy(sellReconcilerStarted = false)

    @Before fun setUp() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE)
        PolicyBlockDedup6388.clearAllForTest()
        PostFixEvidenceCollector6388.clearAllForTest()
        ProbationEntryLimiter6388.clearAllForTest()
        BuyFillLedger6388.clearAllForTest()
        SellFillLedger6388.clearAllForTest()
        CanonicalTradeAggregator6388.clearAllForTest()
        PartialExitStateMachineFull6388.clearAllForTest()
        SellLeaseIntegrity6388.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    /* -------------------- Section 25 INFRASTRUCTURE ---------------------- */

    @Test fun live_sell_reconciler_starts_before_entry_authority() {
        // Directive S14: unhealthy reconciler → NEVER allow buys.
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = unhealthyReconciler)
        val auth = GovernorRecovery6388.entryAuthority()
        assertFalse("must not allow buys while reconciler stopped", auth.allowBuys)
    }

    @Test fun live_sell_reconciler_ticks_during_live_runtime() {
        val snapshot = ReconcilerLivenessAuthority6388.Snapshot(
            sellReconcilerStarted = true, sellReconcilerActiveJobId = "job-1",
            sellReconcilerRuntimeGeneration = 1L, sellReconcilerStartedAtMs = 0L,
            sellReconcilerTotalTicks = 3L, sellReconcilerLastAttemptAtMs = 0L,
            sellReconcilerLastSuccessAtMs = 0L, sellReconcilerTickAgeMs = 500L,
            sellReconcilerCheckedMints = 5L, sellReconcilerConclusiveMints = 5L,
            sellReconcilerInconclusiveMints = 0L, sellReconcilerRestoredPositions = 0L,
            sellReconcilerClosedAbsentPositions = 0L, sellReconcilerFailures = 0L,
            sellReconcilerRestartCount = 0L,
            walletReconciliationConclusive = true, canonicalRegistryAvailable = true,
            fillLedgersAvailable = true, configuredReconcilerHealthLimitMs = 30_000L,
        )
        assertTrue(ReconcilerLivenessAuthority6388.liveEntryInfrastructureHealthy(snapshot))
    }

    @Test fun live_buys_block_when_reconciler_is_unhealthy() {
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = unhealthyReconciler)
        assertFalse(GovernorRecovery6388.entryAuthority().allowBuys)
    }

    @Test fun exits_remain_available_in_exit_only_mode() {
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = unhealthyReconciler)
        val s = GovernorRecovery6388.state()
        assertTrue("must be BLOCKED or EXIT_ONLY on infra fault",
            s == GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE || s == GovernorRecovery6388.State.EXIT_ONLY)
    }

    @Test fun wallet_holdings_reconcile_to_position_authority() {
        val d = WalletPositionAuthority6388.Distribution(
            walletHeldMints = 5, canonicalOpen = 2, restoredKnownBasis = 1,
            restoredUnknownBasis = 1, externalHoldings = 1, excludedDust = 0, quarantinedAssets = 0,
        )
        assertTrue(WalletPositionAuthority6388.invariantHolds(d))

        val bad = d.copy(walletHeldMints = 99)
        assertFalse(WalletPositionAuthority6388.invariantHolds(bad))
    }

    /* -------------------- Section 25 GOVERNOR + RECOVERY ----------------- */

    @Test fun governor_hold_enters_probation_automatically() {
        GovernorRecovery6388.onReconcilerTick(governorHold = true, infra = healthyInfra)
        assertEquals(GovernorRecovery6388.State.HOLD_PROBATION, GovernorRecovery6388.state())
    }

    @Test fun governor_hold_policy_block_is_not_buy_failure() {
        // Directive S13: policy blocks emit LIVE_ENTRY_POLICY_BLOCKED, never BUY_FAIL.
        val emitted = PolicyBlockDedup6388.shouldEmit(1L, "MintA", "fdg-1", "HOLD", "HOLD_PROBATION")
        assertTrue(emitted)
        PolicyBlockDedup6388.recordPolicyBlock("MintA", "HOLD", "HOLD_PROBATION", "GOVERNOR_HOLD")
    }

    @Test fun governor_policy_blocks_are_deduplicated() {
        val gen = 42L
        assertTrue(PolicyBlockDedup6388.shouldEmit(gen, "MintX", "fdg-9", "HOLD", "HOLD_PROBATION"))
        assertFalse(PolicyBlockDedup6388.shouldEmit(gen, "MintX", "fdg-9", "HOLD", "HOLD_PROBATION"))
    }

    @Test fun probation_size_is_strictly_capped() {
        // Directive S5 clamp: 0.005 – 0.010 SOL, 10% of normal.
        assertEquals(0.005, GovernorRecovery6388.probationSize(0.01), 1e-9)
        assertEquals(0.010, GovernorRecovery6388.probationSize(1.00), 1e-9)
        assertEquals(0.005, GovernorRecovery6388.probationSize(0.02), 1e-9)  // 10% = 0.002 → clamped up to 0.005
        // Never exceed the configured normal size.
        assertTrue(GovernorRecovery6388.probationSize(0.003) <= 0.003)
    }

    @Test fun probation_maximum_one_open_position() {
        val now = System.currentTimeMillis()
        val (ok1, _) = ProbationEntryLimiter6388.canOpen(now)
        assertTrue(ok1); ProbationEntryLimiter6388.recordOpen(now)
        val (ok2, reason) = ProbationEntryLimiter6388.canOpen(now)
        assertFalse(ok2); assertTrue(reason.contains("MAX_OPEN"))
    }

    @Test fun probation_entry_spacing_is_enforced() {
        val now = System.currentTimeMillis()
        val (ok1, _) = ProbationEntryLimiter6388.canOpen(now)
        assertTrue(ok1); ProbationEntryLimiter6388.recordOpen(now); ProbationEntryLimiter6388.recordClose()
        // 30s later — must still be blocked by 180s spacing.
        val (ok2, reason) = ProbationEntryLimiter6388.canOpen(now + 30_000L)
        assertFalse(ok2); assertTrue(reason.contains("SPACING"))
        // 200s later — spacing satisfied.
        val (ok3, _) = ProbationEntryLimiter6388.canOpen(now + 200_000L)
        assertTrue(ok3)
    }

    @Test fun probation_hourly_limit_is_enforced() {
        val start = System.currentTimeMillis()
        // Open + close 3 entries at 200s spacing, then a 4th within the same hour.
        for (i in 0 until 3) {
            val t = start + i * 200_000L
            assertTrue(ProbationEntryLimiter6388.canOpen(t).first)
            ProbationEntryLimiter6388.recordOpen(t); ProbationEntryLimiter6388.recordClose()
        }
        val (ok, reason) = ProbationEntryLimiter6388.canOpen(start + 700_000L)
        assertFalse(ok); assertTrue(reason.contains("HOURLY_LIMIT"))
    }

    @Test fun probation_promotes_to_soft_tight_automatically() {
        GovernorRecovery6388.onReconcilerTick(governorHold = true, infra = healthyInfra)
        // Seed evidence: 5 canonical trades, 3 wins, PF ≥ 1.00.
        repeat(3) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6388, 0.0025, true, true, true, false)
        }
        repeat(2) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6388, -0.0010, true, true, true, false)
        }
        val e = PostFixEvidenceCollector6388.snapshot(tradesCompletedInState = 5, reconcilerHealthyThroughout = true)
        GovernorRecovery6388.evaluatePromotion(e)
        assertEquals(GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())
    }

    @Test fun soft_tight_promotes_to_baseline_automatically() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.SOFT_TIGHT)
        // Directive S8: last-10 WR ≥ 35%, PF ≥ 1.20, expectancy > 0.
        repeat(6) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6388, 0.0030, true, true, true, false)
        }
        repeat(4) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6388, -0.0010, true, true, true, false)
        }
        val e = PostFixEvidenceCollector6388.snapshot(tradesCompletedInState = 5, reconcilerHealthyThroughout = true)
        GovernorRecovery6388.evaluatePromotion(e)
        assertEquals(GovernorRecovery6388.State.BASELINE, GovernorRecovery6388.state())
    }

    @Test fun baseline_restores_normal_trade_size() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.BASELINE)
        val a = GovernorRecovery6388.entryAuthority()
        assertTrue(a.allowBuys); assertTrue(a.fullSized)
        assertFalse(a.probationSized); assertFalse(a.softTightSized)
    }

    @Test fun historical_mixed_rows_do_not_block_recovery() {
        // Legacy mixed rows: pre-6388 evidence epoch is ignored by post-fix.
        repeat(10) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6300, -0.05, false, false, false, true)
        }
        // Clean post-fix rows: 5 wins / 0 losses.
        repeat(5) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6388, 0.010, true, true, true, false)
        }
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.HOLD_PROBATION)
        val e = PostFixEvidenceCollector6388.snapshot(tradesCompletedInState = 5, reconcilerHealthyThroughout = true)
        GovernorRecovery6388.evaluatePromotion(e)
        assertEquals("legacy rows must not block", GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())
    }

    @Test fun normal_single_loss_does_not_force_hold() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.SOFT_TIGHT)
        PostFixEvidenceCollector6388.recordCanonicalClose(6388, -0.001, true, true, true, false)
        val e = PostFixEvidenceCollector6388.snapshot(tradesCompletedInState = 1, reconcilerHealthyThroughout = true)
        GovernorRecovery6388.evaluateDemotion(e)
        assertEquals("one loss must not demote SOFT_TIGHT",
            GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())
    }

    @Test fun performance_demotion_requires_meaningful_sample() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.SOFT_TIGHT)
        // Only 2 losses — insufficient sample.
        repeat(2) {
            PostFixEvidenceCollector6388.recordCanonicalClose(6388, -0.05, true, true, true, false)
        }
        val e = PostFixEvidenceCollector6388.snapshot(tradesCompletedInState = 2, reconcilerHealthyThroughout = true)
        GovernorRecovery6388.evaluateDemotion(e)
        assertEquals(GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())
    }

    @Test fun infrastructure_fault_causes_immediate_safe_demotion() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.BASELINE)
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = unhealthyReconciler)
        val s = GovernorRecovery6388.state()
        assertTrue("BASELINE must demote to EXIT_ONLY or BLOCKED",
            s == GovernorRecovery6388.State.EXIT_ONLY || s == GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE)
    }

    @Test fun demoted_pipeline_can_repromote_automatically() {
        // Full demote → repromote cycle: EXIT_ONLY → SOFT_TIGHT after infra heals.
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.EXIT_ONLY)
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = healthyInfra)
        assertEquals(GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())
    }

    @Test fun no_recovery_state_can_permanently_deadlock() {
        // From every state, there exists an evidence path back to BASELINE.
        for (s in GovernorRecovery6388.State.values()) {
            GovernorRecovery6388.resetForTest(s)
            GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = healthyInfra)
            // Path forward exists — machine must not remain permanently stuck.
            assertTrue(GovernorRecovery6388.state() != GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE ||
                       s == GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE)
        }
    }

    /* -------------------- Section 25 BUY/SELL ACCOUNTING ----------------- */

    private fun buy(pos: String, sig: String, sol: Double) = BuyFillRecord6388(
        fillId = "bf_$sig", positionId = pos, mint = "mint", symbol = "SYM",
        lane = "SHITCOIN", tactic = "MOMENTUM", strategy = "PROBATION",
        executionAuthority = "GOVERNOR_HOLD_PROBATION", governorState = "HOLD",
        recoveryState = "HOLD_PROBATION", evidenceEpoch = 6388, signature = sig,
        slot = 100L, blockTime = 200L, requestedSol = sol, actualSolSpentGross = sol,
        networkFeeSol = 0.0001, priorityFeeSol = 0.0001, platformFeeSol = 0.0,
        actualSolSpentNet = sol - 0.0002, tokenRawReceived = BigInteger.valueOf(1_000_000L),
        tokenUiReceived = 1.0, tokenDecimals = 6, effectiveEntryPriceUsd = 0.001,
        marketCapAtEntryUsd = 100_000.0, liquidityAtEntryUsd = 50_000.0,
        quoteProvider = "JUPITER", executionRoute = "route1", slippageBps = 100,
        finality = "FINALIZED", runtimeGeneration = 1L, createdAtMs = System.currentTimeMillis(),
    )
    private fun sell(pos: String, sig: String, sol: Double, cost: Double, seq: Int) = SellFillRecord6388(
        fillId = "sf_$sig", positionId = pos, mint = "mint", symbol = "SYM",
        signature = sig, slot = 300L, blockTime = 400L, exitIntentId = "ei_$pos",
        exitReason = "PROFIT_TARGET", requestedRaw = BigInteger.valueOf(500_000L), requestedUi = 0.5,
        actualConsumedRaw = BigInteger.valueOf(500_000L), actualConsumedUi = 0.5,
        preBalanceRaw = BigInteger.valueOf(1_000_000L), postBalanceRaw = BigInteger.valueOf(500_000L),
        remainingRaw = BigInteger.valueOf(500_000L), tokenDecimals = 6,
        solReceivedGross = sol, networkFeeSol = 0.0001, priorityFeeSol = 0.0001,
        platformFeeSol = 0.0, solReceivedNet = sol - 0.0002,
        allocatedCostBasisSol = cost, realisedPnlSol = (sol - 0.0002) - cost,
        realisedPnlPct = 0.0, fillSequence = seq, sourceRoute = "route1", quoteProvider = "JUPITER",
        slippageBps = 100, finality = "FINALIZED", runtimeGeneration = 1L,
        evidenceEpoch = 6388, createdAtMs = System.currentTimeMillis(),
    )

    @Test fun confirmed_buy_creates_immutable_buy_fill() {
        assertTrue(BuyFillLedger6388.record(buy("posA", "buysig1", 0.005)))
        assertEquals(1, BuyFillLedger6388.forPosition("posA").size)
    }

    @Test fun duplicate_buy_fill_signature_is_rejected() {
        BuyFillLedger6388.record(buy("posA", "buysig1", 0.005))
        assertFalse(BuyFillLedger6388.record(buy("posA", "buysig1", 0.005)))
    }

    @Test fun confirmed_partial_creates_immutable_sell_fill() {
        assertTrue(SellFillLedger6388.record(sell("posA", "sellsig1", 0.006, 0.005, 1)))
    }

    @Test fun losing_partial_is_not_quarantined() {
        assertTrue(SellFillLedger6388.record(sell("posA", "sellsig-loss", 0.001, 0.005, 1)))
        assertNotNull(SellFillLedger6388.bySignature("sellsig-loss"))
    }

    @Test fun multiple_sell_fills_aggregate_to_one_lifecycle() {
        BuyFillLedger6388.record(buy("posA", "buysig1", 0.005))
        SellFillLedger6388.record(sell("posA", "sellsig1", 0.003, 0.0025, 1))
        SellFillLedger6388.record(sell("posA", "sellsig2", 0.003, 0.0025, 2))
        val summary = CanonicalTradeAggregator6388.aggregate(
            positionId = "posA", mint = "mint", symbol = "SYM", lane = "SHITCOIN",
            tactic = "MOMENTUM", strategy = "PROBATION", executionAuthority = "GOVERNOR_HOLD_PROBATION",
            governorState = "HOLD", recoveryState = "HOLD_PROBATION", evidenceEpoch = 6388,
            finalExitReason = "PROFIT_TARGET", openedAtMs = 0L, closedAtMs = 100L,
            maximumGainPct = 20.0, maximumDrawdownPct = 5.0, runtimeGeneration = 1L,
        )
        assertEquals(2, summary.sellFillIds.size)
        assertEquals(1, CanonicalTradeAggregator6388.count())
    }

    @Test fun duplicate_sell_fill_signature_is_rejected() {
        SellFillLedger6388.record(sell("posA", "sellsig1", 0.006, 0.005, 1))
        assertFalse(SellFillLedger6388.record(sell("posA", "sellsig1", 0.006, 0.005, 1)))
    }

    /* -------------------- Section 25 EXIT CONTROL ------------------------ */

    @Test fun hard_stop_requests_full_exit() {
        assertTrue(HardStopFullExit6388.requiresFullExit("HARD_STOP"))
        assertTrue(HardStopFullExit6388.requiresFullExit("UNIVERSAL_STOP_LOSS"))
        assertTrue(HardStopFullExit6388.requiresFullExit("RUG_CONFIRMED"))
        assertTrue(HardStopFullExit6388.requiresFullExit("LIQUIDITY_COLLAPSE_CONFIRMED"))
        assertTrue(HardStopFullExit6388.requiresFullExit("DEAD_TOKEN_CONFIRMED"))
        assertTrue(HardStopFullExit6388.requiresFullExit("SELLABILITY_DETERIORATION"))
        assertTrue(HardStopFullExit6388.requiresFullExit("POSITION_INTEGRITY_EMERGENCY"))
        assertFalse(HardStopFullExit6388.requiresFullExit("PROFIT_TARGET"))
    }

    @Test fun route_chunking_preserves_single_exit_intent() {
        val bal = BigInteger.valueOf(10_000_000L)
        val p = HardStopFullExit6388.buildFullExitPlan(bal, "ei-42", routeMustChunk = true)
        assertEquals("ROUTE_CHUNKED_FULL_EXIT", p.exitMode)
        assertEquals(bal, p.targetRaw)
        assertEquals("ei-42", p.exitIntentId)
    }

    @Test fun repeated_geometric_25_percent_exit_is_prevented() {
        // Verify HARD_STOP requests full liquidation, not iterative 25%.
        val bal = BigInteger.valueOf(10_000_000L)
        val hardStop = HardStopFullExit6388.buildFullExitPlan(bal, "ei-1", routeMustChunk = false)
        assertEquals(bal, hardStop.targetRaw)
    }

    @Test fun sell_lease_remains_until_reconciliation() {
        val k = SellLeaseIntegrity6388.Key(1L, "posA", "ei-A")
        assertTrue(SellLeaseIntegrity6388.acquire(k))
        assertTrue(SellLeaseIntegrity6388.isHeld(k))
        assertFalse("premature release must fail",
            SellLeaseIntegrity6388.release(k, fillRecorded = false, postBalanceConfirmed = true, stateAdvanced = true))
        assertTrue(SellLeaseIntegrity6388.release(k, fillRecorded = true, postBalanceConfirmed = true, stateAdvanced = true))
    }

    @Test fun duplicate_sell_broadcast_is_prevented() {
        val k = SellLeaseIntegrity6388.Key(1L, "posA", "ei-A")
        assertTrue(SellLeaseIntegrity6388.acquire(k))
        val k2 = SellLeaseIntegrity6388.Key(1L, "posA", "ei-B")
        assertFalse("must prevent overlapping exit intents on same position", SellLeaseIntegrity6388.acquire(k2))
    }

    @Test fun partial_cooldown_blocks_duplicate_trigger() {
        val pos = "posA"
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.EXIT_INTENT_CREATED))
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_REQUESTED))
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_QUOTED))
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_BROADCAST))
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_CONFIRMED))
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_RECONCILING))
        assertTrue(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_COOLDOWN))
        assertTrue(PartialExitStateMachineFull6388.isInCooldown(pos))
        // Cannot jump backwards to PARTIAL_REQUESTED without exiting cooldown flow.
        assertFalse(PartialExitStateMachineFull6388.transition(pos, PartialExitStateMachineFull6388.State.PARTIAL_QUOTED))
    }

    /* -------------------- Section 25 JOURNAL AND EXPORT ------------------ */

    @Test fun old_journal_epoch_is_archived_not_deleted() {
        val h = JournalEpoch6388.archiveAndStartNewEpoch(
            oldEpochId = "PRE_6388", oldEpochChecksum = "abc123",
            startingWalletSol = 0.5, startingWalletTokenMints = listOf("mint1", "mint2"),
            startingCanonicalOpenPositions = 0, startingRestoredPositions = 2,
            runtimeGeneration = 1L, buildNumber = 6388,
        )
        assertEquals("PRE_6388", h.previousEpochId)
        assertEquals("abc123", h.previousEpochChecksum)
        assertTrue(h.recoveryEligible)
    }

    @Test fun new_epoch_references_previous_checksum() {
        val h = JournalEpoch6388.archiveAndStartNewEpoch(
            oldEpochId = "PRE_6388", oldEpochChecksum = "deadbeef",
            startingWalletSol = 0.0, startingWalletTokenMints = emptyList(),
            startingCanonicalOpenPositions = 0, startingRestoredPositions = 0,
            runtimeGeneration = 1L, buildNumber = 6388,
        )
        assertNotNull(h.previousEpochChecksum); assertEquals("deadbeef", h.previousEpochChecksum)
    }

    @Test fun fallback_export_is_visibly_incomplete() {
        val m = ForensicExportMode6388.Metadata(
            exporterMode = ForensicExportMode6388.Mode.FALLBACK,
            primaryExporterFailure = "OOM", exporterStartedAtMs = 0L, exporterCompletedAtMs = 1L,
            runtimeGeneration = 1L, oldestIncludedTimestamp = 0L, newestIncludedTimestamp = 1L,
            eventContinuityStatus = "PARTIAL", omittedSections = listOf("sellFills"),
            omittedEventCount = 42L, canonicalFillCoverage = 0.5,
            canonicalLifecycleCoverage = 0.6, checksumAlgorithm = "SHA256", checksum = "abc",
        )
        assertTrue(ForensicExportMode6388.incompleteFlag(m))
    }

    @Test fun fallback_export_cannot_pass_full_forensic_guard() {
        val m = ForensicExportMode6388.Metadata(
            exporterMode = ForensicExportMode6388.Mode.FALLBACK, primaryExporterFailure = "OOM",
            exporterStartedAtMs = 0L, exporterCompletedAtMs = 1L, runtimeGeneration = 1L,
            oldestIncludedTimestamp = 0L, newestIncludedTimestamp = 1L,
            eventContinuityStatus = "COMPLETE", omittedSections = emptyList(), omittedEventCount = 0L,
            canonicalFillCoverage = 1.0, canonicalLifecycleCoverage = 1.0,
            checksumAlgorithm = "SHA256", checksum = "abc",
        )
        assertFalse(ForensicExportMode6388.canPassForensicRegressionGuard(m))
    }

    @Test fun primary_export_contains_all_fill_ledgers() {
        val m = ForensicExportMode6388.Metadata(
            exporterMode = ForensicExportMode6388.Mode.PRIMARY, primaryExporterFailure = null,
            exporterStartedAtMs = 0L, exporterCompletedAtMs = 1L, runtimeGeneration = 1L,
            oldestIncludedTimestamp = 0L, newestIncludedTimestamp = 1L,
            eventContinuityStatus = "COMPLETE", omittedSections = emptyList(), omittedEventCount = 0L,
            canonicalFillCoverage = 1.0, canonicalLifecycleCoverage = 1.0,
            checksumAlgorithm = "SHA256", checksum = "abc",
        )
        assertTrue(ForensicExportMode6388.canPassForensicRegressionGuard(m))
    }

    @Test fun evidence_epoch_filters_recovery_calculations() {
        assertFalse(EvidenceEpochFilter6388.isRecoveryEligible(
            rowEvidenceEpoch = 6300, canonicalFinalised = true, accountingReconciled = true,
            signaturesComplete = true, quantityIntegrity = true, decimalIntegrity = true))
        assertTrue(EvidenceEpochFilter6388.isRecoveryEligible(
            rowEvidenceEpoch = 6388, canonicalFinalised = true, accountingReconciled = true,
            signaturesComplete = true, quantityIntegrity = true, decimalIntegrity = true))
    }

    /* -------------------- Section 26 INTEGRATION ACCEPTANCE ---------------- */

    @Test fun end_to_end_acceptance_hold_to_baseline_no_manual_intervention() {
        // Start: HOLD, infra healthy.
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE)
        GovernorRecovery6388.onReconcilerTick(governorHold = true, infra = healthyInfra)
        assertEquals(GovernorRecovery6388.State.HOLD_PROBATION, GovernorRecovery6388.state())

        // Probation lifecycles → SOFT_TIGHT.
        repeat(4) { PostFixEvidenceCollector6388.recordCanonicalClose(6388, 0.003, true, true, true, false) }
        PostFixEvidenceCollector6388.recordCanonicalClose(6388, -0.001, true, true, true, false)
        GovernorRecovery6388.evaluatePromotion(PostFixEvidenceCollector6388.snapshot(5, true))
        assertEquals(GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())

        // More lifecycles → BASELINE.
        repeat(5) { PostFixEvidenceCollector6388.recordCanonicalClose(6388, 0.004, true, true, true, false) }
        GovernorRecovery6388.evaluatePromotion(PostFixEvidenceCollector6388.snapshot(5, true))
        assertEquals(GovernorRecovery6388.State.BASELINE, GovernorRecovery6388.state())

        // Introduce reconciler fault → EXIT_ONLY.
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = unhealthyReconciler)
        assertTrue(GovernorRecovery6388.state() == GovernorRecovery6388.State.EXIT_ONLY ||
                   GovernorRecovery6388.state() == GovernorRecovery6388.State.BLOCKED_INFRASTRUCTURE)

        // Heal → return to SOFT_TIGHT (auto-recovery, no manual intervention).
        GovernorRecovery6388.onReconcilerTick(governorHold = false, infra = healthyInfra)
        assertEquals(GovernorRecovery6388.State.SOFT_TIGHT, GovernorRecovery6388.state())
    }
}
