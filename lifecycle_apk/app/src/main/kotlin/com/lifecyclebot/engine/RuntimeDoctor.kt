package com.lifecyclebot.engine

object RuntimeDoctor {
    data class Report(
        val snapshot: RuntimeStateSnapshot,
        val faults: List<InvariantGuardian.Fault>,
        val diagnosis: StateDebuggerAI.Diagnosis,
        val recommendedActions: List<RuntimeSelfHealer.Request>,
    )

    @Volatile private var latest: RuntimeStateSnapshot = RuntimeStateSnapshot.current()
    private val recentFaults = java.util.concurrent.ConcurrentLinkedDeque<InvariantGuardian.Fault>()

    fun tick(uiRunning: Boolean? = null): Report {
        val snap = RuntimeStateSnapshot.current(uiRunning)
        latest = snap
        val faults = InvariantGuardian.check(snap, uiRunning = uiRunning ?: (snap.uiState == "RUNNING"))
        faults.forEach { f ->
            recentFaults.addLast(f)
            while (recentFaults.size > 20) recentFaults.pollFirst()
        }
        val ctx = StateDebuggerAI.Context(
            snapshot = snap,
            forensicEvents = PipelineHealthCollector.snapshot().recentEvents.takeLast(200).map { "${it.tag}:${it.symbol}:${it.message}" },
            tradeRows = try { TradeHistoryStore.getAllTrades().takeLast(50).map { "${it.side}:${it.mode}:${it.mint}:${it.pnlPct}:${it.reason}" } } catch (_: Throwable) { emptyList() },
            invariantFaults = recentFaults.toList().takeLast(20),
            configSummary = try {
                val c = com.lifecyclebot.data.ConfigStore.load(BotService.instance!!.applicationContext)
                "paper=${c.paperMode} auto=${c.autoTrade} tradingMode=${c.tradingMode} meme=${c.memeTraderEnabled} markets=${c.marketsTraderEnabled} shadow=${c.shadowPaperEnabled}"
            } catch (_: Throwable) { "config_unavailable" },
            apiHealth = snap.apiHealth,
        )
        val diagnosis = StateDebuggerAI.deterministicFallback(ctx)
        val actions = faults.flatMap { commandsFor(it) }
        actions.forEach { cmd -> try { RuntimeMitigationBus.publish(cmd) } catch (_: Throwable) {} }
        return Report(snap, faults, diagnosis, actions.map { toRequest(it) })
    }

    fun latestSnapshot(): RuntimeStateSnapshot = latest
    fun recentFaults(): List<InvariantGuardian.Fault> = recentFaults.toList()

    private fun commandsFor(f: InvariantGuardian.Fault): List<RuntimeMitigationBus.Command> = when (f.code) {
        InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN -> listOf(RuntimeMitigationBus.Command.PauseTrading(f.detail, 30_000L))
        InvariantGuardian.FaultCode.SELL_RECONCILER_DEAD -> listOf(RuntimeMitigationBus.Command.RestartSellReconciler(f.detail))
        InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION -> listOf(
            // V5.9.1112 — unlock multi-lane runtime after 1109-1111 repairs.
            // Do NOT auto-force QUALITY-only and do NOT disable all specialist lanes
            // on a raw laneEval/intake ratio. Multi-lane recovery intentionally raises
            // lane-eval volume; the repaired FinalExecutionPermit/FDG/preauth guards
            // now own execution containment. RuntimeDoctor may still reduce scanner
            // pressure and quarantine restore poison, but it must not silently re-cage
            // the trader the moment lanes come back.
            RuntimeMitigationBus.Command.DisableScannerSource("MEME_REGISTRY_RESTORE", f.detail, 60_000L),
        )
        InvariantGuardian.FaultCode.PAPER_LIVE_CONTAMINATION -> listOf(RuntimeMitigationBus.Command.PauseTrading(f.detail, 60_000L))
        InvariantGuardian.FaultCode.SCANNER_RESTORE_POISONING -> listOf(RuntimeMitigationBus.Command.QuarantineSource("MEME_REGISTRY_RESTORE", f.detail, 60_000L))
        InvariantGuardian.FaultCode.MAIN_THREAD_STALL -> listOf(RuntimeMitigationBus.Command.ReduceScannerConcurrency(2, f.detail, 60_000L))
        InvariantGuardian.FaultCode.API_LAYER_DEGRADED -> {
            // V5.9.1116 — do not throttle the Meme scanner for unrelated x/groq
            // failures. Birdeye lockdown is still handled by BirdeyeBudgetGate;
            // source-level scanner errors are isolated by runScanBatch.
            val d = f.detail.lowercase()
            if (d.contains("birdeyelocked=true")) listOf(RuntimeMitigationBus.Command.ReduceScannerConcurrency(4, f.detail, 60_000L)) else emptyList()
        }
        InvariantGuardian.FaultCode.HOST_TRACKER_DESYNC, InvariantGuardian.FaultCode.EXEC_REQUEST_INFLATION, InvariantGuardian.FaultCode.LEARNING_LEDGER_DUPLICATION -> listOf(RuntimeMitigationBus.Command.PauseTrading(f.detail, 60_000L))
    }

    private fun toRequest(cmd: RuntimeMitigationBus.Command): RuntimeSelfHealer.Request = when (cmd) {
        is RuntimeMitigationBus.Command.DisableLane -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.DISABLE_LANE, cmd.lane, cmd.reason)
        is RuntimeMitigationBus.Command.DisableScannerSource -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.DISABLE_SCANNER_SOURCE, cmd.source, cmd.reason)
        is RuntimeMitigationBus.Command.ReduceScannerConcurrency -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.REDUCE_SCANNER_CONCURRENCY, cmd.value.toString(), cmd.reason)
        is RuntimeMitigationBus.Command.PauseTrading -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.PAUSE_TRADING, reason = cmd.reason)
        is RuntimeMitigationBus.Command.DisablePreAuth -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.PAUSE_TRADING, reason = cmd.reason)
        is RuntimeMitigationBus.Command.ForceQualityOnly -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.DISABLE_LANE, "NON_QUALITY", cmd.reason)
        is RuntimeMitigationBus.Command.RestartSellReconciler -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.RESTART_SELL_RECONCILER, reason = cmd.reason)
        is RuntimeMitigationBus.Command.QuarantineSource -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.DISABLE_SCANNER_SOURCE, cmd.source, cmd.reason)
    }
}
