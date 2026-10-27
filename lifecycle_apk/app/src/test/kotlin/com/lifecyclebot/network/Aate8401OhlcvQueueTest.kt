package com.lifecyclebot.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Aate8401OhlcvQueueTest {
    @Test fun localRefusalsDoNotSpendWireAttemptsAndTheSameRequestEventuallyGetsBars() = runTest {
        val q = OhlcvRequestQueue8401<Int>(backgroundScope, { testScheduler.currentTime })
        var calls = 0
        val result = q.request("mint", 60_000, 1, { 0 }) {
            calls++
            if (calls <= 8) OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.WAIT_BUDGET)
            else OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_BARS, listOf(7))
        }
        assertEquals(9, calls)
        assertEquals(listOf(7), result.rows)
    }

    @Test fun duplicateRequestsShareTheAnswerAndOtherKeysGetATurnDuringBudgetWaits() = runTest {
        val q = OhlcvRequestQueue8401<Int>(backgroundScope, { testScheduler.currentTime })
        val order = mutableListOf<String>()
        var aCalls = 0
        val a = async { q.request("a", 60_000, 2, { 0 }) {
            order += "a"; aCalls++
            if (aCalls == 1) OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.WAIT_BUDGET)
            else OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_BARS, listOf(1))
        } }
        val duplicate = async { q.request("a", 60_000, 2, { 0 }) { error("duplicate provider call") } }
        val b = async { q.request("b", 60_000, 2, { 0 }) {
            order += "b"; OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_BARS, listOf(2))
        } }
        assertEquals(a.await(), duplicate.await())
        assertEquals(listOf(2), b.await().rows)
        assertEquals(listOf("a", "b", "a"), order)
    }

    @Test fun persistentLocalWaitEndsWithDeadlineAndNeverWithAnsweredEmpty() = runTest {
        val q = OhlcvRequestQueue8401<Int>(backgroundScope, { testScheduler.currentTime })
        val result = q.request("missing", 500, 1, { 100 }) { OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.WAIT_BUDGET) }
        assertEquals(OhlcvRequestQueue8401.State.DEADLINE, result.state)
    }

    @Test fun realProviderFailuresSpendAttemptsButAnsweredEmptyStopsImmediately() = runTest {
        val q = OhlcvRequestQueue8401<Int>(backgroundScope, { testScheduler.currentTime })
        var calls = 0
        assertEquals(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, q.request("failed", 1000, 2, { 0 }) {
            calls++; OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.PROVIDER_FAILURE)
        }.state)
        assertEquals(2, calls)
        calls = 0
        assertEquals(OhlcvRequestQueue8401.State.ANSWERED_EMPTY, q.request("empty", 1000, 3, { 0 }) {
            calls++; OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_EMPTY)
        }.state)
        assertEquals(1, calls)
    }
}
