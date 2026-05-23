package com.lifecyclebot.engine

/** Canonical AATE Runtime Doctor snapshot emitted every 1-5s by RuntimeDoctor. */
data class RuntimeStateSnapshot(
    val buildVersion: String,
    val buildTag: String,
    val runtimeGeneration: Long,
    val uiState: String,
    val runtimeState: String,
    val botLoopActive: Boolean,
    val scannerActive: Boolean,
    val sellReconcilerStarted: Boolean,
    val hostTrackerOpenCount: Int,
    val positionStoreOpenCount: Int,
    val mode: String,
    val enabledTraders: String,
    val intake: Long,
    val safety: Long,
    val v3: Long,
    val laneEval: Long,
    val fdg: Long,
    val exec: Long,
    val exit: Long,
    val learningTrades: Long,
    val uniqueClosedPositionIds: Long,
    val staleSellLocks: Int,
    val mainUpdateSkippedInactive: Long,
    val anrHints: Int,
    val apiHealth: Map<String, ApiSummary>,
    val topBlockReasons: Map<String, Long>,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    data class ApiSummary(val successRatePct: Int, val failures: Int, val avgLatencyMs: Int, val lastError: String)

    companion object {
        fun current(uiRunning: Boolean? = null): RuntimeStateSnapshot {
            val runtime = BotRuntimeController.snapshot()
            val pipe = PipelineHealthCollector.snapshot()
            val mode = when {
                RuntimeRepairState.shouldForcePaper() -> "PAPER_FORCED_BY_DOCTOR"
                RuntimeModeAuthority.isLive() -> "LIVE"
                else -> "PAPER"
            }
            val api = ApiHealthMonitor.snapshot().mapValues { (_, s) ->
                ApiSummary(
                    successRatePct = (s.successRate() * 100.0).toInt(),
                    failures = s.failures4xx.get() + s.failures5xx.get() + s.networkErrors.get(),
                    avgLatencyMs = s.avgLatencyMs().toInt(),
                    lastError = s.lastErrorMessage.get() ?: "",
                )
            }
            return RuntimeStateSnapshot(
                buildVersion = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" },
                buildTag = try { PipelineHealthCollector.dumpText().lineSequence().firstOrNull { it.contains("Tag:") }?.substringAfter("Tag:")?.trim() ?: "unknown" } catch (_: Throwable) { "unknown" },
                runtimeGeneration = runtime.runtimeGeneration,
                uiState = if (uiRunning ?: runtime.runtimeActive) "RUNNING" else "STOPPED",
                runtimeState = runtime.state.name,
                botLoopActive = runtime.botLoopJobActive,
                scannerActive = runtime.scannerActive,
                sellReconcilerStarted = runtime.sellReconcilerStarted,
                hostTrackerOpenCount = runtime.hostTrackerOpenCount,
                positionStoreOpenCount = runtime.hostTrackerOpenCount,
                mode = mode,
                enabledTraders = runtime.enabledTraders,
                intake = pipe.phaseCounts["INTAKE"] ?: 0L,
                safety = pipe.phaseCounts["SAFETY"] ?: 0L,
                v3 = pipe.phaseCounts["V3"] ?: 0L,
                laneEval = pipe.phaseCounts["LANE_EVAL"] ?: 0L,
                fdg = pipe.phaseCounts["FDG"] ?: 0L,
                exec = pipe.phaseCounts["EXEC"] ?: 0L,
                exit = pipe.phaseCounts["EXIT"] ?: 0L,
                learningTrades = TradeOutcomeLedger.uniqueClosedPositionCount().toLong(),
                uniqueClosedPositionIds = TradeOutcomeLedger.uniqueClosedPositionCount().toLong(),
                staleSellLocks = try { BotService.instance?.executor?.sellLockHeldCount() ?: 0 } catch (_: Throwable) { 0 },
                mainUpdateSkippedInactive = pipe.labelCounts["LIFECYCLE/MAIN_UPDATE_SKIPPED_INACTIVE"] ?: 0L,
                anrHints = pipe.anrHints,
                apiHealth = api,
                topBlockReasons = pipe.blockReasonCounts.entries.sortedByDescending { it.value }.take(10).associate { it.key to it.value },
            )
        }
    }
}
