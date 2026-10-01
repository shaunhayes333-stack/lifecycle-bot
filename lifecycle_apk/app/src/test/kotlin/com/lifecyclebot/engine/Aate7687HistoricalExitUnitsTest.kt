package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import org.junit.Assert.assertEquals
import org.junit.Test

class Aate7687HistoricalExitUnitsTest {
    @Test fun reconstructsUsdInsteadOfRecordingSolPerToken() {
        // Ten tokens sold for 0.2 SOL when SOL was $125: $2.50/token.
        assertEquals(2.5, CanonicalPaperTransaction6486.historicalExitPriceUsd7687(
            0.0, 0.2, 10.0, 125.0,
        ), 1e-12)
    }

    @Test fun recordedExecutionMarkTakesPriorityOverProceedsInference() {
        assertEquals(2.48, CanonicalPaperTransaction6486.historicalExitPriceUsd7687(
            2.48, 0.2, 10.0, 125.0,
        ), 1e-12)
    }

    @Test fun unobservedOrInvalidHistoryRemainsUnknown() {
        for (rate in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, CanonicalPaperTransaction6486.historicalExitPriceUsd7687(
                0.0, 0.2, 10.0, rate,
            ), 0.0)
        }
        assertEquals(0.0, CanonicalPaperTransaction6486.historicalExitPriceUsd7687(
            0.0, 0.2, 0.0, 125.0,
        ), 0.0)
        assertEquals(0.0, CanonicalPaperTransaction6486.historicalExitPriceUsd7687(
            0.0, Double.POSITIVE_INFINITY, 10.0, 125.0,
        ), 0.0)
    }
}
