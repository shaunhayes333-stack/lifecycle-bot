package com.lifecyclebot.engine

// ═══════════════════════════════════════════════════════════════════════════
// BotServiceSupervisorExt.kt — V5.9.1002
//
// Third batch of BotService.kt file-split refactor. 10 functions extracted.
// Preceded by: BotServiceLifecycleExt.kt (V5.9.1000), BotServiceIntakeExt.kt (V5.9.1001).
// All 3 batches are pure file-move — zero behavioural changes.
//
// WIDENING (part of this commit, in BotService.kt):
//   wallet, freezeLastExecChangeMs, freezeLastExecCount, freezeRecoveryFiredAt,
//   orchestrator, securityGuard, strategy, notifIdCounter → private → internal
//   (still module-private; no external API surface change)
//
// EXTRACTED FUNCTIONS (706 lines total):
//   runReconcileSweep            (42)   — reconcile paper/live wallet state
//   runFreezeDetectorTick       (154)   — stale-executor freeze detection
//   runFallbackSafetyExit       (100)   — emergency position dump
//   sweepUniversalExits          (63)   — cross-lane exit sweep
//   maybeTickCyclicTradeEngine   (51)   — cyclic compound ring tick
//   manualBuy                    (74)   — manual buy entry
//   manualSell                  (128)   — manual sell exit
//   unwireExternalStreams          (6)   — WS teardown
//   calculateSocialScore          (37)   — token social scoring
//   calculateBootstrapScore       (51)   — bootstrap confidence score
//
// COMPANION REF PRE-FIXING (Rule #35):
//   Companion members that lose implicit resolution in extension fns:
//   status, cfg, cb, now, sc, kill, walletManager, trust, last
//   → all prefixed BotService.X in the extracted bodies.
//
// SAFETY GATES: private-dep audit ✅, companion audit ✅, test-tree ✅,
//   brace delta b=14 maintained ✅, external callsite zero ✅
// ═══════════════════════════════════════════════════════════════════════════

import android.content.Context
import android.content.Intent
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.solana.SolanaWallet

// V5.9.1001 — processTokenMergeQueue() extracted to BotServiceIntakeExt.kt

// V5.9.1000 — runRegimePulse() extracted to BotServiceLifecycleExt.kt

// V5.9.1000 — runSentienceAutoTune() extracted to BotServiceLifecycleExt.kt

internal suspend fun BotService.runReconcileSweep() {
    val cfgNow = ConfigStore.load(applicationContext)
    val w = wallet ?: return
    if (cfgNow.paperMode || !::executor.isInitialized) return

    val activeMints = mutableSetOf<String>()
    try {
        synchronized(BotService.status.tokens) {
            BotService.status.tokens.values.forEach { ts ->
                if (ts.position.isOpen) activeMints.add(ts.mint)
            }
        }
    } catch (_: Exception) {}
    try {
        com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()
            .forEach { activeMints.add(it.mint) }
    } catch (_: Exception) {}
    try {
        com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
            .forEach { activeMints.add(it.mint) }
    } catch (_: Exception) {}
    try {
        com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions()
            .forEach { activeMints.add(it.mint) }
    } catch (_: Exception) {}
    try {
        com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
            .forEach { activeMints.add(it.mint) }
    } catch (_: Exception) {}
    val swept = executor.liveSweepWalletTokens(w, w.getSolBalance(), activeMints)
    if (swept > 0) {
        ErrorLogger.warn("BotService",
            "🔄 RECONCILE SWEEP: liquidated $swept orphan token(s) leaked from V3 exits")
        addLog("🔄 Reconcile: cleared $swept orphan position(s) from wallet")
    }
}

// ── logging ────────────────────────────────────────────

/**
 * Aggressively clean up the watchlist to make room for new opportunities.
 * Removes tokens that are:
 * - Blocked by safety checker (rugcheck failed)
 * - Stale (no price updates)
 * - Dead (zero liquidity or volume)
 * - IDLE too long without getting a buy signal
 * - Underperforming (flat price with no buys)
 */
