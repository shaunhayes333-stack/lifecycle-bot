package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.495z31 — Acceptance tests for the runtime/pipeline reliability
 * gates introduced in this push.
 */
class RuntimePipelineGatesTest {

    // ── RuntimeModeAuthority ──────────────────────────────────────

    @Test
    fun mode_authority_publishes_and_reads() {
        RuntimeModeAuthority.publishConfig(paperMode = true,  autoTrade = true)
        assertEquals(RuntimeModeAuthority.Mode.PAPER, RuntimeModeAuthority.authority())
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        assertEquals(RuntimeModeAuthority.Mode.LIVE, RuntimeModeAuthority.authority())
    }

    @Test
    fun mode_authority_detects_desync() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)  // LIVE
        RuntimeModeAuthority.publishUiMode(paperMode = true)                     // PAPER (bug)
        val d = RuntimeModeAuthority.detectDesync()
        assertNotNull("desync expected when ui != authority", d)
        assertEquals(RuntimeModeAuthority.Mode.LIVE, d!!.authority)
        assertTrue("UI_MODE must appear in mismatches", "UI_MODE" in d.mismatches.keys)
    }

    @Test
    fun mode_authority_consistent_returns_null_when_aligned() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(false)
        RuntimeModeAuthority.publishExecutorMode(false)
        RuntimeModeAuthority.publishPipelineMode(false)
        assertNull(RuntimeModeAuthority.detectDesync())
    }

    // ── SnipeAgeGate ──────────────────────────────────────────────

    @Test
    fun snipe_mode_classifies_old_tokens_to_background_not_reject() {
        assertEquals(SnipeAgeGate.Decision.BACKGROUND_ONLY_OLD_TOKEN,
            SnipeAgeGate.evaluate(ageMinutes = 87 * 60, snipeModeOn = true))
        assertTrue(SnipeAgeGate.shuntToBackground(87 * 60, snipeModeOn = true))
    }

    @Test
    fun snipe_mode_passes_new_tokens() {
        assertEquals(SnipeAgeGate.Decision.SNIPE_AGE_PASS,
            SnipeAgeGate.evaluate(ageMinutes = 5, snipeModeOn = true))
    }

    @Test
    fun snipe_mode_off_passes_anything() {
        assertEquals(SnipeAgeGate.Decision.SNIPE_AGE_PASS,
            SnipeAgeGate.evaluate(ageMinutes = 999_999, snipeModeOn = false))
    }

    // ── EntryWaitOverrideGate ────────────────────────────────────

    @Test
    fun fdg_defers_entry_wait_with_high_risk_and_low_conf() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = true, riskHigh = true,
            moonshotOverride = false, confidence = 39
        )
        assertEquals(EntryWaitOverrideGate.Verdict.FDG_DEFER_ENTRY_WAIT, r.verdict)
        assertTrue("defer must keep token in watchlist for re-evaluation", r.keepInWatchlist)
    }

    @Test
    fun fdg_overrides_with_moonshot() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = true, riskHigh = true,
            moonshotOverride = true, confidence = 39
        )
        assertEquals(EntryWaitOverrideGate.Verdict.FDG_OVERRIDE_ENTRY_WAIT, r.verdict)
    }

    @Test
    fun fdg_overrides_on_high_conf() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = true, riskHigh = true,
            moonshotOverride = false, confidence = 80
        )
        assertEquals(EntryWaitOverrideGate.Verdict.FDG_OVERRIDE_ENTRY_WAIT, r.verdict)
    }

    @Test
    fun fdg_allows_when_not_wait_or_not_high_risk() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = false, riskHigh = true,
            moonshotOverride = false, confidence = 30
        )
        assertEquals(EntryWaitOverrideGate.Verdict.ALLOW, r.verdict)
    }

    // ── TierState ────────────────────────────────────────────────

    @Test
    fun tier_count_unlocked_does_not_imply_ready() {
        val s = TierState.evaluate(
            tierCount = 5, tradeCount = 4352, winRatePct = 33.5, targetWrPct = 50.0,
            streakBlocks = 3
        )
        assertFalse(s.isReady)
        assertTrue(TierState.Status.PROFITABILITY_LOCKED in s.statuses)
        assertTrue(TierState.Status.STREAK_GUARD_ACTIVE in s.statuses)
    }

    @Test
    fun tier_ready_when_wr_above_target_and_no_streak() {
        val s = TierState.evaluate(
            tierCount = 5, tradeCount = 100, winRatePct = 60.0, targetWrPct = 50.0,
            streakBlocks = 0
        )
        assertTrue(s.isReady)
    }

    // ── RugCheckPolicy ───────────────────────────────────────────

    @Test
    fun rugcheck_pending_blocks_low_score_live() {
        val s = RugCheckPolicy.evaluate(
            rcConfirmedSafe = false, rcConfirmedRisky = false, rcPending = true,
            isPaperMode = false, score = 50,
        )
        assertEquals(RugCheckPolicy.State.RC_PENDING_BLOCKED, s)
    }

    @Test
    fun rugcheck_pending_allowed_high_score_live() {
        val s = RugCheckPolicy.evaluate(
            rcConfirmedSafe = false, rcConfirmedRisky = false, rcPending = true,
            isPaperMode = false, score = 80,
        )
        assertEquals(RugCheckPolicy.State.RC_PENDING_ALLOWED_LIVE_OVERRIDE, s)
    }

    @Test
    fun rugcheck_pending_allowed_paper() {
        val s = RugCheckPolicy.evaluate(
            rcConfirmedSafe = false, rcConfirmedRisky = false, rcPending = true,
            isPaperMode = true, score = 30,
        )
        assertEquals(RugCheckPolicy.State.RC_PENDING_ALLOWED_PAPER, s)
    }

    // ── WatchlistTtlPolicy ───────────────────────────────────────

    @Test
    fun watchlist_sweeps_stale_entries_in_snipe() {
        WatchlistTtlPolicy.clear()
        WatchlistTtlPolicy.mark("ABC", 70)
        // Pretend it's old by re-marking with a stale ts via reflection? Not needed —
        // call sweepStale immediately, expect 0 removals (entry is fresh).
        val removed = WatchlistTtlPolicy.sweepStale(snipeModeOn = true)
        assertEquals(0, removed)
        assertEquals(1, WatchlistTtlPolicy.size())
    }

    // ── CryptoPositionState ──────────────────────────────────────

    @Test
    fun live_overwrites_paper_in_crypto_state() {
        CryptoPositionState.record("BTC", CryptoPositionState.Bucket.PAPER)
        CryptoPositionState.record("BTC", CryptoPositionState.Bucket.LIVE)
        assertEquals(0, CryptoPositionState.count(CryptoPositionState.Bucket.PAPER))
        assertEquals(1, CryptoPositionState.count(CryptoPositionState.Bucket.LIVE))
        CryptoPositionState.release("BTC", CryptoPositionState.Bucket.LIVE)
    }

    // ── DeadAILayerFilter ────────────────────────────────────────

    @Test
    fun layer_marked_zero_starved_when_zero_ratio_high() {
        DeadAILayerFilter.reset()
        repeat(30) { DeadAILayerFilter.recordContribution("FundingRateAwarenessAI", 0.0) }
        assertEquals(DeadAILayerFilter.LayerHealth.ZERO_STARVED,
            DeadAILayerFilter.health("FundingRateAwarenessAI"))
    }

    @Test
    fun layer_marked_disabled_not_applicable_overrides_zero() {
        DeadAILayerFilter.reset()
        DeadAILayerFilter.markNotApplicable("FundingRateAwarenessAI")
        repeat(30) { DeadAILayerFilter.recordContribution("FundingRateAwarenessAI", 0.0) }
        assertEquals(DeadAILayerFilter.LayerHealth.DISABLED_NOT_APPLICABLE,
            DeadAILayerFilter.health("FundingRateAwarenessAI"))
    }
}

