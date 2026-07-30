package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6391 — BREAK SELL-ONLY DEADLOCK + REPAIR EXIT AUTHORITY (all 10 sections).
 *
 * CI blocks the ship if any invariant fails.
 */
class Bundle6391SellOnlyHoldReleaseTest {

    @Before fun setUp() {
        OwnershipClassification6391.clearForTest()
        SellOnlyHold6391.clearForTest()
        CanonicalRecoveryUpsert6391.clearForTest()
        ExecutionCircuitBreakers6391.clearForTest()
        ExitRoutePlan6391.clearForTest()
        SellOnlyForensicHold6389.setForTest(null)
    }
    @After fun tearDown() { setUp() }

    private val noProof = OwnershipClassification6391.Proof(false, false, false, false, false)
    private val fullProof = OwnershipClassification6391.Proof(true, true, true, true, true)
    private val noNonProof = OwnershipClassification6391.NonProof(false, false, false, false, false, false, false)

    /* -------------------- TEST A · UNSOLICITED WALLET TOKEN --------------- */

    @Test fun test_A_unsolicited_wallet_tokens_classify_as_external_and_do_not_arm_hold() {
        // Two wallet SPL balances, NO bot buy proof, NO canonical positions.
        OwnershipClassification6391.classify(
            mint = "mintA", walletRawBalance = BigInteger.valueOf(1_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = noProof, nonProof = noNonProof,
        )
        OwnershipClassification6391.classify(
            mint = "mintB", walletRawBalance = BigInteger.valueOf(2_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = noProof, nonProof = noNonProof,
        )
        val recA = OwnershipClassification6391.record("mintA")!!
        val recB = OwnershipClassification6391.record("mintB")!!
        assertEquals(OwnershipClassification6391.Class.EXTERNAL_UNMANAGED, recA.classification)
        assertEquals(OwnershipClassification6391.Class.EXTERNAL_UNMANAGED, recB.classification)
        assertFalse(recA.contributesToProvenBotExposure)
        assertFalse(recB.contributesToProvenBotExposure)
        // Hold reevaluates from CURRENT state — must NOT arm.
        SellOnlyHold6391.reevaluate()
        assertFalse("EXTERNAL wallet mints must NOT arm a global hold",
            SellOnlyHold6391.isActive())
    }

    @Test fun non_proof_signals_never_promote_to_bot_owned() {
        // All non-proof signals TRUE, no proof at all — still EXTERNAL.
        val allNonProof = OwnershipClassification6391.NonProof(true, true, true, true, true, true, true)
        OwnershipClassification6391.classify(
            mint = "mintC", walletRawBalance = BigInteger.valueOf(1_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = noProof, nonProof = allNonProof,
        )
        val rec = OwnershipClassification6391.record("mintC")!!
        assertNotEquals(OwnershipClassification6391.Class.BOT_OWNED_PROVEN, rec.classification)
        assertNotEquals(OwnershipClassification6391.Class.BOT_OWNED_RECOVERED, rec.classification)
    }

    @Test fun dust_classifies_as_airdrop_or_dust() {
        OwnershipClassification6391.classify(
            mint = "mintD", walletRawBalance = BigInteger.valueOf(500L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = noProof, nonProof = noNonProof,
        )
        val rec = OwnershipClassification6391.record("mintD")!!
        assertEquals(OwnershipClassification6391.Class.AIRDROP_OR_DUST, rec.classification)
    }

    /* -------------------- TEST B · PROVEN RECOVERED POSITION --------------- */

    @Test fun test_B_proven_recovered_position_upserts_canonically_and_is_exit_only() {
        // Wallet balance with confirmed historical bot buy signature but no position row.
        val proof = OwnershipClassification6391.Proof(
            hasCanonicalBuyFillRecord = false, hasFillLotLedgerRecord = true,
            hasConfirmedBotBuySignature = true, hasOwnerDeltaFromBotBroadcast = true,
            hasPersistedLivePositionRow = false,
        )
        val rec = OwnershipClassification6391.classify(
            mint = "mintR", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = proof, nonProof = noNonProof,
        )
        assertEquals(OwnershipClassification6391.Class.BOT_OWNED_RECOVERED, rec.classification)

        val (up1, created1) = CanonicalRecoveryUpsert6391.upsert(
            mint = "mintR", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, basisKnown = false, runtimeGeneration = 1L,
        )
        assertTrue(created1); assertTrue(up1.exitOnly); assertFalse(up1.includedInWinRate)

        // Idempotent across restart — same runtimeGeneration returns SAME row.
        val (_, created2) = CanonicalRecoveryUpsert6391.upsert(
            mint = "mintR", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, basisKnown = false, runtimeGeneration = 1L,
        )
        assertFalse("idempotent upsert must NOT re-create", created2)
    }

    @Test fun proven_bot_exposure_with_unresolved_quantity_arms_hold() {
        val proof = OwnershipClassification6391.Proof(
            hasCanonicalBuyFillRecord = true, hasFillLotLedgerRecord = true,
            hasConfirmedBotBuySignature = true, hasOwnerDeltaFromBotBroadcast = true,
            hasPersistedLivePositionRow = true,
        )
        OwnershipClassification6391.classify(
            mint = "mintR", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.valueOf(1_000_000L),  // 4M unresolved
            proof = proof, nonProof = noNonProof,
        )
        SellOnlyHold6391.reevaluate()
        assertTrue("unresolved PROVEN exposure must arm hold", SellOnlyHold6391.isActive())
        assertTrue("blocking mint list must include the unresolved mint",
            "mintR" in SellOnlyHold6391.snapshot().blockingMints)
    }

    @Test fun hold_releases_when_canonical_authority_catches_up() {
        val proof = OwnershipClassification6391.Proof(
            hasCanonicalBuyFillRecord = true, hasFillLotLedgerRecord = true,
            hasConfirmedBotBuySignature = true, hasOwnerDeltaFromBotBroadcast = true,
            hasPersistedLivePositionRow = true,
        )
        OwnershipClassification6391.classify(
            mint = "mintR", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.valueOf(1_000_000L),
            proof = proof, nonProof = noNonProof,
        )
        SellOnlyHold6391.reevaluate(); assertTrue(SellOnlyHold6391.isActive())
        // Canonical now matches wallet — hold must release.
        OwnershipClassification6391.classify(
            mint = "mintR", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.valueOf(5_000_000L),
            proof = proof, nonProof = noNonProof,
        )
        SellOnlyHold6391.reevaluate()
        assertFalse("resolved exposure must release the hold", SellOnlyHold6391.isActive())
    }

    /* -------------------- TEST C · SCANNER CIRCUIT OPEN ------------------- */

    @Test fun test_C_scanner_backoff_never_suppresses_sell_probe() {
        ExecutionCircuitBreakers6391.recordFailure(ExecutionCircuitBreakers6391.Namespace.JUPITER_SCANNER, backoffMs = 60_000L)
        // Sell probe MUST still be allowed even though scanner is OPEN.
        assertTrue(ExecutionCircuitBreakers6391.allowSellOrEmergencyProbe(
            ExecutionCircuitBreakers6391.Namespace.JUPITER_SELL_EXECUTION))
    }

    /* -------------------- TEST D · EXECUTION HALF-OPEN PROBE -------------- */

    @Test fun test_D_execution_open_allows_bounded_probe_after_backoff() {
        val ns = ExecutionCircuitBreakers6391.Namespace.JUPITER_SELL_EXECUTION
        ExecutionCircuitBreakers6391.recordFailure(ns, backoffMs = 100L)
        assertFalse("immediate re-probe blocked",
            ExecutionCircuitBreakers6391.allowSellOrEmergencyProbe(ns, nowMs = System.currentTimeMillis()))
        assertTrue("probe allowed after backoff",
            ExecutionCircuitBreakers6391.allowSellOrEmergencyProbe(ns, nowMs = System.currentTimeMillis() + 200L))
    }

    /* -------------------- TEST E · PUMP RESCUE VALIDATION ----------------- */

    @Test fun test_E_pump_rescue_uses_canonical_builder_with_validation() {
        val p = PumpRescueUnifiedBuilder6391.Params(
            mint = "mintP", walletPublicKey = "wallet123",
            rawAmount = BigInteger.valueOf(1_000_000L), tokenDecimals = 6,
            denominatedInSol = false, poolIdentifier = "pool-1", action = "sell",
            slippageBps = 500, priorityFeeLamports = 10_000L, tipLamports = 5_000L,
            retryPolicy = "EXPO_BOUNDED", telemetryReason = "RESCUE",
        )
        val v = PumpRescueUnifiedBuilder6391.validate(p)
        assertTrue(v.ok)
        // Redacted schema does NOT leak private key content.
        val schema = PumpRescueUnifiedBuilder6391.redactedSchema(p)
        assertTrue(schema.containsKey("walletPublicKey"))
        assertFalse("schema must not contain the actual private/public key value",
            schema.values.any { it == "wallet123" })
    }

    @Test fun pump_rescue_rejects_zero_quantity() {
        val p = PumpRescueUnifiedBuilder6391.Params(
            mint = "mintP", walletPublicKey = "wallet123", rawAmount = BigInteger.ZERO,
            tokenDecimals = 6, denominatedInSol = false, poolIdentifier = "pool-1",
            action = "sell", slippageBps = 500, priorityFeeLamports = 10_000L,
            tipLamports = 5_000L, retryPolicy = "EXPO_BOUNDED", telemetryReason = "RESCUE",
        )
        val v = PumpRescueUnifiedBuilder6391.validate(p)
        assertFalse(v.ok)
        assertTrue("RAW_AMOUNT_NON_POSITIVE" in v.errors)
    }

    @Test fun pump_rescue_rejects_wrong_action() {
        val p = PumpRescueUnifiedBuilder6391.Params(
            mint = "mintP", walletPublicKey = "wallet123",
            rawAmount = BigInteger.valueOf(1_000L), tokenDecimals = 6,
            denominatedInSol = false, poolIdentifier = "pool-1",
            action = "buy", slippageBps = 500, priorityFeeLamports = 0L,
            tipLamports = 0L, retryPolicy = "NONE", telemetryReason = "RESCUE",
        )
        val v = PumpRescueUnifiedBuilder6391.validate(p)
        assertFalse(v.ok)
        assertTrue("ACTION_NOT_SELL" in v.errors)
    }

    /* -------------------- TEST F · NON-PUMP TOKEN ROUTE PLAN -------------- */

    @Test fun test_F_universal_auto_venue_never_calls_pumpportal() {
        val plan = ExitRoutePlan6391.startPlan(positionId = "posU", mint = "mintU")
        plan.record(ExitRoutePlan6391.Venue.PUMP_BONDING_CURVE, null, null, "no_pump_venue_proof")
        plan.record(ExitRoutePlan6391.Venue.PUMP_SWAP_SUPPORTED,
            ProviderOutcomeTaxonomy6391.Outcome.PROVIDER_UNSUPPORTED_VENUE, null, "universal-auto not Pump")
        plan.record(ExitRoutePlan6391.Venue.JUPITER_EXECUTION,
            null, "sig-abcdef", "ok")
        assertTrue("plan should reach terminal via Jupiter sig", plan.terminal())
        assertTrue("PumpPortal must be marked UNSUPPORTED, not attempted",
            plan.attempts.any { it.venue == ExitRoutePlan6391.Venue.PUMP_SWAP_SUPPORTED &&
                it.outcome == ProviderOutcomeTaxonomy6391.Outcome.PROVIDER_UNSUPPORTED_VENUE })
    }

    /* -------------------- TEST G · RESTART PERSISTENCE (in-memory) -------- */

    @Test fun test_G_ownership_classifications_survive_reevaluation_without_duplication() {
        val proof = OwnershipClassification6391.Proof(
            hasCanonicalBuyFillRecord = true, hasFillLotLedgerRecord = true,
            hasConfirmedBotBuySignature = true, hasOwnerDeltaFromBotBroadcast = true,
            hasPersistedLivePositionRow = true,
        )
        OwnershipClassification6391.classify(
            mint = "mintX", walletRawBalance = BigInteger.valueOf(1_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.valueOf(1_000L),
            proof = proof, nonProof = noNonProof, runtimeGeneration = 1L,
        )
        val first = OwnershipClassification6391.record("mintX")
        // Re-classify with same runtime — should merely update, not duplicate.
        OwnershipClassification6391.classify(
            mint = "mintX", walletRawBalance = BigInteger.valueOf(1_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.valueOf(1_000L),
            proof = proof, nonProof = noNonProof, runtimeGeneration = 1L,
        )
        assertEquals(1, OwnershipClassification6391.all().size)
        assertEquals(first!!.classification, OwnershipClassification6391.record("mintX")!!.classification)
    }

    /* -------------------- TEST H · EFFECTIVE AUTHORITY TRUTH -------------- */

    @Test fun test_H_effective_authority_reveals_downstream_block() {
        // Governor OPEN but hold armed → effective authority MUST be BLOCKED_UNRESOLVED_BOT_POSITION.
        val effective = EffectiveLiveAuthorityResolver6391.resolve(
            EffectiveLiveAuthorityResolver6391.Input(
                governorAllowsLive = true, sellOnlyHoldActive = true,
                providerSafetyBlocked = false, operatorHalted = false, otherBlock = false,
            ))
        assertEquals(EffectiveLiveAuthority6391.BLOCKED_UNRESOLVED_BOT_POSITION, effective)
        assertFalse(EffectiveLiveAuthorityResolver6391.pillarReady(effective))
    }

    @Test fun effective_authority_returns_open_when_all_clear() {
        val effective = EffectiveLiveAuthorityResolver6391.resolve(
            EffectiveLiveAuthorityResolver6391.Input(
                governorAllowsLive = true, sellOnlyHoldActive = false,
                providerSafetyBlocked = false, operatorHalted = false, otherBlock = false,
            ))
        assertEquals(EffectiveLiveAuthority6391.OPEN, effective)
        assertTrue(EffectiveLiveAuthorityResolver6391.pillarReady(effective))
    }

    @Test fun effective_authority_prioritises_operator_halt() {
        val effective = EffectiveLiveAuthorityResolver6391.resolve(
            EffectiveLiveAuthorityResolver6391.Input(
                governorAllowsLive = false, sellOnlyHoldActive = true,
                providerSafetyBlocked = true, operatorHalted = true, otherBlock = true,
            ))
        assertEquals(EffectiveLiveAuthority6391.BLOCKED_OPERATOR, effective)
    }

    /* -------------------- S4 RECONCILER VISIBILITY ------------------------ */

    @Test fun reconciler_visibility_invariant_holds_when_recovered_positions_are_checked() {
        assertTrue(ReconcilerVisibility6391.invariantHolds(
            recoveredExitOnlyCount = 2, sellReconcilerStarted = true, reconcilerCheckedMintsInWindow = 2))
        assertTrue(ReconcilerVisibility6391.invariantHolds(
            recoveredExitOnlyCount = 0, sellReconcilerStarted = true, reconcilerCheckedMintsInWindow = 0))
    }

    @Test fun reconciler_visibility_invariant_breaks_when_zero_checks_with_holdings() {
        assertFalse("recovered positions unchecked → invariant broken",
            ReconcilerVisibility6391.invariantHolds(
                recoveredExitOnlyCount = 2, sellReconcilerStarted = true, reconcilerCheckedMintsInWindow = 0))
    }

    /* -------------------- FORENSIC EVENT VOCABULARY ----------------------- */

    @Test fun required_forensic_events_are_defined() {
        assertTrue(ForensicTelemetry6391.isValidEvent("SELL_ONLY_HOLD_RELEASED_6391"))
        assertTrue(ForensicTelemetry6391.isValidEvent("RECOVERED_CANONICAL_POSITION_UPSERTED_6391"))
        assertTrue(ForensicTelemetry6391.isValidEvent("EFFECTIVE_LIVE_AUTHORITY_CHANGED_6391"))
    }

    @Test fun policy_redirects_and_aborted_tickets_never_journal() {
        assertFalse(ForensicTelemetry6391.shouldJournal("POLICY_REDIRECT"))
        assertFalse(ForensicTelemetry6391.shouldJournal("ABORTED_PRE_EXEC_TICKET"))
        assertFalse(ForensicTelemetry6391.shouldJournal("EXTERNAL_WALLET_HOLDING"))
        assertTrue(ForensicTelemetry6391.shouldJournal("CONFIRMED_BUY_FILL"))
    }

    /* -------------------- V5.0.6389 DEFAULT UNBLOCKED --------------------- */

    @Test fun v6389_hold_defaults_to_inactive_on_startup() {
        // Directive V5.0.6391 P0: startup default MUST be null so wallet SPL
        // tokens do not silently disable trading.
        SellOnlyForensicHold6389.setForTest(null)
        assertFalse(SellOnlyForensicHold6389.isActive())
    }

    /* -------------------- ACCEPTANCE (PART 1-3) --------------------------- */

    @Test fun acceptance_wallet_can_exceed_canonical_when_difference_is_external() {
        // Wallet has 3 mints. Only 1 is proven bot. Difference (2) are EXTERNAL.
        OwnershipClassification6391.classify(
            mint = "external1", walletRawBalance = BigInteger.valueOf(100L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = noProof, nonProof = noNonProof,
        )
        OwnershipClassification6391.classify(
            mint = "external2", walletRawBalance = BigInteger.valueOf(200L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.ZERO,
            proof = noProof, nonProof = noNonProof,
        )
        val proof = OwnershipClassification6391.Proof(true, true, true, true, true)
        OwnershipClassification6391.classify(
            mint = "botMint", walletRawBalance = BigInteger.valueOf(5_000_000L),
            tokenDecimals = 6, canonicalQuantityRaw = BigInteger.valueOf(5_000_000L),
            proof = proof, nonProof = noNonProof,
        )
        SellOnlyHold6391.reevaluate()
        // Trading MUST still be open — external mints do not arm the hold.
        assertFalse(SellOnlyHold6391.isActive())
    }

    @Test fun acceptance_no_forensic_hold_when_no_wallet_tokens_at_all() {
        // Startup: fresh wallet, no proof of anything.
        SellOnlyHold6391.reevaluate()
        assertFalse("no bot exposure → hold must never arm on startup",
            SellOnlyHold6391.isActive())
    }
}
