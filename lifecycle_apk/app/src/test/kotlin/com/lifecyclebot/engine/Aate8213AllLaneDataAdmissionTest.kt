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
            val d = CandidateAdmission8136.decide8136(candidate(lane, setup))
            assertEquals(lane, CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
            assertEquals(lane, "GENERIC_PRIOR_CLEARS_8136", d.reason)
            assertFalse(lane, d.specific) // A native-qualified, economic ADMIT is not a statistically proven setup.
            assertEquals(lane, setup, d.chosenSetup)
            assertTrue(lane, d.basis.startsWith("NATIVE_QUALIFIED_8213:"))
            assertEquals(lane, 6.0, d.costPct, 0.0)
            assertTrue(lane, d.trace.any { it.startsWith("nativeOptionalCleared8213=") })
            assertTrue(lane, LiveEdgeGate7877.genericNativeClearsProvenOnly8211(d))
        }
    }

    @Test fun noNativeProofOrUnmeasuredNativeIdentityStillDefers() {
        val c = candidate()
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(nativeQualified8213 = false)).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(nativeSetup8213 = "NONE")).terminal)
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(genericScore = 12.0)).terminal)
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

    @Test fun incompleteExecutablePriceOrDepthDefersBeforeAFalseEconomicRefusal8236() {
        val c = candidate().copy(missingInputs = emptyList(), expectedGrossPct = 5.0, costPct = 60.0)
        // A measured move that cannot pay the real known round trip is still rejected.
        assertEquals("EXECUTION_ECONOMICS_8136", CandidateAdmission8136.decide8136(c).reason)
        // The same cost estimate with missing executable depth or stale price is not proof
        // of unprofitability. The named missing evidence must be fetched/reassessed first.
        val unknownDepth = CandidateAdmission8136.decide8136(c.copy(missingInputs = listOf("liquidity:UNKNOWN")))
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, unknownDepth.terminal)
        assertTrue(unknownDepth.trace.any { it.contains("liquidity:UNKNOWN") })
        val stale = CandidateAdmission8136.decide8136(c.copy(priceAgeMs = 700_000L))
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, stale.terminal)
        assertTrue(stale.trace.any { it.contains("missing=price") })
        // Missing optional tape/shape without a verified native high-edge move cannot
        // make a truly uneconomic ticket pass.
        assertEquals("EXECUTION_ECONOMICS_8136",
            CandidateAdmission8136.decide8136(c.copy(missingInputs = listOf("shape:UNKNOWN"))).reason)
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