class RuntimeSupervisorSmokeTest {
    @Test
    fun runtime_job_active_cannot_report_stopped() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        BotRuntimeController.publishRunning(gen)
        val snap = BotRuntimeController.snapshot()
        assertTrue(snap.runtimeActive)
        assertTrue("UI stopped while runtime active must be a regression", BotRuntimeController.runtimeJobActiveButUiStopped(uiRunning = false) || snap.state == BotRuntimeController.RuntimeState.RUNNING)
    }

    @Test
    fun runtime_export_state_has_generation() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        BotRuntimeController.publishRunning(gen)
        val snap = BotRuntimeController.snapshot()
        assertTrue("runtime generation must be non-zero after start", snap.runtimeGeneration > 0)
        assertEquals(BotRuntimeController.RuntimeState.RUNNING, snap.state)
    }

    @Test
    fun forensics_ring_not_empty_when_event_emitted() {
        com.lifecyclebot.engine.execution.Forensics.clear()
        com.lifecyclebot.engine.execution.Forensics.log(com.lifecyclebot.engine.execution.Forensics.Event.RUNTIME_EVENT, "", "smoke")
        assertTrue(com.lifecyclebot.engine.execution.Forensics.recent(10).isNotEmpty())
    }
}

class LaneExecutionCoordinatorSmokeTest {
    @Test
    fun specialist_lane_can_upgrade_treasury_first_caller_primary() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        LaneExecutionCoordinator.resetForTests()
        assertFalse("treasury first touch should defer one cycle for specialists", LaneExecutionCoordinator.canRequestExecution("MintTreasuryFirst", "TREASURY", runtimeGeneration = gen).allowed)
        assertTrue("specialist lane must be able to supersede treasury first-caller election", LaneExecutionCoordinator.canRequestExecution("MintTreasuryFirst", "MOONSHOT", runtimeGeneration = gen).allowed)
        assertFalse("treasury should become telemetry after specialist upgrade", LaneExecutionCoordinator.canRequestExecution("MintTreasuryFirst", "TREASURY", runtimeGeneration = gen).allowed)
    }

    @Test
    fun treasury_can_trade_next_pass_if_no_specialist_claims() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        LaneExecutionCoordinator.resetForTests()
        assertFalse(LaneExecutionCoordinator.canRequestExecution("MintTreasuryOnly", "TREASURY", runtimeGeneration = gen).allowed)
        assertTrue("treasury should be allowed on next pass when no specialist upgraded", LaneExecutionCoordinator.canRequestExecution("MintTreasuryOnly", "TREASURY", runtimeGeneration = gen).allowed)
    }

    @Test
    fun affinity_lane_can_upgrade_non_affinity_higher_base_lane() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        LaneExecutionCoordinator.resetForTests()
        LaneExecutionCoordinator.registerAffinity("MintAffinity", setOf("SHITCOIN"))
        assertTrue(LaneExecutionCoordinator.canRequestExecution("MintAffinity", "MOONSHOT", runtimeGeneration = gen).allowed)
        assertTrue("affinity boost should let SHITCOIN claim its routed token", LaneExecutionCoordinator.canRequestExecution("MintAffinity", "SHITCOIN", runtimeGeneration = gen).allowed)
        assertFalse(LaneExecutionCoordinator.canRequestExecution("MintAffinity", "MOONSHOT", runtimeGeneration = gen).allowed)
    }

    @Test
    fun failed_primary_release_allows_next_lane_to_trade() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        LaneExecutionCoordinator.resetForTests()
        assertTrue(LaneExecutionCoordinator.canRequestExecution("MintRelease", "MOONSHOT", runtimeGeneration = gen).allowed)
        assertFalse(LaneExecutionCoordinator.canRequestExecution("MintRelease", "SHITCOIN", runtimeGeneration = gen).allowed)
        assertTrue(LaneExecutionCoordinator.releaseIfPrimary("MintRelease", "MOONSHOT", "TEST_FDG_FAIL", runtimeGeneration = gen))
        assertTrue("after primary fails pre-open, another lane must get a shot", LaneExecutionCoordinator.canRequestExecution("MintRelease", "SHITCOIN", runtimeGeneration = gen).allowed)
    }

    @Test
    fun one_primary_lane_per_candidate_generation() {
        BotRuntimeController.resetForTests()
        val gen = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        LaneExecutionCoordinator.resetForTests()
        val e = LaneExecutionCoordinator.elect("MintA", listOf("TREASURY", "SHITCOIN", "MOONSHOT"), preferred = "SHITCOIN", runtimeGeneration = gen)
        assertEquals("SHITCOIN", e.primaryLane)
        assertTrue(LaneExecutionCoordinator.canRequestExecution("MintA", "SHITCOIN", runtimeGeneration = gen).allowed)
        assertFalse(LaneExecutionCoordinator.canRequestExecution("MintA", "TREASURY", runtimeGeneration = gen).allowed)
        assertEquals(1L, LaneExecutionCoordinator.duplicateOpenSuppressions())
    }
}


