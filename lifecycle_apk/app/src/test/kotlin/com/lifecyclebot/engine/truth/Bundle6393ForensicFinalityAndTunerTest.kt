package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.math.BigDecimal

/** V5.0.6393 — all mandatory acceptance fixtures from directive Section Q. */
class Bundle6393ForensicFinalityAndTunerTest {

    @Before fun setUp() {
        PositionStateMachine6393.clearForTest()
        Trade1AdaptiveTuner6393.clearForTest()
        WalletBalanceProof6393.clearForTest()
        ExecutionTelemetrySemantics6393.clearForTest()
    }
    @After fun tearDown() { setUp() }

    /* ---------- MANDATORY DECIMAL FIXTURE (S B) ------------------------- */

    @Test fun decimal_fixture_raw_21651286486_decimals_6_ui_exact() {
        val fill = CanonicalFill6393.build(
            fillId = "f1", positionId = "posPigeon", mint = "3PqZ...pump",
            side = "SELL", transactionSignature = "5gAXkuvx", instructionIndex = 0,
            tokenAccount = "acct",
            rawTokenDelta = BigInteger.valueOf(21_651_286_486L), tokenDecimals = 6,
            lamportDelta = 9_555_928L, feeLamports = 200_000L,
            blockTime = 0L, slot = 0L, proofSource = "TX_META", finality = "FINALIZED",
            venue = "JUPITER", processor = "SELL",
        )
        assertEquals(BigDecimal("21651.286486"), fill.uiTokenDelta)
        assertTrue(fill.roundTripInvariantHolds())
    }

    /* ---------- MANDATORY PIGEOOON SETTLEMENT (S A + S I) --------------- */

    @Test fun pigeon_first_close_produces_trade1_tuner_update_only_once() {
        // Cost 0.006984, proceeds 0.009556 -> +0.002520 SOL -> +36.09%.
        val closeId = "canonical_close_pigeon_1"
        val u1 = Trade1AdaptiveTuner6393.applyClose(
            canonicalCloseId = closeId, strategyKey = "GLOBAL:LIVE",
            netReturnPct = 36.09, isRug = false,
        )
        assertNotNull(u1); assertEquals(1, u1!!.n)
        // Trade 1: winner => size may increase by AT MOST 2%
        assertTrue(u1.new.sizeMultiplier <= 1.02001)
        assertTrue(u1.new.sizeMultiplier >= u1.old.sizeMultiplier)
        // Replay MUST NOT create a second update
        val u2 = Trade1AdaptiveTuner6393.applyClose(
            canonicalCloseId = closeId, strategyKey = "GLOBAL:LIVE",
            netReturnPct = 36.09, isRug = false,
        )
        assertNull("duplicate close MUST NOT re-learn", u2)
        assertEquals(1, Trade1AdaptiveTuner6393.sampleCount("GLOBAL:LIVE"))
    }

    @Test fun confidence_grows_with_n() {
        assertEquals(0.0, Trade1AdaptiveTuner6393.confidence(0), 1e-9)
        assertEquals(1.0 / 13.0, Trade1AdaptiveTuner6393.confidence(1), 1e-9)
        assertTrue(Trade1AdaptiveTuner6393.confidence(20) > Trade1AdaptiveTuner6393.confidence(8))
    }

    @Test fun tuner_never_raises_size_on_small_loss() {
        val u = Trade1AdaptiveTuner6393.applyClose("c1", "L:X",
            netReturnPct = -5.0, isRug = false)!!
        assertTrue("size must decrease on small loss", u.new.sizeMultiplier < u.old.sizeMultiplier)
        assertTrue(u.new.sizeMultiplier >= 0.85)  // bounded by <8 cell
    }

    @Test fun tuner_rug_applies_hardest_local_penalty() {
        val u = Trade1AdaptiveTuner6393.applyClose("c1", "L:Y",
            netReturnPct = -80.0, isRug = true)!!
        assertTrue("rug penalty must at least 8% shrink", u.new.sizeMultiplier <= 0.92 + 1e-9)
        assertEquals("RUG_LOCAL_PENALTY", u.reason)
    }

    /* ---------- MANDATORY RECONCILER TEST (S E) ------------------------- */

