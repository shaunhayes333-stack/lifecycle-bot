package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class Bundle6394CanonicalExecutionTruthTest {

    @Before fun setUp() {
        CanonicalReceiptStore6394.clearForTest()
        PositionLotLedger6394.clearForTest()
        SingleSellStateMachine6394.clearForTest()
        LiveFeeLedger6394.clearForTest()
        ExecutionTicketAuthority6394.clearForTest()
        AccountingQuarantine6394.clearForTest()
        WeeklyGrowthDashboard6394.clearForTest()
        SmartMoneyFeed6394.clearForTest()
    }
    @After fun tearDown() { setUp() }

    /* -------- P0 canonical receipt ---------------------------------------- */

    @Test fun receipt_persist_is_idempotent_by_signature() {
        val r = makeReceipt("SIG1", "posA", "mintA")
        assertTrue(CanonicalReceiptStore6394.persist(r))
        assertFalse("duplicate signature must not persist twice",
            CanonicalReceiptStore6394.persist(r))
        assertEquals(1, CanonicalReceiptStore6394.size())
    }

    /* -------- P0 buy invariants ------------------------------------------- */

    @Test fun negative_buy_spend_fails_invariant() {
        val r = makeReceipt("SIG_NEG", "posB", "mintB").copy(
            preOwnerLamports = BigInteger.valueOf(1_000_000L),
            postOwnerLamports = BigInteger.valueOf(2_000_000L),   // wallet went UP on a buy → negative spend
        )
        val v = BuySettlementInvariants6394.check(r,
            requestedMaxDebitLamports = BigInteger.valueOf(1_000_000L),
            estimatedTokenRaw = BigInteger.valueOf(1_000L), requestedMint = "mintB")
        assertFalse(v.passed)
        assertTrue(v.failures.any { it.contains("NEGATIVE_SOL_SPEND") ||
            it.contains("DEBIT_RECONCILIATION_FAILURE") })
    }

    @Test fun mint_mismatch_fails_invariant() {
        val r = makeReceipt("SIG_MM", "posC", "mintC")
        val v = BuySettlementInvariants6394.check(r,
            requestedMaxDebitLamports = BigInteger.valueOf(10_000_000L),
            estimatedTokenRaw = BigInteger.valueOf(1_000_000L),
            requestedMint = "someOtherMint")
        assertFalse(v.passed)
        assertTrue("MINT_MISMATCH" in v.failures)
    }

    @Test fun estimate_vs_actual_25pct_divergence_quarantines() {
        // Estimated 1000, actual 3000 → 200% divergence (well above 25% quarantine floor).
        val r = makeReceipt("SIG_DIV", "posD", "mintD").copy(
            postTokenRaw = BigInteger.valueOf(3_000L),
            actualReceivedRawAmount = BigInteger.valueOf(3_000L),
        )
        val v = BuySettlementInvariants6394.check(r,
            requestedMaxDebitLamports = BigInteger.valueOf(10_000_000L),
            estimatedTokenRaw = BigInteger.valueOf(1_000L), requestedMint = "mintD")
        assertTrue(v.quoteMismatch)
        assertTrue(v.quarantineDivergent)
    }

    /* -------- P0 lot ledger ----------------------------------------------- */

    @Test fun sell_amount_clamps_to_min_of_requested_remaining_wallet() {
        PositionLotLedger6394.upsert(PositionLot6394(
            lotId = "L1", mint = "mintX", tokenDecimals = 6,
            openedRaw = BigInteger.valueOf(10_000L),
            remainingRaw = BigInteger.valueOf(6_000L),
            entryPrincipalLamports = BigInteger.valueOf(500_000L),
            allocatedFeesLamports = BigInteger.ZERO,
            entrySignature = "BUY_SIG_1", status = "OPEN", version = 0L,
        ))
        val amt = PositionLotLedger6394.computeSellRaw(
            "mintX", BigInteger.valueOf(9_000L), BigInteger.valueOf(4_000L))
        assertEquals(BigInteger.valueOf(4_000L), amt)  // wallet is the tightest bound
    }

    @Test fun closed_position_cannot_reopen_without_new_buy_sig() {
        PositionLotLedger6394.upsert(PositionLot6394(
            lotId = "L2", mint = "mintY", tokenDecimals = 6,
            openedRaw = BigInteger.valueOf(1_000L), remainingRaw = BigInteger.ZERO,
            entryPrincipalLamports = BigInteger.valueOf(500L),
            allocatedFeesLamports = BigInteger.ZERO,
            entrySignature = "OLD_SIG", status = "CLOSED", version = 5L,
        ))
        // Stale recovery tries to bring it back with no new buy signature.
        val reopened = PositionLot6394(
            lotId = "L2b", mint = "mintY", tokenDecimals = 6,
            openedRaw = BigInteger.valueOf(1_000L), remainingRaw = BigInteger.valueOf(1_000L),
            entryPrincipalLamports = BigInteger.valueOf(500L),
            allocatedFeesLamports = BigInteger.ZERO,
            entrySignature = "", status = "OPEN", version = 6L,
        )
        assertFalse("stale recovery reopen without new sig must be rejected",
            PositionLotLedger6394.upsert(reopened))
    }

    @Test fun apply_sell_uses_compare_and_set_versioning() {
        PositionLotLedger6394.upsert(PositionLot6394(
            lotId = "L3", mint = "mintZ", tokenDecimals = 6,
            openedRaw = BigInteger.valueOf(1_000L), remainingRaw = BigInteger.valueOf(1_000L),
            entryPrincipalLamports = BigInteger.valueOf(500L),
            allocatedFeesLamports = BigInteger.ZERO,
            entrySignature = "SIG_L3", status = "OPEN", version = 3L,
        ))
        assertFalse("wrong version must be rejected",
            PositionLotLedger6394.applySellCAS("mintZ", 999L,
                BigInteger.valueOf(500L), "SELL_SIG"))
        assertTrue(PositionLotLedger6394.applySellCAS("mintZ", 3L,
            BigInteger.valueOf(500L), "SELL_SIG"))
        assertEquals("SELL_SIG", PositionLotLedger6394.lastSellSignature("mintZ"))
    }

    /* -------- P0 sell FSM ------------------------------------------------- */

    @Test fun successful_states_do_not_downgrade_under_inconclusive_callback() {
        val mint = "mintFSM"
        SingleSellStateMachine6394.tryTransition(mint,
            SingleSellStateMachine6394.State.CONFIRMED, "exec1")
        assertFalse("inconclusive must NOT downgrade CONFIRMED",
            SingleSellStateMachine6394.tryTransition(mint,
                SingleSellStateMachine6394.State.QUOTING, "exec1"))
        assertEquals(SingleSellStateMachine6394.State.CONFIRMED,
            SingleSellStateMachine6394.currentState(mint))
    }

    @Test fun retry_delays_follow_directive_schedule() {
        assertEquals(2_000L, SingleSellStateMachine6394.retryDelayMs(0))
        assertEquals(5_000L, SingleSellStateMachine6394.retryDelayMs(1))
        assertEquals(15_000L, SingleSellStateMachine6394.retryDelayMs(2))
        assertEquals(30_000L, SingleSellStateMachine6394.retryDelayMs(3))
        assertEquals(60_000L, SingleSellStateMachine6394.retryDelayMs(4))
        assertEquals(60_000L, SingleSellStateMachine6394.retryDelayMs(99))  // capped
    }

    /* -------- P0 live fees ------------------------------------------------ */

    @Test fun fee_split_is_exact_integer_lamports() {
        val cfg = LiveFeeConfig6394(feeBps = 100, recipients = listOf(
            LiveFeeConfig6394.Recipient("wallet_A", 6_000),
            LiveFeeConfig6394.Recipient("wallet_B", 4_000),
        ))
        assertTrue(cfg.valid)
        val amounts = LiveFeeLedger6394.split(BigInteger.valueOf(1_234_567L), cfg)
        assertEquals(BigInteger.valueOf(1_234_567L), amounts.sumOf { it.second })
    }

    @Test fun invalid_config_fails_validation() {
        val cfg = LiveFeeConfig6394(feeBps = 100, recipients = listOf(
            LiveFeeConfig6394.Recipient("wallet_A", 5_000),
            LiveFeeConfig6394.Recipient("wallet_B", 4_000),   // total = 9000, not 10000
        ))
        assertFalse(cfg.valid)
    }

    @Test fun idempotency_key_prevents_duplicate_fee_payments() {
        val key = LiveFeeLedger6394.idempotencyKey(
            "mainnet", "SELL_SIG_1", "APP_FEE", "recipient_A")
        val e = LiveFeeEvent6394(
            feeEventId = "F1", sourceExecutionId = "E1", sourceSellSignature = "SELL_SIG_1",
            sourceMint = "mintM", grossProceedsLamports = BigInteger.valueOf(10_000_000L),
            feeBasisLamports = BigInteger.valueOf(10_000_000L), feeBps = 100,
            totalFeeLamports = BigInteger.valueOf(100_000L),
            recipientWallet = "recipient_A", recipientWeightBps = 10_000,
            recipientAmountLamports = BigInteger.valueOf(100_000L), status = "PENDING",
            payoutSignature = null, payoutSlot = null,
            recipientPreLamports = null, recipientPostLamports = null,
            retryCount = 0, lastError = null,
            accruedAt = System.currentTimeMillis(),
            broadcastAt = null, finalizedAt = null,
        )
        assertTrue(LiveFeeLedger6394.accrue(e, key))
        assertFalse("second accrue with same key must be rejected",
            LiveFeeLedger6394.accrue(e, key))
    }

    /* -------- P0 execution ticket authority ------------------------------- */

    @Test fun ticket_is_single_use() {
        val t = ExecutionTicket6394(
            ticketId = "T1", decisionId = "D1", mint = "mintT", lane = "MOONSHOT",
            requestedSizeSol = 0.01, score = 65.0, minimumScore = 55.0,
            safetyResult = "ALLOWED", governorState = "BASELINE", verdict = "BUY",
            createdAtMs = 0L,
        )
        assertTrue(ExecutionTicketAuthority6394.issue(t))
        assertNotNull(ExecutionTicketAuthority6394.consume("T1"))
        assertNull("second consume must return null",
            ExecutionTicketAuthority6394.consume("T1"))
    }

    @Test fun non_buy_verdict_cannot_authorise_live_buy() {
        val t = ExecutionTicket6394(
            ticketId = "T2", decisionId = "D2", mint = "mintP", lane = "MOMENTUM",
            requestedSizeSol = 0.01, score = 40.0, minimumScore = 55.0,
            safetyResult = "ALLOWED", governorState = "BASELINE",
            verdict = "PROBE_ONLY", createdAtMs = 0L,
        )
        assertFalse(ExecutionTicketAuthority6394.issue(t))
    }

    /* -------- P0 accounting quarantine ------------------------------------ */

    @Test fun quarantine_tag_preserves_row_and_marks_ineligible() {
        assertTrue(AccountingQuarantine6394.tag(AccountingQuarantine6394.Tag(
            rowId = "row1", accountingVersion = 6394, buildNumber = 6394,
            quarantineReason = "NEGATIVE_BUY_SPEND", proofStatus = "PARSED_METADATA_MISSING",
        )))
        assertTrue(AccountingQuarantine6394.isTagged("row1"))
    }

    @Test fun quarantine_rejects_unknown_reason() {
        try {
            AccountingQuarantine6394.tag(AccountingQuarantine6394.Tag(
                rowId = "row2", accountingVersion = 6394, buildNumber = 6394,
                quarantineReason = "BOGUS_REASON", proofStatus = "-",
            ))
            fail("must throw on unknown reason")
        } catch (_: IllegalArgumentException) {}
    }

    /* -------- Weekly Growth Dashboard ------------------------------------- */

    @Test fun weekly_growth_dashboard_publish_and_read() {
        val snap = WeeklyGrowthMode6393.Snapshot(
            weeklyStartEquitySol = 1.0, currentEquitySol = 1.75,
            realisedEquitySol = 0.75, deployedCapitalSol = 0.30,
            protectedCapitalSol = 0.40, peakWeeklyEquitySol = 2.10,
            weeklyDrawdownPct = 3.5,
        )
        WeeklyGrowthDashboard6394.publish(snap)
        val rendered = WeeklyGrowthDashboard6394.renderHealthReportBlock()
        assertTrue(rendered.contains("startEquity"))
        assertTrue(rendered.contains("progress"))
        assertNotNull(WeeklyGrowthDashboard6394.read())
    }

    /* -------- Smart Money Feed ------------------------------------------- */

    @Test fun smart_money_feed_counts_recent_buys_per_mint() {
        val now = System.currentTimeMillis()
        SmartMoneyFeed6394.onWhaleBuy("mintQ", "whale1", now)
        SmartMoneyFeed6394.onWhaleBuy("mintQ", "whale2", now - 20_000L)
        SmartMoneyFeed6394.onWhaleBuy("mintQ", "whale3", now - 200_000L)  // outside 60s
        SmartMoneyFeed6394.onWhaleBuy("mintR", "whale4", now)
        assertEquals(2, SmartMoneyFeed6394.smartMoneyBuysLast60s("mintQ", now))
        assertEquals(1, SmartMoneyFeed6394.smartMoneyBuysLast60s("mintR", now))
    }

    /* -------- helpers ----------------------------------------------------- */

    private fun makeReceipt(sig: String, positionId: String, mint: String) = CanonicalExecutionReceipt6394(
        executionId = "E_$sig", decisionId = "D_$sig", positionId = positionId, lotId = "L_$sig",
        runtimeGeneration = 1L, mint = mint, symbol = "SYM", lane = "MOONSHOT",
        side = "BUY", provider = "JUPITER", route = "route1",
        requestedRawAmount = BigInteger.valueOf(1_000L),
        actualConsumedRawAmount = BigInteger.valueOf(1_000L),
        actualReceivedRawAmount = BigInteger.valueOf(1_000L), tokenDecimals = 6,
        preTokenRaw = BigInteger.ZERO, postTokenRaw = BigInteger.valueOf(1_000L),
        preOwnerLamports = BigInteger.valueOf(10_000_000L),
        postOwnerLamports = BigInteger.valueOf(9_800_000L),
        principalLamports = BigInteger.valueOf(180_000L),
        grossProceedsLamports = BigInteger.ZERO,
        networkFeeLamports = BigInteger.valueOf(5_000L),
        priorityFeeLamports = BigInteger.valueOf(10_000L),
        jitoTipLamports = BigInteger.valueOf(5_000L),
        rentCreatedLamports = BigInteger.ZERO,
        rentRefundedLamports = BigInteger.ZERO,
        appFeeAccruedLamports = BigInteger.ZERO,
        appFeePaidLamports = BigInteger.ZERO,
        netUserProceedsLamports = BigInteger.ZERO,
        signature = sig, slot = 100L, blockTime = 200L,
        confirmationStatus = "FINALIZED", settlementStatus = "SETTLED",
        proofSource = "TX_META", createdAt = 0L, finalizedAt = 0L,
    )
}