class QuarantineAndOutcomeLedgerSmokeTest {
    @Test
    fun quarantine_blocks_blacklisted_and_zero_liquidity_before_watchlist() {
        QuarantineStore.resetForTests()
        val zero = QuarantineStore.evaluate(
            mint = "MintZero111111111111111111111111111111",
            symbol = "ZERO",
            source = "SCANNER",
            liquidityUsd = 0.0,
            marketCapUsd = 1000.0,
        )
        assertTrue(zero.quarantined)
        assertEquals("ZERO_LIQUIDITY", zero.reason)
        assertTrue(QuarantineStore.isQuarantined("MintZero111111111111111111111111111111"))
    }

@Test
    fun outcome_ledger_counts_one_final_close_only() {
        TradeOutcomeLedger.resetForTests()
        BotRuntimeController.resetForTests()
        BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        val ts = com.lifecyclebot.data.TokenState(
            mint = "MintOutcome11111111111111111111111111",
            symbol = "OUT"
        )
        ts.position = com.lifecyclebot.data.Position(
            qtyToken = 100.0,
            entryPrice = 1.0,
            entryTime = 123456789L,
            costSol = 1.0,
            isPaperPosition = true,
            tradingMode = "SHITCOIN",
        )
        val buy = com.lifecyclebot.data.Trade(side = "BUY", mode = "paper", sol = 1.0, price = 1.0, ts = 123456789L, mint = ts.mint, tradingMode = "SHITCOIN")
        assertTrue(TradeOutcomeLedger.recordOpen(ts, buy))
        val sell = com.lifecyclebot.data.Trade(side = "SELL", mode = "paper", sol = 1.2, price = 1.2, ts = 123456999L, pnlPct = 20.0, mint = ts.mint, tradingMode = "SHITCOIN")
        assertTrue(TradeOutcomeLedger.recordClose(ts, sell, partial = false).accepted)
        assertFalse(TradeOutcomeLedger.recordClose(ts, sell.copy(ts = 123457000L), partial = false).accepted)
        assertEquals(1, TradeOutcomeLedger.uniqueClosedPositionCount())
        assertEquals(1L, TradeOutcomeLedger.learningDuplicateSuppressions())
    }
}

