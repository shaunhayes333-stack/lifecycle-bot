package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527CyclicEntryParityTest {
    private val p = CyclicEntryPolicy8527

    private fun choice(action: NativeOpportunityPolicy8522.Action, priority: Double? = 90.0) =
        NativeOpportunityPolicy8522.Decision("mint8527", "CYCLIC", "LIVE", 7L, 3L,
            10_500L, 10_400L, 10_400L, action, true, "CYCLIC_NATIVE_SCORE_70", priority)

    @Test fun missingBirthCannotBecomeConfirmedMatureSellability() {
        assertNull(p.observedAgeMinutes8527(-1.0, Long.MAX_VALUE))
        assertNull(p.observedAgeMinutes8527(Double.NaN, null))
        assertNull(p.observedAgeMinutes8527(Double.POSITIVE_INFINITY, -1L))
        assertEquals(2.0, requireNotNull(p.observedAgeMinutes8527(-1.0, 120_000L)), 0.0)
        assertEquals(12.0, requireNotNull(p.observedAgeMinutes8527(12.0, Long.MAX_VALUE)), 0.0)
        assertEquals(0.0, requireNotNull(p.observedAgeMinutes8527(0.0, null)), 0.0)
        val source = File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        assertTrue(source.contains("CyclicEntryPolicy8527.observedAgeMinutes8527"))
        assertTrue(source.contains("val softUnknown = !ageMin.isFinite()"))
        assertTrue(source.contains("CyclicSellabilityEvidence6567.CONFIRMED_TRUE"))
    }

    @Test fun nativeAndRingUseOneBoundedEntryFloor() {
        assertEquals(38.0, p.baseFloor8527(38.0, 0, 0.0), 0.0)
        assertEquals(53.0, p.baseFloor8527(38.0, 10, 0.0), 0.0)
        assertEquals(25.0, p.baseFloor8527(28.0, 0, -20.0), 0.0)
        assertEquals(90.0, p.baseFloor8527(90.0, 3, 15.0), 0.0)
        assertEquals(38.0, p.baseFloor8527(38.0, -2, Double.NaN), 0.0)
        for (bad in listOf(false, true)) for (danger in listOf(false, true)) {
            val floor = p.memoryFloor8527(38.0, bad, danger)
            assertFalse(p.scoreAllows8527(floor - 0.01, 38.0, bad, danger))
            assertTrue(p.scoreAllows8527(floor, 38.0, bad, danger))
        }
        assertEquals(90.0, p.memoryFloor8527(88.0, true, true), 0.0)
        assertFalse(p.scoreAllows8527(Double.NaN, 38.0, false, false))
        assertFalse(p.scoreAllows8527(100.0, Double.NaN, false, false))
    }

    @Test fun theProbeUsesItsOwnFloorWithTheSameMemoryShaping() {
        assertEquals(12.0, p.memoryFloor8527(2.0, true, true), 0.0)
        assertFalse(p.scoreAllows8527(11.0, 2.0, true, true))
        assertTrue(p.scoreAllows8527(12.0, 2.0, true, true))
        assertFalse(p.scoreAllows8527(12.0, 38.0, true, true))
    }

    @Test fun currentInverseRefusalBindsButTakeCannotBypassNativeScore() {
        assertFalse(p.scoreAllows8527(95.0, 38.0, false, false, choice(NativeOpportunityPolicy8522.Action.REFUSE)))
        assertTrue(p.scoreAllows8527(95.0, 38.0, false, false, choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE)))
        assertFalse(p.scoreAllows8527(20.0, 38.0, false, false, choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE)))
        val projected = choice(NativeOpportunityPolicy8522.Action.REFUSE)
        fun current(mode: String = "LIVE", version: Long = 7L, revision: Long = 3L, now: Long = 11_000L) =
            NativeOpportunityPolicy8522.current8522(projected, "mint8527", "CYCLIC", mode, version, revision, now)
        assertNotNull(current())
        for (expired in listOf(current(mode = "PAPER"), current(version = 8L), current(revision = 4L), current(now = 13_001L))) {
            assertNull(expired)
            assertTrue(p.scoreAllows8527(95.0, 38.0, false, false, expired))
        }
    }

    @Test fun candidateScoreAndConfidenceDoNotBorrowEachOthersUnitsOrTheAdmissionFloor() {
        val lowConfidence = p.candidate8527(95, 12, "ignition")
        assertEquals(95.0, lowConfidence.entryScore, 0.0)
        assertEquals(12.0, lowConfidence.aiConfidence, 0.0)
        assertEquals(12.0, lowConfidence.edgeConfidence, 0.0)
        val probe = p.candidate8527(2, 8, "")
        assertEquals(2.0, probe.entryScore, 0.0)
        assertEquals(8.0, probe.aiConfidence, 0.0)
        assertEquals("cyclic", probe.phase)
        assertEquals("CYCLIC", probe.edgePhase)
        assertEquals(50.0, p.candidate8527(70, null, "").aiConfidence, 0.0)
    }

    @Test fun inversePriorityIsUsedOnlyForSelectionWithoutChangingForecasts() {
        val take = choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, 93.0)
        assertEquals(1_093.0, p.rank8527(30.0, 20, true, take), 0.0)
        assertEquals(1_006.0, p.rank8527(30.0, 20, true, null), 0.0)
        assertTrue(p.rank8527(30.0, 20, true, take) > p.rank8527(90.0, 100, true, null))
        assertTrue(p.rank8527(30.0, 20, true, null) > p.rank8527(90.0, 100, false, take))
    }

    @Test fun productionNativeNormalAndProbePathsSharePolicyAndKeepPhysicalAndFinalityChecks() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        assertTrue(source.contains("scoreFloor8527(bootstrap, cold8527())"))
        assertTrue(source.contains("scoreFloor8527(isBootstrapPhase, cyclicCold)"))
        assertTrue(source.contains("CyclicEntryPolicy8527.scoreAllows8527(score.toDouble(), baseFloor, badExp, danger)"))
        assertTrue(source.contains("CyclicEntryPolicy8527.scoreAllows8527(score.toDouble(), baseFloor, bad, danger, operatorChoice8527(ts))"))
        assertFalse(source.contains("!(expReject || danger)"))
        val strict = source.substringAfter("val best = tokens.values").substringBefore("?: run {")
        val probe = source.substringAfter("val probePick = tokens.values").substringBefore("if (probePick == null)")
        for (path in listOf(strict, probe)) {
            assertTrue(path.contains("CanonicalMintOccupancyRegistry6464.isOpen"))
            assertTrue(path.contains("CanonicalPositionAuthority6441.hasOpenMint"))
            assertTrue(path.contains("resolveCyclicPrice"))
            assertTrue(path.contains("cyclicEntrySellabilityGuard6097"))
            assertTrue(path.contains("ringScoreEligible8527"))
            assertTrue(path.contains("TokenBlacklist.isBlocked"))
            assertTrue(path.contains("MemeLossStreakGuard.isBlocked"))
            assertTrue(path.contains("ringRank8527"))
        }
        assertTrue(source.contains("SpecialistBrainBridge7542.opportunityDecision8522(ts.mint, \"CYCLIC\", token = ts)"))
        assertTrue(source.contains("CyclicEntryPolicy8527.candidate8527(cyclicScore, best.lastV3Confidence, best.phase)"))
        assertTrue(source.contains("score = cyclicFdg?.effectiveEntryScore7687 ?: cyclicScore"))
        assertTrue(source.contains("confidence = cyclicFdg?.confidence"))
        assertTrue(source.contains("quality = cyclicFdg?.quality"))
        assertTrue(source.contains("preResolvedSizeSol = cyclicFdg?.sizeSol ?: sizeSol"))
        assertTrue(source.contains("requireNotNull(cyclicAuth.executionIntent7835).resolvedSize"))
        assertTrue(source.contains("attemptId = cyclicAttemptId"))
        assertTrue(source.contains("currentMint   = best.mint"))
        assertTrue(source.contains("entrySizeSol  = cyclicAuthorizedSize7835"))
    }
}
