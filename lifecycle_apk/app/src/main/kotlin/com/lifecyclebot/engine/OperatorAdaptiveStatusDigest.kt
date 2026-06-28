package com.lifecyclebot.engine

/** V5.0.4365 — compact digest for adaptive/runtime status hooks. Report-only. */
object OperatorAdaptiveStatusDigest {
    fun status(): String {
        val liveProb = try { LiveProbabilityEngine.statusLine().take(120) } catch (_: Throwable) { "LiveProbabilityEngine unavailable" }
        val quality = try { QualityLadder.statusLine().take(120) } catch (_: Throwable) { "QualityLadder unavailable" }
        val laneExpectancy = try { LaneExpectancyDamper.statusLine().take(120) } catch (_: Throwable) { "LaneExpectancyDamper unavailable" }
        val antiChoke = try { AntiChokeManager.statusLine().take(120) } catch (_: Throwable) { "AntiChokeManager unavailable" }
        val freeRange = "FreeRangeMode instance-scoped"
        val sentience = try { SentienceHooks.statusSummary().take(160) } catch (_: Throwable) { "SentienceHooks unavailable" }
        val llmLab = try { com.lifecyclebot.engine.lab.LlmLabEngine.statusLine().take(160) } catch (_: Throwable) { "LlmLabEngine unavailable" }
        val llmStore = try { com.lifecyclebot.engine.lab.LlmLabStore.summary().take(160) } catch (_: Throwable) { "LlmLabStore unavailable" }
        val coldStreak = "ColdStreakDamper instance-scoped"
        val execCounters = try { com.lifecyclebot.engine.runtime.ExecutionCounterContract.snapshot().toString().take(160) } catch (_: Throwable) { "ExecutionCounterContract unavailable" }
        return "OPERATOR_ADAPTIVE_STATUS_DIGEST_4365 liveProb=[$liveProb] quality=[$quality] laneExpectancy=[$laneExpectancy] antiChoke=[$antiChoke] freeRange=[$freeRange] sentience=[$sentience] llmLab=[$llmLab] llmStore=[$llmStore] coldStreak=[$coldStreak] execCounters=[$execCounters] report_only=true no_gate_change=true no_execution_authority=true"
    }
}
