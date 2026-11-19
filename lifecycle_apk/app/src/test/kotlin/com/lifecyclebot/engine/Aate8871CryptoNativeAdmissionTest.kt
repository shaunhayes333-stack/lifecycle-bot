package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CandidateAdmission8136 as Admission
import com.lifecyclebot.engine.truth.CanonicalAssetEntryCandidate6551
import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.perps.CryptoNativeSignal8871
import com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803 as Books
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A real classic qualification survives handoff; it does not create a statistical edge or waive execution safety. */
class Aate8871CryptoNativeAdmissionTest {
    private val now = 1_790_000_000_000L
    private val mint = "DezXAZ8z7PnrnRJjz3wXBoRgixCa5XyAVBqZq7B1r"
    private val asset = "solana|$mint"
    private val version = 887101L

    private fun witness() = CryptoNativeSignal8871.capture(asset, "MOMENTUM", "LONG", version,
        80, 65, 80, 70, now - 2_000L, 0.002, now - 3_000L)!!

    private fun canonical(w: CryptoNativeSignal8871.Witness? = witness()) = CanonicalAssetEntryCandidate6551(
        assetId = asset, symbol = "NATIVE8871", assetClass = AssetClass.CRYPTO_ALT, mode = "LIVE", direction = "LONG",
        requestedVenue = "SOLANA_SPOT", adapter = "CRYPTO_UNIVERSE_EXECUTOR", source = "CRYPTO_NATIVE",
        specialist = "MOMENTUM", score = 80.0, confidence = 0.80,
        evidence = mapOf("strategy7803" to "MOMENTUM", "upstreamConfidence" to "80", "deskLane7391" to "CORE"),
        requestedSizeSol = 0.08, price = 0.002, liquidityUsd = 200_000.0, candidateVersion = version,
        priceObservedAtMs8870 = now - 1_000L, priceSource8870 = "DEXSCREENER", priceAssetId8870 = asset,
        cryptoNativeSignal8871 = w,
    )

    private fun ready(w: CryptoNativeSignal8871.Witness = witness()) = Books.Entry(
        assetKey = w.assetId, symbol = "NATIVE8871", strategy = w.strategy, candidateVersion = w.candidateVersion,
        state = Books.State.READY, score = w.score, confidence = w.confidence, conviction = 80.0,
        reason = "CRYPTO_BRAIN_EXECUTABLE", firstSeenMs = now - 5_000L, updatedAtMs = now - 2_000L,
    )

    private fun validate(c: CanonicalAssetEntryCandidate6551 = canonical(), row: Books.Entry? = ready(), at: Long = now) =
        CryptoNativeSignal8871.validate(c, at) { _, _ -> listOfNotNull(row) }

    private fun candidate() = Admission.Candidate8136(
        mint = mint, lane = "CRYPTO_CORE", selectedSetup = null, priceAgeMs = 1_000L,
        expectedGrossPct = 12.0, costPct = 2.0, costSizeSol = 0.08, costBreakdown = "actual proposal quote",
        candidateVersion = version, live = true,
        // An independently observed positive edge already supplied by the ordinary live adapter.
        // The native handoff must preserve this input, and must never synthesize it for an unproven signal.
        liveEdge8114 = "CHART_BUY",
    )

    private fun decide(c: Admission.Candidate8136) = LaneAuthority8577.apply8577(c, Admission.decide8136(c))

    @Test fun genuine_classic_ready_and_existing_edge_reach_actual_live_admission() {
        val before = candidate()
        assertEquals("NO_SUPPORTED_EDGE_8136", decide(before).reason)
        val after = Admission.withCryptoNative8871(before, validate(), now)
        val decision = decide(after)
        assertEquals(Admission.Terminal8136.ADMIT, decision.terminal)
        assertEquals("NATIVE_QUALIFIED_ADMIT_8259", decision.reason)
        assertEquals("MOMENTUM", decision.chosenSetup)
        assertFalse("Qualification must not claim statistical proof", decision.specific)
        assertTrue(decision.laneQualified8577)
        assertEquals(before.liveEdge8114, after.liveEdge8114)
        assertEquals(before.expectedGrossPct, after.expectedGrossPct, 0.0)
        assertEquals(10.0, decision.expectedNetPct, 0.0)
        assertEquals(0.08, decision.costSizeSol, 0.0)
        assertEquals(before.costBreakdown, decision.costBreakdown)
    }

