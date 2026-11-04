package com.lifecyclebot.engine

import com.lifecyclebot.engine.TokenMetricStageRouter.Stage
import com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136
import com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Aate8527LaunchRoutingTest {
    private fun owner(
        stage: Stage = Stage.FRESH_LAUNCH,
        ageMin: Double = 1.0,
        launchAgeMs: Long? = 60_000L,
        orderedStage: Stage8136? = Stage8136.PRE_IGNITION,
        phase: Phase? = Phase.PRE_IGNITION,
        liquidityUsd: Double = 8_000.0,
        buyPressurePct: Double = 60.0,
        marketCapUsd: Double = 100_000.0,
    ): String? = TokenMetricStageRouter.launchStageOwner8527(
        stage, ageMin, liquidityUsd, buyPressurePct, marketCapUsd,
        launchAgeMs, phase, orderedStage,
    )

    @Test fun sniperHonoursActualInclusive180SecondWindow() {
        for (ageMs in listOf(0L, 120_000L, 180_000L)) {
            assertEquals("PROJECT_SNIPER", owner(ageMin = ageMs / 60_000.0, launchAgeMs = ageMs))
        }
        assertEquals("EXPRESS", owner(ageMin = 180_001L / 60_000.0, launchAgeMs = 180_001L))
    }

    @Test fun threeToTwelveMinuteBaseCannotBeSentBackToSniper() {
        for (ageMs in listOf(180_001L, 240_000L, 600_000L, 720_000L)) {
            assertEquals(
                "SHITCOIN",
                owner(Stage.BASE_START, ageMs / 60_000.0, ageMs, Stage8136.IGNITION, Phase.IGNITION),
            )
        }
        assertEquals("PROJECT_SNIPER", owner(Stage.BASE_START, 3.0, 180_000L))
    }

    @Test fun orderedLifecycleCannotBeRewoundByLegacyIgnition() {
        for (ordered in Stage8136.values().filter { it != Stage8136.PRE_IGNITION && it != Stage8136.IGNITION }) {
            assertNotEquals("PROJECT_SNIPER", owner(orderedStage = ordered, phase = Phase.IGNITION))
            assertNotEquals("PROJECT_SNIPER", owner(Stage.BASE_START, orderedStage = ordered, phase = Phase.IGNITION))
        }
        assertEquals("PROJECT_SNIPER", owner(orderedStage = Stage8136.IGNITION, phase = Phase.IGNITION))
    }

    @Test fun unknownOrInvalidBirthAndMissingOrderedStateNeverBecomeFresh() {
        for (ageMs in listOf<Long?>(null, -1L, Long.MAX_VALUE)) {
            assertNotEquals("PROJECT_SNIPER", owner(ageMin = 0.0, launchAgeMs = ageMs, phase = Phase.IGNITION))
            assertNotEquals("PROJECT_SNIPER", owner(Stage.BASE_START, 0.0, ageMs, phase = Phase.IGNITION))
        }
        assertEquals("EXPRESS", owner(orderedStage = null, phase = Phase.IGNITION))
        assertEquals("SHITCOIN", owner(Stage.BASE_START, orderedStage = null, phase = Phase.IGNITION))
    }

    @Test fun existingRunnerExpressAndSmallPoolBranchesRemainIntact() {
        assertEquals("MOONSHOT", owner(phase = Phase.EXPANDING, orderedStage = Stage8136.EXPANDING, liquidityUsd = 5_000.0, buyPressurePct = 56.0))
        assertEquals("EXPRESS", owner(phase = Phase.EXPANDING, orderedStage = Stage8136.EXPANDING, liquidityUsd = 1_500.0))
        assertEquals("SHITCOIN", owner(phase = Phase.EXPANDING, orderedStage = Stage8136.EXPANDING, liquidityUsd = 1_499.0))
        assertEquals("MOONSHOT", owner(Stage.BASE_START, 30.0, 1_800_000L, marketCapUsd = 150_000.0))
        assertEquals("SHITCOIN", owner(Stage.BASE_START, 30.0, 1_800_000L, marketCapUsd = 149_999.0))
        assertEquals("MOONSHOT", owner(Stage.BASE_START, Double.NaN, null, marketCapUsd = 150_000.0))
    }

    @Test fun unrelatedStageOwnersAndEarlyLiquidityRequirementsAreNotOverridden() {
        assertNull(owner(Stage.CONTROLLED_MARKUP))
        assertNull(owner(Stage.RUG_PRONE))
        assertEquals("PROJECT_SNIPER", owner(liquidityUsd = 1_500.0))
        assertEquals("SHITCOIN", owner(liquidityUsd = 1_499.0))
        assertEquals("SHITCOIN", owner(Stage.BASE_START, liquidityUsd = 7_999.0))
        assertEquals("SHITCOIN", owner(Stage.BASE_START, buyPressurePct = 57.9))
    }
    @Test fun evidenceRerouteCannotReintroduceAnIneligibleSniper() {
        for (stage in listOf(Stage.FRESH_LAUNCH, Stage.BASE_START)) {
            val expected = setOf("SHITCOIN", "EXPRESS", "MOONSHOT")
            assertEquals(expected, TokenMetricStageRouter.evidenceAlternativeLanes8527(stage, 180_001L, Stage8136.IGNITION))
            assertEquals(expected, TokenMetricStageRouter.evidenceAlternativeLanes8527(stage, null, Stage8136.IGNITION))
            assertEquals(expected, TokenMetricStageRouter.evidenceAlternativeLanes8527(stage, 120_000L, Stage8136.EXPANDING))
            assertEquals(expected + "PROJECT_SNIPER", TokenMetricStageRouter.evidenceAlternativeLanes8527(stage, 180_000L, Stage8136.IGNITION))
        }
        for (stage in Stage.values().filter { it != Stage.FRESH_LAUNCH && it != Stage.BASE_START }) {
            assertEquals(
                TokenMetricStageRouter.LANE_STAGE_SHEET_7928[stage].orEmpty() - setOf("STANDARD", "CORE", "V3"),
                TokenMetricStageRouter.evidenceAlternativeLanes8527(stage, null, null),
            )
        }
    }

}