    @Test fun held_position_returning_unknown_stays_open_and_zero_counter_untouched() {
        val ring = WalletBalanceProof6393.ZeroConfirmationRing("posX")
        // Cycle 1: HELD
        ring.addProof("snap1", 1_000L, WalletBalanceProof6393.Proof.HELD)
        assertEquals(1, ring.countHeld()); assertEquals(0, ring.countZero())
        // Cycle 2: provider timeout -> UNKNOWN
        ring.addProof("snap2", 2_000L, WalletBalanceProof6393.Proof.UNKNOWN)
        assertEquals(0, ring.countZero())
        // Cycle 3: HELD again -> counter still 0, position stays open
        ring.addProof("snap3", 3_000L, WalletBalanceProof6393.Proof.HELD)
        assertFalse(ring.eligibleForZeroClose(freshBuyGraceEndMs = 0L, nowMs = 10_000L))
    }

    /* ---------- MANDATORY ZERO-PROOF TEST (S E) ------------------------- */

    @Test fun three_fresh_zero_proofs_spanning_20s_close_exactly_once() {
        val ring = WalletBalanceProof6393.ZeroConfirmationRing("posZ")
        ring.addProof("snapA", 0L, WalletBalanceProof6393.Proof.ZERO)
        ring.addProof("snapB", 10_000L, WalletBalanceProof6393.Proof.ZERO)
        assertFalse(ring.eligibleForZeroClose(freshBuyGraceEndMs = 0L, nowMs = 21_000L))
        ring.addProof("snapC", 21_000L, WalletBalanceProof6393.Proof.ZERO)
        assertTrue(ring.eligibleForZeroClose(freshBuyGraceEndMs = 0L, nowMs = 21_000L))
    }

    @Test fun held_proof_resets_all_zero_confirmations() {
        val ring = WalletBalanceProof6393.ZeroConfirmationRing("posR")
        ring.addProof("s1", 0L, WalletBalanceProof6393.Proof.ZERO)
        ring.addProof("s2", 10_000L, WalletBalanceProof6393.Proof.ZERO)
        ring.addProof("s3", 20_500L, WalletBalanceProof6393.Proof.HELD)   // reset
        ring.addProof("s4", 21_000L, WalletBalanceProof6393.Proof.ZERO)
        assertFalse(ring.eligibleForZeroClose(freshBuyGraceEndMs = 0L, nowMs = 30_000L))
    }

    @Test fun fresh_buy_grace_period_prevents_zero_close() {
        val ring = WalletBalanceProof6393.ZeroConfirmationRing("posG")
        ring.addProof("s1", 0L, WalletBalanceProof6393.Proof.ZERO)
        ring.addProof("s2", 10_000L, WalletBalanceProof6393.Proof.ZERO)
        ring.addProof("s3", 25_000L, WalletBalanceProof6393.Proof.ZERO)
        // 5-minute grace still active
        assertFalse(ring.eligibleForZeroClose(freshBuyGraceEndMs = 300_000L, nowMs = 30_000L))
        assertTrue(ring.eligibleForZeroClose(freshBuyGraceEndMs = 300_000L, nowMs = 310_000L))
    }

    @Test fun unknown_proof_derivation() {
        assertEquals(WalletBalanceProof6393.Proof.UNKNOWN,
            WalletBalanceProof6393.proofOf(WalletBalanceProof6393.ProofInput(
                walletRawBalance = null, snapshotCompletion = "COMPLETE",
                timedOut = true, httpFailed = false, rateLimited = false,
                staleCache = false, parseFailed = false, coroutineCancelled = false,
                providerDisagreement = false, paginationUncertainty = false,
                responseAgeMs = 0L,
            )))
        assertEquals(WalletBalanceProof6393.Proof.HELD,
            WalletBalanceProof6393.proofOf(WalletBalanceProof6393.ProofInput(
                walletRawBalance = BigInteger.valueOf(1_000L),
                snapshotCompletion = "COMPLETE",
                timedOut = false, httpFailed = false, rateLimited = false,
                staleCache = false, parseFailed = false, coroutineCancelled = false,
                providerDisagreement = false, paginationUncertainty = false,
                responseAgeMs = 0L,
            )))
        assertEquals(WalletBalanceProof6393.Proof.ZERO,
            WalletBalanceProof6393.proofOf(WalletBalanceProof6393.ProofInput(
                walletRawBalance = BigInteger.ZERO,
                snapshotCompletion = "COMPLETE",
                timedOut = false, httpFailed = false, rateLimited = false,
                staleCache = false, parseFailed = false, coroutineCancelled = false,
                providerDisagreement = false, paginationUncertainty = false,
                responseAgeMs = 0L,
            )))
    }