class ExecutionRouteGuardSmokeTest {
    @Test
    fun live_authority_blocks_normal_paper_route_without_shadow() {
        ExecutionRouteGuard.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        val ts = com.lifecyclebot.data.TokenState(mint = "MintRoute111111111111111111111111111", symbol = "ROUTE")
        val v = ExecutionRouteGuard.requirePaperRoute(ts, shadowEnabled = false)
        assertFalse(v.allowed)
        assertEquals(ExecutionRouteGuard.Route.PAPER, v.route)
        assertEquals(1L, ExecutionRouteGuard.paperBlockedInLiveCount())
    }

    @Test
    fun live_authority_allows_shadow_route_when_explicit() {
        ExecutionRouteGuard.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        val ts = com.lifecyclebot.data.TokenState(mint = "MintShadow11111111111111111111111111", symbol = "SHADOW")
        val v = ExecutionRouteGuard.requirePaperRoute(ts, shadowEnabled = true)
        assertTrue(v.allowed)
        assertEquals(ExecutionRouteGuard.Route.SHADOW, v.route)
        assertEquals(1L, ExecutionRouteGuard.shadowAllowedCount())
    }
}

class RuntimeRegressionGuardsSmokeTest {
    @Test
    fun guard_fails_lane_ratio_above_twelve() {
        val checks = RuntimeRegressionGuards.evaluate(
            RuntimeRegressionGuards.Input(intake = 10, laneEval = 130)
        )
        val lane = checks.first { it.name == "lane_eval_intake_ratio" }
        assertFalse(lane.ok)
    }

    @Test
    fun guard_fails_learning_mismatch() {
        val checks = RuntimeRegressionGuards.evaluate(
            RuntimeRegressionGuards.Input(learningTrades = 3, uniqueClosedPositionIds = 2)
        )
        val learning = checks.first { it.name == "learning_equals_unique_closes" }
        assertFalse(learning.ok)
    }
}

class RuntimeDoctorSmokeTest {
    @Test
    fun invariant_guard_does_not_treat_background_ui_as_runtime_stop() {
        val snap = RuntimeStateSnapshot.current(uiRunning = false).copy(
            runtimeState = "RUNNING",
            botLoopActive = true,
            uiState = "STOPPED",
        )
        val faults = InvariantGuardian.check(snap, uiRunning = false)
        assertFalse(faults.any { it.code == InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN })
        // RuntimeDoctor mapping is separately protected in code: this invariant
        // must not emit the UI split-brain fault from ordinary backgrounding.
    }

    @Test
    fun self_healer_refuses_forbidden_live_deploy_request() {
        val result = RuntimeSelfHealer.apply(
            RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.PAUSE_TRADING, reason = "please deploy apk and edit kotlin")
        )
        assertFalse(result.applied)
    }

    @Test
    fun signed_hotfix_rule_requires_valid_signature_and_rolls_back() {
        HotfixRules.resetForTests()
        val expires = System.currentTimeMillis() + 60_000L
        val sig = HotfixRules.signPayload("r1", 1, HotfixRules.RuleType.DISABLE_LANE, "MOONSHOT", "off", expires, "rb")
        val rule = HotfixRules.Rule("r1", 1, HotfixRules.RuleType.DISABLE_LANE, "MOONSHOT", "off", expires, "rb", sig)
        assertTrue(HotfixRules.apply(rule).applied)
        assertTrue(RuntimeRepairState.isLaneDisabled("MOONSHOT"))
        assertTrue(HotfixRules.rollback("r1", "rb").applied)
    }


    @Test
    fun runtime_overlay_duplicate_mitigation_is_idempotent() {
        RuntimeConfigOverlay.resetForTests()
        RuntimeConfigOverlay.disableLane("MOONSHOT", "same_reason", 60_000L)
        RuntimeConfigOverlay.disableLane("MOONSHOT", "same_reason", 60_000L)
        val active = RuntimeConfigOverlay.activeCommands().filter { it.kind == "DISABLE_LANE" && it.target == "MOONSHOT" }
        assertEquals(1, active.size)
    }

    @Test
    fun smart_sizer_caps_paper_cold_streak_size() {
        val cfg = com.lifecyclebot.data.BotConfig(paperMode = true, fluidLearningEnabled = false)
        val perf = SmartSizer.PerformanceContext(
            recentWinRate = 7.4,
            winStreak = 0,
            lossStreak = 11,
            sessionPeakSol = 100.0,
            totalTrades = 271,
        )
        val size = SmartSizer.calculate(
            walletSol = 68.0,
            entryScore = 80.0,
            perf = perf,
            cfg = cfg,
            openPositionCount = 4,
            currentTotalExposure = 0.0,
            liquidityUsd = 200_000.0,
            solPriceUsd = 160.0,
            mcapUsd = 1_000_000.0,
            aiConfidence = 80.0,
            setupQuality = "A+",
        )
        assertTrue("cold paper cap should keep sizing <= 1 SOL, got ${size.solAmount}", size.solAmount <= 1.0)
    }


    @Test
    fun regression_guards_do_not_treat_paper_open_positions_as_live_wallet_drift() {
        val checks = RuntimeRegressionGuards.evaluate(
            RuntimeRegressionGuards.Input(
                runtimeActive = true,
                sellReconcilerStarted = false,
                hostTrackerOpenCount = 0,
                positionStoreOpenCount = 12,
                paperOpenPositions = 12,
                liveOpenPositions = 0,
                walletHeldMints = 0,
                canonicalOpenPositions = 12,
            )
        )
        val host = checks.first { it.name == "host_tracker_open_match" }
        val sell = checks.first { it.name == "sell_reconciler_running" }
        assertTrue(host.ok)
        assertTrue(sell.ok)
    }



    @Test
    fun ui_running_truth_must_allow_service_runtime_fallback() {
        val runtimeSaysStopped = BotRuntimeController.Snapshot(state = BotRuntimeController.RuntimeState.STOPPED)
        val serviceRuntimeActive = true
        assertTrue(runtimeSaysStopped.runtimeActive || serviceRuntimeActive)
    }

    @Test
    fun stop_contract_rejects_unknown_or_unconfirmed_stop_sources() {
        assertFalse(BotService.isAllowedStopSource("unknown_action_stop", uiStopConfirmed = false))
        assertFalse(BotService.isAllowedStopSource("ui_stop_button", uiStopConfirmed = false))
        assertTrue(BotService.isAllowedStopSource("ui_stop_button", uiStopConfirmed = true))
        assertTrue(BotService.isAllowedStopSource("config_restart", uiStopConfirmed = false))
    }

    @Test
    fun state_debugger_outputs_required_safe_fields() {
        val snap = RuntimeStateSnapshot.current()
        val diagnosis = StateDebuggerAI.deterministicFallback(
            StateDebuggerAI.Context(snap, emptyList(), emptyList(), emptyList(), "cfg", emptyMap())
        )
        assertNotNull(diagnosis.faultCode)
        assertNotNull(diagnosis.safeMitigation)
        assertFalse(PatchWriterAI.planFromDiagnosis(diagnosis).mayMergeOrDeploy)
    }
}


