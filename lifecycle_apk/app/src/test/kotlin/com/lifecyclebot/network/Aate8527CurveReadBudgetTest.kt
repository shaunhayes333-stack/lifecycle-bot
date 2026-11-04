package com.lifecyclebot.network

import org.junit.Assert.*
import org.junit.Test

/** Deterministic replay of the production ladder, governors and monotonic read budget. */
class Aate8527CurveReadBudgetTest {
    private val publicA = "https://api.mainnet-beta.solana.com"
    private val publicB = "https://solana-rpc.publicnode.com"
    private val helius = "https://mainnet.helius-rpc.com/?api-key=test"
    private val ladder get() = listOf(publicA, publicB, helius)

    @Test fun twoFailedPublicRungsStillReachAuthenticatedFallbackWithinTheSameBudget() {
        var nowNs = 0L
        val calls = mutableListOf<Pair<String, Long>>()
        var answer: Double? = null
        CurveReadBudget8527.walk(ladder, false, admit = { true }, clockNanos = { nowNs }) { url, _, ms ->
            calls += url to ms
            if (url == helius) {
                nowNs += 1_500_000_000L
                answer = 0.25
                true
            } else {
                nowNs += ms * 1_000_000L // a transport timeout at both public nodes
                false
            }
        }
        assertEquals(listOf(publicA, publicB, helius), calls.map { it.first })
        assertEquals(listOf(1_000L, 1_000L, 2_000L), calls.map { it.second })
        assertEquals(0.25, answer!!, 0.0)
        assertTrue(nowNs <= CurveReadBudget8527.TOTAL_MS * 1_000_000L)
    }

    @Test fun anApprovedOrHeldReserveReadAsksAuthenticatedFirstAndStopsOnItsAnswer() {
        var calls = 0
        CurveReadBudget8527.walk(ladder, true, admit = { true }, clockNanos = { 0L }) { url, _, ms ->
            calls++
            assertEquals(helius, url)
            assertEquals(4_000L, ms)
            true
        }
        assertEquals(1, calls)
        assertEquals(ladder, CurveReadBudget8527.order(ladder, false))
    }

    @Test fun unavailableRungsAndAnExhaustedRateGovernorAreNotSentOrTurnedIntoData() {
        var nowNs = 0L
        val calls = mutableListOf<String>()
        val governor = KeylessRpc8359.Governor8359(1)
        assertTrue(governor.tryTake8359(0L))
        var answer: Double? = null
        CurveReadBudget8527.walk(ladder, false, admit = { url ->
            when (url) {
                publicA -> false // existing host lockout
                publicB -> governor.tryTake8359(0L) // existing governor exhausted
                else -> true
            }
        }, clockNanos = { nowNs }) { url, _, ms ->
            calls += url
            assertEquals(helius, url)
            assertEquals(4_000L, ms)
            nowNs += 100_000_000L
            answer = 0.5
            true
        }
        assertEquals(listOf(helius), calls)
        assertEquals(0.5, answer!!, 0.0)
    }

    @Test fun noAdmittedOrAnsweredRungRemainsUnknown() {
        var calls = 0
        var answer: Double? = null
        CurveReadBudget8527.walk(ladder, false, admit = { false }, clockNanos = { 0L }) { _, _, _ ->
            calls++
            answer = 0.0
            true
        }
        assertEquals(0, calls)
        assertNull(answer)
    }

    @Test fun elapsedBudgetNeverStartsAFurtherRequest() {
        var nowNs = 0L
        var calls = 0
        CurveReadBudget8527.walk(ladder, true, admit = { true }, clockNanos = { nowNs }) { _, _, ms ->
            calls++
            nowNs += ms * 1_000_000L
            false
        }
        assertEquals(1, calls)
        assertEquals(4_000_000_000L, nowNs)
    }

    @Test fun cancellationNeverWalksIntoAnotherRung() {
        var cancelled = false
        var calls = 0
        CurveReadBudget8527.walk(ladder, false, admit = { true }, clockNanos = { 0L }, interrupted = { cancelled }) { _, _, _ ->
            calls++
            cancelled = true
            false
        }
        assertEquals(1, calls)
    }

    @Test fun fastPublicAnswerRetainsCostSavingAndUnusedProbeTimeRemainsAvailable() {
        var calls = 0
        CurveReadBudget8527.walk(ladder, false, admit = { true }, clockNanos = { 0L }) { url, _, _ ->
            calls++
            assertEquals(publicA, url)
            true
        }
        assertEquals(1, calls)
        assertEquals(0L, CurveReadBudget8527.hopMs(2_000L, true, true))
        assertEquals(3_800L, CurveReadBudget8527.hopMs(3_800L, false, false))
    }
}