    @Test fun jupiter_is_not_wallet_balance_authority() {
        assertFalse(WalletBalanceProof6393.isWalletBalanceAuthority("JUPITER"))
        assertTrue(WalletBalanceProof6393.isWalletBalanceAuthority("HELIUS_FRESH"))
    }

    /* ---------- MANDATORY RECOVERED-POSITION TEST (S F) ----------------- */

    @Test fun recovered_position_invariant_passes_21_of_21() {
        val c = ManagedVsRecoveredCounters6393(
            botManagedOpenPositions = 5, walletRecoveredOpenPositions = 2,
            botManagedWalletHeldMints = 5, otherWalletHeldMints = 2,
            freshBuyGracePositions = 0,
            canonicalBotManagedOpenPositions = 5, canonicalRecoveredOpenPositions = 2,
        )
        assertTrue(c.invariantHolds())
        assertEquals(7, c.totalWalletHeldMints)
    }

    /* ---------- MANDATORY TELEMETRY TEST (S G) -------------------------- */

    @Test fun score_floor_blocks_never_inflate_buy_execution_failed() {
        repeat(265) { ExecutionTelemetrySemantics6393.scoreFloorBlock() }
        assertEquals(265L, ExecutionTelemetrySemantics6393.scoreFloorBlocked.get())
        assertEquals(0L, ExecutionTelemetrySemantics6393.buyExecutionFailed.get())
        assertEquals(265L, ExecutionTelemetrySemantics6393.preauthorisationBlocked.get())
    }

    /* ---------- POSITION STATE MACHINE (S C) ---------------------------- */

    @Test fun only_closed_settled_learning_eligible() {
        assertTrue(PositionStateMachine6393.canonicalLearningEligible(
            PositionStateMachine6393.State.CLOSED_SETTLED))
        assertFalse(PositionStateMachine6393.canonicalLearningEligible(
            PositionStateMachine6393.State.SELL_BROADCAST))
        assertFalse(PositionStateMachine6393.canonicalLearningEligible(
            PositionStateMachine6393.State.OPEN))
    }

    @Test fun commit_canonical_close_is_exactly_once_per_position() {
        assertTrue(PositionStateMachine6393.commitCanonicalClose("cc1", "pos1"))
        assertFalse("duplicate commit MUST return false",
            PositionStateMachine6393.commitCanonicalClose("cc1", "pos1"))
        assertFalse("another close for same positionId MUST return false",
            PositionStateMachine6393.commitCanonicalClose("cc2", "pos1"))
    }

    @Test fun state_transitions_reject_illegal_moves() {
        assertTrue(PositionStateMachine6393.isValidTransition(
            PositionStateMachine6393.State.SELL_CONFIRMED,
            PositionStateMachine6393.State.CLOSED_SETTLED))
        assertFalse(PositionStateMachine6393.isValidTransition(
            PositionStateMachine6393.State.CREATED,
            PositionStateMachine6393.State.CLOSED_SETTLED))
    }

    /* ---------- ASYMMETRIC EXIT STRUCTURE (S M) ------------------------- */

    @Test fun asymmetric_exit_ladders_reserve_runner() {
        val qual = AsymmetricExitStructure6393.defaultLadder(
            AsymmetricExitStructure6393.LaneClass.QUALITY_BLUECHIP)
        assertEquals(2, qual.size)
        val quality_ladder_sold = qual.sumOf { it.sellFractionOfInitial }
        assertEquals(0.50, quality_ladder_sold, 1e-9)   // 50% runner retained
        assertEquals(0.50, AsymmetricExitStructure6393.runnerFraction(
            AsymmetricExitStructure6393.LaneClass.QUALITY_BLUECHIP), 1e-9)
        assertEquals(0.40, AsymmetricExitStructure6393.runnerFraction(
            AsymmetricExitStructure6393.LaneClass.MOONSHOT_SHITCOIN), 1e-9)
    }

    /* ---------- WEEKLY 5X GROWTH MODE (S N) ----------------------------- */

    @Test fun weekly_drawdown_shapes_size_never_hold() {
        assertEquals(1.00, WeeklyGrowthMode6393.sizeMultiplierFromDrawdown(2.0), 1e-9)
        assertEquals(0.85, WeeklyGrowthMode6393.sizeMultiplierFromDrawdown(7.5), 1e-9)
        assertEquals(0.65, WeeklyGrowthMode6393.sizeMultiplierFromDrawdown(15.0), 1e-9)
        assertEquals(0.40, WeeklyGrowthMode6393.sizeMultiplierFromDrawdown(25.0), 1e-9)
    }

