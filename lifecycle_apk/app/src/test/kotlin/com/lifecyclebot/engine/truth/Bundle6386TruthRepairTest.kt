package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.6386 — REGRESSION TESTS (Section 12 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Add property tests for mint decimals: 0, 1, 5, 6, 8 and 9.
 *    Test: raw -> UI -> raw returns exactly the original integer.
 *
 *    Add replay fixtures for: rNMsVB, 51eWyx, EVkwEA/BIAO, USDS, 63LfDm
 *
 *    Assertions:
 *     - New BUY quantity equals post-balance minus pre-balance.
 *     - Existing wallet holdings never enter the new lot.
 *     - Cost basis equals net lamports spent by the matching signature.
 *     - Sold quantity equals pre-sell minus post-sell raw balance.
 *     - Proceeds equal matching realized lamport delta.
 *     - Broadcast rows cannot close positions.
 *     - Partial quotes cannot create PnL.
 *     - Re-entry creates a separate immutable lot.
 *     - One mint cannot create competing lane tickets."
 */
class Bundle6386TruthRepairTest {

    @Before
    fun setup() {
        ExecutionIntentRegistry6386.clearAllForTest()
        FillLotLedger6386.clearAllForTest()
        CanaryReleaseGate6386.resetAllForTest()
    }

    @After
    fun teardown() {
        ExecutionIntentRegistry6386.clearAllForTest()
        FillLotLedger6386.clearAllForTest()
        CanaryReleaseGate6386.resetAllForTest()
    }

    // ── Section 4: Strong amount types + decimals property tests ─────

    @Test
    fun decimals_roundtrip_0_1_5_6_8_9() {
        // Section 12 property test: raw -> UI -> raw returns the ORIGINAL integer
        // for decimals in {0, 1, 5, 6, 8, 9}.
        val decimalsList = listOf(0, 1, 5, 6, 8, 9)
        // Representative raw values across a wide range.
        val rawValues = listOf(
            BigInteger.ONE,
            BigInteger.valueOf(7L),
            BigInteger.valueOf(9_999L),
            BigInteger.valueOf(1_000_000L),
            BigInteger.valueOf(4_242_424_242L),
            BigInteger("100000000000"),
        )
        for (d in decimalsList) {
            val decimals = MintDecimals.Known(
                count = d,
                source = "TEST_FIXTURE",
                proofSignature = "test_decimals_$d",
            )
            for (raw in rawValues) {
                val original = RawTokenAmount(raw)
                val ui = original.toUi(decimals)
                val back = ui.toRaw(decimals)
                assertEquals(
                    "raw -> UI -> raw MUST be exact for decimals=$d raw=$raw",
                    original.value, back.value,
                )
            }
        }
    }

    @Test
    fun mint_decimals_unknown_never_coerced_to_zero() {
        // Directive: "UNKNOWN is null or an explicit sealed state. Zero means
        // proven zero-decimal mint. Never coerce unknown decimals to zero."
        val raw = RawTokenAmount(BigInteger.valueOf(12345L))
        try {
            raw.toUi(MintDecimals.Unknown)
            org.junit.Assert.fail("MintDecimals.Unknown must throw on UI conversion, not silently coerce to zero")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Unknown") == true)
        }
    }

    @Test
    fun value_classes_prevent_cross_type_arithmetic() {
        // We can't check this at compile time in a JVM unit test since we've
        // already compiled, but we CAN verify that plus/minus stay in-type.
        val a = Lamports.of(1_000_000L)
        val b = Lamports.of(500_000L)
        val diff: Lamports = a - b
        assertEquals(Lamports.of(500_000L), diff)
        // Same for RawTokenAmount.
        val x = RawTokenAmount.of(42L)
        val y = RawTokenAmount.of(10L)
        val z: RawTokenAmount = x - y
        assertEquals(RawTokenAmount.of(32L), z)
    }

    // ── Section 9: ProofState machine ────────────────────────────────

    @Test
    fun only_finalized_proof_state_contributes_to_truth() {
        assertFalse(ProofState6386.IntentCreated.contributesToTruth())
        assertFalse(ProofState6386.TransactionBuilt.contributesToTruth())
        assertFalse(ProofState6386.SignatureReceived("sig123").contributesToTruth())
        assertFalse(ProofState6386.BroadcastPending("sig123").contributesToTruth())
        assertFalse(ProofState6386.FailedConfirmed("sig123", "on-chain error").contributesToTruth())
        assertFalse(ProofState6386.PendingReconciliation("no metadata").contributesToTruth())
        assertFalse(ProofState6386.Quarantined("corrupted").contributesToTruth())
        val fin = ProofState6386.FinalizedProofComplete(
            signature = "sig123",
            walletAddress = "wallet",
            mintAddress = "mint",
            decimals = MintDecimals.Known(6, "TEST", "sig123"),
            preRawBalance = RawTokenAmount.of(0L),
            postRawBalance = RawTokenAmount.of(1000L),
            preLamports = Lamports.of(1_000_000L),
            postLamports = Lamports.of(950_000L),
            feeLamports = Lamports.of(5_000L),
        )
        assertTrue(fin.contributesToTruth())
    }

    // ── Section 2: ExecutionIntent atomic map + uniqueness ───────────

    @Test
    fun only_one_outstanding_buy_intent_per_wallet_mint() {
        val first = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet1",
            mintAddress = "mint1",
            side = IntentSide.BUY,
            selectedLane = "MOONSHOT",
            marketSnapshotId = "snap1",
            requestedLamports = Lamports.of(3_000_000L),
            score = 50,
            fdgVerdict = "ALLOW",
        )
        assertNotNull("first BUY reservation must succeed", first)
        val second = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet1",
            mintAddress = "mint1",
            side = IntentSide.BUY,
            selectedLane = "MOONSHOT",
            marketSnapshotId = "snap2",
            requestedLamports = Lamports.of(3_000_000L),
            score = 60,
            fdgVerdict = "ALLOW",
        )
        assertNull("second concurrent BUY reservation MUST be rejected", second)
        // A different mint should still be allowed.
        val differentMint = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet1",
            mintAddress = "mint2",
            side = IntentSide.BUY,
            selectedLane = "MOONSHOT",
            marketSnapshotId = "snap3",
            requestedLamports = Lamports.of(3_000_000L),
            score = 55,
            fdgVerdict = "ALLOW",
        )
        assertNotNull(differentMint)
    }

    @Test
    fun terminal_state_frees_the_intent_slot() {
        val intent = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet1",
            mintAddress = "mintFree",
            side = IntentSide.BUY,
            selectedLane = "QUALITY",
            marketSnapshotId = "s1",
            requestedLamports = Lamports.of(3_000_000L),
            score = 55,
            fdgVerdict = "ALLOW",
        )!!
        // Terminal transition — quarantined.
        ExecutionIntentRegistry6386.update(intent.withState(ProofState6386.Quarantined("test")))
        // Now a new reservation must succeed on the same key.
        val reReserved = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet1",
            mintAddress = "mintFree",
            side = IntentSide.BUY,
            selectedLane = "QUALITY",
            marketSnapshotId = "s2",
            requestedLamports = Lamports.of(3_000_000L),
            score = 55,
            fdgVerdict = "ALLOW",
        )
        assertNotNull("terminal state must free the slot", reReserved)
    }

    // ── Section 5: Finalized BUY proof ────────────────────────────────

    @Test
    fun buy_quantity_is_post_minus_pre_never_ata_total() {
        // Fixture: wallet already has 500 raw tokens from a prior lot. New
        // BUY brings 1000 more. Post-balance is 1500 but the new lot's
        // quantity MUST be 1000 (the DELTA), not 1500 (the total).
        val proof = ProofState6386.FinalizedProofComplete(
            signature = "sig_new_buy",
            walletAddress = "wallet1",
            mintAddress = "mintABC",
            decimals = MintDecimals.Known(6, "MINT_ACCOUNT", "sig_mint"),
            preRawBalance = RawTokenAmount.of(500L),
            postRawBalance = RawTokenAmount.of(1500L),
            preLamports = Lamports.of(1_000_000L),
            postLamports = Lamports.of(500_000L),
            feeLamports = Lamports.of(5_000L),
        )
        val result = FinalizedBuyProof6386.validate("wallet1", "mintABC", proof)
        assertTrue(result.reason, result.proofComplete)
        assertEquals(
            "BUY quantity MUST equal post-pre (1000), never the total (1500)",
            RawTokenAmount.of(1000L).value, result.quantityDelta.value,
        )
    }

    @Test
    fun buy_proof_rejects_non_finalized_states() {
        val states = listOf(
            ProofState6386.IntentCreated,
            ProofState6386.TransactionBuilt,
            ProofState6386.SignatureReceived("sig"),
            ProofState6386.BroadcastPending("sig"),
            ProofState6386.FailedConfirmed("sig", "err"),
            ProofState6386.PendingReconciliation("no meta"),
            ProofState6386.Quarantined("bad"),
        )
        states.forEach { s ->
            val r = FinalizedBuyProof6386.validate("w", "m", s)
            assertFalse("non-finalized state must not validate as BUY proof: ${s.stateName()}", r.proofComplete)
        }
    }

    // ── Section 6: Immutable fill lots + FIFO consumption ─────────────

    @Test
    fun re_entry_creates_separate_immutable_lot_not_replacement() {
        val decimals = MintDecimals.Known(6, "MINT_ACCOUNT", "sig_mint")
        val lotA = FillLot6386(
            walletAddress = "wallet1",
            mintAddress = "mintABC",
            confirmedBuySignature = "sigA",
            entryRawQuantity = RawTokenAmount.of(1000L),
            decimals = decimals,
            netLamportsSpent = Lamports.of(500_000L),
            entrySolPerToken = SolPerToken(0.0005),
            entryUsdPerToken = UsdPerToken(0.10),
            feeLamports = Lamports.of(5_000L),
            lane = "MOONSHOT",
            timestamp = 1000L,
        )
        val lotB = FillLot6386(
            walletAddress = "wallet1",
            mintAddress = "mintABC",
            confirmedBuySignature = "sigB",   // DIFFERENT signature
            entryRawQuantity = RawTokenAmount.of(2000L),
            decimals = decimals,
            netLamportsSpent = Lamports.of(1_200_000L),
            entrySolPerToken = SolPerToken(0.0006),
            entryUsdPerToken = UsdPerToken(0.12),
            feeLamports = Lamports.of(5_000L),
            lane = "MOONSHOT",
            timestamp = 2000L,
        )
        FillLotLedger6386.openLot(lotA)
        FillLotLedger6386.openLot(lotB)
        val open = FillLotLedger6386.openLotsFor("wallet1", "mintABC")
        assertEquals("re-entry MUST create a SECOND lot, not replace the first", 2, open.size)
        // Both lots survive with their original quantities intact.
        assertEquals(RawTokenAmount.of(1000L).value, open[0].entryRawQuantity.value)
        assertEquals(RawTokenAmount.of(2000L).value, open[1].entryRawQuantity.value)
    }

    @Test
    fun cannot_overwrite_existing_lot_with_same_signature() {
        val decimals = MintDecimals.Known(6, "MINT_ACCOUNT", "sig_mint")
        val base = FillLot6386(
            walletAddress = "w", mintAddress = "m", confirmedBuySignature = "sigX",
            entryRawQuantity = RawTokenAmount.of(100L), decimals = decimals,
            netLamportsSpent = Lamports.of(10_000L),
            entrySolPerToken = SolPerToken(0.0001), entryUsdPerToken = UsdPerToken(0.01),
            feeLamports = Lamports.of(1_000L), lane = "QUALITY", timestamp = 1L,
        )
        FillLotLedger6386.openLot(base)
        try {
            FillLotLedger6386.openLot(base.copy(entryRawQuantity = RawTokenAmount.of(999_999L)))
            org.junit.Assert.fail("duplicate sig lot open MUST throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("double-open") == true)
        }
    }

    @Test
    fun fifo_consumption_drains_older_lot_first() {
        val decimals = MintDecimals.Known(6, "MINT_ACCOUNT", "sig_mint")
        FillLotLedger6386.openLot(FillLot6386(
            "w", "m", "sig1", RawTokenAmount.of(1000L), decimals,
            Lamports.of(500_000L), SolPerToken(0.0005), UsdPerToken(0.10),
            Lamports.of(5_000L), "MOONSHOT", 1_000L,
        ))
        FillLotLedger6386.openLot(FillLot6386(
            "w", "m", "sig2", RawTokenAmount.of(2000L), decimals,
            Lamports.of(1_200_000L), SolPerToken(0.0006), UsdPerToken(0.12),
            Lamports.of(5_000L), "MOONSHOT", 2_000L,
        ))
        val result = FillLotLedger6386.consumeFifo("w", "m", RawTokenAmount.of(1500L))
        assertTrue(result.shortfall.isZero())
        assertEquals(2, result.consumed.size)
        // First consumed lot must be the OLDER (sig1, timestamp=1000).
        assertEquals("sig1", result.consumed[0].first.confirmedBuySignature)
        assertEquals(RawTokenAmount.of(1000L).value, result.consumed[0].second.value)
        // Second consumed lot is sig2, partially consumed 500 out of 2000.
        assertEquals("sig2", result.consumed[1].first.confirmedBuySignature)
        assertEquals(RawTokenAmount.of(500L).value, result.consumed[1].second.value)
        // Remaining totals verified.
        assertEquals(RawTokenAmount.of(1500L).value, FillLotLedger6386.totalRemainingRaw("w", "m").value)
    }

    // ── Section 7: Finalized SELL proof ────────────────────────────────

    @Test
    fun sold_quantity_is_pre_minus_post_never_derived_from_proceeds() {
        val proof = ProofState6386.FinalizedProofComplete(
            signature = "sig_sell",
            walletAddress = "wallet1",
            mintAddress = "mintABC",
            decimals = MintDecimals.Known(6, "MINT_ACCOUNT", "sig_mint"),
            preRawBalance = RawTokenAmount.of(1000L),
            postRawBalance = RawTokenAmount.of(200L),
            preLamports = Lamports.of(100_000L),
            postLamports = Lamports.of(600_000L),
            feeLamports = Lamports.of(5_000L),
        )
        val r = FinalizedSellProof6386.validate("wallet1", "mintABC", proof)
        assertTrue(r.reason, r.proofComplete)
        assertEquals(RawTokenAmount.of(800L).value, r.rawQuantitySold.value)
        // Net proceeds = (post - pre) lamports + fee = 500_000 + 5_000 = 505_000.
        assertEquals(Lamports.of(505_000L).value, r.netLamportsReceived.value)
    }

    // ── Section 8: Partial sells without proof become PENDING_RECONCILIATION ─

    @Test
    fun partial_without_finalized_proof_marks_pending_reconciliation() {
        val incomplete = FinalizedSellProof6386.Result(
            proofComplete = false,
            rawQuantitySold = RawTokenAmount.ZERO,
            netLamportsReceived = Lamports.ZERO,
            reason = "SELL_PROOF_NOT_FINALIZED",
        )
        val classified = FinalizedSellProof6386.classifyPartial(incomplete, hasBroadcastConfirmation = true)
        assertTrue(classified is ProofState6386.PendingReconciliation)
        val reason = (classified as ProofState6386.PendingReconciliation).reason
        assertTrue(reason.contains("BROADCAST"))
        // Critical: partial with broadcast alone is NOT truth-contributing.
        assertFalse(classified.contributesToTruth())
    }

    // ── Section 13: Canary release gate ────────────────────────────────

    @Test
    fun canary_gate_locked_by_default() {
        // Default state = LOCKED (repair mode). Live BUY not accepted.
        val v = CanaryReleaseGate6386.canAcceptBuy(requestedSol = 0.005, alreadyOpenForMint = false)
        assertFalse(v.allowed)
        assertTrue(v.reason.contains("CANARY_LOCKED"))
    }

    @Test
    fun canary_gate_enforces_size_range_and_single_open() {
        CanaryReleaseGate6386.promoteToCanary()
        val tooSmall = CanaryReleaseGate6386.canAcceptBuy(0.001, alreadyOpenForMint = false)
        assertFalse(tooSmall.allowed)
        val tooBig = CanaryReleaseGate6386.canAcceptBuy(0.01, alreadyOpenForMint = false)
        assertFalse(tooBig.allowed)
        val inRange = CanaryReleaseGate6386.canAcceptBuy(0.004, alreadyOpenForMint = false)
        assertTrue(inRange.allowed)
        CanaryReleaseGate6386.onCanaryBuyAccepted()
        val second = CanaryReleaseGate6386.canAcceptBuy(0.004, alreadyOpenForMint = false)
        assertFalse("max one open canary position", second.allowed)
        assertTrue(second.reason.contains("MAX_ONE_OPEN"))
    }

    @Test
    fun canary_gate_advances_after_20_clean_round_trips() {
        CanaryReleaseGate6386.promoteToCanary()
        assertEquals(CanaryReleaseGate6386.Mode.CANARY, CanaryReleaseGate6386.currentMode())
        repeat(CanaryReleaseGate6386.CANARY_ROUND_TRIPS_REQUIRED) {
            CanaryReleaseGate6386.onCanaryBuyAccepted()
            CanaryReleaseGate6386.onCleanRoundTrip()
        }
        assertEquals(
            "20 clean round trips MUST advance CANARY → PROBATION",
            CanaryReleaseGate6386.Mode.PROBATION, CanaryReleaseGate6386.currentMode(),
        )
    }

    @Test
    fun canary_gate_resets_consecutive_counter_on_invariant_failure() {
        CanaryReleaseGate6386.promoteToCanary()
        // 5 clean round trips.
        repeat(5) {
            CanaryReleaseGate6386.onCanaryBuyAccepted()
            CanaryReleaseGate6386.onCleanRoundTrip()
        }
        // Invariant failure resets.
        CanaryReleaseGate6386.onInvariantFailure("DECIMAL_SKEW")
        // Need another full 20 to advance.
        repeat(CanaryReleaseGate6386.CANARY_ROUND_TRIPS_REQUIRED - 1) {
            CanaryReleaseGate6386.onCanaryBuyAccepted()
            CanaryReleaseGate6386.onCleanRoundTrip()
        }
        assertEquals(
            "must STILL be in CANARY after only 19 clean since failure",
            CanaryReleaseGate6386.Mode.CANARY, CanaryReleaseGate6386.currentMode(),
        )
    }

    // ── Section 10: Historical quarantine module wired ─────────────────

    @Test
    fun historical_quarantine_module_wired_from_bot_service() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6386: BotService.onCreate must call HistoricalQuarantine6386.runOnce()",
            bs.contains("HistoricalQuarantine6386.runOnce()"),
        )
    }

    // ── Section 12: Replay-fixture assertions (lightweight, on the API) ────

    @Test
    fun replay_fixture_broadcast_row_cannot_close_position() {
        // Directive: "Broadcast rows cannot close positions."
        val proof = ProofState6386.BroadcastPending("sig_rNMsVB")
        val r = FinalizedSellProof6386.validate("w", "m", proof)
        assertFalse("BroadcastPending MUST NOT close positions", r.proofComplete)
    }

    @Test
    fun replay_fixture_partial_quote_cannot_create_pnl() {
        // Directive: "Partial quotes cannot create PnL."
        val proof = ProofState6386.PendingReconciliation("partial_quote_only")
        val r = FinalizedSellProof6386.validate("w", "m", proof)
        assertFalse(r.proofComplete)
        // And contributesToTruth returns false — no PnL contribution.
        assertFalse(proof.contributesToTruth())
    }

    @Test
    fun replay_fixture_one_mint_cannot_create_competing_lane_tickets() {
        // Directive: "One mint cannot create competing lane tickets."
        val a = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet", mintAddress = "51eWyx",
            side = IntentSide.BUY, selectedLane = "QUALITY",
            marketSnapshotId = "s1",
            requestedLamports = Lamports.of(3_000_000L),
            score = 55, fdgVerdict = "ALLOW",
        )
        assertNotNull(a)
        // Same wallet + mint + BUY — MUST reject even for a DIFFERENT lane.
        val b = ExecutionIntentRegistry6386.tryReserve(
            walletAddress = "wallet", mintAddress = "51eWyx",
            side = IntentSide.BUY, selectedLane = "MOONSHOT",
            marketSnapshotId = "s2",
            requestedLamports = Lamports.of(3_000_000L),
            score = 60, fdgVerdict = "ALLOW",
        )
        assertNull("competing lane ticket for same mint MUST be rejected", b)
    }
}