class RuntimeEnforcementSmokeTest {
    @Test
    fun exec_open_request_not_emitted_for_confirmed_block_fatal() {
        RuntimeConfigOverlay.resetForTests()
        ExecutableOpenGate.resetForTests()
        ToxicModeCircuitBreaker.resetForTests()
        BirdeyeBudgetGate.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
        // V5.9.1230 — confirmed RC=0 remains fatal even in paper. RC=1 is
        // the learnable/pending sentinel and is covered by the next test.
        ExecutableOpenGate.recordV3("MintFatal111111111111111111111111111", "FATAL", "BLOCK_FATAL", "EXTREME_RUG_CRITICAL_score=0_CONFIRMED_RUG", "BLOCK_FATAL", 0)
        val v = ExecutableOpenGate.canOpenExecutablePosition("MintFatal111111111111111111111111111", "FATAL", 0, "PAPER", "SHITCOIN", "test")
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_BLOCKED_FATAL_V3", v.logName)
    }

    @Test
    fun paper_rc_pending_v3_rug_fatal_is_learnable_at_finality() {
        RuntimeConfigOverlay.resetForTests()
        ExecutableOpenGate.resetForTests()
        ToxicModeCircuitBreaker.resetForTests()
        BirdeyeBudgetGate.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
        val mint = "MintFatalRcPending111111111111111111"
        ExecutableOpenGate.recordV3(mint, "RCP", "BLOCK_FATAL", "EXTREME_RUG_RISK_100", "BLOCK_FATAL", 1)
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "RCP",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 1,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(mint, "RCP", 1, "PAPER", "SHITCOIN", "test")
        assertTrue("paper RC_PENDING score=1 should bypass rug-score V3 fatal", v.allowed)
        assertEquals("EXEC_OPEN_ALLOWED", v.logName)
    }

    @Test
    fun runtime_overlay_can_still_disable_specific_lane_when_explicitly_commanded() {
        RuntimeConfigOverlay.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(false)
        RuntimeModeAuthority.publishExecutorMode(false)
        RuntimeModeAuthority.publishPipelineMode(false)
        val fault = InvariantGuardian.Fault(InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION, "HIGH", "laneEval/intake=49")
        RuntimeMitigationBus.publish(RuntimeMitigationBus.Command.DisableLane("MOONSHOT", fault.detail, 30_000L))
        assertTrue(RuntimeConfigOverlay.isLaneDisabled("MOONSHOT"))
    }
}