    @Test fun ready_alone_cannot_invent_positive_live_evidence() {
        val c = Admission.withCryptoNative8871(candidate().copy(liveEdge8114 = null), validate(), now)
        val decision = decide(c)
        assertEquals(Admission.Terminal8136.REFUSE, decision.terminal)
        assertEquals(Admission.LIVE_NATIVE_OPINION_UNPROVEN_8536, decision.reason)
        assertNull(c.liveEdge8114)
        assertTrue(c.setups.isEmpty())
        assertNull(c.cell)
    }

    @Test fun wrong_identity_generation_strategy_side_or_changed_score_cannot_inherit_ready() {
        val c = canonical()
        val invalid = listOf(
            c.copy(assetId = "ethereum|0x0000000000000000000000000000000000000001"),
            c.copy(candidateVersion = version + 1L), c.copy(direction = "SHORT"), c.copy(assetClass = AssetClass.STOCK),
            c.copy(evidence = c.evidence + ("strategy7803" to "PULLBACK")),
            c.copy(evidence = c.evidence + ("upstreamConfidence" to "79")), c.copy(score = 40.0),
            c.copy(cryptoNativeSignal8871 = null),
        )
        for (bad in invalid) {
            assertNull("Invalid handoff inherited READY: $bad", validate(bad))
            assertFalse(decide(Admission.withCryptoNative8871(candidate(), validate(bad), now)).admitted)
        }
        assertNull(validate(c, ready().copy(candidateVersion = version + 1L)))
        assertNull(validate(c, ready().copy(assetKey = "ethereum|$mint")))
        assertNull(validate(c, ready().copy(strategy = "PULLBACK")))
        assertNull(validate(c, ready().copy(score = 79)))
    }

    @Test fun absent_expired_future_or_unready_book_cannot_inherit_ready() {
        assertNull(validate(row = null))
        assertNull(validate(row = ready().copy(state = Books.State.WATCHING)))
        assertNull(validate(row = ready().copy(updatedAtMs = now - Books.READY_TTL_MS - 1L)))
        assertNull(validate(row = ready().copy(updatedAtMs = now + 1L)))
        assertNull(validate(at = now + Books.READY_TTL_MS))
        assertNull(validate(canonical(witness().copy(qualifiedAtMs = now + 1L))))
        assertNull(validate(canonical(witness().copy(signalObservedAtMs = 0L))))
        assertNull(validate(canonical(witness().copy(signalObservedAtMs = now + 1L))))
        assertNull(validate(canonical(witness().copy(signalObservedAtMs = now - 300_001L))))
        val q = validate()!!
        assertEquals(candidate(), Admission.withCryptoNative8871(candidate(), q, now + Books.READY_TTL_MS))
        val newer = candidate().copy(candidateVersion = version + 1L)
        assertEquals(newer, Admission.withCryptoNative8871(newer, q, now))
        val other = candidate().copy(mint = "11111111111111111111111111111111")
        assertEquals(other, Admission.withCryptoNative8871(other, q, now))
    }

    @Test fun classic_capture_requires_actual_existing_floors_and_never_promotes_desk_overlays() {
        assertNull(CryptoNativeSignal8871.capture(asset, "MOMENTUM", "LONG", version,
            64, 65, 80, 70, now, 0.002, now))
        assertNull(CryptoNativeSignal8871.capture(asset, "MOMENTUM", "LONG", version,
            80, 65, 69, 70, now, 0.002, now))
        assertNull(CryptoNativeSignal8871.capture(asset, "DESK_MOONSHOT", "LONG", version,
            80, 65, 80, 70, now, 0.002, now))
        assertNull(CryptoNativeSignal8871.capture(asset, "MOMENTUM", "SHORT", version,
            80, 65, 80, 70, now, 0.002, now))
        assertNull(CryptoNativeSignal8871.capture(asset, "MOMENTUM", "LONG", 0L,
            80, 65, 80, 70, now, 0.002, now))
    }

    @Test fun existing_feed_eligibility_is_not_replaced_by_a_fifteen_second_admission_choke() {
        val w = witness().copy(signalObservedAtMs = now - 20_000L)
        val q = validate(canonical(w), ready(w))
        assertNotNull(q)
        assertTrue(decide(Admission.withCryptoNative8871(candidate().copy(priceAgeMs = 20_000L), q, now)).admitted)
    }

