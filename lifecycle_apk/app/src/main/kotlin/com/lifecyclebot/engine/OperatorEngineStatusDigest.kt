package com.lifecyclebot.engine

/** V5.0.4371 — compact digest for remaining engine status/snapshot hooks. Report-only. */
object OperatorEngineStatusDigest {
    fun status(): String {
        val sizing = try { LiveSizingProfile.summary().take(140) } catch (_: Throwable) { "LiveSizingProfile unavailable" }
        val keys = try { KeyValidator.snapshot().size.toString() + " keys" } catch (_: Throwable) { "KeyValidator unavailable" }
        val tokenMeta = "TokenMetaCache snapshot is instance-scoped"
        val endpoint = try { AutoEndpointMigrator.snapshot().size.toString() + " hosts" } catch (_: Throwable) { "AutoEndpointMigrator unavailable" }
        val memeTrace = try { MemePipelineTracer.snapshot().toString().take(140) } catch (_: Throwable) { "MemePipelineTracer unavailable" }
        // V5.0.6962 §THE_COUNT_WITHOUT_THE_CAUSE.
        //
        // memeTrace above publishes MemePipelineTracer.snapshot() — the STAGE
        // counts, including "ENTRY_BLOCKED = N". MemePipelineTracer.blockReasons(),
        // the per-reason breakdown that says WHY those N were blocked, had zero
        // callers. So the operator could see that entries were being refused and
        // never which gate was refusing them.
        //
        // On a book where every meme lane is negative and the open question is
        // whether the bot is starved of entries or drowning in bad ones, "412
        // blocked" without the reason mix is the least useful possible form of
        // that number. Top six keeps the digest line bounded.
        val memeBlockReasons = try {
            MemePipelineTracer.blockReasons().entries
                .sortedByDescending { it.value }
                .take(6)
                .joinToString(",") { "${it.key}=${it.value}" }
                .ifBlank { "none" }
        } catch (_: Throwable) { "MemePipelineTracer.blockReasons unavailable" }
        val cycle = try { CycleTimingTracker.snapshot().toString().take(140) } catch (_: Throwable) { "CycleTimingTracker unavailable" }
        val copilot = try { TradingCopilot.snapshot().toString().take(140) } catch (_: Throwable) { "TradingCopilot unavailable" }
        val fdg = try { com.lifecyclebot.engine.learning.FdgRouteVerdict.snapshot().toString().take(140) } catch (_: Throwable) { "FdgRouteVerdict unavailable" }
        val runtimeGuards = "RuntimeRegressionGuards summary requires supplied check list"
        val behaviorLearning = "BehaviorLearning summary is instance-scoped"
        val ev = "EVCalculator summary is result-scoped"
        return "OPERATOR_ENGINE_STATUS_DIGEST_4371 sizing=[$sizing] keys=[$keys] tokenMeta=[$tokenMeta] endpoint=[$endpoint] memeTrace=[$memeTrace] memeBlockReasons=[$memeBlockReasons] cycle=[$cycle] copilot=[$copilot] fdg=[$fdg] runtimeGuards=[$runtimeGuards] behaviorLearning=[$behaviorLearning] ev=[$ev] report_only=true no_gate_change=true no_execution_authority=true"
    }
}
