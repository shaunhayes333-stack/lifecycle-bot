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
        // V5.0.6967 — per-lane exploration usage (micro, shadow) for the hour.
        // The ceilings this is measured against are now ENFORCED (they were not
        // before this version), so a lane sitting at its cap is the budget doing
        // its job rather than a symptom.
        val explorationBudget6967 = try {
            com.lifecyclebot.engine.learning.ExplorationBudget.snapshot().entries
                .filter { it.value.first > 0 || it.value.second > 0 }
                .sortedByDescending { it.value.first }
                .take(6)
                .joinToString(",") { "${it.key}:micro=${it.value.first}/shadow=${it.value.second}" }
                .ifBlank { "none" }
        } catch (_: Throwable) { "ExplorationBudget unavailable" }
        // V5.0.6972 — exit-reason x regime win rates. Every caller of
        // FluidLearning.recordPaperSell let exitReason/regime default, so this
        // table held one bucket (UNKNOWN@NEUT) for its whole life. Callers fixed
        // this version; this is the readout that shows the buckets filling.
        val exitTags6972 = try {
            FluidLearning.exitTagSummary6972()
        } catch (_: Throwable) { "FluidLearning unavailable" }
        // V5.0.6973 — three more zero-caller readouts, all reads.
        //
        //   getPnl24hSol           a rolling 24h P&L. The digest carries lifetime
        //                          and session figures and nothing in between, so
        //                          "is today going badly" had no number.
        //   lastPostMortemAgeMs    how long since the sentience loop last ran a
        //                          post-mortem. A stalled learner is invisible
        //                          otherwise — it simply stops improving.
        //   getPendingOrOpenCount  the tracker's own open+pending count, which
        //                          INCLUDES BUY_PENDING and CONFIRMED_PENDING_
        //                          BALANCE where status.openPositionCount does
        //                          not. Publishing both side by side makes a
        //                          divergence between the two visible rather
        //                          than something to rediscover later.
        val pnl24h6973 = try {
            "%.4f".format(TradeHistoryStore.getPnl24hSol())
        } catch (_: Throwable) { "-" }
        val postMortemAge6973 = try {
            val ms = SentienceHooks.lastPostMortemAgeMs()
            if (ms == Long.MAX_VALUE) "never" else "${ms / 60_000L}m"
        } catch (_: Throwable) { "-" }
        val trackerOpenPending6973 = try {
            HostWalletTokenTracker.getPendingOrOpenCount().toString()
        } catch (_: Throwable) { "-" }
        val cycle = try { CycleTimingTracker.snapshot().toString().take(140) } catch (_: Throwable) { "CycleTimingTracker unavailable" }
        val copilot = try { TradingCopilot.snapshot().toString().take(140) } catch (_: Throwable) { "TradingCopilot unavailable" }
        val fdg = try { com.lifecyclebot.engine.learning.FdgRouteVerdict.snapshot().toString().take(140) } catch (_: Throwable) { "FdgRouteVerdict unavailable" }
        val runtimeGuards = "RuntimeRegressionGuards summary requires supplied check list"
        val behaviorLearning = "BehaviorLearning summary is instance-scoped"
        val ev = "EVCalculator summary is result-scoped"
        return "OPERATOR_ENGINE_STATUS_DIGEST_4371 sizing=[$sizing] keys=[$keys] tokenMeta=[$tokenMeta] endpoint=[$endpoint] memeTrace=[$memeTrace] memeBlockReasons=[$memeBlockReasons] explorationBudget6967=[$explorationBudget6967] exitTags6972=[$exitTags6972] pnl24h=[$pnl24h6973] postMortemAge=[$postMortemAge6973] trackerOpenPending=[$trackerOpenPending6973] cycle=[$cycle] copilot=[$copilot] fdg=[$fdg] runtimeGuards=[$runtimeGuards] behaviorLearning=[$behaviorLearning] ev=[$ev] report_only=true no_gate_change=true no_execution_authority=true"
    }
}
