package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexSampleBudget8871
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class Aate8871CortexSamplingTest {
    private val granted = CortexSampleBudget8871.Claim.GRANTED
    private val wait = CortexSampleBudget8871.Claim.WAIT

    @Test fun graphBudgetOpensOnlyAtItsExistingSixSecondBoundary() {
        var nanos = 0L
        val budget = CortexSampleBudget8871(6_000L) { nanos }
        assertEquals(granted, budget.claim())
        assertEquals(wait, budget.claim())
        nanos = 5_999_999_999L
        assertEquals(wait, budget.claim())
        nanos = 6_000_000_000L
        assertEquals(granted, budget.claim())
        assertEquals(wait, budget.claim())
    }

    @Test fun delayedDecisionsCannotManufactureMoreWorkerSlotsInTheSameElapsedSecond() {
        var nanos = 1_000_000_000L
        val budget = CortexSampleBudget8871(6_000L) { nanos }
        // These episodes arrive together, despite having unrelated, out-of-order decision wall clocks.
        val decisions = (0 until 1_000).map { if (it % 2 == 0) 1_790_000_000_000L + it else 1_789_999_000_000L - it }
        assertEquals(1, decisions.count { budget.claim() == granted })
        nanos += 6_000_000_000L
        assertEquals(1, decisions.reversed().count { budget.claim() == granted })
    }

    @Test fun theGlobalBudgetCannotExceedTenReadsPerMinuteUnderContinuousDemand() {
        var nanos = 0L
        val budget = CortexSampleBudget8871(6_000L) { nanos }
        var allowed = 0
        for (millis in 0L until 60_000L step 10L) {
            nanos = millis * 1_000_000L
            repeat(3) { if (budget.claim() == granted) allowed++ }
        }
        assertEquals(10, allowed)
    }

    @Test fun regressingTimeDoesNotRewindTheLastAcceptedGrant() {
        var nanos = 10_000_000_000L
        val budget = CortexSampleBudget8871(6_000L) { nanos }
        assertEquals(granted, budget.claim())
        nanos = 1L
        assertEquals(wait, budget.claim())
        nanos = 15_999_999_999L
        assertEquals(wait, budget.claim())
        nanos = 16_000_000_000L
        assertEquals(granted, budget.claim())
    }

    @Test fun monotonicClockOriginsAndNanoTimeWrapDoNotChangeTheInterval() {
        var nanos = -20_000_000_000L
        val negativeOrigin = CortexSampleBudget8871(6_000L) { nanos }
        assertEquals(granted, negativeOrigin.claim())
        nanos += 6_000_000_000L
        assertEquals(granted, negativeOrigin.claim())
        nanos = Long.MAX_VALUE - 2_000_000_000L
        val wrapping = CortexSampleBudget8871(6_000L) { nanos }
        assertEquals(granted, wrapping.claim())
        nanos += 5_000_000_000L
        assertEquals(wait, wrapping.claim())
        nanos += 1_000_000_000L
        assertEquals(granted, wrapping.claim())
    }

    @Test fun simultaneousWorkersCannotSpendTheSameSlotTwice() {
        val nanos = AtomicLong(1_000_000_000L)
        val budget = CortexSampleBudget8871(6_000L) { nanos.get() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(12)
        val allowed = AtomicLong()
        val workers = (0 until 12).map {
            Thread {
                try {
                    start.await()
                    if (budget.claim() == granted) allowed.incrementAndGet()
                } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        assertTrue("all claimers complete", done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join(100L) }
        assertEquals(1L, allowed.get())
        nanos.addAndGet(6_000_000_000L)
        assertEquals(granted, budget.claim())
    }

    @Test fun admittedAndPrewarmIntervalsKeepTheirSeparateExistingBudgets() {
        var nanos = 0L
        val general = CortexSampleBudget8871(6_000L) { nanos }
        val admitted = CortexSampleBudget8871(2_000L) { nanos }
        val prewarm = CortexSampleBudget8871(20_000L) { nanos }
        assertEquals(granted, general.claim())
        assertEquals(granted, admitted.claim())
        assertEquals(granted, prewarm.claim())
        nanos = 2_000_000_000L
        assertEquals(wait, general.claim())
        assertEquals(granted, admitted.claim())
        assertEquals(wait, prewarm.claim())
        nanos = 6_000_000_000L
        assertEquals(granted, general.claim())
        assertEquals(wait, prewarm.claim())
        nanos = 20_000_000_000L
        assertEquals(granted, prewarm.claim())
    }
}
