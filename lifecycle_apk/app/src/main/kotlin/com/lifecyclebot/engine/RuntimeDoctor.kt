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
        InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION -> fanoutMitigations(f)
        InvariantGuardian.FaultCode.PAPER_LIVE_CONTAMINATION -> listOf(RuntimeMitigationBus.Command.PauseTrading(f.detail, 60_000L))
        InvariantGuardian.FaultCode.SCANNER_RESTORE_POISONING -> listOf(RuntimeMitigationBus.Command.QuarantineSource("MEME_REGISTRY_RESTORE", f.detail, 60_000L))
        InvariantGuardian.FaultCode.MAIN_THREAD_STALL -> fanoutMitigations(f)
        InvariantGuardian.FaultCode.API_LAYER_DEGRADED -> listOf(RuntimeMitigationBus.Command.ReduceScannerConcurrency(2, f.detail, 60_000L))
        InvariantGuardian.FaultCode.HOST_TRACKER_DESYNC,
        InvariantGuardian.FaultCode.EXEC_REQUEST_INFLATION,
        InvariantGuardian.FaultCode.LEARNING_LEDGER_DUPLICATION,
        InvariantGuardian.FaultCode.FDG_FANOUT_EXPLOSION,
        InvariantGuardian.FaultCode.FDG_SIGNAL_BYPASS,
        InvariantGuardian.FaultCode.EXIT_SWEEP_UNSTABLE -> fanoutMitigations(f)
    }

    private fun fanoutMitigations(f: InvariantGuardian.Fault): List<RuntimeMitigationBus.Command> {
        val snap = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val topSource = snap?.intakeBySource?.maxByOrNull { it.value }?.key ?: "PUMP_PORTAL_WS"
        return listOf(
            RuntimeMitigationBus.Command.ForceQualityOnly(f.detail, 5 * 60_000L),
            RuntimeMitigationBus.Command.ReduceScannerConcurrency(1, f.detail, 5 * 60_000L),
            RuntimeMitigationBus.Command.DisableScannerSource(topSource, f.detail, 60_000L),
            RuntimeMitigationBus.Command.QuarantineSource(topSource, f.detail, 60_000L),
        )
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
