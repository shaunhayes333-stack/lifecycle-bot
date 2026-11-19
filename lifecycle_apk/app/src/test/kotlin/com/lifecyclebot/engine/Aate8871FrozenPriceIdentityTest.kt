package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8871FrozenPriceIdentityTest {
    private fun price(value: Double, at: Long, asset: String = "eth|asset") =
        ObservedPriceIdentity8871.Observation(asset, value, at)

    @Test fun aDifferentFreshRegistryPriceCannotRetimeTheFrozenPrice() {
        assertEquals(1_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 1_000L),
            200_000L, price(2.0, 199_000L)))
    }

    @Test fun anActualSameAssetSameValueObservationCanRepairAnUnknownClock() {
        assertEquals(199_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 0L),
            200_000L, price(1.0, 199_000L)))
        assertEquals(199_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 1_000L),
            200_000L, price(1.0, 199_000L)))
    }

    @Test fun anotherChainAndAnAfterDecisionObservationCannotSupplyTheClock() {
        assertEquals(1_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 1_000L),
            200_000L, price(1.0, 199_000L, "base|asset")))
        assertEquals(1_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 1_000L),
            200_000L, price(1.0, 200_001L)))
    }

    @Test fun existingDatedInputSurvivesAnOlderOrAbsentRegistryMark() {
        assertEquals(199_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 199_000L), 200_000L))
        assertEquals(199_000L, ObservedPriceIdentity8871.observedAt(price(1.0, 199_000L),
            200_000L, price(1.0, 1_000L)))
    }

    @Test fun invalidValuesAndUnknownOrFutureClocksDoNotAcquireFreshness() {
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0L, ObservedPriceIdentity8871.observedAt(price(value, 199_000L),
                200_000L, price(value, 199_000L)))
        }
        assertEquals(0L, ObservedPriceIdentity8871.observedAt(price(1.0, 200_001L), 200_000L))
        assertEquals(0L, ObservedPriceIdentity8871.observedAt(price(1.0, 0L),
            200_000L, price(1.0, 0L)))
    }

    @Test fun assessmentFreezesClockBeforeVotersAndAdmissionUsesTheSameIdentityRule() {
        val cortex = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
            .substringAfter("fun assess(ts: TokenState, laneRaw: String").substringBefore("val a = Assessment")
        assertTrue(cortex.indexOf("val priceAt8871") in 0 until cortex.indexOf("CortexVoters7885.readAll"))
        assertTrue(cortex.contains("readCompletedMs8522 - priceAt8871"))
        assertFalse(cortex.contains("maxOf(ts.lastPriceUpdate, registryAt)"))
        val admission = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(admission.contains("val observedAt = com.lifecyclebot.engine.ObservedPriceIdentity8871.observedAt("))
        assertFalse(admission.contains("maxOf(localAt, canonicalPrice?.timestampMs"))
    }
}
