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
        return Report(snap, faults, diagnosis, faults.mapNotNull { actionFor(it) })
    }

    fun latestSnapshot(): RuntimeStateSnapshot = latest
    fun recentFaults(): List<InvariantGuardian.Fault> = recentFaults.toList()

    private fun actionFor(f: InvariantGuardian.Fault): RuntimeSelfHealer.Request? = when (f.code) {
        InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.FORCE_UI_RUNTIME_REBIND, reason = f.detail)
        InvariantGuardian.FaultCode.SELL_RECONCILER_DEAD -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.RESTART_SELL_RECONCILER, reason = f.detail)
        InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.DISABLE_LANE, target = "NOISY", reason = f.detail)
        InvariantGuardian.FaultCode.PAPER_LIVE_CONTAMINATION -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.PAUSE_TRADING, reason = f.detail)
        InvariantGuardian.FaultCode.SCANNER_RESTORE_POISONING -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.DISABLE_SCANNER_SOURCE, target = "MEME_REGISTRY_RESTORE", reason = f.detail)
        InvariantGuardian.FaultCode.MAIN_THREAD_STALL -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.REDUCE_SCANNER_CONCURRENCY, target = "2", reason = f.detail)
        InvariantGuardian.FaultCode.API_LAYER_DEGRADED -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.REDUCE_SCANNER_CONCURRENCY, target = "2", reason = f.detail)
        InvariantGuardian.FaultCode.HOST_TRACKER_DESYNC, InvariantGuardian.FaultCode.EXEC_REQUEST_INFLATION, InvariantGuardian.FaultCode.LEARNING_LEDGER_DUPLICATION -> RuntimeSelfHealer.Request(RuntimeSelfHealer.Action.PAUSE_TRADING, reason = f.detail)
    }
}