/**
 * V5.9.494 — UNIVERSAL EXIT SWEEP.
 *
 * Operator: 'where are the fluid stops and dynamic profit lockers?
 * nothing should rip 35% out of us! ensure all trading tools layers
 * etc are wired correctly for live trading.'
 *
 * Runs at the end of every main loop tick. Hits two coverage gaps
 * that processTokenCycle leaves open:
 *
 *   1) CashGenerationAI Treasury positions whose mints aren't in
 *      BotService.status.tokens (or whose ts.position.isTreasuryPosition is
 *      stale). The treasury exit signal at line ~9246 only fires
 *      when ts is found AND tagged as treasury — for older bot
 *      restarts or sub-trader-private positions, that gate misses.
 *
 *   2) V3 lane open positions (BotService.status.tokens with isOpen=true)
 *      where the scanner stopped returning fresh quotes. The
 *      V5.9.426 fallback path catches some of these but doesn't
 *      run profit-lock or partial-sell logic — only hard SL.
 *
 * For (1) we ask CashGenerationAI for any non-HOLD signals and
 * route them through executor.requestSell, synthesising a
 * TokenState if the mint isn't in BotService.status.tokens.
 *
 * For (2) we iterate every open V3 position and call
 * executor.runManageOnly() which executes:
 *      checkProfitLock → checkPartialSell → riskCheck (fluid stops)
 * once. Cheap on the happy path (returns immediately if no
 * trigger), and finally fires the sells we keep missing.
 */

/**
 * V5.9.634 — Freeze Detector + Cap Diagnostics
 *
 * Symptom this fixes: bot ramps to ~85 trades / 13 open then halts
 * entirely for 30+ minutes while PumpPortal intake is still flowing
 * and the scanner is alive. Either:
 *   (a) all sub-traders are silently rejecting every candidate
 *       (RejectStats from V5.9.633 will tell us which one), OR
 *   (b) every open position is stuck pendingVerify / dead and the
 *       per-lane concurrent cap is thus saturated, OR
 *   (c) some boolean flag (paused, halted, distrusted) flipped on
 *       and never flipped back.
 *
 * We track [CanonicalLearningCounters.executedTradesTotal] — if it
 * has not advanced for 3 minutes WHILE marketScanner != null AND
 * we have at least one open meme position, fire one automated
 * unfreeze. All actions are cheap and idempotent. Self-rate-limited
 * to one fire per 5 minutes.
 *
 * Extracted from botLoop in V5.9.634c so the loop stays under the
 * JVM 64KB per-method bytecode ceiling.
 */
