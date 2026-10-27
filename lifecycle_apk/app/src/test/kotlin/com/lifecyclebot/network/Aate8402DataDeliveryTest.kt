package com.lifecyclebot.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class Aate8402DataDeliveryTest {
    @Test fun repeatedLocalReadsCannotMoveTheProviderWindowForever() {
        val attempts = ConcurrentHashMap<String, Long>()
        assertTrue(SolanaOhlcvFeed6916.claimAttemptWindow8402(attempts, "mint", 0, 2500))
        for (now in 100L..2400L step 100L)
            assertFalse(SolanaOhlcvFeed6916.claimAttemptWindow8402(attempts, "mint", now, 2500))
        assertEquals(0L, attempts["mint"])
        assertTrue(SolanaOhlcvFeed6916.claimAttemptWindow8402(attempts, "mint", 2500, 2500))
        assertEquals(2500L, attempts["mint"])
    }

    @Test fun availableAlternateBarsDoNotWaitForTheRemoteProviderBudget() = runTest {
        val q = OhlcvRequestQueue8401<Int>(backgroundScope, { testScheduler.currentTime })
        val result = q.request("local", 60_000, 1, { 60_000 }) {
            OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_BARS, listOf(7))
        }
        assertEquals(listOf(7), result.rows)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun shortCoalescedCallerCannotCancelTheLongerOwnedRequest() = runTest {
        val q = OhlcvRequestQueue8401<Int>(backgroundScope, { testScheduler.currentTime })
        var calls = 0
        val owner = async { q.request("mint", 10_000, 1, { 2_000 }) {
            calls++
            if (calls == 1) OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.WAIT_BUDGET)
            else OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_BARS, listOf(9))
        } }
        runCurrent()
        val short = async { q.request("mint", 500, 1, { 0 }) { error("must coalesce") } }
        assertEquals(OhlcvRequestQueue8401.State.DEADLINE, short.await().state)
        assertEquals(listOf(9), owner.await().rows)
        assertEquals(2, calls)
    }
}
