package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8870HorizonVaultClockTest {
    private val now = 1_000_000L
    private fun read(at: Long = now - 19_000L) = OnChainDepth8038.Read("pool8870", 2.0, 100.0, at)

    @Test fun a_cached_vault_answer_cannot_be_retimed_by_a_later_http_completion() {
        val mark = ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", read(), 75.0, now)!!
        assertEquals(1.5, mark.usd, 0.0)
        assertEquals(now - 19_000L, mark.observedAtMs)
        assertEquals("pool8870", mark.venue)
        assertTrue(mark.independentBasis)
        // A target 5 seconds ago was NOT observed by this 19-second-old cache answer.
        assertTrue(mark.observedAtMs < now - 5_000L)
        assertNull("after a slow provider call the same read is stale, not freshly priced",
            ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", read(), 75.0, now + 2_000L))
    }

    @Test fun a_vault_refresh_landing_during_http_wait_keeps_its_actual_timestamp() {
        val landed = read(now + 10_000L)
        val mark = ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", landed, 75.0, now + 12_000L)!!
        assertEquals(now + 10_000L, mark.observedAtMs)
        assertEquals(1.5, mark.usd, 0.0)
    }

    @Test fun unknown_conversion_invalid_reserves_and_future_reads_stay_unknown() {
        assertNull(ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", read(), 0.0, now))
        assertNull(ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", read(), Double.NaN, now))
        assertNull(ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", read().copy(baseTokens = 0.0), 75.0, now))
        assertNull(ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", read(now + 1L), 75.0, now))
        assertNull(ForwardReturnLabeler7731.horizonVaultMark8870("mint8870", null, 75.0, now))
    }
}
