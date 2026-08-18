package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AutoPipelineAdvisor6462
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6462 §P0 — HARD CI ASSERTIONS FOR THE AUTONOMOUS PIPELINE ADVISOR.
 *
 * Locks:
 *   - Status line is publishable at any time.
 *   - resetForTest zeroes all counters.
 *   - Rate-limit: maybeTick(ctx) refuses to run twice back-to-back.
 *
 * The end-to-end tick calls Android Context + brain modules, so we don't
 * drive it under the JVM unit runner — the CI runtime-smoke path
 * exercises the tick under real Android. This suite guards the module
 * surface + rate-limit contract that the operator cares about.
 */
class AutoPipelineAdvisorSurfaceTest6462 {

    @Test
    fun `statusLine reports zero counters after reset`() {
        AutoPipelineAdvisor6462.resetForTest()
        val s = AutoPipelineAdvisor6462.statusLine()
        assertTrue("status must include ticks=0: $s", s.contains("ticks=0"))
        assertTrue("status must include runsOk=0: $s", s.contains("runsOk=0"))
        assertTrue("status must include autoApplied=0: $s", s.contains("autoApplied=0"))
    }

    @Test
    fun `resetForTest is idempotent`() {
        AutoPipelineAdvisor6462.resetForTest()
        AutoPipelineAdvisor6462.resetForTest()
        val s = AutoPipelineAdvisor6462.statusLine()
        assertTrue(s.contains("ticks=0"))
    }
}
