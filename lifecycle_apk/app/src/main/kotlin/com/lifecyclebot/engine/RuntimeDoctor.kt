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
    @Volatile private var lastHeavyMitigationAtMs: Long = 0L
    @Volatile private var lastHeavyWorkerTimeout: Long = 0L
    @Volatile private var lastHeavyExitTimeout: Long = 0L
    @Volatile private var lastHeavyExitReset: Long = 0L
    @Volatile private var lastFanoutMitigationAtMs: Long = 0L
    private const val HEAVY_MITIGATION_COOLDOWN_MS = 120_000L
    private const val FANOUT_MITIGATION_COOLDOWN_MS = 120_000L
    private const val HEAVY_WORKER_TIMEOUT_DELTA = 250L

    fun resetForTests() {
        latest = RuntimeStateSnapshot.current()
        recentFaults.clear()
        lastHeavyMitigationAtMs = 0L
        lastHeavyWorkerTimeout = 0L
        lastHeavyExitTimeout = 0L
        lastHeavyExitReset = 0L
        lastFanoutMitigationAtMs = 0L
    }

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
        val actions = faults.flatMap { commandsFor(it, snap) }
        actions.forEach { cmd -> try { RuntimeMitigationBus.publish(cmd) } catch (_: Throwable) {} }
        return Report(snap, faults, diagnosis, actions.map { toRequest(it) })
    }

    fun latestSnapshot(): RuntimeStateSnapshot = latest
    fun recentFaults(): List<InvariantGuardian.Fault> = recentFaults.toList()

    private fun commandsFor(f: InvariantGuardian.Fault, snap: RuntimeStateSnapshot): List<RuntimeMitigationBus.Command> = when (f.code) {
        InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN -> listOf(RuntimeMitigationBus.Command.PauseTrading(f.detail, 30_000L))
        InvariantGuardian.FaultCode.SELL_RECONCILER_DEAD -> listOf(RuntimeMitigationBus.Command.RestartSellReconciler(f.detail))
        InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION -> fanoutMitigations(f, snap)
        InvariantGuardian.FaultCode.PAPER_LIVE_CONTAMINATION -> listOf(RuntimeMitigationBus.Command.PauseTrading(f.detail, 60_000L))
        InvariantGuardian.FaultCode.SCANNER_RESTORE_POISONING -> listOf(RuntimeMitigationBus.Command.QuarantineSource("MEME_REGISTRY_RESTORE", f.detail, 60_000L))
        InvariantGuardian.FaultCode.MAIN_THREAD_STALL -> lightMitigations(f)
        InvariantGuardian.FaultCode.API_LAYER_DEGRADED -> listOf(RuntimeMitigationBus.Command.ReduceScannerConcurrency(2, f.detail, 60_000L))
        InvariantGuardian.FaultCode.HOST_TRACKER_DESYNC,
        InvariantGuardian.FaultCode.EXEC_REQUEST_INFLATION,
        InvariantGuardian.FaultCode.LEARNING_LEDGER_DUPLICATION,
        InvariantGuardian.FaultCode.FDG_FANOUT_EXPLOSION,
        InvariantGuardian.FaultCode.FDG_SIGNAL_BYPASS,
        InvariantGuardian.FaultCode.EXIT_SWEEP_UNSTABLE -> exitStabilityMitigations(f, snap)
    }

    private fun lightMitigations(f: InvariantGuardian.Fault): List<RuntimeMitigationBus.Command> {
        return listOf(RuntimeMitigationBus.Command.ReduceScannerConcurrency(2, f.detail, 60_000L))
    }

    private fun exitStabilityMitigations(f: InvariantGuardian.Fault, snap: RuntimeStateSnapshot): List<RuntimeMitigationBus.Command> {
        val pipe = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val worker = pipe?.labelCounts?.get("LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT") ?: 0L
        val exitTimeout = pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_TIMEOUT") ?: 0L
        val exitReset = pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_FORCE_RESET")
            ?: pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_RESET")
            ?: 0L
        val now = System.currentTimeMillis()
        val activeQualityOnly = RuntimeConfigOverlay.isHardQualityOnlyActive()
        val workerDelta = worker - lastHeavyWorkerTimeout
        val exitAdvanced = exitTimeout > lastHeavyExitTimeout || exitReset > lastHeavyExitReset
        val cooldownPassed = now - lastHeavyMitigationAtMs >= HEAVY_MITIGATION_COOLDOWN_MS

        // V5.9.1131 — cumulative workerTimeout counters are monotonically increasing.
        // 3098 showed RuntimeDoctor re-publishing FORCE_QUALITY_ONLY + scanner cap
        // every loop from the same 607 historical timeouts, creating 656 mitigation
        // writes and a self-DOS. If the system is already in quality-only and the
        // counters have not materially advanced, observe only.
        if (activeQualityOnly && !exitAdvanced && workerDelta < HEAVY_WORKER_TIMEOUT_DELTA) {
            return emptyList()
        }
        if (!cooldownPassed && !exitAdvanced && workerDelta < HEAVY_WORKER_TIMEOUT_DELTA) {
            return emptyList()
        }
        lastHeavyMitigationAtMs = now
        lastHeavyWorkerTimeout = worker
        lastHeavyExitTimeout = exitTimeout
        lastHeavyExitReset = exitReset
        return fanoutMitigations(f, snap)
    }

    private fun fanoutMitigations(f: InvariantGuardian.Fault, snapState: RuntimeStateSnapshot): List<RuntimeMitigationBus.Command> {
        val snap = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val topSource = snap?.intakeBySource?.maxByOrNull { it.value }?.key ?: "PUMP_PORTAL_WS"
        val now = System.currentTimeMillis()
        // V5.9.1150 — RuntimeDoctor was still self-DOSing: 3117 showed
        // RUNTIME_MITIGATION_APPLIED=264, repeatedly publishing the same scanner
        // cap because cumulative workerTimeout/fanout counters keep satisfying
        // invariant checks. Keep mitigations idempotent at the doctor source too:
        // observe during cooldown unless this is the targeted restore quarantine.
        val cooldown = now - lastFanoutMitigationAtMs < FANOUT_MITIGATION_COOLDOWN_MS
        val shouldQuarantineRestore = topSource == "MEME_REGISTRY_RESTORE"
        if (cooldown && !shouldQuarantineRestore) return emptyList()
        lastFanoutMitigationAtMs = now
        // V5.9.1132 — NO AUTO LANE DISABLES.
        // Operator explicitly rejected the 24h loop where RuntimeDoctor kept
        // forcing QUALITY-only / disabling lanes as a mitigation. Fanout pressure
        // is handled with soft throughput shaping only. Lanes stay enabled;
        // FDG/safety/execution authority remain the real guards.
        return buildList {
            add(RuntimeMitigationBus.Command.ReduceScannerConcurrency(2, f.detail, 60_000L))
            if (shouldQuarantineRestore) add(RuntimeMitigationBus.Command.QuarantineSource(topSource, f.detail, 60_000L))
        }
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
