package com.lifecyclebot.engine

/**
 * V5.0.4295 — A40 report-only sentinel for terminal-learning fanout mux safety.
 *
 * Background fanout must consume event-local snapshots captured before coroutine
 * launch. It must not re-read mutable TokenState for mode/lane/source/positionId
 * after the event moved on, otherwise paper/live attribution and learner labels
 * can drift.
 */
object LearningFanoutMuxSentinel {
    val requiredEventLocalFields: List<String> = listOf(
        "mode",
        "lane",
        "source",
        "positionId",
        "mint",
        "symbol",
        "timestamp",
        "build",
    )

    fun report(
        mode: String,
        lane: String,
        source: String,
        positionId: String,
        mint: String,
        symbol: String,
        eventTsMs: Long,
        build: String = com.lifecyclebot.BuildConfig.VERSION_NAME,
    ) {
        try {
            val missing = mutableListOf<String>()
            if (mode.isBlank()) missing += "mode"
            if (lane.isBlank()) missing += "lane"
            if (source.isBlank()) missing += "source"
            if (positionId.isBlank()) missing += "positionId"
            if (mint.isBlank()) missing += "mint"
            if (eventTsMs <= 0L) missing += "timestamp"
            if (missing.isNotEmpty()) {
                ForensicLogger.lifecycle(
                    "LEARNING_FANOUT_MUX_SENTINEL_4295",
                    "missing=${missing.joinToString(",")} mode=${mode.take(32)} lane=${lane.take(32)} source=${source.take(48)} positionId=${positionId.take(64)} mint=${mint.take(10)} symbol=${symbol.take(16)} build=$build report_only=true no_learning_block=true"
                )
                PipelineHealthCollector.labelInc("LEARNING_FANOUT_MUX_SENTINEL_4295")
            }
        } catch (_: Throwable) {}
    }

    fun status(): String = "LEARNING_FANOUT_MUX_SENTINEL_4295 required=${requiredEventLocalFields.joinToString(",")} report_only=true event_local_snapshot_required=true no_mutable_tokenstate_in_background=true"
}