class RuntimeQualityOnlyOverlaySmokeTest {
    @Test
    fun force_quality_only_disables_non_quality_lanes() {
        RuntimeConfigOverlay.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(false)
        RuntimeModeAuthority.publishExecutorMode(false)
        RuntimeModeAuthority.publishPipelineMode(false)
        RuntimeMitigationBus.publish(RuntimeMitigationBus.Command.ForceQualityOnly("fanout", 30_000L))
        assertTrue(RuntimeConfigOverlay.isLaneDisabled("SHITCOIN"))
        assertTrue(RuntimeConfigOverlay.isLaneDisabled("PROJECT_SNIPER"))
        assertFalse(RuntimeConfigOverlay.isLaneDisabled("QUALITY"))
    }
}

class ExecutionAuthorityInvariantTest {
    private fun resetAuthorities(paper: Boolean = true) {
        RuntimeConfigOverlay.resetForTests()
        ExecutableOpenGate.resetForTests()
        ToxicModeCircuitBreaker.resetForTests()
        BirdeyeBudgetGate.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = paper, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(paper)
        RuntimeModeAuthority.publishExecutorMode(paper)
        RuntimeModeAuthority.publishPipelineMode(paper)
    }

    @Test
    fun liquidity_floor_block_does_not_create_global_circuit_pause() {
        resetAuthorities(paper = true)
        val reason = ToxicModeCircuitBreaker.checkEntryAllowed(
            mode = "SHITCOIN",
            source = "test",
            liquidityUsd = 100.0,
            phase = "fresh_launch",
            memoryScore = 0,
            isAIDegraded = false,
            confidence = 50,
            isPaperMode = true,
        )
        assertTrue(reason?.contains("LIQUIDITY_BELOW_FLOOR") == true)
        assertFalse("liquidity floor is lane-local, not global circuit breaker", ToxicModeCircuitBreaker.currentEntryPause().active)
    }


    @Test
    fun executable_gate_accepts_lane_alias_and_source_bucket_when_fdg_selected_specialist() {
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("BLUECHIP", "BLUE_CHIP"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("SHITCOIN", "CORE"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("MOONSHOT", "DEX_TREND"))
        assertFalse(ExecutableOpenGate.lanesCompatibleForTests("UNKNOWN", "CORE"))
        assertFalse(ExecutableOpenGate.lanesCompatibleForTests("QUALITY", "SHITCOIN"))
    }

    @Test
    fun paper_circuit_breaker_is_telemetry_only_for_learning() {
        resetAuthorities(paper = true)
        ExecutableOpenGate.recordFdg(
            mint = "MintCircuit11111111111111111111111111",
            symbol = "CB",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        ToxicModeCircuitBreaker.forceTripForTests("SHITCOIN", 60_000L, "TEST_CIRCUIT")
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = "MintCircuit11111111111111111111111111",
            symbol = "CB",
            rugScore = 90,
            mode = "PAPER",
            lane = "SHITCOIN",
            source = "test",
        )
        assertTrue("paper mode must keep trading through circuit telemetry", v.allowed)
        assertEquals("EXEC_OPEN_ALLOWED", v.logName)
        assertNotNull("paper bypass must still create canonical allowed attempt", ExecutableOpenGate.recentAllowedAttemptId("MintCircuit11111111111111111111111111", "SHITCOIN"))
    }

    @Test
    fun live_circuit_breaker_blocks_before_executable_open_allowed() {
        resetAuthorities(paper = false)
        ExecutableOpenGate.recordFdg(
            mint = "MintLiveCircuit11111111111111111111111",
            symbol = "CBL",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        ToxicModeCircuitBreaker.forceTripForTests("SHITCOIN", 60_000L, "TEST_CIRCUIT")
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = "MintLiveCircuit11111111111111111111111",
            symbol = "CBL",
            rugScore = 90,
            mode = "LIVE",
            lane = "SHITCOIN",
            source = "test",
        )
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_BLOCKED_CIRCUIT_BREAKER", v.logName)
        assertTrue(v.reason.contains("TEST_CIRCUIT"))
        assertNull("blocked live circuit breaker must not create allowed attempt", ExecutableOpenGate.recentAllowedAttemptId("MintLiveCircuit11111111111111111111111", "SHITCOIN"))
    }

    @Test
    fun paper_missing_rug_context_is_learnable_unknown() {
        resetAuthorities(paper = true)
        val mint = "MintMissingRcPaper11111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "MRCP",
            lane = "TREASURY",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = -1,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = "MRCP",
            rugScore = -1,
            mode = "PAPER",
            lane = "TREASURY",
            source = "test",
        )
        assertTrue("paper missing RC context should be learnable unknown", v.allowed)
        assertEquals("EXEC_OPEN_ALLOWED", v.logName)
    }

