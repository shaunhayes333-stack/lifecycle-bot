package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6389 — CANONICAL SETTLEMENT, COHORT AND ANR HARDENING (Sections 1-10).
 *
 * Every named invariant from the directive is validated below. If any test
 * fails, CI blocks the ship. No cherry-picking allowed.
 */
class Bundle6389CanonicalSettlementTest {

    @Before fun setUp() {
        CanonicalCloseFinality6389.clearAllForTest()
        JournalCohort6389.resetForTest()
        CanonicalUnitTypes6389.clearForTest()
        PreExecPolicyRedirectTaxonomy6389.clearForTest()
        MainThreadHardening6389.clearForTest()
        SellOnlyForensicHold6389.setForTest("SETUP_DEFAULT")
    }
    @After fun tearDown() { setUp() }

    /* -------------------- S1 CANONICAL CLOSE FINALITY --------------------- */

    @Test fun forbidden_sources_never_canonicalise() {
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("LIVE_BROADCAST"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("SELL_START"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("RISK_EXIT_SIGNAL"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("TAKE_PROFIT_SIGNAL"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("PENDING_VERIFICATION"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("ADVISOR_OUTCOME"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("ESTIMATED_PROCEEDS"))
        assertTrue(CanonicalCloseFinality6389.isForbiddenSource("REASON_ONLY"))
        assertFalse(CanonicalCloseFinality6389.isForbiddenSource("SELL_TX_PARSE_OK"))
    }

    private fun rec(sig: String, pnlLamports: BigInteger,
                    src: CanonicalCloseFinality6389.Source = CanonicalCloseFinality6389.Source.SELL_TX_PARSE_OK,
                    classification: JournalCohort6389.Classification = JournalCohort6389.Classification.FRESH_COHORT,
                    quarantined: Boolean = false): CanonicalCloseRecord6389 = CanonicalCloseRecord6389(
        key = CanonicalCloseFinality6389.CloseKey(1L, "pos-$sig", "buysig-$sig", sig),
        source = src, mint = "mint", symbol = "SYM", cohortRunId = "cohort-1",
        classification = classification,
        tokenPurchasedRaw = BigInteger.valueOf(1_000_000L),
        tokenConsumedRaw = BigInteger.valueOf(1_000_000L),
        tokenRemainingRaw = BigInteger.ZERO, tokenDecimals = 6,
        buyLamportsSpent = BigInteger.valueOf(5_000_000L),
        sellLamportsReceived = BigInteger.valueOf(5_500_000L),
        allocatedCostLamports = BigInteger.valueOf(5_000_000L),
        feeLamports = BigInteger.valueOf(200_000L),
        realisedPnlLamports = pnlLamports,
        isFullClose = true, quarantined = quarantined, quarantineReasons = emptyList(),
        closedAtMs = System.currentTimeMillis(),
    )

    @Test fun unique_close_key_prevents_duplicate_inserts() {
        val r1 = rec("SIG_A", BigInteger.valueOf(500_000L))
        val (_, created1) = CanonicalCloseFinality6389.registerOrUpdate(r1)
        assertTrue(created1)
        val (_, created2) = CanonicalCloseFinality6389.registerOrUpdate(r1)
        assertFalse("replayed callback must update, not create", created2)
        assertEquals(1, CanonicalCloseFinality6389.size())
    }

    /* -------------------- S2 AUTHORITATIVE PNL ---------------------------- */

    @Test fun full_close_allocates_full_remaining_basis() {
        val i = AuthoritativePnl6389.AllocationInput(
            remainingCanonicalCostBasisLamports = BigInteger.valueOf(18_097_558L),
            positionTokenRawBeforeSell = BigInteger.valueOf(70_359_837_452L),
            tokenConsumedRaw = BigInteger.valueOf(35_000_000_000L),  // smaller than position
            sellLamportsReceived = BigInteger.valueOf(17_527_827L),
            isFullClose = true,   // WALLET-ZERO: allocate ALL remaining basis
        )
        val r = AuthoritativePnl6389.allocate(i)
        assertEquals(BigInteger.valueOf(18_097_558L), r.allocatedCostLamports)
        assertEquals(BigInteger.valueOf(-569_731L), r.realisedPnlLamports)
    }

    @Test fun partial_close_prorates_by_actual_consumed_raw() {
        val i = AuthoritativePnl6389.AllocationInput(
            remainingCanonicalCostBasisLamports = BigInteger.valueOf(10_000_000L),
            positionTokenRawBeforeSell = BigInteger.valueOf(1_000_000L),
            tokenConsumedRaw = BigInteger.valueOf(250_000L),
            sellLamportsReceived = BigInteger.valueOf(3_000_000L),
            isFullClose = false,
        )
        val r = AuthoritativePnl6389.allocate(i)
        // 10_000_000 * 250_000 / 1_000_000 = 2_500_000
        assertEquals(BigInteger.valueOf(2_500_000L), r.allocatedCostLamports)
        assertEquals(BigInteger.valueOf(500_000L), r.realisedPnlLamports)
    }

    /* -------------------- S3 HARD SETTLEMENT INVARIANTS ------------------- */

    private fun invariantInput(
        pnl: BigInteger, sell: BigInteger, consumed: BigInteger, walletBefore: BigInteger,
        remaining: BigInteger, fullBasis: BigInteger, allocated: BigInteger,
        journal: BigInteger, ledger: BigInteger, sig: String = "sig-x",
        proofSource: String = "SELL_TX_PARSE_OK",
        proceedsInPnl: Boolean = false, qtyInSol: Boolean = false, leveraged: Boolean = false,
    ) = HardSettlementInvariants6389.Input(
        realisedPnlLamports = pnl, sellLamportsReceived = sell, tokenConsumedRaw = consumed,
        walletRawBeforeSell = walletBefore, remainingRaw = remaining,
        fullRemainingCostBasisLamports = fullBasis, allocatedCostLamports = allocated,
        journalPnlLamports = journal, settlementLedgerPnlLamports = ledger,
        sellSignature = sig, proofSource = proofSource,
        proceedsCopiedIntoPnlField = proceedsInPnl, tokenQuantityInSolField = qtyInSol,
        leveraged = leveraged,
    )

    @Test fun invariant_pnl_greater_than_proceeds_is_quarantined() {
        val i = invariantInput(BigInteger.valueOf(2_000_000L), BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("PNL_EXCEEDS_PROCEEDS") })
    }

    @Test fun invariant_consumed_exceeds_wallet_is_quarantined() {
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.valueOf(10L), BigInteger.valueOf(5L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.ZERO, BigInteger.ZERO)
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("CONSUMED_EXCEEDS_WALLET_BEFORE_SELL") })
    }

    @Test fun invariant_wallet_zero_incomplete_basis_is_quarantined() {
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.valueOf(1_000L),
            BigInteger.ZERO, BigInteger.valueOf(10_000_000L), BigInteger.valueOf(5_000_000L),
            BigInteger.ZERO, BigInteger.ZERO)
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("WALLET_ZERO_INCOMPLETE_BASIS_ALLOC") })
    }

    @Test fun invariant_journal_vs_ledger_mismatch_is_quarantined() {
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.valueOf(100L), BigInteger.valueOf(200L))
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("JOURNAL_VS_LEDGER_MISMATCH") })
    }

    @Test fun invariant_broadcast_source_is_quarantined() {
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.ZERO, BigInteger.ZERO, proofSource = "LIVE_BROADCAST")
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("BROADCAST_OR_NON_FINAL_SOURCE") })
    }

    @Test fun invariant_proceeds_in_pnl_field_is_quarantined() {
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.ZERO, BigInteger.ZERO, proceedsInPnl = true)
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("PROCEEDS_IN_PNL_FIELD") })
    }

    @Test fun invariant_token_qty_in_sol_field_is_quarantined() {
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.ZERO, BigInteger.ZERO, qtyInSol = true)
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("TOKEN_QTY_IN_SOL_FIELD") })
    }

    @Test fun invariant_duplicate_sell_signature_is_quarantined() {
        // Register two records with the SAME sell signature.
        val a = rec("DUP_SIG", BigInteger.valueOf(1L))
        val b = a.copy(key = a.key.copy(positionId = "pos-DUP_SIG_2"))
        CanonicalCloseFinality6389.registerOrUpdate(a)
        CanonicalCloseFinality6389.registerOrUpdate(b)
        val i = invariantInput(BigInteger.ZERO, BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.ZERO, BigInteger.ZERO, sig = "DUP_SIG")
        val v = HardSettlementInvariants6389.check(i)
        assertTrue(v.quarantine)
        assertTrue(v.reasons.any { it.contains("DUPLICATE_SELL_SIGNATURE") })
    }

    /* -------------------- S4 KNOWN-MINT REPAIR ---------------------------- */

    @Test fun known_mint_repair_belka() {
        assertTrue(KnownMintHistoricalRepair6389.shouldInvalidate("BELKA", 0.091))
        assertTrue(KnownMintHistoricalRepair6389.shouldInvalidate("BELKA", -0.001))
        assertTrue(KnownMintHistoricalRepair6389.shouldInvalidate("BELKA", -0.000294))
        assertFalse("real canonical PnL must NOT be invalidated",
            KnownMintHistoricalRepair6389.shouldInvalidate("BELKA", -0.000569731))
        assertEquals(BigInteger.valueOf(-569_731L),
            KnownMintHistoricalRepair6389.expectedPnlLamports("BELKA"))
    }

    @Test fun known_mint_repair_michi_never_records_proceeds_as_profit() {
        assertTrue(KnownMintHistoricalRepair6389.shouldInvalidate("\$MICHI", 0.046))
        assertTrue(KnownMintHistoricalRepair6389.shouldInvalidate("\$MICHI", 0.046479496))
        assertEquals(BigInteger.valueOf(544_000L),
            KnownMintHistoricalRepair6389.expectedPnlLamports("\$MICHI"))
    }

    @Test fun known_mint_repair_cygnets_removes_negative_duplicates() {
        assertTrue(KnownMintHistoricalRepair6389.shouldInvalidate("CYGNETS", -1.0))
    }

    /* -------------------- S5 JOURNAL COHORT ------------------------------- */

    @Test fun cohort_reset_generates_run_id_and_start_ms() {
        val id = JournalCohort6389.beginNewCohort(runtimeGeneration = 7L, nowMs = 1_000_000L)
        assertTrue(id.startsWith("COHORT_7_"))
        assertEquals(1_000_000L, JournalCohort6389.currentStartMs())
    }

    @Test fun positions_opened_before_cohort_start_are_inherited() {
        JournalCohort6389.beginNewCohort(1L, nowMs = 100_000L)
        val c = JournalCohort6389.classify(
            openedAtMs = 50_000L, hasConfirmedCohortBuySignature = false, walletRecovered = false,
        )
        assertEquals(JournalCohort6389.Classification.INHERITED_POSITION, c)
    }

    @Test fun wallet_recovered_without_confirmed_buy_sig_is_recovered_unknown_basis() {
        JournalCohort6389.beginNewCohort(1L, nowMs = 100_000L)
        val c = JournalCohort6389.classify(
            openedAtMs = 200_000L, hasConfirmedCohortBuySignature = false, walletRecovered = true,
        )
        assertEquals(JournalCohort6389.Classification.RECOVERED_UNKNOWN_BASIS, c)
    }

    @Test fun inherited_and_recovered_are_excluded_from_fresh_metrics() {
        assertFalse(JournalCohort6389.eligibleForFreshMetrics(
            JournalCohort6389.Classification.INHERITED_POSITION))
        assertFalse(JournalCohort6389.eligibleForFreshMetrics(
            JournalCohort6389.Classification.RECOVERED_UNKNOWN_BASIS))
        assertTrue(JournalCohort6389.eligibleForFreshMetrics(
            JournalCohort6389.Classification.FRESH_COHORT))
    }

    @Test fun display_snapshot_separates_four_sections() {
        JournalCohort6389.beginNewCohort(1L, nowMs = 100_000L)
        val fresh = rec("fresh-1", BigInteger.valueOf(1000L)).copy(
            classification = JournalCohort6389.Classification.FRESH_COHORT)
        val inh = rec("inh-1", BigInteger.valueOf(-2000L)).copy(
            classification = JournalCohort6389.Classification.INHERITED_POSITION)
        val recovered = rec("rec-1", BigInteger.valueOf(500L)).copy(
            classification = JournalCohort6389.Classification.RECOVERED_UNKNOWN_BASIS)
        val d = JournalCohort6389.buildDisplay(listOf(fresh, inh, recovered))
        assertEquals(1, d.freshCohortCount)
        assertEquals(1, d.inheritedCount)
        assertEquals(1, d.recoveredCount)
        assertEquals(3, d.allTimeCount)
    }

    /* -------------------- S6 CANONICAL UNIT TYPES ------------------------- */

    @Test fun ui_amount_uses_decimals() {
        val raw = BigInteger.valueOf(70_359_837_452L)
        val ui = CanonicalUnitTypes6389.uiAmount(raw, decimals = 6)
        // 70_359_837_452 / 1_000_000 = 70359.837452
        assertEquals(70359.837452, ui, 1e-6)
    }

    @Test fun ui_sol_uses_9_decimals() {
        val ui = CanonicalUnitTypes6389.uiSol(BigInteger.valueOf(17_527_827L))
        assertEquals(0.017527827, ui, 1e-9)
    }

    @Test fun ambiguous_field_names_are_forbidden() {
        assertTrue(CanonicalUnitTypes6389.isAmbiguous("sol"))
        assertTrue(CanonicalUnitTypes6389.isAmbiguous("qty"))
        assertTrue(CanonicalUnitTypes6389.isAmbiguous("cost"))
        assertFalse(CanonicalUnitTypes6389.isAmbiguous("buyLamportsSpent"))
        assertFalse(CanonicalUnitTypes6389.isAmbiguous("tokenConsumedRaw"))
    }

    @Test fun qty_decimal_skew_summary_cannot_show_audit_zero_while_active_quarantine_exists() {
        assertEquals(0L, CanonicalUnitTypes6389.auditCount())
        CanonicalUnitTypes6389.recordSkewQuarantine()
        assertEquals(1L, CanonicalUnitTypes6389.auditCount())
        assertEquals(1L, CanonicalUnitTypes6389.learningExcludedCount())
        CanonicalUnitTypes6389.releaseSkewQuarantine()
        assertEquals(0L, CanonicalUnitTypes6389.auditCount())
    }

    /* -------------------- S7 PRE-EXEC POLICY REDIRECT --------------------- */

    @Test fun pre_exec_policy_redirect_does_not_count_as_execution_failure() {
        PreExecPolicyRedirectTaxonomy6389.record("LANE_CONTRACT_BLUECHIP_PUMPFUN", "MintX")
        assertEquals(1L, PreExecPolicyRedirectTaxonomy6389.count())
        assertTrue("executorAttempts" in PreExecPolicyRedirectTaxonomy6389.excludedFromCounters)
        assertTrue("providerFailureRate" in PreExecPolicyRedirectTaxonomy6389.excludedFromCounters)
        assertTrue("buyFailureRate" in PreExecPolicyRedirectTaxonomy6389.excludedFromCounters)
        assertTrue("transactionReliability" in PreExecPolicyRedirectTaxonomy6389.excludedFromCounters)
    }

    /* -------------------- S8 UNKNOWN BROADCAST POLLING -------------------- */

    @Test fun unknown_broadcast_holds_until_owner_delta_observed() {
        val s = UnknownBroadcastPolling6389.PollState(
            txSignature = "abc", startedAtMs = 0L,
            txSignatureConfirmed = false, txSignatureExpired = false, txSignatureFailed = false,
            ownerTokenDeltaObserved = false, walletSolDeltaObserved = false,
            executionLeaseHeld = true,
        )
        assertEquals(UnknownBroadcastPolling6389.Decision.KEEP_POLLING,
            UnknownBroadcastPolling6389.decide(s, nowMs = 1000L))
    }

    @Test fun unknown_broadcast_treats_owner_delta_as_landed() {
        val s = UnknownBroadcastPolling6389.PollState(
            txSignature = "abc", startedAtMs = 0L,
            txSignatureConfirmed = false, txSignatureExpired = false, txSignatureFailed = false,
            ownerTokenDeltaObserved = true, walletSolDeltaObserved = false,
            executionLeaseHeld = true,
        )
        assertEquals(UnknownBroadcastPolling6389.Decision.TREAT_AS_LANDED,
            UnknownBroadcastPolling6389.decide(s, nowMs = 1000L))
    }

    @Test fun unknown_broadcast_falls_back_only_after_failure_or_expiry() {
        val expired = UnknownBroadcastPolling6389.PollState(
            txSignature = "abc", startedAtMs = 0L,
            txSignatureConfirmed = false, txSignatureExpired = true, txSignatureFailed = false,
            ownerTokenDeltaObserved = false, walletSolDeltaObserved = false,
            executionLeaseHeld = true,
        )
        assertEquals(UnknownBroadcastPolling6389.Decision.FALL_BACK_TO_JUPITER,
            UnknownBroadcastPolling6389.decide(expired, nowMs = 100L))
        val timedOut = expired.copy(txSignatureExpired = false)
        assertEquals(UnknownBroadcastPolling6389.Decision.FALL_BACK_TO_JUPITER,
            UnknownBroadcastPolling6389.decide(timedOut, nowMs = 46_000L))
    }

    /* -------------------- S9 MAIN-THREAD HARDENING ------------------------ */

    @Test fun render_snapshot_is_throttled_to_1_hz() {
        val s1 = MainThreadHardening6389.PositionSnapshot(System.currentTimeMillis(), emptyList())
        assertTrue(MainThreadHardening6389.publish(s1))
        val s2 = MainThreadHardening6389.PositionSnapshot(System.currentTimeMillis(), emptyList())
        assertFalse("second publish inside 1s must be throttled",
            MainThreadHardening6389.publish(s2))
        assertTrue("force must bypass throttle",
            MainThreadHardening6389.publish(s2, force = true))
    }

    @Test fun main_thread_stall_counts_only_above_700ms() {
        MainThreadHardening6389.recordMainThreadStall(500L)
        assertEquals(0L, MainThreadHardening6389.stallCountAbove700ms())
        MainThreadHardening6389.recordMainThreadStall(800L)
        assertEquals(1L, MainThreadHardening6389.stallCountAbove700ms())
    }

    /* -------------------- S10 SELL-ONLY FORENSIC HOLD --------------------- */

    @Test fun acceptance_gate_blocks_when_any_criterion_fails() {
        SellOnlyForensicHold6389.engage("STARTUP_FORENSIC_HOLD")
        val a = SellOnlyForensicHold6389.Acceptance(
            signatureBackedUniqueCloses = 5,   // FAIL — below 20
            broadcastRowsInCanonical = 0, duplicateCloseSignatures = 0,
            quantityOrDecimalQuarantines = 0, journalVsLedgerMismatches = 0,
            proceedsReportedAsPnl = 0, inheritedIncludedInFreshMetrics = 0,
            maxBotCycleMs = 10_000L, exitCoordinatorStaleResetsIn15m = 0,
            mainThreadStallsAbove700ms = 0L,
        )
        assertFalse(a.allPass())
        assertFalse(SellOnlyForensicHold6389.tryRelease(a))
        assertTrue(SellOnlyForensicHold6389.isActive())
    }

    @Test fun acceptance_gate_releases_when_clean_cohort_confirmed() {
        SellOnlyForensicHold6389.engage("STARTUP_FORENSIC_HOLD")
        val a = SellOnlyForensicHold6389.Acceptance(
            signatureBackedUniqueCloses = 20, broadcastRowsInCanonical = 0,
            duplicateCloseSignatures = 0, quantityOrDecimalQuarantines = 0,
            journalVsLedgerMismatches = 0, proceedsReportedAsPnl = 0,
            inheritedIncludedInFreshMetrics = 0, maxBotCycleMs = 25_000L,
            exitCoordinatorStaleResetsIn15m = 0, mainThreadStallsAbove700ms = 0L,
        )
        assertTrue(a.allPass())
        assertTrue(SellOnlyForensicHold6389.tryRelease(a))
        assertFalse(SellOnlyForensicHold6389.isActive())
    }

    /* -------------------- END-TO-END: BELKA REPAIR ------------------------ */

    @Test fun belka_full_close_produces_directive_expected_pnl() {
        val i = AuthoritativePnl6389.AllocationInput(
            remainingCanonicalCostBasisLamports = BigInteger.valueOf(18_097_558L),
            positionTokenRawBeforeSell = BigInteger.valueOf(70_359_837_452L),
            tokenConsumedRaw = BigInteger.valueOf(70_359_837_452L),
            sellLamportsReceived = BigInteger.valueOf(17_527_827L),
            isFullClose = true,
        )
        val r = AuthoritativePnl6389.allocate(i)
        // Directive: expected PnL ≈ -0.000569731 SOL = -569_731 lamports
        assertEquals(BigInteger.valueOf(-569_731L), r.realisedPnlLamports)
    }
}
