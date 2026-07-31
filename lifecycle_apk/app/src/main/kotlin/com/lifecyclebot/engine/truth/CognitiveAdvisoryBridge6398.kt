package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * V5.0.6398 — COGNITIVE ADVISORY BRIDGE (wire-up).
 *
 * Connects the real SuperAGI / SSI / LLM output surfaces to
 * AdaptiveFloorBrain6397.postAdvisory. Cognitive layers emit a signed
 * conviction score in [-1.0, +1.0] where positive = tighten (raise floor,
 * be more selective) and negative = relax (lower floor, participate more).
 *
 * The bridge clamps and quantises to the brain's per-channel ±3 range and
 * publishes with the raw reason string.
 */
object CognitiveAdvisoryBridge6398 {

    /** Maximum floor delta any single cognitive layer may push. */
    const val MAX_CHANNEL_DELTA: Int = AdaptiveFloorBrain6397.ADVISORY_MAX_DELTA

    val superAgiPosted = AtomicLong(0L)
    val ssiPosted = AtomicLong(0L)
    val llmPosted = AtomicLong(0L)

    /**
     * Post a SuperAGI advisory. `conviction` in [-1.0, +1.0].
     * Positive → tighten floor. Negative → relax floor.
     */
    fun postSuperAgi(conviction: Double, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val delta = (conviction.coerceIn(-1.0, 1.0) * MAX_CHANNEL_DELTA).roundToInt()
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SUPER_AGI, delta, reason, nowMs)
        superAgiPosted.incrementAndGet()
    }

    fun postSsi(conviction: Double, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val delta = (conviction.coerceIn(-1.0, 1.0) * MAX_CHANNEL_DELTA).roundToInt()
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SSI, delta, reason, nowMs)
        ssiPosted.incrementAndGet()
    }

    fun postLlm(conviction: Double, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val delta = (conviction.coerceIn(-1.0, 1.0) * MAX_CHANNEL_DELTA).roundToInt()
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, delta, reason, nowMs)
        llmPosted.incrementAndGet()
    }

    fun clearAll() {
        AdaptiveFloorBrain6397.clearAdvisory(AdaptiveFloorBrain6397.AdvisoryChannel.SUPER_AGI)
        AdaptiveFloorBrain6397.clearAdvisory(AdaptiveFloorBrain6397.AdvisoryChannel.SSI)
        AdaptiveFloorBrain6397.clearAdvisory(AdaptiveFloorBrain6397.AdvisoryChannel.LLM)
    }

    internal fun clearAllForTest() {
        clearAll()
        superAgiPosted.set(0L); ssiPosted.set(0L); llmPosted.set(0L)
    }
}
