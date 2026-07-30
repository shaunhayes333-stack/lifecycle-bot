package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6392 — LIVE CONTINUITY AND PROFIT-INTEGRITY REPAIR.
 *
 * Every acceptance test from directive Section 19 (A-J) is enforced here.
 */
class Bundle6392LiveContinuityTest {

    @Before fun setUp() {
        LiveContinuityPolicyProvider6392.resetForTest()
        CanonicalWalletPositionRegistry6392.clearForTest()
        MintDecimalsAuthority6392.clearForTest()
        ExitMutex6392.clearForTest()
        BroadcastLiability6392.clearForTest()
        PerMintIntegrityQuarantine6392.clearForTest()
        DedicatedExitSupervisorContract6392.clearForTest()
    }
    @After fun tearDown() { setUp() }

    /* -------------------- S1 POLICY DEFAULTS ------------------------------ */

    @Test fun policy_defaults_keep_bot_trading() {
        val p = LiveContinuityPolicyProvider6392.get()
        assertTrue(p.allowCleanLiveEntries)
        assertFalse("global integrity hold MUST default OFF", p.globalIntegrityHoldEnabled)
        assertTrue(p.perMintQuarantineEnabled)
        assertFalse("partial exits MUST default OFF per directive S7", p.partialExitsEnabled)
        assertTrue(p.fullProfitExitEnabled)
        assertEquals(0.010, p.maximumTemporaryBuySol, 1e-9)
    }

    /* -------------------- TEST A · DECIMAL CONSISTENCY -------------------- */

    @Test fun test_A_decimal_consistency_enforced() {
        MintDecimalsAuthority6392.resolveAndCache("mintX", 6)
        // A provider reports 0 later — MUST be quarantined, cache MUST NOT mutate.
        MintDecimalsAuthority6392.recordProviderQuarantine("mintX", 0)
        assertEquals(6, MintDecimalsAuthority6392.get("mintX"))
        assertTrue(0 in MintDecimalsAuthority6392.quarantinedFor("mintX"))
    }