internal suspend fun BotService.runFreezeDetectorTick(
    loopCount: Int,
    BotService.cfg: com.lifecyclebot.data.BotConfig,
) {
    try {
        val BotService.now = System.currentTimeMillis()
        val curExec = com.lifecyclebot.engine.CanonicalLearningCounters
            .executedTradesTotal.get()
        if (curExec != freezeLastExecCount) {
            freezeLastExecCount = curExec
            freezeLastExecChangeMs = BotService.now
        }

        // ── Cap diagnostics: per-lane open count + caps ────────
        val memeOpen = BotService.status.tokens.values.count { ts ->
            try { ts.position.qtyToken > 0.0 } catch (_: Throwable) { false }
        }
        val memePending = BotService.status.tokens.values.count { ts ->
            try { ts.position.pendingVerify && ts.position.qtyToken > 0.0 } catch (_: Throwable) { false }
        }
        val memeCap = if (BotService.cfg.paperMode) BotService.cfg.maxConcurrentPositions else BotService.cfg.maxConcurrentLivePositions
        val cbState = try {
            if (::securityGuard.isInitialized) securityGuard.getCircuitBreakerState() else null
        } catch (_: Throwable) { null }
        val haltedTag = when {
            cbState == null              -> ""
            cbState.isHalted             -> " · HALTED:${cbState.haltReason.take(30)}"
            cbState.consecutiveLosses>=3 -> " · streak=${cbState.consecutiveLosses}"
            else                         -> ""
        }
        val freezeAgeSec = (BotService.now - freezeLastExecChangeMs) / 1000
        val capLine = "🪪 caps: meme=$memeOpen/$memeCap (pending=$memePending) · execAge=${freezeAgeSec}s · execTotal=$curExec$haltedTag"
        ErrorLogger.info("BotService", capLine)
        addLog(capLine)

        // ── Freeze detector ────────────────────────────────────
        val staleMs = BotService.now - freezeLastExecChangeMs
        val scannerAlive = marketScanner != null
        val haveOpens = memeOpen > 0
        val canFireAgain = (BotService.now - freezeRecoveryFiredAt) > 5 * 60_000L
        val freezeStaleThresholdMs = 3 * 60_000L
        if (staleMs > freezeStaleThresholdMs && scannerAlive && haveOpens && canFireAgain) {
            freezeRecoveryFiredAt = BotService.now
            ErrorLogger.error("BotService",
                "🔓 FREEZE_DETECTOR: 0 executions in ${staleMs/1000}s while scanner alive + memeOpen=$memeOpen — running auto-unfreeze")
            addLog("🔓 FREEZE_DETECTOR fired (${staleMs/1000}s stale) — auto-unfreezing")

            // 1) Reset scanner staleness in case it's silently
            //    paused on a saturated cooldown map.
            try { marketScanner?.checkAndResetIfStale() } catch (_: Throwable) {}

            // 2) Reconnect external streams (PumpPortal /
            //    DataOrchestrator / Pyth Hermes), fail-open.
            try { orchestrator?.reconnectStreams() } catch (_: Throwable) {}

            // 3) If SecurityGuard is halted from a stale streak,
            //    clear it. The next loss will re-halt it; we just
            //    don't want a permanent stuck halt blocking buys.
            try {
                if (::securityGuard.isInitialized) {
                    val BotService.cb = securityGuard.getCircuitBreakerState()
                    if (BotService.cb.isHalted) {
                        ErrorLogger.warn("BotService",
                            "🔓 FREEZE_DETECTOR: SecurityGuard halted (${BotService.cb.haltReason}) — clearing halt")
                        securityGuard.clearHalt()
                    }
                }
            } catch (_: Throwable) {}

            // 4) Clear FinalDecisionGate's learning state — V5.9.182
            //    already exposes this for the same reason at boot.
            try { FinalDecisionGate.resetLearningState() } catch (_: Throwable) {}

            // 5) Bump the AntiChokeManager.
            try {
                com.lifecyclebot.engine.AntiChokeManager.tick(
                    isPaperMode = BotService.cfg.paperMode,
                    wallet      = wallet,
                    tokens      = BotService.status.tokens,
                    loopCount   = loopCount,
                )
            } catch (_: Throwable) {}

            addLog("🔓 FREEZE_DETECTOR: scanner reset · streams reconnected · halt cleared · FDG learning state cleared")
        }
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "Freeze detector tick error: ${e.message}")
    }
}

