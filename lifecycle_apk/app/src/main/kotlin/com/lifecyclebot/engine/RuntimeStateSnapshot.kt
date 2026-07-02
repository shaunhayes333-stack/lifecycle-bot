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
    val staleBuyPendingBalanceProof: Int = 0,
    val openAwaitingWalletProof: Int = 0,
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
            val walletHeld = try { HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { 0 }
            val hostOpen = try { HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { runtime.hostTrackerOpenCount }
            val hostPendingConfirmed = try { HostWalletTokenTracker.getPendingConfirmedCount() } catch (_: Throwable) { 0 }
            val heldMints = try { HostWalletTokenTracker.getActuallyHeldMints() } catch (_: Throwable) { emptySet<String>() }
            // V5.0.3730 — live-open truth must be wallet/host-backed.
            // Runtime 5.0.3727 showed liveStore=1 host=0 walletHeld=0 after a live sell
            // finalized. Raw BotService.status.openPositions still contained a stale live
            // TokenState, poisoning HOST_TRACKER_DESYNC and SellOnlySafeMode even though the
            // wallet/tracker were empty. In LIVE, a local TokenState is not an open position
            // unless the host wallet tracker still holds or is actively selling that mint.
            // V5.0.3792 — HOST WALLET IS SOURCE OF TRUTH (operator: "no tokens in my
            // wallet except SOL but the bot thinks there is 3"). A raw live TokenState in
            // BotService.status.openPositions is NOT an open position unless the host
            // wallet tracker still counts it open-for-accounting (wallet-positive, fresh
            // buy liability, or live sell in-flight). Stale ghost TokenStates (Size 0,
            // Entry —, e.g. WSOLP/VDoRrq/DiYVat) otherwise inflated localLiveOpen ->
            // canonicalOpen -> canonicalOpen>walletHeld drift -> SELL_ONLY_SAFE_MODE ->
            // every live buy hard-blocked. Intersect with the tracker's accounting-open
            // set so the wallet remains the single source of truth.
            // null = tracker call FAILED (fall back to legacy raw count so we never
            // under-count a real position). An empty SET is the valid healthy answer
            // ("nothing is open") and MUST gate the ghosts out — do not fall back on it.
            val accountingOpenMints: Set<String>? = try { HostWalletTokenTracker.getOpenForAccountingMints() } catch (_: Throwable) { null }
            val localLiveOpen = try {
                statusOpen.count { ts ->
                    if (ts.position.isPaperPosition) false
                    else if (accountingOpenMints == null) true          // tracker error → legacy raw count
                    else accountingOpenMints.contains(ts.mint)          // host-tracker truth gates ghosts
                }
            } catch (_: Throwable) { 0 }
            val lifecyclePendingConfirmed = try { TokenLifecycleTracker.confirmedPendingCount() } catch (_: Throwable) { 0 }
            // V5.0.4556 — raw TokenLifecycleTracker.openCount() includes stale
            // non-terminal lifecycle rows restored from disk. Runtime report 4535
            // showed canonicalOpen=2 while hostTrackerOpen=0 and walletHeld=0,
            // raising TRACKER_OPEN_DESYNC_CRITICAL even though those rows were not
            // host/wallet-backed managed positions. Canonical runtime slots may
            // only use the host-backed lifecycle bridge; raw lifecycle rows remain
            // cleanup/reconcile inputs elsewhere, never live-open truth here.
            val lifecycleOpen = try { TokenLifecycleTracker.liveMemeOpenCount() } catch (_: Throwable) { 0 }
            val managedLiveOpen = maxOf(localLiveOpen, hostOpen, lifecyclePendingConfirmed, lifecycleOpen)
            val liveOpen = managedLiveOpen
            // V5.0.3685 — P0: SellOnlySafeMode compares hostOpen (live tracker) vs
            // positionStoreOpen. The old statusOpen.size included paper positions, so
            // ANY paper sim made positionStoreOpen > hostOpen → permanent SELL_ONLY_SAFE_MODE.
            // In LIVE runtime we compare only live open positions vs the host tracker.
            val positionStoreOpen = try {
                val isLiveNow = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
                if (isLiveNow) liveOpen else statusOpen.size
            } catch (_: Throwable) { paperOpen + liveOpen }
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
                // V5.0.4541 — canonical LIVE truth is MANAGED live truth.
                // Runtime reports showed walletHeld=6 while hostOpen/liveOpen=3, which
                // raised ORPHAN_LIVE_POSITIONS and inflated slot truth even though the
                // extra wallet mints were unmanaged inventory/dust. Physical wallet-held
                // proof still matters for positions that are represented by host/local/
                // lifecycle trackers, and confirmed-pending buys remain counted; raw
                // wallet extras without managed identity must be reconciled/purged, not
                // treated as executable open slots.
                managedLiveOpen
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
            val positionReconSnapshot = try { com.lifecyclebot.engine.execution.PositionWalletReconciler.snapshot() } catch (_: Throwable) { null }
            val reconcilerGrace = positionReconSnapshot?.grace ?: 0
            val orphanLive = try {
                if (isPaperRuntime) 0
                else {
                    // V5.0.4541 — orphanLive must mean managed-state desync, not raw
                    // wallet extra inventory. If host/local/lifecycle agree on 3 managed
                    // live positions and the wallet contains 6 token mints, the extra 3
                    // are wallet-reconciliation work, not open executable slots. Counting
                    // walletHeld-liveOpen here created false HIGH mechanical faults and
                    // made the operator think volume had collapsed.
                    val graceAllowance = maxOf(1, reconcilerGrace)
                    val managedDesync = maxOf(
                        (localLiveOpen - hostOpen - graceAllowance).coerceAtLeast(0),
                        (lifecycleOpen - managedLiveOpen - graceAllowance).coerceAtLeast(0),
                        (lifecyclePendingConfirmed - managedLiveOpen - graceAllowance).coerceAtLeast(0),
                    )
                    managedDesync.coerceAtLeast(0)
                }
            } catch (_: Throwable) { 0 }

            val reconcilerChecked = try {
                maxOf(
                    positionReconSnapshot?.totalChecked ?: 0,
                    try { com.lifecyclebot.engine.sell.SellReconciler.totalChecked.toInt() } catch (_: Throwable) { 0 },
                    try { com.lifecyclebot.engine.sell.LiveWalletReconciler.totalChecked() } catch (_: Throwable) { 0 },
                )
            } catch (_: Throwable) { 0 }

            val staleBuyPendingProof = try { HostWalletTokenTracker.countStaleBuyPendingBalanceProof(90_000L) } catch (_: Throwable) { 0 }
            val openAwaitingWalletProof = try { HostWalletTokenTracker.getOpenAwaitingWalletProofCount(90_000L) } catch (_: Throwable) { 0 }

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
                // V5.0.3685 — P0: snapshot().size counted terminal LANDED/FAILED_FINAL
                // jobs → SellOnlySafeMode.activeJobs was always > 0 after the first sell.
                val activeJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.activeCount() } catch (_: Throwable) { 0 }
                val pendingSell = try { com.lifecyclebot.engine.sell.CloseLease.activeBlockingLeaseCount() } catch (_: Throwable) { 0 }
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
                staleBuyPendingBalanceProof = staleBuyPendingProof,
                openAwaitingWalletProof = openAwaitingWalletProof,
            )
        }
    }
}