/**
 * V5.0.6394 — EARLY LAUNCH BYPASS tests. Score < 55 with HIGH_CONVICTION_EARLY
 * scout verdict must be allowed as a strictly-sized micro-probe.
 */
class Bundle6394EarlyLaunchBypassTest {
    @org.junit.Before fun setUp() { EarlyLaunchBypass6394.clearForTest() }

    @org.junit.Test fun high_conviction_early_below_floor_becomes_micro_probe() {
        val d = EarlyLaunchBypass6394.evaluate(
            liveScore = 48.0,
            scoutTier = EarlyEntryScout6390.Tier.HIGH_CONVICTION_EARLY,
            hardSafetyPassed = true, mintPairResolved = true,
            freshLiquidityProof = true, sellQuoteable = true,
            sameMintOpen = false, reentryLockout = false,
        )
        org.junit.Assert.assertTrue(d.allow)
        org.junit.Assert.assertEquals(0.30, d.sizeMultiplier, 1e-9)
        org.junit.Assert.assertTrue(d.reason.contains("EARLY_LAUNCH_MICRO_PROBE"))
    }

    @org.junit.Test fun score_below_absolute_min_stays_blocked_even_with_high_conviction() {
        val d = EarlyLaunchBypass6394.evaluate(
            liveScore = 30.0,
            scoutTier = EarlyEntryScout6390.Tier.HIGH_CONVICTION_EARLY,
            hardSafetyPassed = true, mintPairResolved = true,
            freshLiquidityProof = true, sellQuoteable = true,
            sameMintOpen = false, reentryLockout = false,
        )
        org.junit.Assert.assertFalse(d.allow)
        org.junit.Assert.assertTrue(d.reason.contains("BELOW_ABSOLUTE_MIN"))
    }