internal fun BotService.runFallbackSafetyExit(ts: TokenState, cfg: BotConfig, wallet: SolanaWallet?) {
    try {
        val price = ts.lastPrice
        if (price <= 0.0 || ts.position.entryPrice <= 0.0) return
        val effectiveBalance = BotService.status.getEffectiveBalance(BotService.cfg.paperMode)

        // V5.9.678 — emit PHASE.EXIT forensic so the funnel counter
        // EXIT in the Pipeline Health dump finally reflects reality.
        // Prior to this, no code path anywhere in the bot emitted
        // PHASE.EXIT so operator dumps perpetually showed EXIT=0
        // even when many positions were being evaluated for exit.
        val _pnl = ((price - ts.position.entryPrice) / ts.position.entryPrice) * 100.0
        try {
            ForensicLogger.phase(
                ForensicLogger.PHASE.EXIT_GATE,
                ts.symbol,
                "pnl=${_pnl.toInt()}% mode=${ts.position.tradingMode.takeIf { it.isNotBlank() } ?: "NONE"} BotService.sc=${com.lifecyclebot.v3.scoring.ShitCoinTraderAI.hasPosition(ts.mint)} ms=${com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(ts.mint)}"
            )
        } catch (_: Throwable) {}

        // ── ShitCoinTraderAI delegation ─────────────────────────────
        if (com.lifecyclebot.v3.scoring.ShitCoinTraderAI.hasPosition(ts.mint)) {
            val sig = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.checkExit(ts.mint, price)
            if (sig != com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.HOLD) {
                ErrorLogger.warn("BotService",
                    "🛡️ [FALLBACK_EXIT][SHITCOIN] ${ts.symbol} | signal=$sig | price=$price (DexScreener down)")
                if (sig == com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.PARTIAL_TAKE) {
                    executor.requestPartialSell(
                        ts = ts, sellPercentage = 0.25,
                        reason = "FALLBACK_SHITCOIN_PARTIAL_TAKE",
                        wallet = wallet, walletBalance = effectiveBalance,
                    )
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.markFirstTakeDone(ts.mint)
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.onPartialSell(ts.mint, 0.25) // V5.9.705
                    com.lifecyclebot.engine.PositionPersistence.savePosition(ts)               // V5.9.705
                } else {
                    val fbScResult = executor.requestSell(
                        ts = ts,
                        reason = "FALLBACK_SHITCOIN_${sig.name}",
                        wallet = wallet, walletSol = effectiveBalance,
                    )
                    // V5.9.706 FIX: clean up sub-trader state unless retryable
                    if (fbScResult != Executor.SellResult.FAILED_RETRYABLE) {
                        com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(ts.mint, price, sig)
                        com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                    }
                }
                return
            }
        }

        // ── MoonshotTraderAI delegation ─────────────────────────────
        if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(ts.mint)) {
            val sig = com.lifecyclebot.v3.scoring.MoonshotTraderAI.checkExit(ts.mint, price)
            if (sig != com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.HOLD) {
                ErrorLogger.warn("BotService",
                    "🛡️ [FALLBACK_EXIT][MOONSHOT] ${ts.symbol} | signal=$sig | price=$price (DexScreener down)")
                if (sig == com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.PARTIAL_TAKE) {
                    val partialPct = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getPartialSellPct(ts.mint)
                    executor.requestPartialSell(
                        ts = ts, sellPercentage = partialPct,
                        reason = "FALLBACK_MOONSHOT_PARTIAL_TAKE_${(partialPct * 100).toInt()}PCT",
                        wallet = wallet, walletBalance = effectiveBalance,
                    )
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.onPartialSell(ts.mint, partialPct) // V5.9.705
                    com.lifecyclebot.engine.PositionPersistence.savePosition(ts)                    // V5.9.705
                } else {
                    val fbMsResult = executor.requestSell(
                        ts = ts,
                        reason = "FALLBACK_MOONSHOT_${sig.name}",
                        wallet = wallet, walletSol = effectiveBalance,
                    )
                    // V5.9.706 FIX: clean up sub-trader state unless retryable
                    if (fbMsResult != Executor.SellResult.FAILED_RETRYABLE) {
                        com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, price, sig)
                        com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                    }
                }
                return
            }
        }

        // ── Last-resort hard-floor (orphaned position) ──────────────
        // If neither sub-trader has this mint registered (e.g. state
        // wasn't rehydrated after restart), still fire the canonical
        // -20% meme hard-floor to stop catastrophic bleed.
        val pnlPct = ((price - ts.position.entryPrice) / ts.position.entryPrice) * 100.0
        if (pnlPct <= -20.0) {
            ErrorLogger.warn("BotService",
                "🛑 [FALLBACK_SAFETY_SL][ORPHAN] ${ts.symbol} | ${pnlPct.toInt()}% — no sub-trader has mint; firing hard-floor")
            executor.requestSell(
                ts = ts,
                reason = "FALLBACK_ORPHAN_HARD_FLOOR_${pnlPct.toInt()}PCT",
                wallet = wallet, walletSol = effectiveBalance,
            )
        }
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "[FALLBACK_SAFETY] error: ${e.message}")
    }
}

