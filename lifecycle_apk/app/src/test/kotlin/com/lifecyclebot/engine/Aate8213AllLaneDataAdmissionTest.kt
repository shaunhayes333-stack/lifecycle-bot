package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.*
import org.junit.Test

/** Native qualification is independent evidence for ALL twelve meme specialists, not a Sniper-only exception. */
class Aate8213AllLaneDataAdmissionTest {
    private val natives = linkedMapOf(
        "QUALITY" to "LIQUIDITY_DEPTH_QUALITY",
        "BLUECHIP" to "MAINSTREAM_CRYPTO_SWING",
        "SHITCOIN" to "VOLUME_IGNITION_SCALP",
        "CYCLIC" to "CYCLIC_COMPOUND",
        "EXPRESS" to "VOLUME_IGNITION_SCALP",
        "CORE" to "MAINSTREAM_CRYPTO_SWING",
        "MOONSHOT" to "DIAMOND_HANDS_RUNNER",
        "PROJECT_SNIPER" to "DEGEN_MICRO_SNIPE",
        "DIP_HUNTER" to "PANIC_REVERSION_BOUNCE",
        "MANIPULATED" to "VOLUME_IGNITION_SCALP",
        "TREASURY" to "CASHFLOW_SCALP",
        "CASHGEN" to "CASHFLOW_SCALP",
    )

    private fun candidate(lane: String = "MOONSHOT", setup: String = "DIAMOND_HANDS_RUNNER") =
        CandidateAdmission8136.Candidate8136(
            mint = "optional-8213", lane = lane, selectedSetup = null,
            priceAgeMs = 800L,
            missingInputs = listOf("shape:INVALID", "tape:UNKNOWN", "chg:DEFAULT_UNOBSERVED"),
            expectedGrossPct = 21.0, costPct = 6.0,
            costSizeSol = 0.031, costBreakdown = "size=0.0310SOL known_liq_quote",
            genericScore = 65.0, genericFloor = 15.0,
            nativeQualified8213 = true, nativeSetup8213 = setup,
        )

    @Test fun twelveNativeSpecialistsCanTradeThroughOptionalGapsWithSizedPositiveEconomics() {
        assertEquals(12, natives.size)
        for ((lane, setup) in natives) {
            // V5.0.8259 — the lane's own qualification admits. V5.0.8279 §SELECT_LIKE_8114 — as the lane's OPINION
            // (specific = false): every 8114 live door (proven-only, chase rule, learned negatives, watch-first, V3 base
            // signal, cell proof ladder) still judges it. Only a SUPPORTED record is candidate-specific evidence.
            val d = CandidateAdmission8136.decide8136(candidate(lane, setup))
            assertEquals(lane, CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
            assertEquals(lane, "NATIVE_QUALIFIED_ADMIT_8259", d.reason)
            assertFalse(lane, d.specific)
            assertEquals(lane, setup, d.chosenSetup)
            assertTrue(lane, d.basis.startsWith("NATIVE_QUALIFIED_8259:"))
            assertEquals(lane, 6.0, d.costPct, 0.0)
            assertTrue(lane, d.trace.any { it.startsWith("nativeQualifiedAdmit8259=") })
            // live too: the 8238 "supported record only" lock does not refuse the lane's own judgment here — the 8114
            // proven-only door in LiveEdgeGate7877 is the live authority for an opinion admit
            val live = CandidateAdmission8136.decide8136(candidate(lane, setup).copy(live = true))
            assertEquals(lane, "NATIVE_QUALIFIED_ADMIT_8259", live.reason)
            assertFalse(lane, live.specific)
            // 8279: the generic V3 floor (7843) is the second floor again — a native opinion under it is refused by name
            assertEquals(lane, "GENERIC_SCORE_FLOOR_8136", CandidateAdmission8136.decide8136(candidate(lane, setup).copy(genericScore = 12.0, missingInputs = emptyList())).reason)
            // and under the floor it no longer carries a candidate through optional gaps either
            assertEquals(lane, CandidateAdmission8136.Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(candidate(lane, setup).copy(genericScore = 12.0)).terminal)
        }
    }

    @Test fun noNativeProofOrUnmeasuredNativeIdentityStillDefers() {
        val c = candidate()
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(nativeQualified8213 = false)).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(nativeSetup8213 = "NONE")).terminal)
        // economics, safety and critical facts still bind the native admit
        assertEquals("EXECUTION_ECONOMICS_8136", CandidateAdmission8136.decide8136(c.copy(expectedGrossPct = 7.0)).reason)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(missingInputs = c.missingInputs + "sellability:UNKNOWN")).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(priceAgeMs = 600_000L)).terminal)   // past STALE_PRICE_MS_8136 (5 min)
    }

    @Test fun executionSafetyAndMeasuredNegativesStillBind() {
        val c = candidate()
        val critical = c.copy(missingInputs = c.missingInputs + "liquidity:UNKNOWN")
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(critical).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(priceAgeMs = 360_000L)).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(costPct = Double.NaN)).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.REFUSE,
            CandidateAdmission8136.decide8136(c.copy(expectedGrossPct = 7.0)).terminal)
        assertEquals("EXECUTION_ECONOMICS_8136",
            CandidateAdmission8136.decide8136(c.copy(expectedGrossPct = 7.0)).reason)
        assertEquals("SAFETY_REFUSED_8136",
            CandidateAdmission8136.decide8136(c.copy(safetyRefusal = "RUG")).reason)
        assertEquals("STRUCTURAL_RISK_8136",
            CandidateAdmission8136.decide8136(c.copy(structuralRisk = "UNSELLABLE")).reason)

        val loser = CandidateAdmission8136.Evidence8136(
            kind = "SETUP", id = "IGNITION_5M", n = 45, meanPct = -25.0, sePct = 2.0)
        val judged = c.copy(selectedSetup = "IGNITION_5M", setups = listOf(loser))
        assertEquals("SETUP_RECORD_LOSING_8136", CandidateAdmission8136.decide8136(judged).reason)
        val stage = CandidateAdmission8136.Evidence8136(
            kind = "STAGE", id = "PEAK_EXHAUSTION", n = 50, meanPct = -16.0, sePct = 1.0)
        val stageJudge = c.copy(stage = "PEAK_EXHAUSTION", lossEpochAware8169 = true, stageLoss8169 = stage)
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(stageJudge).reason)
    }

    @Test fun statisticalSpecificEdgeDoesNotNeedOptionalChartContextOrNativeQualification() {
        val c = candidate().copy(nativeQualified8213 = false, nativeSetup8213 = null,
            selectedSetup = "IGNITION_5M",
            setups = listOf(CandidateAdmission8136.Evidence8136(
                kind = "SETUP", id = "IGNITION_5M", n = 45, meanPct = 28.0, sePct = 4.0)))
        val result = CandidateAdmission8136.decide8136(c)
        assertEquals(CandidateAdmission8136.Terminal8136.ADMIT, result.terminal)
        assertTrue(result.specific)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", result.reason)
    }
}