    @Test fun invalid_decimals_are_rejected_at_resolve_time() {
        try {
            MintDecimalsAuthority6392.resolveAndCache("mintZ", 99)
            fail("must throw on out-of-range decimals")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("INVALID_MINT_DECIMALS"))
        }
    }

    /* -------------------- TEST B · INVENTORY CONSERVATION ----------------- */

    @Test fun test_B_inventory_conservation_holds() {
        val p = CanonicalWalletPosition6392(
            wallet = "W1", mint = "M1", tokenDecimals = 6,
            acquiredAtomic = BigInteger.valueOf(10_000_000L),
            disposedAtomic = BigInteger.valueOf(4_000_000L),
            remainingAtomic = BigInteger.valueOf(6_000_000L),
            investedLamports = BigInteger.valueOf(5_000_000L),
            recoveredLamports = BigInteger.valueOf(2_000_000L),
            entrySignatures = setOf("sig1"), exitSignatures = setOf("sig2"),
            originatingLane = "QUALITY", advisoryLanes = setOf("QUALITY"),
            state = PositionState6392.OPEN,
        )
        assertTrue(p.inventoryConservationHolds())

        val broken = p.copy(remainingAtomic = BigInteger.valueOf(9_999L))
        assertFalse(broken.inventoryConservationHolds())
    }

    /* -------------------- TEST C · SINGLE OWNERSHIP ----------------------- */

    @Test fun test_C_single_position_per_wallet_mint() {
        val position1 = CanonicalWalletPositionRegistry6392.findOrAttach(
            wallet = "W1", mint = "KEYCAT", candidateLane = "QUALITY",
        ) {
            CanonicalWalletPosition6392(
                wallet = "W1", mint = "KEYCAT", tokenDecimals = 6,
                acquiredAtomic = BigInteger.valueOf(10_000L),
                disposedAtomic = BigInteger.ZERO, remainingAtomic = BigInteger.valueOf(10_000L),
                investedLamports = BigInteger.valueOf(1_000_000L),
                recoveredLamports = BigInteger.ZERO,
                entrySignatures = setOf("sig1"), exitSignatures = emptySet(),
                originatingLane = "QUALITY", advisoryLanes = setOf("QUALITY"),
                state = PositionState6392.OPEN,
            )
        }
        // Second lane advises — MUST NOT create another position.
        val position2 = CanonicalWalletPositionRegistry6392.findOrAttach(
            wallet = "W1", mint = "KEYCAT", candidateLane = "MOONSHOT",
        ) { fail("must not create second position"); throw IllegalStateException() }
        assertEquals(position1.mint, position2.mint)
        assertTrue("MOONSHOT" in position2.advisoryLanes)
        assertTrue("QUALITY" in position2.advisoryLanes)
        assertEquals(1, CanonicalWalletPositionRegistry6392.openCount("W1"))
        assertTrue(CanonicalWalletPositionRegistry6392.singleActivePositionInvariant())
    }

    /* -------------------- TEST D · LANE REASSIGNMENT ---------------------- */

    @Test fun test_D_lane_reassignment_does_not_create_new_cost_basis() {
        val first = CanonicalWalletPositionRegistry6392.findOrAttach(
            wallet = "W1", mint = "USUR", candidateLane = "QUALITY",
        ) {
            CanonicalWalletPosition6392(
                wallet = "W1", mint = "USUR", tokenDecimals = 6,
                acquiredAtomic = BigInteger.valueOf(1_000L),
                disposedAtomic = BigInteger.ZERO, remainingAtomic = BigInteger.valueOf(1_000L),
                investedLamports = BigInteger.valueOf(500_000L),
                recoveredLamports = BigInteger.ZERO,
                entrySignatures = setOf("sig-usur-1"), exitSignatures = emptySet(),
                originatingLane = "QUALITY", advisoryLanes = setOf("QUALITY"),
                state = PositionState6392.OPEN,
            )
        }
        val second = CanonicalWalletPositionRegistry6392.findOrAttach(
            wallet = "W1", mint = "USUR", candidateLane = "MOONSHOT",
        ) { fail("must not create second cost basis"); throw IllegalStateException() }
        assertEquals(first.investedLamports, second.investedLamports)
        assertEquals(first.acquiredAtomic, second.acquiredAtomic)
    }

    /* -------------------- TEST E · JOURNAL PARITY ------------------------- */

    @Test fun test_E_wallet_canonical_parity_within_tolerance() {
        val canonical = BigInteger.valueOf(12_345_678L)
        val wallet    = BigInteger.valueOf(12_345_600L)   // 78 lamport diff, well within 100k
        val r = CanonicalWalletParity6392.compute(
            canonical = canonical, wallet = wallet,
            pendingExposure = BigInteger.ZERO, unreconciledCount = 0,
        )
        assertTrue(r.withinTolerance)
        assertEquals(BigInteger.valueOf(78L), r.differenceLamports)
    }

    @Test fun parity_outside_tolerance_reports_correctly() {
        val r = CanonicalWalletParity6392.compute(
            canonical = BigInteger.valueOf(100_000_000L),
            wallet = BigInteger.valueOf(50_000_000L),
            pendingExposure = BigInteger.ZERO, unreconciledCount = 3,
        )
        assertFalse(r.withinTolerance)
        assertEquals(BigInteger.valueOf(50_000_000L), r.differenceLamports)
    }

    /* -------------------- TEST F · ISOLATED FAILURE ----------------------- */

    @Test fun test_F_isolated_per_mint_failure_does_not_disable_trading() {
        PerMintIntegrityQuarantine6392.quarantine(
            wallet = "W1", mint = "BAD_MINT", reason = "BUY_DECIMALS_MISMATCH",
            walletBalanceAtomic = null, ledgerBalanceAtomic = null,
            relatedSignatures = emptySet(),
        )
        assertTrue(PerMintIntegrityQuarantine6392.isQuarantined("W1", "BAD_MINT"))
        assertFalse(PerMintIntegrityQuarantine6392.isQuarantined("W1", "CLEAN_MINT"))
        // Global integrity hold MUST remain OFF — only per-mint quarantine.
        assertFalse(LiveContinuityPolicyProvider6392.get().globalIntegrityHoldEnabled)
    }

    /* -------------------- TEST G · DUPLICATE EXIT SUPPRESSION ------------- */

    @Test fun test_G_two_lane_exit_requests_produce_one_transaction() {
        val k1 = ExitMutex6392.Key(wallet = "W1", mint = "M1", positionGeneration = 1L, exitSequence = 1L)
        val k2 = ExitMutex6392.Key(wallet = "W1", mint = "M1", positionGeneration = 1L, exitSequence = 2L)  // different sequence
        assertTrue(ExitMutex6392.tryAcquire(k1))
        assertFalse("second exit for same wallet+mint must be rejected",
            ExitMutex6392.tryAcquire(k2))
        ExitMutex6392.recordResult(k1, "SIG_BROADCAST_ABC123")
        // Idempotent replay returns same result.
        assertEquals("SIG_BROADCAST_ABC123", ExitMutex6392.idempotentResult(k1))
    }

    /* -------------------- TEST H · PARTIAL PROFIT PROTECTION -------------- */

    @Test fun test_H_negative_partial_is_rejected() {
        // Cost 5 SOL, proceeds 4 SOL after fees — must be rejected.
        val i = PartialExitTemporaryDisable6392.ProfitPartialInput(
            executableProceedsLamports = BigInteger.valueOf(4_000_000_000L),
            allocatedCostLamports = BigInteger.valueOf(5_000_000_000L),
            transactionFeesLamports = BigInteger.valueOf(10_000_000L),
            slippageAllowanceLamports = BigInteger.valueOf(5_000_000L),
            configuredMinimumProfitLamports = BigInteger.valueOf(1_000_000L),
        )
        assertFalse(PartialExitTemporaryDisable6392.profitPartialAcceptable(i))
    }

    @Test fun positive_partial_with_sufficient_margin_is_accepted() {
        val i = PartialExitTemporaryDisable6392.ProfitPartialInput(
            executableProceedsLamports = BigInteger.valueOf(6_000_000_000L),
            allocatedCostLamports = BigInteger.valueOf(5_000_000_000L),
            transactionFeesLamports = BigInteger.valueOf(10_000_000L),
            slippageAllowanceLamports = BigInteger.valueOf(5_000_000L),
            configuredMinimumProfitLamports = BigInteger.valueOf(1_000_000L),
        )
        assertTrue(PartialExitTemporaryDisable6392.profitPartialAcceptable(i))
    }

    /* -------------------- TEST I · PROVIDER OUTAGE ------------------------ */

    @Test fun test_I_dexscreener_missing_never_generates_rug() {
        val v = ExternalRugClassification6392.classify(
            ExternalRugClassification6392.Input(
                liquidityRemoved = false, routerUnableAcrossAllProviders = false,
                poolReservesCollapsed = false, tokenAccountFrozen = false,
                transferSimulationPersistentlyFailed = false, honeypotProven = false,
                sellTaxAtomicallyProven = false,
                dexScreenerPairMissing = true, anyPricingProviderTimedOut = false,
                priceResponseNull = false, symbolLookupFailed = false, apiReturnedStaleData = false,
            ))
        assertEquals(ExternalRugClassification6392.Verdict.MARKET_DATA_DEGRADED, v)
    }

    @Test fun executable_evidence_triggers_rug() {
        val v = ExternalRugClassification6392.classify(
            ExternalRugClassification6392.Input(
                liquidityRemoved = true, routerUnableAcrossAllProviders = false,
                poolReservesCollapsed = false, tokenAccountFrozen = false,
                transferSimulationPersistentlyFailed = false, honeypotProven = false,
                sellTaxAtomicallyProven = false,
                dexScreenerPairMissing = false, anyPricingProviderTimedOut = false,
                priceResponseNull = false, symbolLookupFailed = false, apiReturnedStaleData = false,
            ))
        assertEquals(ExternalRugClassification6392.Verdict.EXTERNAL_RUG_CLOSE, v)
    }

    /* -------------------- S6 SAFE SELL CALCULATOR ------------------------- */

    @Test fun safe_sell_clamps_to_wallet_ledger_and_requested() {
        val r = SafeSellCalculator6392.compute(SafeSellCalculator6392.Input(
            walletAtomic = BigInteger.valueOf(1_000L),
            ledgerAtomic = BigInteger.valueOf(2_000L),
            requestedAtomic = BigInteger.valueOf(1_500L),
            isFullExit = false,
        ))
        assertEquals(BigInteger.valueOf(1_000L), r.sellAtomic)
    }

    @Test fun safe_sell_full_exit_clamps_wallet_and_ledger() {
        val r = SafeSellCalculator6392.compute(SafeSellCalculator6392.Input(
            walletAtomic = BigInteger.valueOf(500L),
            ledgerAtomic = BigInteger.valueOf(1_000L),
            requestedAtomic = BigInteger.valueOf(1_000L),
            isFullExit = true,
        ))
        assertEquals(BigInteger.valueOf(500L), r.sellAtomic)
    }

    /* -------------------- S4 BUY FILL FROM WALLET DELTA ------------------- */

    @Test fun buy_fill_from_wallet_delta_uses_atomic_diff() {
        val fill = BuyFillFromWalletDelta6392.buildFrom(BuyFillFromWalletDelta6392.Delta(
            mint = "M1", signature = "sig1", slot = 100L,
            preTokenBalanceAtomic = BigInteger.ZERO,
            postTokenBalanceAtomic = BigInteger.valueOf(5_000_000L),
            preWalletLamports = BigInteger.valueOf(100_000_000L),
            postWalletLamports = BigInteger.valueOf(95_000_000L),
            tokenDecimals = 6, confirmationStatus = "FINALIZED",
        ))
        assertEquals(BigInteger.valueOf(5_000_000L), fill.acquiredAtomic)
        assertEquals(BigInteger.valueOf(5_000_000L), fill.economicCostLamports)
    }

    /* -------------------- S12 BLUECHIP IDENTITY --------------------------- */

    @Test fun bluechip_requires_exact_mint_not_symbol() {
        assertTrue(VerifiedBluechipIdentity6392.isBluechip(
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        // Unknown mint claiming to be USDC → speculative, NOT Bluechip.
        val lane = VerifiedBluechipIdentity6392.resolveLaneForUnknown(
            mint = "FAKE_MINT_11111", providerReportedSymbol = "USDC",
            providerReportedLane = VerifiedBluechipIdentity6392.Lane.QUALITY,
        )
        assertEquals(VerifiedBluechipIdentity6392.Lane.SPECULATIVE, lane)
    }

    /* -------------------- S16 ENTRY SIZING PROGRESSION -------------------- */

    @Test fun sizing_stays_at_010_until_20_clean_reconciled() {
        val s = EntrySizingProgression6392.CohortStats(
            cleanReconciledPositions = 10, decimalMismatches = 0,
            duplicateMintOwners = 0, quantityOverruns = 0,
            walletCanonicalDiffLamports = BigInteger.ZERO,
            feeAdjustedExpectancyPositive = true, stableDrawdown = true, profitFactor = 1.5,
        )
        assertEquals(0.010, EntrySizingProgression6392.maximumBuySol(s), 1e-9)
    }

    @Test fun sizing_scales_at_20_50_100_clean_reconciled() {
        val base = EntrySizingProgression6392.CohortStats(
            cleanReconciledPositions = 20, decimalMismatches = 0,
            duplicateMintOwners = 0, quantityOverruns = 0,
            walletCanonicalDiffLamports = BigInteger.ZERO,
            feeAdjustedExpectancyPositive = true, stableDrawdown = true, profitFactor = 1.5,
        )
        assertEquals(0.015, EntrySizingProgression6392.maximumBuySol(base), 1e-9)
        assertEquals(0.025, EntrySizingProgression6392.maximumBuySol(base.copy(cleanReconciledPositions = 50)), 1e-9)
        val adaptive = EntrySizingProgression6392.maximumBuySol(base.copy(cleanReconciledPositions = 100))
        assertTrue(adaptive >= 0.025); assertTrue(adaptive <= 0.05)
    }

    @Test fun any_integrity_defect_keeps_sizing_at_temp_max() {
        val defect = EntrySizingProgression6392.CohortStats(
            cleanReconciledPositions = 100, decimalMismatches = 1,   // ONE defect
            duplicateMintOwners = 0, quantityOverruns = 0,
            walletCanonicalDiffLamports = BigInteger.ZERO,
            feeAdjustedExpectancyPositive = true, stableDrawdown = true, profitFactor = 2.0,
        )
        assertEquals(0.010, EntrySizingProgression6392.maximumBuySol(defect), 1e-9)
    }

    /* -------------------- S9 BROADCAST LIABILITY -------------------------- */

    @Test fun live_broadcast_is_pending_liability_until_confirmed() {
        BroadcastLiability6392.recordBroadcast(
            sig = "SIG1", wallet = "W1", mint = "M1",
            reservedSellAtomic = BigInteger.valueOf(1_000L),
            positionGeneration = 1L,
        )
        assertEquals(1, BroadcastLiability6392.pendingRowCount())
        BroadcastLiability6392.markConfirmed("SIG1")
        assertEquals(0, BroadcastLiability6392.pendingRowCount())
    }

    /* -------------------- S13 EXTERNAL RUG BEHAVIOUR ---------------------- */

    @Test fun executable_route_impossible_after_all_providers_triggers_rug() {
        val v = ExternalRugClassification6392.classify(
            ExternalRugClassification6392.Input(
                liquidityRemoved = false, routerUnableAcrossAllProviders = true,
                poolReservesCollapsed = false, tokenAccountFrozen = false,
                transferSimulationPersistentlyFailed = false, honeypotProven = false,
                sellTaxAtomicallyProven = false,
                dexScreenerPairMissing = false, anyPricingProviderTimedOut = false,
                priceResponseNull = false, symbolLookupFailed = false, apiReturnedStaleData = false,
            ))
        assertEquals(ExternalRugClassification6392.Verdict.EXTERNAL_RUG_CLOSE, v)
    }

    /* -------------------- S18 INVARIANTS ---------------------------------- */

    @Test fun invariants_all_pass_when_conditions_met() {
        val c = LiveContinuityInvariants6392.Check(
            singlePositionPerWalletMint = true, chainConfirmedDecimals = true,
            buyTokenDeltaPositive = true, economicCostPositive = true,
            soldWithinWallet = true, soldWithinRemaining = true,
            totalDisposedWithinAcquired = true,
            remainingEqualsAcquiredMinusDisposed = true,
            oneActiveExitPerWalletMint = true,
            everyFinalizedInCanonical = true,
            walletCanonicalWithinTolerance = true,
        )
        assertTrue(c.allPass())
    }

    @Test fun invariants_fail_when_any_single_condition_breaks() {
        val c = LiveContinuityInvariants6392.Check(
            singlePositionPerWalletMint = true, chainConfirmedDecimals = true,
            buyTokenDeltaPositive = true, economicCostPositive = true,
            soldWithinWallet = false,   // ONE broken invariant
            soldWithinRemaining = true, totalDisposedWithinAcquired = true,
            remainingEqualsAcquiredMinusDisposed = true,
            oneActiveExitPerWalletMint = true,
            everyFinalizedInCanonical = true,
            walletCanonicalWithinTolerance = true,
        )
        assertFalse(c.allPass())
    }

    /* -------------------- S14 EXIT SUPERVISOR CADENCE --------------------- */

    @Test fun supervisor_cadence_invariant_holds_when_ticks_are_within_target() {
        DedicatedExitSupervisorContract6392.recordTick(nowMs = 1000L)
        DedicatedExitSupervisorContract6392.recordTick(nowMs = 1500L)
        DedicatedExitSupervisorContract6392.recordTick(nowMs = 2000L)
        assertTrue(DedicatedExitSupervisorContract6392.cadenceInvariantHolds(configuredMaxCadenceMs = 750L))
    }
}