internal fun BotService.sweepUniversalExits(
    BotService.cfg: com.lifecyclebot.data.BotConfig,
    wallet: com.lifecyclebot.network.SolanaWallet?,
    effectiveBalance: Double,
) {
    // Part 1 — Treasury sub-trader exits (any mint, with or without ts).
    try {
        val treasuryExits = com.lifecyclebot.v3.scoring.CashGenerationAI
            .checkAllPositionsForExit()
        treasuryExits.forEach { (mint, signal) ->
            if (signal == com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.HOLD) return@forEach
            val ts = synchronized(BotService.status.tokens) { BotService.status.tokens[mint] }
                ?: synthesizeTreasuryTokenState(mint) ?: return@forEach
            // V5.9.495i — POST-BUY SETTLE-IN GRACE for treasury sweeps
            // too. Operator: "it buys them then 5 seconds later it
            // sells them". 45s breathing room before any exit fires.
            val posAgeMs = System.currentTimeMillis() - ts.position.entryTime
            if (posAgeMs < 45_000L) return@forEach
            val pnlPct = if (ts.position.entryPrice > 0)
                ((ts.lastPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
            else 0.0
            ErrorLogger.warn(
                "BotService",
                "🧹 SWEEP TREASURY-EXIT: ${ts.symbol} | $signal | pnl=${pnlPct.toInt()}% — mint missed processTokenCycle",
            )
            try {
                val r = executor.requestSell(
                    ts        = ts,
                    reason    = "TREASURY_${signal.name}_SWEEP",
                    wallet    = wallet,
                    walletSol = effectiveBalance,
                )
                if (r == com.lifecyclebot.engine.Executor.SellResult.CONFIRMED ||
                    r == com.lifecyclebot.engine.Executor.SellResult.PAPER_CONFIRMED) {
                    com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                        mint, ts.lastPrice, signal,
                    )
                    addLog("🧹 [SWEEP] TREASURY ${ts.symbol}: ${signal.name} +${pnlPct.toInt()}% | result=$r")
                }
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "Sweep treasury sell error: ${e.message}")
            }
        }
    } catch (e: Exception) {
        ErrorLogger.warn("BotService", "Sweep treasury error: ${e.message}")
    }

    // Part 2 — V3 lane: ensure profit-lock + partial + risk fire on
    // every open position, not just those with fresh scanner data.
    try {
        val openTokens = synchronized(BotService.status.tokens) {
            BotService.status.tokens.values.filter { it.position.isOpen }.toList()
        }
        openTokens.forEach { ts ->
            try { executor.runManageOnly(ts, wallet, effectiveBalance) }
            catch (e: Exception) {
                ErrorLogger.debug("BotService", "Sweep manage(${ts.symbol}): ${e.message}")
            }
        }
    } catch (e: Exception) {
        ErrorLogger.warn("BotService", "Sweep V3-manage error: ${e.message}")
    }
}

/**
 * V5.9.779 — EMERGENT MEME-ONLY: rehydrate a minimal TokenState for the
 * SellReconciler trigger when the mint isn't in BotService.status.tokens (e.g.
 * after a stopBot trim, a service BotService.kill, or a user-side restart). The
 * TokenState we synthesise is just enough for Executor.requestSell to
 * resolve quantity and broadcast a sell — the HostWalletTokenTracker
 * + Executor's RPC fallback paths fill in the rest. The synthesised
 * ts is also added back into BotService.status.tokens so the bot loop's exit
 * gate can pick it up on the next tick.
 */
/**
 * V5.9.780a — extracted from botLoop to keep the suspend state machine
 * under the JVM 64 KB method-size limit. Identical semantics:
 *   PAPER mode → tick if CYCLIC is enabled
 *   LIVE  mode → tick only if CYCLIC enabled AND walletUsd >= $1500
 */
