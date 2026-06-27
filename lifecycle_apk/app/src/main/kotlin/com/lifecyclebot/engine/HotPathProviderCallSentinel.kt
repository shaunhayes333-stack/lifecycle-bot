package com.lifecyclebot.engine

/**
 * V5.0.4295 — A39 report-only source-contract sentinel.
 *
 * ASI/SSI helpers may use providers only from background workers. Scanner,
 * FinalDecisionGate, Executor sizing/buy/sell hot paths must never wait on
 * Gemini/OpenRouter/Groq/LLM/API provider calls introduced by intelligence layers.
 */
object HotPathProviderCallSentinel {
    val hotPathFiles: List<String> = listOf(
        "BotService.kt",
        "FinalDecisionGate.kt",
        "Executor.kt",
        "SmartSizer.kt",
        "ProcessorAmountPlanner.kt",
    )

    val forbiddenProviderTokens: List<String> = listOf(
        "GeminiCopilot.generate",
        "OpenRouter",
        "Groq",
        "callProvider",
        "provider.complete",
        "llm.complete",
        "blockingProviderCall",
    )

    val allowedBackgroundWorkers: List<String> = listOf(
        "AsyncStrategyLab",
        "ResearchScout",
        "MultiAgentCriticStack",
        "ReflectiveOptimizerGEPA",
    )

    fun status(): String = "HOT_PATH_PROVIDER_CALL_SENTINEL_4295 hot_paths=${hotPathFiles.joinToString(",")} forbidden=${forbiddenProviderTokens.size} allowed_background=${allowedBackgroundWorkers.joinToString(",")} report_only=true no_pause_no_size_change=true"

    fun emit() {
        try {
            ForensicLogger.lifecycle("HOT_PATH_PROVIDER_CALL_SENTINEL_4295", status().take(900))
            PipelineHealthCollector.labelInc("HOT_PATH_PROVIDER_CALL_SENTINEL_4295")
        } catch (_: Throwable) {}
    }
}