    @org.junit.Test fun hard_safety_failure_never_bypassed() {
        val d = EarlyLaunchBypass6394.evaluate(
            liveScore = 50.0,
            scoutTier = EarlyEntryScout6390.Tier.HIGH_CONVICTION_EARLY,
            hardSafetyPassed = false, mintPairResolved = true,
            freshLiquidityProof = true, sellQuoteable = true,
            sameMintOpen = false, reentryLockout = false,
        )
        org.junit.Assert.assertFalse(d.allow)
        org.junit.Assert.assertEquals("HARD_SAFETY_FAILED", d.reason)
    }

    @org.junit.Test fun early_interest_tier_not_enough_to_bypass_score_floor() {
        val d = EarlyLaunchBypass6394.evaluate(
            liveScore = 48.0,
            scoutTier = EarlyEntryScout6390.Tier.EARLY_INTEREST,
            hardSafetyPassed = true, mintPairResolved = true,
            freshLiquidityProof = true, sellQuoteable = true,
            sameMintOpen = false, reentryLockout = false,
        )
        org.junit.Assert.assertFalse(d.allow)
        org.junit.Assert.assertTrue(d.reason.contains("INSUFFICIENT_FOR_BYPASS"))
    }

    @org.junit.Test fun above_floor_returns_normal_full_size() {
        val d = EarlyLaunchBypass6394.evaluate(
            liveScore = 65.0,
            scoutTier = EarlyEntryScout6390.Tier.NOT_QUALIFIED,
            hardSafetyPassed = true, mintPairResolved = true,
            freshLiquidityProof = true, sellQuoteable = true,
            sameMintOpen = false, reentryLockout = false,
        )
        org.junit.Assert.assertTrue(d.allow)
        org.junit.Assert.assertEquals(1.0, d.sizeMultiplier, 1e-9)
    }
}