internal fun BotService.maybeTickCyclicTradeEngine(wallet: SolanaWallet?) {
    try {
        val isPaperRuntime = com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()
        val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        val walletSolNow = BotService.walletManager.state.value.solBalance
        val walletUsdNow = walletSolNow * solPrice.coerceAtLeast(0.0)
        val cyclicEnabled = com.lifecyclebot.engine.EnabledTraderAuthority.isEnabled(
            com.lifecyclebot.engine.EnabledTraderAuthority.Trader.CYCLIC
        )
        val liveThreshold = 1500.0
        val allowTick = when {
            isPaperRuntime -> cyclicEnabled
            else -> cyclicEnabled && walletUsdNow >= liveThreshold
        }
        if (allowTick) {
            val cyclicTokens = synchronized(BotService.status.tokens) { BotService.status.tokens.toMap() }
            CyclicTradeEngine.tick(
                context   = applicationContext,
                tokens    = cyclicTokens,
                executor  = executor,
                wallet    = wallet,
                walletSol = walletSolNow,
            )
        } else {
            try {
                ForensicLogger.lifecycle(
                    "CYCLIC_TICK_SKIPPED",
                    "mode=${if (isPaperRuntime) "PAPER" else "LIVE"} cyclicEnabled=$cyclicEnabled walletUsd=${"%.2f".format(walletUsdNow)} threshold=$liveThreshold",
                )
            } catch (_: Throwable) {}
        }
    } catch (e: Exception) {
        ErrorLogger.error("BotService", "CyclicTradeEngine tick error: ${e.message}", e)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// V5.9.317: MANUAL TRADE API (paper + live, end-to-end)
// ═══════════════════════════════════════════════════════════════════════════
// Routes to executor.doBuy / executor.doSell which already handle all
// routing (paper vs live), security guards, exposure caps, fee splits and
// shadow-paper mirroring. This is the single source of truth used by the
// manual BUY/SELL buttons on the active token panel in MainActivity.

/**
 * Manual BUY for a specific token. Returns (success, message) for UI feedback.
 * - In paper mode: routes to paperBuy (no wallet required).
 * - In live mode: routes through full security guard + Jupiter swap pipeline.
 * - Sizing: caller-supplied sol amount (no SmartSizer override) so user has
 *   precise control. Validation prevents overdraw / negative amounts.
 */
internal fun BotService.manualBuy(mint: String, solAmount: Double): Pair<Boolean, String> {
    if (mint.isBlank()) return false to "No token selected"
    if (solAmount <= 0.0 || solAmount.isNaN() || solAmount.isInfinite()) {
        return false to "Invalid amount: $solAmount SOL"
    }
    if (!::executor.isInitialized) return false to "Bot not started"

    val ts = BotService.status.tokens[mint] ?: return false to "Token not in watchlist: ${mint.take(8)}"
    if (ts.position.isOpen) {
        return false to "Position already open: ${ts.symbol}"
    }

    val cfgNow = ConfigStore.load(applicationContext)
    val isPaper = cfgNow.paperMode
    val w = wallet
    // V5.9.495o — operator: manual BUY toasted "Insufficient wallet SOL:
    // 0.0000 < 0.0600" while UI top bar showed 0.9439◎. Fresh on-demand
    // `getSolBalance()` was failing (3-retry RPC throws → catch returns
    // 0.0). The cached `WalletManager.state.value.solBalance` is what
    // the UI displays and is refreshed on a periodic cadence — BotService.trust it
    // when fresh RPC fails. Only fall back to fresh RPC if cache is empty.
    val walletSol = if (isPaper) {
        try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Exception) { 0.0 }
    } else {
        val cached = try {
            com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).state.value.solBalance
        } catch (_: Throwable) { 0.0 }
        if (cached > 0.0) cached else try { w?.getSolBalance() ?: 0.0 } catch (_: Exception) { 0.0 }
    }

    // Live-mode preflight: ensure wallet exists + has enough SOL.
    if (!isPaper) {
        if (w == null) return false to "Live wallet not connected"
        // Reserve 0.01 SOL for swap fees (mirrors V5.9.309 fix).
        if (walletSol < solAmount + 0.01) {
            return false to "Insufficient wallet SOL: ${"%.4f".format(walletSol)} < ${"%.4f".format(solAmount + 0.01)}"
        }
    }

    return try {
        ErrorLogger.info("BotService",
            "👆 MANUAL BUY: ${ts.symbol} | ${"%.4f".format(solAmount)} SOL | mode=${if (isPaper) "PAPER" else "LIVE"}")
        addLog("👆 Manual BUY: ${ts.symbol} ${"%.4f".format(solAmount)} SOL ${if (isPaper) "(paper)" else "(LIVE)"}")
        executor.doBuy(
            ts = ts,
            sol = solAmount,
            score = 50.0,                // neutral score: this is a user override, not an AI decision
            wallet = w,
            walletSol = walletSol,
            identity = null,             // let TradeIdentityManager assign
            quality = "MANUAL",
            skipGraduated = false,
        )
        true to "Buy submitted (${if (isPaper) "paper" else "LIVE"})"
    } catch (e: Exception) {
        ErrorLogger.error("BotService", "manualBuy error for ${ts.symbol}", e)
        false to "Error: ${e.message ?: e.javaClass.simpleName}"
    }
}

/**
 * Manual SELL of an open position. Returns (success, message).
 * - In paper mode: routes to paperSell (instant fill at BotService.last price).
 * - In live mode: routes through Jupiter swap pipeline + reconnect logic.
 * - Reason tag "MANUAL" so journal entries are clearly attributed.
 */