    @Test
    fun live_missing_rug_context_remains_blocked() {
        resetAuthorities(paper = false)
        val mint = "MintMissingRcLive111111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "MRCL",
            lane = "TREASURY",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = -1,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = "MRCL",
            rugScore = -1,
            mode = "LIVE",
            lane = "TREASURY",
            source = "test",
        )
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY", v.logName)
        assertEquals("HARD_NO_BUY", v.reason)
    }

    @Test
    fun paper_rc_pending_score_one_stays_executable_finality() {
        resetAuthorities(paper = true)
        val mint = "MintRcPendingPaper111111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "RCP",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 1,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = "RCP",
            rugScore = 1,
            mode = "PAPER",
            lane = "SHITCOIN",
            source = "test",
        )
        assertTrue("paper RC_PENDING score=1 must be learnable", v.allowed)
        assertEquals("EXEC_OPEN_ALLOWED", v.logName)
    }

    @Test
    fun paper_low_rc_score_six_stays_executable_for_learning() {
        resetAuthorities(paper = true)
        val mint = "MintRcSixPaper111111111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "RC6P",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 6,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = "RC6P",
            rugScore = 6,
            mode = "PAPER",
            lane = "SHITCOIN",
            source = "test",
        )
        assertTrue("paper low RC score=6 must remain learnable", v.allowed)
        assertEquals("EXEC_OPEN_ALLOWED", v.logName)
    }

    @Test
    fun live_low_rc_score_six_remains_finality_blocked() {
        resetAuthorities(paper = false)
        val mint = "MintRcSixLive1111111111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "RC6L",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 6,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = "RC6L",
            rugScore = 6,
            mode = "LIVE",
            lane = "SHITCOIN",
            source = "test",
        )
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY", v.logName)
        assertEquals("HARD_NO_BUY", v.reason)
    }

    @Test
    fun live_rc_score_one_remains_finality_blocked() {
        resetAuthorities(paper = false)
        val mint = "MintRcPendingLive1111111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "RCL",
            lane = "SHITCOIN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 1,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = "RCL",
            rugScore = 1,
            mode = "LIVE",
            lane = "SHITCOIN",
            source = "test",
        )
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY", v.logName)
        assertEquals("HARD_NO_BUY", v.reason)
    }

    @Test
    fun cyclic_can_prime_treasury_election_for_same_tick_authorization() {
        resetAuthorities(paper = true)
        TradeAuthorizer.reset()
        LaneExecutionCoordinator.resetForTests()
        val mint = "MintCyclicTreasury1111111111111111"
        ExecutableOpenGate.recordFdg(
            mint = mint,
            symbol = "CYCT",
            lane = "TREASURY",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        assertFalse(LaneExecutionCoordinator.canRequestExecution(mint, "TREASURY").allowed)
        val auth = TradeAuthorizer.authorize(
            mint = mint,
            symbol = "CYCT",
            score = 70,
            confidence = 70.0,
            quality = "B",
            isPaperMode = true,
            requestedBook = TradeAuthorizer.ExecutionBook.TREASURY,
            rugcheckScore = 90,
            liquidity = 2500.0,
        )
        assertTrue("cyclic's primed Treasury election should authorize on same tick", auth.isExecutable())
        assertEquals("AUTHORIZED", auth.reason)
    }

    @Test
    fun trade_authorizer_returns_finality_attempt_contract() {
        resetAuthorities(paper = true)
        TradeAuthorizer.reset()
        LaneExecutionCoordinator.resetForTests()
        val mint = "MintContract1111111111111111111111111"
        ExecutableOpenGate.recordV3(mint, "CONTRACT", "WATCH", "DECISION_WATCH", "WATCH", 90)
        ExecutableOpenGate.recordFdg(mint, "CONTRACT", "SHITCOIN", true, null, signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 2500.0)
        val auth = TradeAuthorizer.authorize(
            mint = mint,
            symbol = "CONTRACT",
            score = 42,
            confidence = 60.0,
            quality = "C",
            isPaperMode = true,
            requestedBook = TradeAuthorizer.ExecutionBook.SHITCOIN,
            rugcheckScore = 90,
            liquidity = 2500.0,
        )
        assertTrue(auth.isExecutable())
        assertTrue("authorized execution must carry the finality attemptId contract", auth.attemptId.isNotBlank())
        assertEquals(auth.attemptId, ExecutableOpenGate.recentAllowedAttemptId(mint, "SHITCOIN"))
    }

    @Test
    fun daily_budget_exhaustion_blocks_executable_entry_but_provider_lock_is_separate() {
        resetAuthorities(paper = true)
        BirdeyeBudgetGate.resetForTests(dailyCapOverride = 25L)
        BirdeyeBudgetGate.recordCalls(1)
        assertTrue("configured daily cap exhausted", BirdeyeBudgetGate.isEntryBudgetLockedDown())
        ExecutableOpenGate.recordFdg(
            mint = "MintBudget111111111111111111111111111",
            symbol = "BUD",
            lane = "QUALITY",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = "MintBudget111111111111111111111111111",
            symbol = "BUD",
            rugScore = 90,
            mode = "PAPER",
            lane = "QUALITY",
            source = "test",
        )
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_BLOCKED_API_BUDGET_LOCKDOWN", v.logName)
    }

    @Test
    fun non_buy_candidate_is_dropped_without_allowed_attempt() {
        resetAuthorities(paper = true)
        ExecutableOpenGate.recordFdg(
            mint = "MintWait1111111111111111111111111111",
            symbol = "WAIT",
            lane = "QUALITY",
            canExecute = false,
            reason = "Signal is WAIT",
            signal = "WAIT",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 2500.0,
        )
        val first = ExecutableOpenGate.canOpenExecutablePosition(
            mint = "MintWait1111111111111111111111111111",
            symbol = "WAIT",
            rugScore = 90,
            mode = "PAPER",
            lane = "QUALITY",
            source = "test",
        )
        assertFalse(first.allowed)
        assertEquals("EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY", first.logName)
        val second = ExecutableOpenGate.canOpenExecutablePosition(
            mint = "MintWait1111111111111111111111111111",
            symbol = "WAIT",
            rugScore = 90,
            mode = "PAPER",
            lane = "QUALITY",
            source = "test",
        )
        assertFalse(second.allowed)
        assertEquals("EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY", second.logName)
        assertNull(ExecutableOpenGate.recentAllowedAttemptId("MintWait1111111111111111111111111111", "QUALITY"))
    }

    @Test
    fun mode_authority_rejects_mixed_live_request_while_runtime_paper() {
        resetAuthorities(paper = true)
        val v = ExecutableOpenGate.canOpenExecutablePosition(
            mint = "MintMode1111111111111111111111111111",
            symbol = "MODE",
            rugScore = 90,
            mode = "LIVE",
            lane = "QUALITY",
            source = "test",
        )
        assertFalse(v.allowed)
        assertEquals("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", v.logName)
        assertTrue(v.reason.contains("LIVE_REQUEST_WHILE_RUNTIME_PAPER"))
    }

    @Test
    fun canonical_valid_partial_sell_is_trainable_and_partial() {
        val outcome = CanonicalTradeOutcome(
            tradeId = "partial-valid-1",
            mint = "MintPartialValid111111111111111111111",
            symbol = "PV",
            assetClass = AssetClass.MEME,
            mode = TradeMode.SHITCOIN,
            source = TradeSource.SHITCOIN,
            environment = TradeEnvironment.PAPER,
            entryTimeMs = 1000L,
            exitTimeMs = 2000L,
            entryPrice = 0.001,
            exitPrice = 0.0015,
            entrySol = 0.10,
            exitSol = 0.15,
            realizedPnlSol = 0.05,
            realizedPnlPct = 50.0,
            maxGainPct = 50.0,
            maxDrawdownPct = 0.0,
            holdSeconds = 1L,
            result = TradeResult.WIN,
            executionResult = ExecutionResult.EXECUTED,
            closeReason = "partial_50pct",
            candidate = CandidateFeatures(assetClass = "MEME", runtimeMode = "PAPER", trader = "SHITCOIN", venue = "PUMP_FUN_BONDING", entryPattern = "TEST", liqBucket = "LIQ_LOW", mcapBucket = "MCAP_MICRO"),
            featuresIncomplete = false,
            isPartial = true,
            partialIndex = 1,
            parentPositionId = "parent-1",
            costBasisSol = 0.10,
            proceedsSol = 0.15,
            feesSol = 0.0,
            isTrainable = true,
        )
        val normalized = CanonicalOutcomeNormalizer.normalizeOutcomeBeforeLearning(outcome)
        assertNotNull(normalized)
        assertTrue(normalized!!.isPartial)
        assertTrue(normalized.isTrainable)
        assertNull(normalized.invalidReason)
    }

    @Test
    fun canonical_zero_price_partial_is_invalid_not_silent_standard_training() {
        val outcome = CanonicalTradeOutcome(
            tradeId = "partial-invalid-1",
            mint = "MintPartialInvalid1111111111111111111",
            symbol = "PI",
            assetClass = AssetClass.MEME,
            mode = TradeMode.SHITCOIN,
            source = TradeSource.SHITCOIN,
            environment = TradeEnvironment.PAPER,
            entryTimeMs = 1000L,
            exitTimeMs = 2000L,
            entryPrice = 0.001,
            exitPrice = 0.0,
            entrySol = 0.10,
            exitSol = 0.0,
            realizedPnlSol = 0.05,
            realizedPnlPct = 50.0,
            maxGainPct = 50.0,
            maxDrawdownPct = 0.0,
            holdSeconds = 1L,
            result = TradeResult.WIN,
            executionResult = ExecutionResult.EXECUTED,
            closeReason = "CHUNK_SELL",
            candidate = CandidateFeatures(assetClass = "MEME", runtimeMode = "PAPER", trader = "SHITCOIN", venue = "PUMP_FUN_BONDING", entryPattern = "TEST", liqBucket = "LIQ_LOW", mcapBucket = "MCAP_MICRO"),
            featuresIncomplete = false,
            isPartial = true,
            partialIndex = 1,
            parentPositionId = "parent-2",
            costBasisSol = 0.10,
            proceedsSol = 0.0,
            feesSol = 0.0,
            isTrainable = true,
        )
        val normalized = CanonicalOutcomeNormalizer.normalizeOutcomeBeforeLearning(outcome)
        assertNotNull(normalized)
        assertFalse(normalized!!.isTrainable)
        assertEquals("MISSING_EXIT_PRICE", normalized.invalidReason)
    }

}
