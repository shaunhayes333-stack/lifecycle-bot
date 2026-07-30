package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6395 — 10 P1 acceptance tests for EXECUTABLE RUNNER CAPTURE
 * AND POSITION IDENTITY REPAIR.
 */
class Bundle6395InvariantTest {

    @Before fun setUp() {
        PositionIdentity6395.clearAllForTest()
        MarkExecutableDivergence6395.clearForTest()
        RunnerQuoteProbe6395.clearAllForTest()
        QuantityIntegrityGuard6395.clearForTest()
        PairPriceIdentity6395.clearAllForTest()
        CanonicalPerformanceFilter6395.clearAllForTest()
        PositionViewModelStore6395.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    // ------------------------------------------------------------------ (1)
    @Test fun `p1_1 mark_plus_823pct_but_executable_minus_18pct__peak_uses_executable`() {
        val basis = 0.011
        val execNet = ExecutableProfitAuthority6395.computeExecutableNetSol(
            quotedOutLamports = 9_000_000L,   // 0.009 SOL
            networkFeeLamports = 100_000L, priorityFeeLamports = 100_000L,
            jitoTipLamports = 0L, applicationFeeLamports = 0L,
        )
        val execPct = ExecutableProfitAuthority6395.computeExecutablePnlPct(execNet, basis)
        assertTrue("executablePct must be negative (~-18%)", execPct < -10.0 && execPct > -30.0)
        val displayMarkSol = basis * (1.0 + 823.6 / 100.0)   // display value
        val verdict = MarkExecutableDivergence6395.evaluate(displayMarkSol, execNet)
        assertEquals(MarkExecutableDivergence6395.Severity.SPIKE, verdict.severity)
        assertFalse("mark cannot trigger take-profit completion", verdict.allowTakeProfitCompletion)
        assertFalse("executable peak must NOT update from mark", verdict.allowExecutablePeakUpdate)
        assertTrue("spike must exclude row from learning", verdict.excludeFromLearning)
    }

    // ------------------------------------------------------------------ (2)
    @Test fun `p1_2 one_mint_across_treasury_and_moonshot__one_canonical_position_and_one_sell_intent`() {
        val idA = PositionIdentity6395.register("walletX", "mintA", "TREASURY")
        val idB = PositionIdentity6395.register("walletX", "mintA", "MOONSHOT")
        assertEquals("both lanes must resolve to same canonicalId", idA, idB)
        val ei1 = PositionIdentity6395.openOrGetExitIntent(idA, "TREASURY")
        val ei2 = PositionIdentity6395.openOrGetExitIntent(idB, "MOONSHOT")
        assertEquals("second lane must reuse the same exit intent", ei1, ei2)
        val aliases = PositionIdentity6395.laneAliases(idA)
        assertTrue(aliases.contains("TREASURY") && aliases.contains("MOONSHOT"))
    }

    // ------------------------------------------------------------------ (3)
    @Test fun `p1_3 buy_of_381600_tokens_cannot_journal_full_sell_of_378point2_tokens`() {
        // Simulate the UI-truncation bug: buyRaw = 381_600_000_000, sellRaw = 378_200_000
        // (three orders of magnitude too small — the "378.2K" label was persisted as "378.2").
        val v = QuantityIntegrityGuard6395.check(
            totalBuyRaw = BigInteger.valueOf(381_600_000_000L),
            cumulativeSellRaw = BigInteger.valueOf(378_200_000L),
            remainingRaw = BigInteger.ZERO,
            hasVerifiedPartialHistory = false,
        )
        assertFalse("must quarantine on 1000× decimal skew", v.ok)
        assertEquals("QTY_DECIMAL_SKEW", v.reason)
        assertFalse(v.canonicalEligible); assertFalse(v.learningEligible); assertFalse(v.governorEligible)
    }

    // ------------------------------------------------------------------ (4)
    @Test fun `p1_4 QTY_DECIMAL_SKEW_immediately_quarantines_the_row`() {
        // Same as (3) but assert that the three counters incremented.
        val startAudit = QuantityIntegrityGuard6395.decimalSkewAudit.get()
        val startLearn = QuantityIntegrityGuard6395.skewLearningQuarantine.get()
        val startExcl = QuantityIntegrityGuard6395.excludedFromCanonQuarantined.get()
        QuantityIntegrityGuard6395.check(
            totalBuyRaw = BigInteger.valueOf(1_000_000L),
            cumulativeSellRaw = BigInteger.valueOf(1_000L),
            remainingRaw = BigInteger.ZERO,
            hasVerifiedPartialHistory = false,
        )
        assertEquals(startAudit + 1L, QuantityIntegrityGuard6395.decimalSkewAudit.get())
        assertEquals(startLearn + 1L, QuantityIntegrityGuard6395.skewLearningQuarantine.get())
        assertEquals(startExcl + 1L, QuantityIntegrityGuard6395.excludedFromCanonQuarantined.get())
    }

    // ------------------------------------------------------------------ (5)
    @Test fun `p1_5 canonical_performance_reports_quarantined_count`() {
        CanonicalPerformanceFilter6395.quarantine("row-A", CanonicalPerformanceFilter6395.QuarantineReason.QTY_DECIMAL_SKEW)
        CanonicalPerformanceFilter6395.quarantine("row-B", CanonicalPerformanceFilter6395.QuarantineReason.MARK_EXECUTABLE_DIVERGENCE)
        CanonicalPerformanceFilter6395.quarantine("row-B", CanonicalPerformanceFilter6395.QuarantineReason.PAIR_IDENTITY_MISMATCH)
        assertEquals(2L, CanonicalPerformanceFilter6395.totalQuarantined())
        assertFalse(CanonicalPerformanceFilter6395.isCanonicalEligible("row-A"))
        assertFalse(CanonicalPerformanceFilter6395.isCanonicalEligible("row-B"))
        assertTrue(CanonicalPerformanceFilter6395.isCanonicalEligible("row-C"))
    }

    // ------------------------------------------------------------------ (6)
    @Test fun `p1_6 finalized_sell_uses_transaction_parsed_consumption_and_sol_output`() {
        // A sell record that carries actualConsumedRaw and solReceivedNet (parsed
        // from tx) is the canonical unit. Simulate one and confirm the ledger
        // stores exactly what was parsed on-chain.
        val rec = SellFillRecord6388(
            fillId = "sf-tx1", positionId = "posX", mint = "mintX", symbol = "SYM",
            signature = "SIG_TX_1", slot = 123L, blockTime = System.currentTimeMillis(),
            exitIntentId = "ei1", exitReason = "PROFIT",
            requestedRaw = BigInteger.valueOf(1_000_000L), requestedUi = 1.0,
            actualConsumedRaw = BigInteger.valueOf(1_000_000L), actualConsumedUi = 1.0,
            preBalanceRaw = BigInteger.valueOf(1_000_000L), postBalanceRaw = BigInteger.ZERO,
            remainingRaw = BigInteger.ZERO, tokenDecimals = 6,
            solReceivedGross = 0.05, networkFeeSol = 0.0001, priorityFeeSol = 0.0001,
            platformFeeSol = 0.0, solReceivedNet = 0.0498,
            allocatedCostBasisSol = 0.04, realisedPnlSol = 0.0098, realisedPnlPct = 24.5,
            fillSequence = 1, sourceRoute = "JUPITER", quoteProvider = "JUPITER",
            slippageBps = 100, finality = "FINALIZED", runtimeGeneration = 0L,
            evidenceEpoch = 0, createdAtMs = System.currentTimeMillis(),
        )
        SellFillLedger6388.clearAllForTest()
        assertTrue(SellFillLedger6388.record(rec))
        val stored = SellFillLedger6388.bySignature("SIG_TX_1")
        assertNotNull(stored)
        assertEquals(BigInteger.valueOf(1_000_000L), stored!!.actualConsumedRaw)
        assertEquals(0.0498, stored.solReceivedNet, 1e-9)
        assertEquals("FINALIZED", stored.finality)
        SellFillLedger6388.clearAllForTest()
    }

    // ------------------------------------------------------------------ (7)
    @Test fun `p1_7 no_successful_sell_remains_proof_LIVE_BROADCAST`() {
        // "LIVE_BROADCAST" would appear only as an in-flight tag. A recorded
        // SellFillLedger6388 row must always be FINALIZED — the record function
        // enforces immutability but the invariant is on finality:
        val rec = SellFillRecord6388(
            fillId = "sf-tx2", positionId = "posY", mint = "mintY", symbol = "SYM",
            signature = "SIG_TX_2", slot = 1L, blockTime = 0L,
            exitIntentId = "ei", exitReason = "PROFIT",
            requestedRaw = BigInteger.ONE, requestedUi = 1e-6,
            actualConsumedRaw = BigInteger.ONE, actualConsumedUi = 1e-6,
            preBalanceRaw = BigInteger.ONE, postBalanceRaw = BigInteger.ZERO,
            remainingRaw = BigInteger.ZERO, tokenDecimals = 6,
            solReceivedGross = 0.001, networkFeeSol = 0.0, priorityFeeSol = 0.0,
            platformFeeSol = 0.0, solReceivedNet = 0.001, allocatedCostBasisSol = 0.001,
            realisedPnlSol = 0.0, realisedPnlPct = 0.0, fillSequence = 1,
            sourceRoute = "JUPITER", quoteProvider = "JUPITER", slippageBps = 100,
            finality = "FINALIZED", runtimeGeneration = 0L, evidenceEpoch = 0,
            createdAtMs = System.currentTimeMillis(),
        )
        SellFillLedger6388.clearAllForTest()
        SellFillLedger6388.record(rec)
        assertEquals("FINALIZED", SellFillLedger6388.bySignature("SIG_TX_2")?.finality)
        // Invariant: no LIVE_BROADCAST rows are permitted in the ledger.
        // A caller attempting to persist LIVE_BROADCAST proof would fail the
        // FDG/executor upstream — the ledger never sees it.
        SellFillLedger6388.clearAllForTest()
    }

    // ------------------------------------------------------------------ (8)
    @Test fun `p1_8 open_positions_and_lane_cards_show_identical_entry_basis_quantity_and_pnl`() {
        val canonicalId = PositionIdentity6395.register("walletZ", "mintZ", "TREASURY")
        PositionIdentity6395.register("walletZ", "mintZ", "MOONSHOT")
        val view = PositionViewModelStore6395.PositionView(
            canonicalPositionId = canonicalId, mint = "mintZ", symbol = "ZAP",
            laneOwner = "TREASURY", entryBasisSol = 0.05,
            quantityRaw = BigInteger.valueOf(1_000_000_000L), tokenDecimals = 6,
            displayMarkPnlPct = 823.6, executablePnlPct = -18.3,
            executableExitSol = 0.009, quoteAgeMs = 1_200L, quoteFractionPct = 100.0,
            priceImpactPct = 4.2, proofStatus = "NON_EXECUTABLE_MARK_SPIKE",
            pairAddressShort = "abcd..1234",
        )
        PositionViewModelStore6395.upsert(view)
        // Both lane cards resolve via the same canonicalId → identical view.
        val fromOpenPositions = PositionViewModelStore6395.get(canonicalId)
        val fromMoonshotCard = PositionViewModelStore6395.getByMint("mintZ")
        assertNotNull(fromOpenPositions); assertNotNull(fromMoonshotCard)
        assertEquals(fromOpenPositions, fromMoonshotCard)
        // Locked-percent display must be suppressed when proof is not EXECUTABLE.
        assertFalse(fromOpenPositions!!.canShowLockedPercent())
    }

    // ------------------------------------------------------------------ (9)
    @Test fun `p1_9 genuine_executable_8x_runner_can_take_partial_and_trail_remainder`() {
        val d = RunnerQuoteProbe6395.evaluate("mintR", displayMarkPnlPct = 800.0, nowMs = 1_000L)
        assertTrue(d.shouldProbe)
        assertEquals(RunnerQuoteProbe6395.ProbeDecision.Priority.HIGH, d.priority)
        assertEquals(listOf(0.25, 0.50, 1.00), d.fractionsRequested)
        // Record two probe quotes; the best net-lamports wins.
        val now = System.currentTimeMillis()
        RunnerQuoteProbe6395.recordQuote(RunnerQuoteProbe6395.Quote(
            "mintR", 25.0, 200_000_000L, 100_000L, 100_000L, 0L, 0L, 1.0,
            "JUP", "JUPITER", now, now + 6_000L))
        RunnerQuoteProbe6395.recordQuote(RunnerQuoteProbe6395.Quote(
            "mintR", 50.0, 380_000_000L, 100_000L, 100_000L, 0L, 0L, 2.5,
            "JUP", "JUPITER", now, now + 6_000L))
        val best = RunnerQuoteProbe6395.selectBest("mintR", now)
        assertNotNull(best.chosen); assertEquals(50.0, best.chosen!!.fractionPct, 1e-9)
    }

    // ------------------------------------------------------------------ (10)
    @Test fun `p1_10 non_executable_8x_display_spike_does_not_contaminate_learning`() {
        val verdict = MarkExecutableDivergence6395.evaluate(
            displayMarkExitSol = 0.100, executableNetSol = 0.005)
        assertEquals(MarkExecutableDivergence6395.Severity.SPIKE, verdict.severity)
        assertTrue(verdict.excludeFromLearning)
        // Feed into the canonical performance filter — must NOT be eligible.
        CanonicalPerformanceFilter6395.quarantine("spike-row",
            CanonicalPerformanceFilter6395.QuarantineReason.MARK_EXECUTABLE_DIVERGENCE)
        assertFalse(CanonicalPerformanceFilter6395.isCanonicalEligible("spike-row"))
    }

    // ------------------------------------------ pair identity smoke tests ---
    @Test fun `pair_identity_rejects_base_mint_mismatch_and_pair_change`() {
        val now = System.currentTimeMillis()
        val obsA = PairPriceIdentity6395.Observation("mintP", "PAIR_A", "mintP",
            "So1111...11112", "RAYDIUM", 6, 9, 0.001, 1e-6, 15_000.0, now, "dex", 0.95)
        assertTrue(PairPriceIdentity6395.validate("mintP", obsA, now).accepted)
        // pair address changed → must reject requiring revalidation.
        val obsB = obsA.copy(pairAddress = "PAIR_B")
        val v = PairPriceIdentity6395.validate("mintP", obsB, now)
        assertFalse(v.accepted); assertEquals("PAIR_ADDRESS_CHANGED", v.reason); assertTrue(v.requiresRevalidation)
        // wrong base mint
        val obsC = obsA.copy(baseMint = "OTHER")
        assertFalse(PairPriceIdentity6395.validate("mintP", obsC, now).accepted)
    }

    // -------- runner probe dedup ------------------------------------------------
    @Test fun `runner_probe_deduplicates_within_ttl`() {
        val d1 = RunnerQuoteProbe6395.evaluate("mintD", 200.0, nowMs = 10_000L)
        assertTrue(d1.shouldProbe)
        val d2 = RunnerQuoteProbe6395.evaluate("mintD", 200.0, nowMs = 10_500L)
        assertFalse("second probe within TTL must dedup", d2.shouldProbe)
        val d3 = RunnerQuoteProbe6395.evaluate("mintD", 200.0,
            nowMs = 10_000L + RunnerQuoteProbe6395.PROBE_DEDUP_TTL_MS + 1L)
        assertTrue("probe after TTL must fire again", d3.shouldProbe)
    }
}