internal fun BotService.manualSell(mint: String): Pair<Boolean, String> {
    if (mint.isBlank()) return false to "No token selected"
    if (!::executor.isInitialized) return false to "Bot not started"

    val cfgNow = ConfigStore.load(applicationContext)
    val isPaper = cfgNow.paperMode
    val w = wallet
    // V5.9.495o — same cached-balance fallback as manualBuy.
    val walletSol = if (isPaper) {
        try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Exception) { 0.0 }
    } else {
        val cached = try {
            com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).state.value.solBalance
        } catch (_: Throwable) { 0.0 }
        if (cached > 0.0) cached else try { w?.getSolBalance() ?: 0.0 } catch (_: Exception) { 0.0 }
    }
    if (!isPaper && w == null) return false to "Live wallet not connected"

    // V5.9.474 — operator-reported manual-SELL store-mismatch bug.
    //
    // Symptom: DMC visible in 'Treasury Scalps' card with +4.1% PnL but
    // pressing manual SELL toasted "No open position to sell" because
    // the old code only inspected `BotService.status.tokens[mint].position.isOpen`.
    // CashGenerationAI / ShitCoinTraderAI / QualityTraderAI /
    // BlueChipTraderAI / MoonshotTraderAI all maintain their OWN position
    // maps and (a) do NOT always set ts.position.isOpen=true on the
    // shared TokenState, (b) sometimes the mint isn't in BotService.status.tokens
    // at all (cleanup races, reboot rehydration). The sub-trader cards
    // were reading from those private maps but the sell button was
    // reading from the main one — visibility/action mismatch.
    //
    // Fix: scan ALL position stores in priority order. If found:
    //   1. ts.position.isOpen=true  → use main executor.doSell path
    //      (works for ShitCoin / Quality / BlueChip / Moonshot since
    //      those layers DO mirror to ts.position when buying).
    //   2. Treasury-only position (CashGen has it, ts.position closed)
    //      → call CashGenerationAI.closePosition directly so the
    //      strategy bookkeeping clears even if the swap path is busy.
    //   3. None of the above → return a meaningful error listing every
    //      store we checked so the operator knows it's truly absent.
    val ts = BotService.status.tokens[mint]

    // Path 1: main TokenState says open → use existing fast path.
    if (ts != null && ts.position.isOpen) {
        return try {
            ErrorLogger.info("BotService",
                "👆 MANUAL SELL [main]: ${ts.symbol} | qty=${ts.position.qtyToken} | mode=${if (isPaper) "PAPER" else "LIVE"}")
            addLog("👆 Manual SELL: ${ts.symbol} ${if (isPaper) "(paper)" else "(LIVE)"}")
            val result = executor.doSell(ts, "MANUAL", w, walletSol)
            true to "Sell submitted (${result.name})"
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "manualSell error for ${ts.symbol}", e)
            false to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Path 2: scan sub-trader stores. Each holds its own position map.
    // If any of them has the mint we route the sell through it.
    val symbol = ts?.symbol ?: mint.take(8)

    try {
        val tp = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
        if (tp != null) {
            val price = com.lifecyclebot.v3.scoring.CashGenerationAI.getTrackedPrice(mint)
                ?: tp.entryPrice
            ErrorLogger.info("BotService", "👆 MANUAL SELL [Treasury]: ${tp.symbol} @ \$${price}")
            addLog("👆 Manual SELL (Treasury): ${tp.symbol}")
            // If we also have a TokenState, run the swap; close the
            // treasury bookkeeping regardless so the card disappears.
            val realTs = ts
            val sellResult = if (realTs != null) {
                try { executor.doSell(realTs, "MANUAL_TREASURY", w, walletSol).name } catch (_: Exception) { "TREASURY_BOOKKEEP_ONLY" }
            } else "TREASURY_BOOKKEEP_ONLY (no TokenState)"
            com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                mint, price, com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT)
            return true to "Treasury position closed ($sellResult)"
        }
    } catch (e: Exception) {
        ErrorLogger.warn("BotService", "manualSell Treasury check error: ${e.message}")
    }

    // ShitCoin / Quality / BlueChip / Moonshot all keep their positions
    // in private activePositions maps. They DO mirror to ts.position
    // when buying so the Path 1 branch above usually catches them. If
    // we got here, the main flag fell out of sync — list them so the
    // operator can see which store has it and we still attempt the
    // swap via doSell when a TokenState exists.
    if (ts != null) {
        data class StoreHit(val name: String, val symbol: String)
        val hits = mutableListOf<StoreHit>()
        try {
            if (com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().any { it.mint == mint })
                hits += StoreHit("ShitCoin", ts.symbol)
        } catch (_: Exception) {}
        try {
            if (com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().any { it.mint == mint })
                hits += StoreHit("Quality", ts.symbol)
        } catch (_: Exception) {}
        try {
            if (com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().any { it.mint == mint })
                hits += StoreHit("BlueChip", ts.symbol)
        } catch (_: Exception) {}
        try {
            if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(mint))
                hits += StoreHit("Moonshot", ts.symbol)
        } catch (_: Exception) {}
        if (hits.isNotEmpty()) {
            ErrorLogger.info("BotService",
                "👆 MANUAL SELL [sub-trader resync]: ${ts.symbol} found in ${hits.joinToString(",") { it.name }} — forcing doSell")
            addLog("👆 Manual SELL (${hits.first().name}): ${ts.symbol}")
            return try {
                val result = executor.doSell(ts, "MANUAL_${hits.first().name.uppercase()}", w, walletSol)
                true to "Sell submitted via ${hits.first().name} (${result.name})"
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "manualSell sub-trader error for ${ts.symbol}", e)
                false to "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    return false to "No open position found for ${symbol} in any store (main/Treasury/ShitCoin/Quality/BlueChip/Moonshot)"
}

