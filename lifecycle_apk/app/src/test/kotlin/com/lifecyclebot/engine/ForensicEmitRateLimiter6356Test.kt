package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ForensicEmitRateLimiter6356Test {

    @Before fun reset() { ForensicEmitRateLimiter6356.resetForTest() }

    @Test
    fun first_call_emits_second_call_suppresses() {
        assertTrue(ForensicEmitRateLimiter6356.shouldEmit("LABEL_A", "scope1"))
        assertFalse("second call within cooldown must be suppressed",
            ForensicEmitRateLimiter6356.shouldEmit("LABEL_A", "scope1"))
    }

    @Test
    fun different_scopes_do_not_interfere() {
        assertTrue(ForensicEmitRateLimiter6356.shouldEmit("LABEL_A", "MOONSHOT"))
        assertTrue("independent scope must not be starved",
            ForensicEmitRateLimiter6356.shouldEmit("LABEL_A", "SHITCOIN"))
        assertTrue(ForensicEmitRateLimiter6356.shouldEmit("LABEL_A", "QUALITY"))
    }

    @Test
    fun short_cooldown_lapses_and_reallows_emission() {
        assertTrue(ForensicEmitRateLimiter6356.shouldEmit("LABEL_X", "s", cooldownMs = 1L))
        Thread.sleep(20L)
        assertTrue(ForensicEmitRateLimiter6356.shouldEmit("LABEL_X", "s", cooldownMs = 1L))
    }
}

class LiveProbabilityEngineForensicRateLimit6356Test {

    @Test
    fun rapid_pivot_forensic_uses_rate_limiter() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue("RAPID_PIVOT_SHAPED_4572 emit must be behind ForensicEmitRateLimiter6356.shouldEmit",
            txt.contains("ForensicEmitRateLimiter6356.shouldEmit(\"LIVE_PROBABILITY_RAPID_PIVOT_SHAPED_4572\""))
    }

    @Test
    fun size_shape_forensic_uses_rate_limiter() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue("SIZE_SHAPE_5999 emit must be behind ForensicEmitRateLimiter6356.shouldEmit",
            txt.contains("ForensicEmitRateLimiter6356.shouldEmit(\"LIVE_PROBABILITY_SIZE_SHAPE_5999\""))
    }

    @Test
    fun raw_reality_clamp_forensic_uses_rate_limiter() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue("RAW_REALITY_CLAMP_6000 emit must be behind ForensicEmitRateLimiter6356.shouldEmit",
            txt.contains("ForensicEmitRateLimiter6356.shouldEmit(\"LIVE_PROBABILITY_RAW_REALITY_CLAMP_6000\""))
    }

    @Test
    fun label_inc_still_fires_every_call() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        // labelInc must NOT be gated by the rate limiter — cheap in-memory counters
        // are the only accurate frequency signal after the fix.
        assertTrue(txt.contains("PipelineHealthCollector.labelInc(\"LIVE_PROBABILITY_RAPID_PIVOT_SHAPED_4572_"))
        assertTrue(txt.contains("PipelineHealthCollector.labelInc(\"LIVE_PROBABILITY_SIZE_SHAPE_5999_"))
        assertTrue(txt.contains("PipelineHealthCollector.labelInc(\"LIVE_PROBABILITY_RAW_REALITY_CLAMP_6000_"))
    }
}
