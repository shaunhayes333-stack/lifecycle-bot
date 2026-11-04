package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.KeylessMarks8359
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** Actual shared pending/in-flight queue ownership; no HTTP worker is started by these fixtures. */
class Aate8527LaneCoverageLeaseTest {
    private val mint = "Lease85271111111111111111111111111pump"
    private var now = 0L

    @Before fun reset() {
        KeylessMarks8359.resetForTests8359()
        ProviderLanes8285.resetForTests8285()
        DexBatchLane8287.completeBatch8527(listOf(mint), null)
        now = System.currentTimeMillis()
        KeylessMarks8359.putLabelWantedForTests8418(mint, now + 120_000L)
    }

    @After fun clean() {
        KeylessMarks8359.resetForTests8359()
        DexBatchLane8287.completeBatch8527(listOf(mint), null)
        ProviderLanes8285.resetForTests8285()
    }

    private fun enqueue(at: Long = now): Boolean = ProviderLanes8285.enqueue8285(
        ProviderLanes8285.DEX_8285, ProviderLanes8285.KIND_PAIR_8285, mint, ProviderLanes8285.Prio8285.HANDOFF, at)

    @Test fun merelyWantingOrRejectingARequestNeverSuppressesFallback() {
        assertFalse(KeylessMarks8359.laneCovered8418(mint, now))
        assertFalse(DexBatchLane8287.enqueue8287("invalid"))
        assertNull(DexBatchLane8287.outstandingAt8527("invalid"))
        assertFalse(KeylessMarks8359.laneCovered8418(mint, now))
    }

    @Test fun coalescingSharesTheFirstAdmissionRatherThanClaimingRejectionOrRenewal() {
        assertTrue(enqueue())
        assertFalse(enqueue(now + 15_000L))
        assertEquals(now, DexBatchLane8287.outstandingAt8527(mint)!!)
        assertTrue(KeylessMarks8359.laneCovered8418(mint, now + 15_000L))
        assertFalse("re-asking did not renew the twenty-second promise",
            KeylessMarks8359.laneCovered8418(mint, now + 20_001L))
    }

    @Test fun drainPreservesOwnershipAndTheOriginalQueueAgeUntilCompletion() {
        enqueue(now - 19_000L)
        val batch = DexBatchLane8287.claimBatch8527()
        assertEquals(listOf(mint), batch)
        assertEquals(0, ProviderLanes8285.pendingCount8285(ProviderLanes8285.DEX_8285, ProviderLanes8285.KIND_PAIR_8285))
        assertEquals(now - 19_000L, DexBatchLane8287.outstandingAt8527(mint)!!)
        assertTrue(KeylessMarks8359.laneCovered8418(mint, now))
        assertFalse(KeylessMarks8359.laneCovered8418(mint, now + 1_001L))
        // Existing in-flight work is coalesced without kicking a second worker.
        assertFalse(DexBatchLane8287.enqueue8287(mint))
        DexBatchLane8287.completeBatch8527(batch, null)
        assertNull(DexBatchLane8287.outstandingAt8527(mint))
    }

    @Test fun aFailedBatchReleasesFallbackImmediatelyAndRetriesCannotMaskItAgain() {
        enqueue()
        val batch = DexBatchLane8287.claimBatch8527()
        assertTrue(KeylessMarks8359.laneCovered8418(mint, now))
        DexBatchLane8287.completeBatch8527(batch, null) // production failure completion hook
        assertFalse(KeylessMarks8359.laneCovered8418(mint, now))
        repeat(5) { i ->
            enqueue(now + i * 1_000L)
            assertFalse("a renewed request is not new price evidence",
                KeylessMarks8359.laneCovered8418(mint, now + i * 1_000L))
        }
    }

    @Test fun missingInvalidAndZeroAnswersNeverCountAsCoverage() {
        for (prices in listOf(emptyMap(), mapOf(mint to Double.NaN), mapOf(mint to 0.0), mapOf(mint to -1.0))) {
            KeylessMarks8359.resetForTests8359()
            KeylessMarks8359.putLabelWantedForTests8418(mint, now + 120_000L)
            enqueue()
            KeylessMarks8359.onLaneAnswer8418(listOf(mint), prices)
            assertFalse(KeylessMarks8359.laneCovered8418(mint, now))
        }
    }

    @Test fun onlyARealPositiveAnswerRestoresNormalCoalescingAfterFailure() {
        enqueue()
        KeylessMarks8359.onLaneAnswer8418(listOf(mint), emptyMap())
        assertFalse(KeylessMarks8359.laneCovered8418(mint, now))
        KeylessMarks8359.onLaneAnswer8418(listOf(mint), mapOf(mint to 0.0042))
        assertTrue(KeylessMarks8359.laneCovered8418(mint, now))
    }
}
