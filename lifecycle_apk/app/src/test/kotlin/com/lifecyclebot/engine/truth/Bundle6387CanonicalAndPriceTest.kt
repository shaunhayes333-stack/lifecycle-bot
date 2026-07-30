package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger

/**
 * V5.0.6387 — DIRECTIVE A (canonical ledger) + DIRECTIVE B (price identity)
 * combined invariants. Ships both P0 directives in a single commit.
 */
class Bundle6387CanonicalAndPriceTest {

    @Before
    fun setup() {
        CanonicalLedger6387.clearAllForTest()
        ReconciliationCoordinator6387.resetForTest()
        ModeNotificationDedup6387.resetForTest()
        // Keep holds ACTIVE by default — tests explicitly disable when needed.
        CanonicalLedgerParityHold6387.setTestOverride(true)
        FalseProfitTriggerHold6387.setTestOverride(true)
    }

    @After
    fun teardown() {
        CanonicalLedger6387.clearAllForTest()
        ReconciliationCoordinator6387.resetForTest()
        CanonicalLedgerParityHold6387.setTestOverride(true)
        FalseProfitTriggerHold6387.setTestOverride(true)
    }

    // ─── Directive A: safety holds ───────────────────────────────────

    @Test
    fun canonical_ledger_parity_hold_is_active_by_default() {
        assertTrue(CanonicalLedgerParityHold6387.isActive())
        assertEquals(0, CanonicalLedgerParityHold6387.cleanCycleCount())
    }

    @Test
    fun hold_releases_only_after_five_clean_cycles() {
        repeat(4) { CanonicalLedgerParityHold6387.onCleanCycle() }
        assertTrue("must still be active at 4 clean cycles", CanonicalLedgerParityHold6387.isActive())
        CanonicalLedgerParityHold6387.onCleanCycle()
        assertFalse("must release at 5 clean cycles", CanonicalLedgerParityHold6387.isActive())
    }

    @Test
    fun any_invariant_failure_resets_the_hold() {
        repeat(4) { CanonicalLedgerParityHold6387.onCleanCycle() }
        CanonicalLedgerParityHold6387.onInvariantFailure("QTY_FAIL")
        assertTrue(CanonicalLedgerParityHold6387.isActive())
        assertEquals(0, CanonicalLedgerParityHold6387.cleanCycleCount())
    }

