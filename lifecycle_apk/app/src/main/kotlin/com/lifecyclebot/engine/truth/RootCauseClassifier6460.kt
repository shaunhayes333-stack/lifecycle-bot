package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6460 §P0 — ROOT CAUSE CLASSIFIER (single authority).
 *
 * Operator: header still claims UI_MAIN_THREAD while actual worst
 * trading phase is POST_LEARNING_MAINTENANCE=54.5s. Use the correlated
 * trading-path duration as the primary attribution.
 */
object RootCauseClassifier6460 {
    enum class Cause { HEALTHY, POST_LEARNING_MAINTENANCE, SCANNER_STALL, PROVIDER_TIMEOUT, UI_MAIN_THREAD, UNKNOWN }
    private val classifications = AtomicLong(0L)

    fun classify(
        postLearningMs: Long = 0L,
        scannerWorstMs: Long = 0L,
        providerWorstMs: Long = 0L,
        uiMainThreadMs: Long = 0L,
    ): Cause {
        classifications.incrementAndGet()
        val candidates = listOf(
            Cause.POST_LEARNING_MAINTENANCE to postLearningMs,
            Cause.SCANNER_STALL to scannerWorstMs,
            Cause.PROVIDER_TIMEOUT to providerWorstMs,
            Cause.UI_MAIN_THREAD to uiMainThreadMs,
        )
        val worst = candidates.maxByOrNull { it.second } ?: return Cause.HEALTHY
        if (worst.second < 3_000L) return Cause.HEALTHY
        try { PipelineHealthCollector.labelInc("ROOT_CAUSE_${worst.first}_6460") } catch (_: Throwable) {}
        return worst.first
    }

    fun statusLine(): String = "classifications=${classifications.get()}"
}
