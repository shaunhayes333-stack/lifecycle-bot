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
        return "OPERATOR_AUX_STATUS_DIGEST_4364 tokenRefresh=[$tokenRefresh] birdeyeBudget=[$birdeyeBudget] apiHealth=[$apiHealth] fees=[$fees] exits=[$exits] liveTuner=[$liveTuner] scannerBrain=[$scannerBrain] playbook=[$playbook] strategyVariants=[$strategyVariants] exploration=[$exploration] noTrade=[$noTrade] sellFailures=[$sellFailures] sellJobs=[$sellJobs] report_only=true no_execution_authority=true no_gate_change=true playbook_execution_authority=Executor.liveBuy"
    }
}