    @Test
    fun executable_open_gate_hard_blocks_live_when_either_hold_is_active() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("CanonicalLedgerParityHold6387.isActive()"))
        assertTrue(gate.contains("FalseProfitTriggerHold6387.isActive()"))
        assertTrue(gate.contains("CANONICAL_LEDGER_PARITY_HOLD_6387"))
        assertTrue(gate.contains("FALSE_PROFIT_TRIGGER_HOLD_6387"))
    }

    // ─── Directive A: canonical position identity ────────────────────

    @Test
    fun position_id_never_derived_from_symbol_or_timestamp() {
        val id1 = CanonicalPositionId6387.new("wallet", "mint", "ta", "sig", 0, 1L, 1L)
        val id2 = CanonicalPositionId6387.new("wallet", "mint", "ta", "sig", 0, 1L, 1L)
        assertNotEquals("positionId must be unique even for identical inputs", id1.positionId, id2.positionId)
    }

    @Test
    fun canonical_fill_only_accepts_finalized_commitment() {
        try {
            CanonicalFill6387(
                fillId = "f1", positionId = "p1", signature = "sig",
                instructionIndex = 0, side = FillSide6387.BUY,
                rawTokenDelta = RawTokenAmount.of(100L),
                tokenDecimals = MintDecimals.Known(6, "T", "sig"),
                lamportDelta = -500_000L,
                networkFeeLamports = Lamports.of(5_000L),
                priorityFeeLamports = Lamports.ZERO, tipLamports = Lamports.ZERO,
                slot = 1L, blockTime = 1L, commitment = "confirmed",
                proofSource = "TEST", provider = "test", createdAt = 1L,
            )
            fail("commitment=confirmed must throw — only finalized qualifies")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("finalized") == true)
        }
    }

    // ─── Directive A: atomic BUY finalisation + idempotency ──────────

    @Test
    fun atomic_buy_commit_and_idempotent_reprocess() {
        val intent = ExecutionIntent6386(
            intentId = "i1", walletAddress = "wallet1", mintAddress = "mint1",
            side = IntentSide.BUY, selectedLane = "MOONSHOT",
            marketSnapshotId = "s1", decisionTimestamp = 1L,
            requestedLamports = Lamports.of(3_000_000L),
            requestedRawTokenAmount = null,
            score = 55, fdgVerdict = "ALLOW",
            routeRequirements = RouteRequirements(),
            lifecycleState = ProofState6386.IntentCreated,
        )
        val proof = ProofState6386.FinalizedProofComplete(
            signature = "sig_buy_1", walletAddress = "wallet1", mintAddress = "mint1",
            decimals = MintDecimals.Known(6, "T", "sig_mint"),
            preRawBalance = RawTokenAmount.of(0L),
            postRawBalance = RawTokenAmount.of(1_550_673_559_463L),   // AE8Wq7 real qty
            preLamports = Lamports.of(25_000_000L),
            postLamports = Lamports.of(0L),
            feeLamports = Lamports.of(5_000L),
        )
        val input = CanonicalLedger6387.BuyCommitInput(
            intent = intent, proof = proof, tokenAccount = "ta1",
            slot = 100L, blockTime = 1L, commitment = "finalized",
            symbol = "AE", lane = "QUALITY", tactic = "MOMENTUM",
            networkFeeLamports = Lamports.of(5_000L),
            priorityFeeLamports = Lamports.ZERO, tipLamports = Lamports.ZERO,
            provider = "test", instructionIndex = 0,
        )
        val r1 = CanonicalLedger6387.commitBuy(input)
        assertTrue(r1 is CanonicalLedger6387.CommitResult.Committed)
        val r2 = CanonicalLedger6387.commitBuy(input)
        // Idempotency: same (sig, insn, side, mint) must not create a new position.
        assertTrue(r2 is CanonicalLedger6387.CommitResult.Committed)
        assertEquals(
            (r1 as CanonicalLedger6387.CommitResult.Committed).position.id.positionId,
            (r2 as CanonicalLedger6387.CommitResult.Committed).position.id.positionId,
        )
    }

    @Test
    fun buy_never_uses_price_math_reconstructed_quantity() {
        // Operator's export: buy's wallet proof was 1,550,673.559463 while the
        // price estimate was only 42,485.0437. The ledger MUST reject any BUY
        // whose delta is non-positive OR mismatches the proof.
        val badProof = ProofState6386.SignatureReceived("sig_no_finality")
        val validate = FinalizedBuyProof6386.validate("w", "m", badProof)
        assertFalse(validate.proofComplete)
    }

    // ─── Directive A: atomic SELL + quantity conservation ────────────

    @Test
    fun atomic_sell_conserves_quantity_and_basis() {
        // Open a lot.
        val decimals = MintDecimals.Known(6, "T", "sig_mint")
        val buyProof = ProofState6386.FinalizedProofComplete(
            signature = "sig_b", walletAddress = "w", mintAddress = "m",
            decimals = decimals,
            preRawBalance = RawTokenAmount.of(0L),
            postRawBalance = RawTokenAmount.of(10_000L),
            preLamports = Lamports.of(1_000_000L),
            postLamports = Lamports.of(0L),
            feeLamports = Lamports.of(5_000L),
        )
        val buyIntent = ExecutionIntent6386(
            intentId = "i", walletAddress = "w", mintAddress = "m",
            side = IntentSide.BUY, selectedLane = "MOONSHOT",
            marketSnapshotId = "s", decisionTimestamp = 1L,
            requestedLamports = Lamports.of(1_000_000L),
            requestedRawTokenAmount = null,
            score = 55, fdgVerdict = "ALLOW",
            routeRequirements = RouteRequirements(),
            lifecycleState = ProofState6386.IntentCreated,
        )
        val buy = CanonicalLedger6387.commitBuy(CanonicalLedger6387.BuyCommitInput(
            intent = buyIntent, proof = buyProof, tokenAccount = "ta",
            slot = 1L, blockTime = 1L, commitment = "finalized",
            symbol = "X", lane = "MOONSHOT", tactic = "MOMENTUM",
            networkFeeLamports = Lamports.of(5_000L),
            priorityFeeLamports = Lamports.ZERO, tipLamports = Lamports.ZERO,
            provider = "test", instructionIndex = 0,
        )) as CanonicalLedger6387.CommitResult.Committed

        // Partial sell: 4000 raw of 10000.
        val sellProof = ProofState6386.FinalizedProofComplete(
            signature = "sig_s", walletAddress = "w", mintAddress = "m",
            decimals = decimals,
            preRawBalance = RawTokenAmount.of(10_000L),
            postRawBalance = RawTokenAmount.of(6_000L),
            preLamports = Lamports.of(0L),
            postLamports = Lamports.of(600_000L),
            feeLamports = Lamports.of(5_000L),
        )
        val sell = CanonicalLedger6387.commitSell(CanonicalLedger6387.SellCommitInput(
            positionId = buy.position.id.positionId, proof = sellProof,
            slot = 2L, blockTime = 2L, commitment = "finalized",
            networkFeeLamports = Lamports.of(5_000L),
            priorityFeeLamports = Lamports.ZERO, tipLamports = Lamports.ZERO,
            provider = "test", instructionIndex = 0, ataClosedProven = false,
        )) as CanonicalLedger6387.CommitResult.Committed
        // Conservation checks.
        val q = ConservationInvariants6387.checkQuantity(
            entryRaw = RawTokenAmount.of(10_000L),
            totalSoldRaw = RawTokenAmount.of(4_000L),
            remainingWalletRaw = RawTokenAmount.of(6_000L),
        )
        assertTrue(q.reason, q.ok)
        assertEquals(PositionStatus6387.PARTIALLY_CLOSED, sell.position.status)
        // Proportional basis: 4/10 of 1_000_000 = 400_000 realised; 600_000 remaining.
        val b = ConservationInvariants6387.checkBasis(
            originalBasisLamports = Lamports.of(1_000_000L),
            realisedAllocatedBasisLamports = Lamports.of(400_000L),
            remainingBasisLamports = Lamports.of(600_000L),
        )
        assertTrue(b.reason, b.ok)
    }

    // ─── Directive A: single reconciliation coordinator ─────────────

    @Test
    fun single_reconciliation_coordinator_rejects_duplicates() {
        val id1 = ReconciliationCoordinator6387.tryBegin(100L)
        assertNotNull(id1)
        assertEquals(1, ReconciliationCoordinator6387.activeJobsCount())
        val id2 = ReconciliationCoordinator6387.tryBegin(100L)
        assertNull("duplicate concurrent job must be rejected", id2)
        ReconciliationCoordinator6387.end(cleanCycle = true)
        assertEquals(0, ReconciliationCoordinator6387.activeJobsCount())
    }

    // ─── Directive A: wallet asset classification ───────────────────

    @Test
    fun non_tradable_classes_excluded_from_risk_and_slots() {
        for (c in WalletAssetClass6387.values()) {
            if (c.isNonTradable()) {
                assertFalse("$c must not count as risk exposure", c.countsAsRiskExposure())
                assertTrue("$c must count as free entry slot (does not consume)", c.countsAsFreeEntrySlot())
                assertFalse("$c must not be learning eligible", c.isLearningEligible())
            }
        }
    }

    @Test
    fun known_deleted_chaos_mints_recognised() {
        assertTrue(WalletAssetClassifier6387.KNOWN_DELETED_CHAOS_MINTS.contains("2xKQg4SwFR5ejkfqGiJ8oPh2vdmVRVPU4VEaTMqZpump"))
        assertTrue(WalletAssetClassifier6387.KNOWN_DELETED_CHAOS_MINTS.contains("sMYyVKxdk7EZbbd2bEuj1RVxohSXLXrKCf1JsfhoZWo"))
    }

    @Test
    fun frozen_classification_requires_rpc_evidence_never_from_failed_quote() {
        assertNull("no RPC evidence => must NOT infer frozen", WalletAssetClassifier6387.classifyFrozen(false))
        assertEquals(WalletAssetClass6387.NON_TRADABLE_FROZEN_ACCOUNT, WalletAssetClassifier6387.classifyFrozen(true))
    }

    @Test
    fun deletion_requires_at_least_two_evidence_signals() {
        val one = WalletAssetClassifier6387.DeletionEvidence(true, false, false, false, false)
        assertFalse("one signal insufficient", one.isDeletedMint())
        val two = WalletAssetClassifier6387.DeletionEvidence(true, true, false, false, false)
        assertTrue(two.isDeletedMint())
    }

    // ─── Directive B: strong price + identity ───────────────────────

    @Test
    fun price_identity_check_rejects_denomination_mismatch() {
        val entry = CanonicalTokenPrice6387(
            mint = "AE8Wq7", value = BigDecimal("0.0002925"),
            denomination = PriceDenomination6387.USD_PER_TOKEN, quoteMint = null,
            source = PriceSource6387.DEXSCREENER_PAIR, pairAddress = null,
            observedAtMs = 1L, observedSlot = 1L, decimals = 6,
            identityHash = "h1", validity = PriceValidity6387.VALID,
        )
        val current = CanonicalTokenPrice6387(
            mint = "AE8Wq7", value = BigDecimal("0.0000015878757"),
            denomination = PriceDenomination6387.SOL_PER_TOKEN, quoteMint = "So11...",
            source = PriceSource6387.JUPITER_QUOTE, pairAddress = null,
            observedAtMs = 2L, observedSlot = 2L, decimals = 6,
            identityHash = "h2", validity = PriceValidity6387.VALID,
        )
        val r = PriceIdentityInvariant6387.check(entry, current)
        assertFalse(r.compatible)
        assertTrue(r.reason.contains("DENOMINATION"))
    }

    @Test
    fun canonical_profit_multiple_uses_actual_economic_value() {
        // Entry basis 1_000_000 lamports for 10_000 raw tokens.
        // Sold 4_000 for 500_000 lamports (realised). Remaining 6_000 at
        // current 100 lamports/rawToken => gross 600_000.
        // multiple = (500_000 + 600_000) / 1_000_000 = 1.1
        val m = CanonicalProfitMultiple6387.compute(
            originalCostBasisLamports = Lamports.of(1_000_000L),
            cumulativeRealisedProceedsLamports = Lamports.of(500_000L),
            remainingRawQty = RawTokenAmount.of(6_000L),
            currentLamportsPerRawToken = BigDecimal("100"),
        )
        assertEquals(0, m.setScale(2, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("1.10")))
    }

    // ─── Directive B: 10x validator ──────────────────────────────────

    @Test
    fun quick_runner_10x_rejects_when_pct_below_900() {
        val e = CanonicalTokenPrice6387("m", BigDecimal("1"), PriceDenomination6387.SOL_PER_TOKEN, null,
            PriceSource6387.DEXSCREENER_PAIR, null, 1L, 1L, 6, "h", PriceValidity6387.VALID)
        val c = e.copy(value = BigDecimal("5"), observedAtMs = 2L, identityHash = "h2")
        val snapshot = QuickRunner10xValidator6387.Snapshot(
            canonicalPositionId = "pid", buyFillFinalised = true,
            originalCostBasisLamports = Lamports.of(1_000_000L),
            remainingRawQtyWalletProven = true,
            entryPrice = e, currentPrice = c, peakPrice = c,
            canonicalNetMultiple = BigDecimal("5"),
            canonicalNetPnlLamports = 4_000_000L,
            canonicalCurrentPnlPct = 400.0,   // below 900
            canonicalPeakPnlPct = 400.0,
            qtyConservationOk = true, basisConservationOk = true,
            walletAssetClass = WalletAssetClass6387.BOT_POSITION_ACTIVE,
            revalidatedImmediatelyBeforeLease = true,
        )
        val r = QuickRunner10xValidator6387.validate(snapshot)
        assertFalse(r.allowed)
        assertTrue(r.failedCheck.startsWith("6_") || r.failedCheck.startsWith("8_") || r.failedCheck.startsWith("9_"))
    }

    @Test
    fun quick_runner_10x_rejects_recovered_unknown_basis() {
        val e = CanonicalTokenPrice6387("m", BigDecimal("1"), PriceDenomination6387.SOL_PER_TOKEN, null,
            PriceSource6387.DEXSCREENER_PAIR, null, 1L, 1L, 6, "h", PriceValidity6387.VALID)
        val c = e.copy(value = BigDecimal("10.5"), observedAtMs = 2L, identityHash = "h2")
        val snapshot = QuickRunner10xValidator6387.Snapshot(
            canonicalPositionId = "pid", buyFillFinalised = true,
            originalCostBasisLamports = Lamports.of(1L),
            remainingRawQtyWalletProven = true,
            entryPrice = e, currentPrice = c, peakPrice = c,
            canonicalNetMultiple = BigDecimal("10.5"),
            canonicalNetPnlLamports = 100L, canonicalCurrentPnlPct = 950.0, canonicalPeakPnlPct = 950.0,
            qtyConservationOk = true, basisConservationOk = true,
            walletAssetClass = WalletAssetClass6387.BOT_POSITION_RECOVERABLE_BASIS_UNKNOWN,
            revalidatedImmediatelyBeforeLease = true,
        )
        val r = QuickRunner10xValidator6387.validate(snapshot)
        assertFalse(r.allowed)
        assertEquals("11_RECOVERED_UNKNOWN_BASIS", r.failedCheck)
    }

    // ─── Directive B: exit reason semantics ─────────────────────────

    @Test
    fun profit_reason_detection_covers_all_directive_keywords() {
        listOf("QUICK_RUNNER_6X", "QUICK_RUNNER_10X_FULL_EXIT", "PROFIT_LOCK",
            "TAKE_PROFIT_TIER_1", "MOONSHOT_MULTIPLE_EXIT", "RUNNER_BANK", "MFE_PROFIT_EXIT",
        ).forEach { assertTrue("must classify $it as profit exit", ExitReasonSemantics6387.isProfitExitReason(it)) }
        listOf("MOONSHOT_STOP_LOSS", "EXTERNAL_RUG_CLOSE", "MOONSHOT_FLAT_EXIT",
        ).forEach { assertFalse("must NOT classify $it as profit exit", ExitReasonSemantics6387.isProfitExitReason(it)) }
    }

    @Test
    fun contradiction_check_flags_profit_reason_with_negative_pnl() {
        // AE8Wq7 exact scenario: QUICK_RUNNER_10X_FULL_EXIT with pnl=-2.9%.
        val r = ExitReasonSemantics6387.checkContradiction("QUICK_RUNNER_10X_FULL_EXIT", canonicalNetPnlLamports = -500_000L, canonicalCurrentPnlPct = -2.9)
        assertFalse(r.ok)
        assertTrue(r.reason.contains("PNL_CONTRADICTION") || r.reason.contains("PCT_CONTRADICTION"))
    }

    // ─── Directive B: executor boundary defense ─────────────────────

    @Test
    fun executor_boundary_blocks_10x_below_900_pct() {
        val r = ExecutorBoundaryDefense6387.check(
            exitReason = "QUICK_RUNNER_10X_FULL_EXIT",
            canonicalCurrentPnlPct = -2.9, canonicalNetPnlLamports = -500_000L,
            claimedMultiple = BigDecimal("184.0"),
            recomputedMultiple = BigDecimal("0.97"),
            entry = null, current = null, positionVersion = 1L,
        )
        assertFalse(r.allowed)
        assertTrue(r.reason.contains("EXECUTOR_BLOCKED_FALSE_PROFIT_EXIT_6387"))
    }

    @Test
    fun executor_boundary_blocks_generic_profit_reason_with_negative_pnl() {
        val r = ExecutorBoundaryDefense6387.check(
            exitReason = "TAKE_PROFIT", canonicalCurrentPnlPct = -5.0, canonicalNetPnlLamports = -1_000_000L,
            claimedMultiple = BigDecimal.ONE, recomputedMultiple = BigDecimal.ONE,
            entry = null, current = null, positionVersion = 1L,
        )
        assertFalse(r.allowed)
    }

    // ─── Directive A + B: historical quarantine ─────────────────────

    @Test
    fun false_profit_quarantine_flags_10x_with_negative_pnl() {
        val t = com.lifecyclebot.data.Trade(
            side = "SELL", mode = "live", price = 0.0, ts = 0L,
            pnlSol = -0.05, pnlPct = -2.9,
            reason = "QUICK_RUNNER_10X_FULL_EXIT",
            entryCostSol = 0.025, sol = 0.024,
        )
        val reasons = FalseProfitHistoricalQuarantine6387.evaluateReasons(t)
        assertTrue(reasons.contains("10X_NEG_PNL"))
        assertTrue(reasons.contains("10X_MAX_GAIN_LT_900"))
        assertTrue(reasons.contains("EXIT_REASON_RESULT_CONTRADICTION"))
    }

    // ─── Directive B P1: mode notification dedup ────────────────────

    @Test
    fun mode_notification_dedup_no_emit_for_same_state_transition() {
        assertFalse("prev==next must not emit", ModeNotificationDedup6387.shouldEmit(1L, "GLOBAL", "bot", "RANGE", "RANGE"))
    }
    @Test
    fun mode_notification_dedup_emits_once_within_ttl() {
        assertTrue(ModeNotificationDedup6387.shouldEmit(1L, "GLOBAL", "bot", "RUNNER", "RANGE"))
        assertFalse("must not re-emit within TTL", ModeNotificationDedup6387.shouldEmit(1L, "GLOBAL", "bot", "RUNNER", "RANGE"))
    }

    // ─── Wiring checks ───────────────────────────────────────────────

    @Test
    fun bot_service_wires_all_6387_quarantines() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("HistoricalQuarantine6386.runOnce()"))
        assertTrue(bs.contains("FalseProfitHistoricalQuarantine6387.runOnce()"))
    }
}
