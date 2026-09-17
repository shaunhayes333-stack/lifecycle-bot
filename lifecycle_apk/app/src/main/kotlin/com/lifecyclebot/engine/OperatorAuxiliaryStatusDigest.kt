package com.lifecyclebot.engine

/**
 * V5.0.4364 — compact operator digest for status-capable systems that were
 * otherwise only reachable through scattered debug surfaces. Report-only.
 */
object OperatorAuxiliaryStatusDigest {
    fun status(): String {
        val tokenRefresh = try { TokenRefreshPolicy.snapshot().toString().take(140) } catch (_: Throwable) { "TokenRefreshPolicy unavailable" }
        val birdeyeBudget = try { BirdeyeBudgetGate.snapshot().toString().take(140) } catch (_: Throwable) { "BirdeyeBudgetGate unavailable" }
        val apiHealth = try { ApiHealthMonitor.snapshot().size.toString() + " hosts" } catch (_: Throwable) { "ApiHealthMonitor unavailable" }
        val fees = try { FeeAccumulator.snapshot().take(140) } catch (_: Throwable) { "FeeAccumulator unavailable" }
        val exits = try { ExitReasonTracker.snapshot().take(140) } catch (_: Throwable) { "ExitReasonTracker unavailable" }
        val liveTuner = try { LiveStrategyTuner.statusLine().take(140) } catch (_: Throwable) { "LiveStrategyTuner unavailable" }
        val scannerBrain = try { ScannerSourceBrain.summary().take(140) } catch (_: Throwable) { "ScannerSourceBrain unavailable" }
        val playbook = try { CommonSenseTradePlaybook.statusLine().take(180) } catch (_: Throwable) { "CommonSenseTradePlaybook unavailable" }
        val strategyVariants = try { com.lifecyclebot.engine.learning.StrategyVariantStore.snapshot().toString().take(140) } catch (_: Throwable) { "StrategyVariantStore unavailable" }
        val exploration = try { com.lifecyclebot.engine.learning.ExplorationBudget.snapshot().size.toString() + " buckets" } catch (_: Throwable) { "ExplorationBudget unavailable" }
        val noTrade = try { com.lifecyclebot.engine.learning.NoTradeObservationStore.snapshot().toString().take(140) } catch (_: Throwable) { "NoTradeObservationStore unavailable" }
        val sellFailures = try { com.lifecyclebot.engine.sell.SellFailureHistory.snapshot().size.toString() + " mints" } catch (_: Throwable) { "SellFailureHistory unavailable" }
        val sellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size.toString() + " jobs" } catch (_: Throwable) { "SellJobRegistry unavailable" }
        val growthDash = try { com.lifecyclebot.engine.truth.GrowthDashboardSnapshot6409.tile() } catch (_: Throwable) { "GrowthDashboardSnapshot6409 unavailable" }
        val liveExecReadiness6411 = try { com.lifecyclebot.engine.truth.LiveExecutionReadiness6411.tile() } catch (_: Throwable) { "LiveExecutionReadiness6411 unavailable" }
        val adapterCircuits6411 = try { com.lifecyclebot.engine.truth.ProviderDomainCircuits6411.statusLine() } catch (_: Throwable) { "ProviderDomainCircuits6411 unavailable" }
        val attemptJournal6411 = try { com.lifecyclebot.engine.truth.ExecutionAttemptJournal6411.statusLine() } catch (_: Throwable) { "ExecutionAttemptJournal6411 unavailable" }
        val ticketMachine6411 = try { com.lifecyclebot.engine.truth.ExecutionTicketMachine6411.statusLine() } catch (_: Throwable) { "ExecutionTicketMachine6411 unavailable" }
        val canary6411 = try { com.lifecyclebot.engine.truth.LiveCanaryMode6411.statusLine() } catch (_: Throwable) { "LiveCanaryMode6411 unavailable" }
        val scannerDedupe6411 = try { com.lifecyclebot.engine.truth.ScannerCanonicalDedupe6411.statusLine() } catch (_: Throwable) { "ScannerCanonicalDedupe6411 unavailable" }
        val tokenMapVersion6411 = try { com.lifecyclebot.engine.truth.TokenMapVersionGuard6411.statusLine() } catch (_: Throwable) { "TokenMapVersionGuard6411 unavailable" }
        val cycleProfiler6411 = try { com.lifecyclebot.engine.truth.CycleProfiler6411.tile() } catch (_: Throwable) { "CycleProfiler6411 unavailable" }
        val workerPools6411 = try { com.lifecyclebot.engine.truth.WorkerPoolDomainRegistry6411.statusLine() } catch (_: Throwable) { "WorkerPoolDomainRegistry6411 unavailable" }
        val safetyProof6411 = try { com.lifecyclebot.engine.truth.SafetyProofDegradation6411.statusLine() } catch (_: Throwable) { "SafetyProofDegradation6411 unavailable" }
        val laneQuar6411 = try { com.lifecyclebot.engine.truth.LaneQuarantineRegistry6411.statusLine() } catch (_: Throwable) { "LaneQuarantineRegistry6411 unavailable" }
        val exitInv6411 = try { com.lifecyclebot.engine.truth.ExitPipelineInvariant6411.statusLine() } catch (_: Throwable) { "ExitPipelineInvariant6411 unavailable" }
        val posIdent6411 = try { com.lifecyclebot.engine.truth.PositionIdentity6411.statusLine() } catch (_: Throwable) { "PositionIdentity6411 unavailable" }
        val costBasisRepair6412 = try { com.lifecyclebot.engine.truth.PositionCostBasisRepair6412.statusLine() } catch (_: Throwable) { "PositionCostBasisRepair6412 unavailable" }
        val moonshot6415 = try { com.lifecyclebot.engine.truth.EarlyMoonshotHunter6415.statusLine() } catch (_: Throwable) { "EarlyMoonshotHunter6415 unavailable" }
        val liveGrowth6416 = try { com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.statusLine() } catch (_: Throwable) { "LiveGrowthCompounder6416 unavailable" }
        // V5.0.6949 §THE_READOUT_THAT_ANSWERS_WHETHER_ANY_OF_THIS_WORKED.
        //
        // LiveWinDNAStore.winningExitReasons / losingExitReasons / holdTimeStats
        // and ExitIntelligence.getProfitableRate / getLearnedMaxHoldMinutes all
        // had zero callers. They are exactly the numbers that say whether the
        // exit work in 6919-6949 changed anything: WHICH reasons close winners,
        // WHICH close losers, how long holds actually run, and where the learned
        // max hold has settled.
        //
        // Without them the only evidence available was the operator's journal
        // showing STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP and TICK_HARD_FLOOR as
        // the only exits on record — which is a symptom, not a distribution.
        // Top three each way keeps the digest line bounded.
        val exitReasons6949 = try {
            val win = com.lifecyclebot.engine.LiveWinDNAStore.winningExitReasons()
                .take(3).joinToString(",") { "${it.first}:${it.second}" }.ifBlank { "none" }
            val lose = com.lifecyclebot.engine.LiveWinDNAStore.losingExitReasons()
                .take(3).joinToString(",") { "${it.first}:${it.second}" }.ifBlank { "none" }
            val (p50, p75, p90) = com.lifecyclebot.engine.LiveWinDNAStore.holdTimeStats()
            "win=[$win] lose=[$lose] holdMinP50=$p50 P75=$p75 P90=$p90"
        } catch (_: Throwable) { "LiveWinDNAStore unavailable" }
        val exitLearner6949 = try {
            "profitableRate=${"%.1f".format(com.lifecyclebot.engine.ExitIntelligence.getProfitableRate())}% " +
                "learnedMaxHoldMin=${com.lifecyclebot.engine.ExitIntelligence.getLearnedMaxHoldMinutes()} " +
                "totalExits=${com.lifecyclebot.engine.ExitIntelligence.getTotalExits()} " +
                "trackedPeaks=${com.lifecyclebot.engine.truth.PeakAdaptiveTrail6390.trackedPeakCount6948()}"
        } catch (_: Throwable) { "ExitIntelligence unavailable" }
        return "OPERATOR_AUX_STATUS_DIGEST_4364 exitReasons6949=[$exitReasons6949] exitLearner6949=[$exitLearner6949]tokenRefresh=[$tokenRefresh] birdeyeBudget=[$birdeyeBudget] apiHealth=[$apiHealth] fees=[$fees] exits=[$exits] liveTuner=[$liveTuner] scannerBrain=[$scannerBrain] playbook=[$playbook] strategyVariants=[$strategyVariants] exploration=[$exploration] noTrade=[$noTrade] sellFailures=[$sellFailures] sellJobs=[$sellJobs] growthDashboard=[$growthDash] liveExecReadiness6411=[$liveExecReadiness6411] adapterCircuits6411=[$adapterCircuits6411] attemptJournal6411=[$attemptJournal6411] ticketMachine6411=[$ticketMachine6411] canary6411=[$canary6411] scannerDedupe6411=[$scannerDedupe6411] tokenMapVersion6411=[$tokenMapVersion6411] cycleProfiler6411=[$cycleProfiler6411] workerPools6411=[$workerPools6411] safetyProof6411=[$safetyProof6411] laneQuar6411=[$laneQuar6411] exitInv6411=[$exitInv6411] posIdent6411=[$posIdent6411] costBasisRepair6412=[$costBasisRepair6412] moonshot6415=[$moonshot6415] liveGrowth6416=[$liveGrowth6416] report_only=true no_execution_authority=true no_gate_change=true playbook_execution_authority=Executor.liveBuy"
    }
}
