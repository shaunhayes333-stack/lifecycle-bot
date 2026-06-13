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
    // V5.9.1162 — domain-separated open truth. Host wallet truth is LIVE
    // wallet only; paper positions live in BotStatus/position store and must
    // not be hidden by a wallet-held count of zero.
    val paperOpenPositions: Int,
    val liveOpenPositions: Int,
    val walletHeldMints: Int,
    val canonicalOpenPositions: Int,
    val orphanPaperPositions: Int,
    val orphanLivePositions: Int,
    // V5.9.1518 — PATCH ITEM 1/7: position-wallet reconciler progress. Must be
    // >0 within ~30s of runtime start; if it stays 0 while running, the
    // reconciler is stalled (RECONCILER_STALLED fault).
    val reconcilerTotalChecked: Int,
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
    val activeMitigations: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
) {
    data class ApiSummary(val successRatePct: Int, val failures: Int, val avgLatencyMs: Int, val lastError: String)

    companion object {
        fun current(uiRunning: Boolean? = null): RuntimeStateSnapshot {
            val runtime = BotRuntimeController.snapshot()
            val pipe = PipelineHealthCollector.snapshot()
            // V5.9.1586 — single runtime authority. Doctor/repair state must never
            // rewrite LIVE into PAPER after start. If the operator starts LIVE and
            // wallet readiness is green, every scanner/lane/FDG/executor reader must
            // see LIVE. Repair may pause trading, not shadow-convert the mode.
            val mode = try { RuntimeModeAuthority.authority().name } catch (_: Throwable) { if (runtime.paperMode) "PAPER" else "LIVE" }
            val statusOpen = try { BotService.status.openPositions } catch (_: Throwable) { emptyList<com.lifecyclebot.data.TokenState>() }
            val paperOpen = try { statusOpen.count { it.position.isPaperPosition } } catch (_: Throwable) { 0 }
            val liveOpen = try { statusOpen.count { !it.position.isPaperPosition } } catch (_: Throwable) { 0 }
            val walletHeld = try { HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { 0 }
            val hostOpen = try { HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { runtime.hostTrackerOpenCount }
            val lifecycleOpen = try { TokenLifecycleTracker.openCount() } catch (_: Throwable) { 0 }
            val positionStoreOpen = try { statusOpen.size } catch (_: Throwable) { paperOpen + liveOpen }
            // V5.9.1371 — MODE-AWARE orphan + canonical accounting.
            // Pre-1369 the host/lifecycle trackers mirrored paper positions, so
            // orphanPaper = paperOpen - hostOpen made sense. V5.9.1369 deliberately
            // stopped paper trades from entering those LIVE-WALLET-TRUTH trackers
            // (they were ghost accumulators). As a side effect hostOpen is now
            // (correctly) 0 in paper, which made the OLD formula report
            // orphanPaper = paperOpen for EVERY open sim — a false positive the
            // runtime doctor then flagged. The trackers are live-truth; comparing
            // paper sims against them is a category error. Compute per mode:
            //   • PAPER: the authoritative open set is status.openPositions (the
            //     sim book). canonical = that. An orphan is a paper PositionStore
            //     row with NO matching active TokenState (phantom) — the
            //     SellReconciler paper pass closes those, so steady-state = 0.
            //   • LIVE: unchanged — host/lifecycle trackers are truth, paper rows
            //     shouldn't exist, and orphanLive watches tracker-vs-store drift.
            val isPaperRuntime = try { !RuntimeModeAuthority.isLive() } catch (_: Throwable) { true }
            val canonicalOpen = if (isPaperRuntime) {
                positionStoreOpen
            } else {
                // V5.9.1561 — canonical LIVE truth is wallet-held balance.
                // Stale local/position/lifecycle rows are drift to reconcile, not
                // canonical opens. This fixes walletHeld=0/canonical=1 false poison.
                walletHeld
            }
            val orphanPaper = try {
                if (isPaperRuntime) {
                    // Phantom paper rows: PositionStore paper entries with no live
                    // TokenState backing. status.openPositions IS the live set, so
                    // by construction these are already reconciled => 0. The
                    // SellReconciler paper pass enforces it against status.tokens.
                    com.lifecyclebot.engine.sell.SellReconciler.paperOrphanCount()
                } else {
                    // In live, any paper position is itself an orphan (shouldn't exist).
                    paperOpen
                }
            } catch (_: Throwable) { 0 }
            val orphanLive = try {
                if (isPaperRuntime) 0
                else (walletHeld - liveOpen).coerceAtLeast(0)
            } catch (_: Throwable) { 0 }

            val reconcilerChecked = try {
                maxOf(
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.snapshot().totalChecked,
                    try { com.lifecyclebot.engine.sell.SellReconciler.totalChecked.toInt() } catch (_: Throwable) { 0 },
                    try { com.lifecyclebot.engine.sell.LiveWalletReconciler.totalChecked() } catch (_: Throwable) { 0 },
                )
            } catch (_: Throwable) { 0 }

            val api = ApiHealthMonitor.snapshot().mapValues { (_, s) ->
                ApiSummary(
                    successRatePct = (s.successRate() * 100.0).toInt(),
                    failures = s.failures4xx.get() + s.failures5xx.get() + s.networkErrors.get(),
                    avgLatencyMs = s.avgLatencyMs().toInt(),
                    lastError = s.lastErrorMessage.get() ?: "",
                )
            }
            // V5.9.1533 — feed SELL-ONLY SAFE MODE the canonical signals each tick so
            // it can gate new live buys when the runtime is unhealthy (spec item 1+9).
            try {
                val activeJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size } catch (_: Throwable) { 0 }
                val pendingSell = try { com.lifecyclebot.engine.sell.CloseLease.activeLeaseCount() } catch (_: Throwable) { 0 }
                val workerTimeouts = try { (pipe.labelCounts["LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT"] ?: 0L).toInt() } catch (_: Throwable) { 0 }
                val closedNonDust = try { com.lifecyclebot.engine.HostWalletTokenTracker.closeAuthorityAudit().closedWithNonDustBalance } catch (_: Throwable) { 0 }
                val staleExit = try { com.lifecyclebot.engine.sell.StalePriceExitGuard.anyActive() } catch (_: Throwable) { false }
                com.lifecyclebot.engine.sell.SellOnlySafeMode.updateSignals(
                    pendingSellQueue = pendingSell,
                    activeJobs = activeJobs,
                    workerTimeoutCount = workerTimeouts,
                    orphanLive = orphanLive,
                    hostOpen = hostOpen,
                    storeOpen = positionStoreOpen,
                    closedNonDust = closedNonDust,
                    staleLivePriceExit = staleExit,
                )
            } catch (_: Throwable) {}

            return RuntimeStateSnapshot(
                buildVersion = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" },
                // V5.9.1170 — never call PipelineHealthCollector.dumpText() from
                // runtime snapshots. dumpText is a forensic export builder; pulling
                // it from RuntimeDoctor/botLoop made diagnostics part of the hot path.
                buildTag = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" },
                runtimeGeneration = runtime.runtimeGeneration,
                uiState = if (uiRunning ?: runtime.runtimeActive) "RUNNING" else "STOPPED",
                runtimeState = runtime.state.name,
                botLoopActive = runtime.botLoopJobActive,
                scannerActive = runtime.scannerActive,
                sellReconcilerStarted = runtime.sellReconcilerStarted,
                hostTrackerOpenCount = hostOpen,
                positionStoreOpenCount = positionStoreOpen,
                paperOpenPositions = paperOpen,
                liveOpenPositions = liveOpen,
                walletHeldMints = walletHeld,
                canonicalOpenPositions = canonicalOpen,
                orphanPaperPositions = orphanPaper,
                orphanLivePositions = orphanLive,
                reconcilerTotalChecked = reconcilerChecked,
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
                activeMitigations = RuntimeConfigOverlay.activeCommands().map { "${it.kind}:${it.target}:${it.value}" },
            )
        }
    }
}