    @Test fun negative_economics_safety_actual_losses_and_missing_execution_inputs_still_refuse() {
        val q = validate()!!
        val loss = Admission.Evidence8136("CELL", "own-cell", 40, -12.0, 1.0)
        val cases = listOf(
            candidate().copy(expectedGrossPct = -1.0), candidate().copy(expectedGrossPct = 1.0),
            candidate().copy(costPct = Double.NaN), candidate().copy(expectedGrossPct = Double.NaN),
            candidate().copy(safetyRefusal = "FREEZE_AUTHORITY"), candidate().copy(cell = loss),
            candidate().copy(selectedSetup = "MOMENTUM", timing = loss.copy(kind = "TIMING")),
            candidate().copy(missingInputs = listOf("LIQUIDITY:UNKNOWN")),
            candidate().copy(missingInputs = listOf("SELLABILITY:UNKNOWN")),
            candidate().copy(priceAgeMs = 300_001L),
        )
        for (c in cases) assertFalse("Native qualification erased refusal: $c",
            decide(Admission.withCryptoNative8871(c, q, now)).admitted)
        assertEquals("CELL_PROVEN_LOSING_8136",
            decide(Admission.withCryptoNative8871(candidate().copy(cell = loss), q, now)).reason)
    }

    @Test fun signal_revision_is_preserved_and_legacy_version_is_only_a_fallback() {
        var legacyReads = 0
        assertEquals(version, CryptoNativeSignal8871.candidateVersion(version) { legacyReads++; 5L })
        assertEquals(0, legacyReads)
        assertEquals(5L, CryptoNativeSignal8871.candidateVersion(0L) { legacyReads++; 5L })
        assertEquals(1, legacyReads)
    }

    @Test fun integrity_classification_reuses_the_very_admission_that_refused() {
        val missing = Admission.decide8136(candidate().copy(missingInputs = listOf("LIQUIDITY:UNKNOWN")))
        val assessment = CrossAssetCortex7931.EntryAssessment8871("DATA_INCOMPLETE_DEFER_8136", missing)
        assertTrue(CrossAssetCortex7931.refusalBinds8871(assessment.refusal!!, assessment))
        assertTrue(CrossAssetCortex7931.refusalBinds8871("DATA_INCOMPLETE_DEFER_8136",
            CrossAssetCortex7931.EntryAssessment8871("DATA_INCOMPLETE_DEFER_8136", null)))
    }

    @Test fun canonical_adapter_supplies_actual_ticket_revision_and_the_same_admission_to_the_gate() {
        val cross = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CrossAssetCortex7931.kt").readText()
        assertTrue(cross.contains("candidateVersion = c.candidateVersion, proposedSizeSol = c.requestedSizeSol"))
        assertTrue(cross.contains("cryptoNative8871 = native"))
        assertTrue(cross.contains("candidateVersion = c.candidateVersion, candidateAdmission = admission"))
        assertTrue(cross.contains("reassess8871 = assess8871, onAdmission8871 = { evaluated8871 = it }"))
        assertTrue(cross.contains("return EntryAssessment8871(refusal, evaluated8871)"))
        val contract = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        assertTrue(contract.contains("refusalBinds8871(why, entryAssessment8871)"))
    }

    @Test fun held_policy_reassessment_is_the_refusal_classifiers_actual_admission() {
        val c = Admission.withCryptoNative8871(candidate(), validate(), now)
        val supplied = decide(c)
        assertTrue(supplied.admitted)
        var reads = 0
        // Policy/inputs may reveal a missing executable fact. The positive outside-policy admission cannot stand in.
        val held = LiveEdgeGate7877.admissionForPolicy8871(supplied, paper = false, shadow = true) {
            reads++
            Admission.decide8136(c.copy(costPct = Double.NaN, missingInputs = listOf("QUOTE:UNKNOWN")))
        }!!
        assertEquals(1, reads)
        assertEquals(Admission.Terminal8136.DEFER_DATA, held.terminal)
        val actual = CrossAssetCortex7931.EntryAssessment8871(held.reason, held)
        assertTrue(CrossAssetCortex7931.refusalBinds8871(held.reason, actual))
        assertFalse("The previous wrong classification would have treated it as a learned door",
            CrossAssetCortex7931.refusalBinds8871(held.reason, CrossAssetCortex7931.EntryAssessment8871(held.reason, supplied)))
        assertSame(supplied, LiveEdgeGate7877.admissionForPolicy8871(supplied, paper = false, shadow = false) {
            fail("Ordinary live decision must be reused"); null
        })
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(gate.contains("if (onAdmission8871 == null || (candidateAdmission != null && !paper && !shadowOnly8393))"))
        assertTrue(gate.contains("onAdmission8871?.invoke(admission8577)"))
        assertTrue(gate.contains("val doors8577 = core ?: hold ?: niche ?: wait ?: proof8536"))
    }
}
