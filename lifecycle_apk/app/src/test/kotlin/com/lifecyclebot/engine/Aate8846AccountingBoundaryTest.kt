package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionSpineAcceptanceWindow6647
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** 8845 runtime: journal receipt age 288 ms at the *old* 30-second deadline. */
class Aate8846AccountingBoundaryTest {
    @Test fun newlyOpenedReceiptAtExpiredBoundaryGetsOneStrictlyBoundedRetry() {
        val since = AtomicLong(0L)
        val at = 1_000_000L
        assertTrue(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, true, 288L, since, at))
        assertEquals(at, since.get())
        // The receipt may finish before the reconciler's next fully current pass.
        assertTrue(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, false, null, since, at + 5_000L))
        assertFalse(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, false, null, since, at + 10_000L))
        assertFalse(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, true, 20L, since, at + 15_000L))
    }

    @Test fun oldOpenUnknownOrCurrentFailedAccountNeverGetsExtraGrace() {
        val t = 2_000_000L
        val since = AtomicLong(0L)
        assertFalse(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, true, 20_000L, since, t))
        assertFalse(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, false, 288L, since, t))
        assertFalse(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(true, true, null, since, t))
        assertFalse(ExecutionSpineAcceptanceWindow6647.edgeReceiptRetry8846(false, true, 288L, since, t))
        assertEquals(0L, since.get())
    }
}