    @Test fun weekly_never_martingales_after_loss() {
        assertFalse(WeeklyGrowthMode6393.neverMartingaleSize(
            priorTradeReturnPct = -20.0, plannedNextSizeMultiplier = 1.50,
            currentSizeMultiplier = 1.00))
        assertTrue(WeeklyGrowthMode6393.neverMartingaleSize(
            priorTradeReturnPct = 10.0, plannedNextSizeMultiplier = 1.05,
            currentSizeMultiplier = 1.00))
    }

    @Test fun weekly_progress_math_is_correct() {
        val s = WeeklyGrowthMode6393.Snapshot(
            weeklyStartEquitySol = 1.0, currentEquitySol = 2.0,
            realisedEquitySol = 1.0, deployedCapitalSol = 0.5,
            protectedCapitalSol = 0.5, peakWeeklyEquitySol = 2.2,
            weeklyDrawdownPct = 5.0,
        )
        assertEquals(5.0, s.targetEquitySol, 1e-9)
        assertEquals(25.0, s.progressToTargetPct, 1e-6)   // 1 / 4 = 25%
        assertEquals(2.0, s.geometricGrowthRate, 1e-9)
    }

    /* ---------- POSITION SIZING (S O) ----------------------------------- */

    @Test fun sizing_respects_exposure_cap_after_reserve() {
        val r = PositionSizing6393.compute(PositionSizing6393.Components(
            configuredBaseSol = 0.02, availableWalletSol = 1.0, reserveSol = 0.2,
            strategyConfidence = 0.5, laneMultiplier = 1.0, tacticMultiplier = 1.0,
            drawdownMultiplier = 1.0, liquidityCapacityMultiplier = 1.0,
            expectedSlippagePct = 1.0, currentTotalExposureSol = 0.0,
            sampleConfidenceMultiplier = 0.5,
        ))
        assertTrue(r.sizeSol > 0.0)
        assertTrue("must be bounded by walletAfterReserve * exposureCap", r.sizeSol <= 0.20)
    }

    /* ---------- GOVERNOR EPOCH (S P) ------------------------------------ */

    @Test fun performance_hold_requires_reliable_sample() {
        assertFalse(GovernorEpoch6393.canEnterHoldFromPerformance(sampleN = 3))
        assertTrue(GovernorEpoch6393.canEnterHoldFromPerformance(sampleN = 20))
    }

    @Test fun quarantined_or_recovered_do_not_influence_governor() {
        assertFalse(GovernorEpoch6393.eligibleForGovernorInfluence(
            PositionStateMachine6393.State.CLOSED_SETTLED, backfilled = true))
        assertTrue(GovernorEpoch6393.eligibleForGovernorInfluence(
            PositionStateMachine6393.State.CLOSED_SETTLED, backfilled = false))
    }

    @Test fun epoch_id_is_the_6393_strategy_epoch() {
        assertEquals("STRATEGY_EPOCH_6393", GovernorEpoch6393.EPOCH_ID)
    }

    /* ---------- TACTIC ROTATION (S J) ----------------------------------- */

    @Test fun tactic_ranking_prefers_lower_confidence_expected_r() {
        val ranked = TacticRotation6393.rank(listOf(
            TacticRotation6393.TacticStats("A", 5, 0.10, 0.10, 0.20, 0.60, 1000L, 0.9),
            TacticRotation6393.TacticStats("B", 8, 0.50, 0.30, 0.05, 0.80, 500L, 0.8),
            TacticRotation6393.TacticStats("C", 3, 0.30, 0.20, 0.10, 0.70, 800L, 0.85),
        ))
        assertEquals("B", ranked.first().tactic)
    }

    @Test fun tactic_rotation_requires_three_losses_plus_negative_expectancy() {
        assertFalse(TacticRotation6393.shouldRotate(2, -0.10, false))
        assertTrue(TacticRotation6393.shouldRotate(3, -0.10, false))
        assertFalse(TacticRotation6393.shouldRotate(3, +0.10, false))
        assertTrue("catastrophic rug rotates immediately",
            TacticRotation6393.shouldRotate(0, +0.10, true))
    }
}
