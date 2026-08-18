package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperPositionDustInvariant6460
import com.lifecyclebot.engine.truth.RootCauseClassifier6460
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6460 hard CI assertions.
 */
class DustAndRootCauseAcceptanceTest {

    @Test
    fun `dust invariant collapses zero-qty position with residual cost to CLOSED`() {
        // Operator's exact case: qty=0.0 cost=0.050
        val r = PaperPositionDustInvariant6460.enforce(remainingQty = 0.0, remainingCostSol = 0.05, mint = "TEST")
        assertTrue("dust must transition to closed", r.closed)
        assertEquals(0.0, r.remainingQty, 1e-12)
        assertEquals(0.0, r.remainingCostSol, 1e-12)
    }

    @Test
    fun `dust invariant preserves non-dust position untouched`() {
        val r = PaperPositionDustInvariant6460.enforce(remainingQty = 10.5, remainingCostSol = 0.05, mint = "TEST2")
        assertTrue("non-dust must NOT close", !r.closed)
        assertEquals(10.5, r.remainingQty, 1e-12)
        assertEquals(0.05, r.remainingCostSol, 1e-12)
    }

    @Test
    fun `root cause classifier attributes maintenance duration correctly`() {
        // POST_LEARNING_MAINTENANCE=54.5s must win over UI_MAIN_THREAD=200ms.
        val c = RootCauseClassifier6460.classify(
            postLearningMs = 54_500L,
            scannerWorstMs = 100L,
            providerWorstMs = 100L,
            uiMainThreadMs = 200L,
        )
        assertEquals(RootCauseClassifier6460.Cause.POST_LEARNING_MAINTENANCE, c)
    }

    @Test
    fun `root cause classifier reports HEALTHY when no phase exceeds threshold`() {
        val c = RootCauseClassifier6460.classify(
            postLearningMs = 500L, scannerWorstMs = 200L, providerWorstMs = 100L, uiMainThreadMs = 50L,
        )
        assertEquals(RootCauseClassifier6460.Cause.HEALTHY, c)
    }
}
