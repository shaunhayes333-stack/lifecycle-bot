package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.v3.bridge.V3Adapter
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.eligibility.CooldownManager
import com.lifecyclebot.v3.eligibility.EligibilityGate
import com.lifecyclebot.v3.eligibility.ExposureGuard
import com.lifecyclebot.v3.scanner.AgeEvidenceKind8527
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import com.lifecyclebot.v3.scoring.CapitalEfficiencyAI
import com.lifecyclebot.v3.scoring.EducationSubLayerAI
import com.lifecyclebot.v3.scoring.LiquidityAI
import com.lifecyclebot.v3.scoring.TokenDNAClusteringAI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8527V3AgeEvidenceTest {
    private val sixHours = 6L * 60L * 60_000L
    private val context = TradingContext(TradingConfigV3(), V3BotMode.PAPER)

    private fun candidate(exact: Double = -1.0, minimum: Double? = null, liquidity: Double = 20_000.0) =
        CandidateSnapshot(
            mint = "8527-age-contract", symbol = "AGE", source = SourceType.DEX_TRENDING,
            discoveredAtMs = System.currentTimeMillis(), ageMinutes = exact,
            liquidityUsd = liquidity, marketCapUsd = 100_000.0, buyPressurePct = 60.0,
            volume1mUsd = 100.0, volume5mUsd = 500.0, minimumAgeMinutes8527 = minimum,
        )

    @Test fun onlySixHourProvenFloorAndRealEstablishedPoolCanReplaceExactBirthForScoring() {
        assertNull(V3Adapter.selectScoringAge8527(null, null, 20_000.0))
        assertNull(V3Adapter.selectScoringAge8527(null, sixHours - 1L, 20_000.0))
        listOf(0.0, 1_999.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull(V3Adapter.selectScoringAge8527(null, sixHours, it))
        }
        val bound = V3Adapter.selectScoringAge8527(null, sixHours, 2_000.0)!!
        assertNull(bound.exactMinutes)
        assertEquals(360.0, bound.minimumMinutes, 0.0)
        assertNull(V3Adapter.selectScoringAge8527(Long.MAX_VALUE, Long.MAX_VALUE, 20_000.0))
        assertNull(V3Adapter.selectScoringAge8527(-1L, -1L, 20_000.0))
    }

    @Test fun exactBirthWinsAndARealZeroMinuteLaunchIsNotUnknown() {
        val birth = V3Adapter.selectScoringAge8527(0L, sixHours, 1_000.0)!!
        assertEquals(0.0, birth.exactMinutes!!, 0.0)
        val exact = candidate(exact = 0.0)
        assertEquals(AgeEvidenceKind8527.KNOWN, exact.ageKind8527)
        assertTrue(exact.ageAtMost8527(15.0))
        assertTrue(exact.ageBelow8527(5.0))
        assertFalse(exact.ageAtLeast8527(360.0))
        assertTrue(FinalDecisionEngine.freshLaunchProbe8527(exact.ageMinutes))
    }

    @Test fun lowerBoundsAndUnknownCannotClaimAnyFreshWindowOrProbeExemption() {
        val bound = candidate(minimum = 360.0)
        assertEquals(AgeEvidenceKind8527.PROVEN_OLDER_THAN, bound.ageKind8527)
        assertNull(bound.exactAgeMinutes8527)
        assertTrue(bound.ageAtLeast8527(360.0))
        assertFalse(bound.ageBelow8527(5.0))
        assertFalse(bound.ageAtMost8527(15.0))
        assertFalse(bound.ageAtMost8527(10_000.0))
        assertFalse(FinalDecisionEngine.freshLaunchProbe8527(bound.ageMinutes))
        val unknown = candidate()
        assertEquals(AgeEvidenceKind8527.UNKNOWN, unknown.ageKind8527)
        assertFalse(unknown.hasScorableAge8527)
        assertFalse(unknown.ageAtLeast8527(0.0))
        assertFalse(unknown.ageAtMost8527(15.0))
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertFalse(FinalDecisionEngine.freshLaunchProbe8527(it))
        }
    }

    @Test fun actualAdapterConsumesHistoryFloorWithoutPublishingItAsBirth() {
        val now = System.currentTimeMillis()
        val mint = "8527V3ProvenHistoryFloor".padEnd(44, 'C')
        AdmissionBirth8144.noteHistoryFloor8144(mint, now - sixHours - 60_000L)
        val ts = TokenState(mint = mint, symbol = "BOUND")
        ts.lastLiquidityUsd = 20_000.0
        ts.lastMcap = 100_000.0
        val result = V3Adapter.toCandidate(ts)
        assertEquals(AgeEvidenceKind8527.PROVEN_OLDER_THAN, result.ageKind8527)
        assertEquals(-1.0, result.ageMinutes, 0.0)
        assertNull(result.exactAgeMinutes8527)
        assertTrue(result.provenMinimumAgeMinutes8527!! >= 360.0)
        assertFalse(result.ageAtMost8527(15.0))
        // No NaN is introduced into JSON or later integer conversions; the bound has its own field.
        val json = JSONObject().put("ageMinutes", result.ageMinutes)
            .put("minimumAgeMinutes8527", result.minimumAgeMinutes8527)
        assertEquals(-1.0, JSONObject(json.toString()).getDouble("ageMinutes"), 0.0)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, AdmissionBirth8144.readFor8144(ts).kind)
    }

    @Test fun actualEligibilityScoresEstablishedBoundsButKeepsUnknownAndThinOldTokensOut() {
        val gate = EligibilityGate(TradingConfigV3(), CooldownManager(), ExposureGuard())
        assertTrue(gate.evaluate(candidate(minimum = 360.0)).passed)
        assertEquals("BIRTH_METADATA_HYDRATING_7441", gate.evaluate(candidate()).reason)
        assertEquals("BIRTH_METADATA_HYDRATING_7441", gate.evaluate(candidate(minimum = 359.0)).reason)
        assertEquals("BIRTH_METADATA_HYDRATING_7441", gate.evaluate(candidate(minimum = 360.0, liquidity = 1_999.0)).reason)
        assertEquals("TOO_OLD", gate.evaluate(candidate(exact = 4_320.0, liquidity = 900.0)).reason)
    }

    @Test fun ageModelsDoNotPutUnobservedAgeInYoungestOrOldestExactCohorts() {
        assertNull(CapitalEfficiencyAI.bandFor8527(-1.0))
        assertNull(CapitalEfficiencyAI.bandFor8527(Double.NaN))
        assertEquals("AGE_0_15m", CapitalEfficiencyAI.bandFor8527(0.0))
        assertEquals("AGE_4_24h", CapitalEfficiencyAI.bandFor8527(360.0))
        assertEquals(0, CapitalEfficiencyAI.score(candidate(minimum = 360.0), context).value)
        assertEquals("AGE_UNKNOWN", TokenDNAClusteringAI.ageBand8527(candidate()))
        assertEquals("AGE_UNKNOWN", TokenDNAClusteringAI.ageBand8527(candidate(minimum = 360.0)))
        assertEquals("AGE_12h_plus", TokenDNAClusteringAI.ageBand8527(candidate(minimum = 720.0)))
        assertEquals("AGE_0_5m", TokenDNAClusteringAI.ageBand8527(candidate(exact = 0.0)))
    }

    @Test fun ageIndependentLiquidityStillScoresAndLowerBoundDoesNotGetFreshThinPoolExemption() {
        val model = LiquidityAI()
        val observedOld = model.score(candidate(minimum = 360.0, liquidity = 2_500.0), context)
        val exactYoung = model.score(candidate(exact = 1.0, liquidity = 2_500.0), context)
        val observedDeep = model.score(candidate(minimum = 360.0, liquidity = 40_000.0), context)
        assertTrue(observedOld.reason.contains("Thin liquidity (stalled)"))
        assertTrue(exactYoung.reason.contains("Thin liquidity (fresh, exempt)"))
        assertTrue(observedDeep.reason.contains("Strong liquidity base"))
    }

    @Test fun entryAgeLearningHonorsUnknownExactAgeInsteadOfOutcomeDefaultZero() {
        assertNull(EducationSubLayerAI.exactEntryAge8527(candidate(minimum = 360.0), 0.0))
        assertNull(EducationSubLayerAI.exactEntryAge8527(candidate(), 0.0))
        assertEquals(5.0, EducationSubLayerAI.exactEntryAge8527(candidate(exact = 5.0), 0.0)!!, 0.0)
        assertEquals(8.0, EducationSubLayerAI.exactEntryAge8527(null, 8.0)!!, 0.0)
        assertNull(EducationSubLayerAI.exactEntryAge8527(null, Double.NaN))
    }
}