internal fun BotService.unwireExternalStreams() {
    try { com.lifecyclebot.network.PumpFunWS.stop() } catch (_: Exception) {}
    try { com.lifecyclebot.network.HeliusEnhancedWS.stop() } catch (_: Exception) {}
    try { com.lifecyclebot.network.PythHermesStream.unsubscribeAll() } catch (_: Exception) {}
    // EmergentLlmClient is request-scoped — nothing to disconnect.
}

// V5.9.1001 — synthesizeFallbackPair() extracted to BotServiceIntakeExt.kt

// V5.9.1001 — tryFallbackPriceData() extracted to BotServiceIntakeExt.kt

// ═══════════════════════════════════════════════════════════════════════════
// SHITCOIN LAYER HELPERS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Calculate social score for a token based on available signals
 */
internal fun BotService.calculateSocialScore(ts: TokenState): Int {
    var score = 0
    
    // Boost for trending tokens
    if (ts.source.contains("TRENDING", ignoreCase = true)) score += 25
    if (ts.source.contains("BOOSTED", ignoreCase = true)) score += 20
    
    // Boost for tokens from recognized platforms
    if (ts.source.contains("PUMP_FUN", ignoreCase = true)) score += 15
    if (ts.source.contains("RAYDIUM", ignoreCase = true)) score += 10
    
    // Positive signals from source naming
    if (ts.source.contains("VERIFIED", ignoreCase = true)) score += 10
    if (ts.source.contains("MOONSHOT", ignoreCase = true)) score += 10
    
    // Symbol length heuristic (legitimate projects often have 3-6 char tickers)
    val symbolLen = ts.symbol.length
    if (symbolLen in 3..6) score += 10
    if (symbolLen > 10) score -= 5  // Too long often indicates scam
    
    // Bundle risk affects social perception
    if (ts.safety.bundleRisk == "LOW") score += 10
    if (ts.safety.bundleRisk == "HIGH") score -= 15
    
    return score.coerceIn(0, 100)
}

// V5.9.1001 — scanAndSellOrphans() extracted to BotServiceIntakeExt.kt

/**
 * Calculate a raw signal score for bootstrap entry decisions.
 * This is independent of V3 score - uses raw market signals only.
 * Used to allow bootstrap trades even when V3 rejects a token.
 */
internal fun BotService.calculateBootstrapScore(
    buyPressurePct: Double,
    liquidityUsd: Double,
    momentum: Double,
    volatility: Double,
): Int {
    var score = 40  // Base score
    
    // Buy pressure (most important)
    score += when {
        buyPressurePct >= 70 -> 30
        buyPressurePct >= 60 -> 25
        buyPressurePct >= 50 -> 20
        buyPressurePct >= 40 -> 10
        else -> 0
    }
    
    // Liquidity
    score += when {
        liquidityUsd >= 10000 -> 15
        liquidityUsd >= 5000 -> 12
        liquidityUsd >= 3000 -> 8
        liquidityUsd >= 1500 -> 5
        else -> 0
    }
    
    // Momentum
    score += when {
        momentum >= 20 -> 10
        momentum >= 10 -> 7
        momentum >= 5 -> 4
        momentum >= 0 -> 2
        else -> 0
    }
    
    // Volatility penalty (too volatile = risky)
    score -= when {
        volatility >= 50 -> 10
        volatility >= 30 -> 5
        else -> 0
    }
    
    return score.coerceIn(0, 100)
}
