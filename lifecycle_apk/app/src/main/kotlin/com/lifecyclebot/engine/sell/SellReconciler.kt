package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.764 — EMERGENT CRITICAL item C (sell-reconciler watchdog).
 *
 * Operator forensics_20260515_151634.json: `reconciler.totalChecked = 0`
 * meant the existing reconciliation pass never actually exercised stuck
 * live positions; CABAL + HIM stayed OPEN_TRACKING with non-zero wallet
 * balances and empty last_sell_signature for hours.
 *
 * Responsibilities every 10 s in LIVE mode:
 *   - scan HostWalletTokenTracker.getOpenTrackedPositions()
 *   - increment RECONCILER_TICK + RECONCILER_OPEN_CHECKED counters
 *   - per position: read fresh wallet token balance via SolanaWallet
 *     * balance == 0 → tracker marked CLOSED (no signature recorded — the
 *       sell either landed off-app or the wallet was emptied externally)
 *     * balance > 0 AND SellJobRegistry has a stuck job (in-flight age
 *       past LOCK_TTL_MS) → force-release the lock (Registry already
 *       handles this on isLockedAndFresh()) and emit RECONCILER_EXIT_REQUEUED
 *     * balance > 0 AND no active job for the mint → emit
 *       RECONCILER_EXIT_REQUEUED so the bot loop picks up the position
 *       again on the next cycle
 *   - prune terminal SellJob entries
 *
 * This watchdog does NOT broadcast sells itself — it owns observation +
 * unblock only. The actual sell broadcast still happens through
 * `Executor.liveSell()` so all retry / slippage / qty-guard logic remains
 * in one place.
 */
object SellReconciler {
    private const val TICK_INTERVAL_MS: Long = 10_000L
    private val jobRef = AtomicReference<Job?>(null)
    @Volatile private var paperMode: Boolean = true
    @Volatile private var wallet: SolanaWallet? = null

    // V5.9.779 — EMERGENT MEME-ONLY: optional callback to actually
    // trigger a live sell when this watchdog requeues a position.
    // Operator forensics 5.0.2709 verified that
    // RECONCILER_EXIT_REQUEUED fired but no broadcast happened
    // because the bot loop's exit path could not find the mint in
    // status.tokens after a stop/start cycle. Callback is set by
    // BotService.startBot() and rehydrates a TokenState from
    // HostWalletTokenTracker when needed before invoking requestSell.
    @Volatile private var sellTrigger: ((String, String, Double) -> Unit)? = null

    /** Total ticks executed since service start — surfaced for debugging. */
    @Volatile var totalTicks: Long = 0L
    /** Cumulative open positions inspected across all ticks. */
    @Volatile var totalChecked: Long = 0L
    /** V5.9.774 — wall-clock at last tick (0 = never). Surfaced in forensics. */
    @Volatile var lastTickAtMs: Long = 0L
    /** V5.9.774 — true once a live-mode tick loop is active. Surfaced in forensics. */
    @Volatile var isStarted: Boolean = false
    /** V5.9.765 — count of consecutive ticks where a mint has price=0 so we
     *  can emit a PRICE_STALE_LIVE_POSITION anomaly after the second tick. */
    private val zeroPriceStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** V5.9.765 — only emit one RECONCILER_START forensic event per service lifecycle. */
    @Volatile private var startEventEmitted: Boolean = false

    /** Idempotent start. Caller (BotService) re-invokes whenever mode or
     *  wallet changes; we restart cleanly. */
    fun start(
        scope: CoroutineScope,
        isPaperMode: Boolean,
        hostWallet: SolanaWallet?,
        sellTrigger: ((mint: String, symbol: String, balance: Double) -> Unit)? = null,
    ) {
        paperMode = isPaperMode
        wallet = hostWallet
        this.sellTrigger = sellTrigger
        // Cancel any prior loop.
        jobRef.get()?.cancel()
        // V5.9.1371 — PAPER MODE now ALSO runs the reconciler. Previously this
        // hard-returned (isStarted=false) because the reconciler was a live-
        // wallet watchdog. But the runtime doctor's success criteria require
        // sellReconcilerStarted=true EVERY generation and orphanPaperPositions=0.
        // In paper there is no wallet to reconcile against, so the paper pass
        // reconciles PositionPersistence (the persisted book) against the live
        // status.tokens sim set: a persisted paper row with no matching OPEN
        // TokenState is a phantom orphan — close/suppress it and log
        // ORPHAN_RECONCILED. Real open sims are adopted (no-op). This keeps the
        // canonical paper book == the active sim book.
        if (hostWallet == null && !isPaperMode) {
            // LIVE but no wallet yet — can't reconcile against chain; stay down.
            isStarted = false
            jobRef.set(null)
            return
        }
        if (isPaperMode) {
            val paperJob = scope.launch(Dispatchers.IO) {
                isStarted = true
                ErrorLogger.info("SellReconciler", "🩹 SellReconciler started (10s tick, PAPER mode — orphan reconcile)")
                if (!startEventEmitted) {
                    startEventEmitted = true
                    try {
                        ForensicLogger.lifecycle(
                            "RECONCILER_START",
                            "intervalMs=$TICK_INTERVAL_MS paperMode=true walletPresent=false",
                        )
                    } catch (_: Throwable) {}
                }
                while (isActive) {
                    try { reconcilePaperOnce() } catch (e: Throwable) {
                        ErrorLogger.warn("SellReconciler", "paper tick error: ${e.message}")
                    }
                    delay(TICK_INTERVAL_MS)
                }
            }
            jobRef.set(paperJob)
            return
        }
        val newJob = scope.launch(Dispatchers.IO) {
            isStarted = true
            ErrorLogger.info("SellReconciler", "🩹 SellReconciler started (10s tick, LIVE mode)")
            // V5.9.765 — EMERGENT priority 1 + acceptance test D: one-shot
            // RECONCILER_START forensic event so operator dumps prove the
            // watchdog has actually engaged (operator forensics_20260515_161017
            // showed reconciler.totalChecked = 0 with 2 open live positions —
            // the legacy LiveWalletReconciler was throttled out by the
            // 30 s gap, but the new SellReconciler's tick proves it ran).
            if (!startEventEmitted) {
                startEventEmitted = true
                try {
                    ForensicLogger.lifecycle(
                        "RECONCILER_START",
                        "intervalMs=$TICK_INTERVAL_MS paperMode=$isPaperMode walletPresent=true",
                    )
                } catch (_: Throwable) {}
            }
            while (isActive) {
                try { tickOnce() } catch (e: Throwable) {
                    ErrorLogger.warn("SellReconciler", "tick error: ${e.message}")
                }
                delay(TICK_INTERVAL_MS)
            }
        }
        jobRef.set(newJob)
    }

    fun stop() {
        isStarted = false
        jobRef.getAndSet(null)?.cancel()
    }

    /** Single reconciliation pass. Public for unit-tests / manual nudges. */
    suspend fun tickOnce() {
        if (paperMode) return
        val w = wallet ?: return
        val open = HostWalletTokenTracker.getOpenTrackedPositions()
        totalTicks++
        totalChecked += open.size.toLong()
        lastTickAtMs = System.currentTimeMillis()
        try {
            ForensicLogger.lifecycle(
                "RECONCILER_TICK",
                "tickCount=$totalTicks openTracked=${open.size} sellJobsActive=${SellJobRegistry.activeCount()}",
            )
        } catch (_: Throwable) {}

        if (open.isEmpty()) {
            SellJobRegistry.pruneTerminal()
            return
        }

        // One wallet read for the whole pass (cheaper than per-mint queries).
        val tokens = withContext(Dispatchers.IO) {
            try { w.getTokenAccountsWithDecimalsBounded() } catch (_: Throwable) { emptyMap() }
        }

        // V5.9.765 — EMERGENT priority 2: live price hydration for every
        // OPEN_TRACKING position. Operator forensics_20260515_161017 showed
        // HIM and CABAL stuck at currentPriceUsd = 0.0 — no maxGainPct,
        // no drawdown, no exit eligibility. The host_tracker has a
        // recordPriceUpdate(mint, priceUsd, gainPct) hook; we just need
        // to call it. DexscreenerApi.batchPriceFetch accepts up to 30
        // mints per HTTP call, respects the global rate-limiter.
        val openMints = open.map { it.mint }
        val priceMap: Map<String, Double> = try {
            if (openMints.isEmpty()) emptyMap()
            else withContext(Dispatchers.IO) {
                com.lifecyclebot.network.DexscreenerApi().batchPriceFetch(openMints)
            }
        } catch (_: Throwable) { emptyMap() }

        for (pos in open) {
            try {
                hydratePrice(pos, priceMap)
                reconcileOne(pos, tokens)
            } catch (e: Throwable) {
                ErrorLogger.warn("SellReconciler", "reconcileOne(${pos.mint.take(10)}): ${e.message}")
            }
        }
        SellJobRegistry.pruneTerminal()
    }

    // ────────────────────────────────────────────────────────────────────────
    // V5.9.1371 — PAPER-MODE RECONCILIATION + ORPHAN CLEANUP
    //
    // Live mode reconciles host-wallet truth. Paper mode has no wallet, so the
    // authoritative open set is BotService.status.openPositions (the sim book).
    // The persisted book (PositionPersistence) can drift from it after stop/
    // start trims, service kills, or a sell that cleared the TokenState but not
    // the persisted row. Any persisted paper row with NO matching OPEN
    // TokenState is a phantom orphan: remove it from persistence and log
    // ORPHAN_RECONCILED. Genuinely-open sims are left untouched (adopted).
    // ────────────────────────────────────────────────────────────────────────

    @Volatile private var lastPaperOrphanCount: Int = 0

    /** Cheap read of the most recent paper-orphan count (for runtime snapshot). */
    fun paperOrphanCount(): Int = lastPaperOrphanCount

    private fun computePaperOrphans(): List<String> {
        val openMints: Set<String> = try {
            com.lifecyclebot.engine.BotService.status.openPositions
                .filter { it.position.isPaperPosition }
                .map { it.mint }
                .toSet()
        } catch (_: Throwable) { emptySet() }
        val persisted = try {
            com.lifecyclebot.engine.PositionPersistence.loadPositions()
        } catch (_: Throwable) { emptyMap() }
        // Orphan = PAPER persisted row that is NOT in the live open-sim set.
        // (Only paper rows: a live row is the live reconciler's concern.)
        return persisted.entries
            .filter { it.value.isPaperPosition && it.key !in openMints }
            .map { it.key }
    }

    suspend fun reconcilePaperOnce() {
        totalTicks++
        lastTickAtMs = System.currentTimeMillis()
        val orphans = withContext(Dispatchers.IO) { computePaperOrphans() }
        lastPaperOrphanCount = 0
        if (orphans.isEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "RECONCILER_TICK",
                    "tickCount=$totalTicks paperMode=true orphans=0",
                )
            } catch (_: Throwable) {}
            return
        }
        var reconciled = 0
        for (mint in orphans) {
            try {
                // Phantom persisted paper row: no live sim backs it. Remove it.
                com.lifecyclebot.engine.PositionPersistence.removePosition(mint)
                reconciled++
                try {
                    ForensicLogger.lifecycle(
                        "ORPHAN_RECONCILED",
                        "mint=${mint.take(10)} mode=PAPER action=removed_phantom_persisted_row",
                    )
                } catch (_: Throwable) {}
            } catch (e: Throwable) {
                ErrorLogger.warn("SellReconciler", "paper orphan reconcile ${mint.take(10)}: ${e.message}")
            }
        }
        // Recompute post-cleanup so the snapshot reflects steady state (should be 0).
        lastPaperOrphanCount = try { computePaperOrphans().size } catch (_: Throwable) { 0 }
        totalChecked += orphans.size.toLong()
        try {
            ForensicLogger.lifecycle(
                "RECONCILER_TICK",
                "tickCount=$totalTicks paperMode=true orphansFound=${orphans.size} reconciled=$reconciled remaining=$lastPaperOrphanCount",
            )
        } catch (_: Throwable) {}
    }

    /** V5.9.765 — push fresh DexScreener price into host_tracker fields and
     *  emit anomaly events when a mint stays at price=0 across multiple
     *  ticks. */
    private fun hydratePrice(
        pos: HostWalletTokenTracker.TrackedTokenPosition,
        priceMap: Map<String, Double>,
    ) {
        val priceUsd = priceMap[pos.mint] ?: 0.0
        val entry = pos.entryPriceUsd ?: 0.0
        if (priceUsd > 0.0) {
            val gainPct = if (entry > 0.0) ((priceUsd - entry) / entry) * 100.0 else 0.0
            HostWalletTokenTracker.recordPriceUpdate(pos.mint, priceUsd, gainPct)
            zeroPriceStreak.remove(pos.mint)
            return
        }
        // Price returned 0 (no DS pair OR rate-limit denied the batch).
        // Track the consecutive zero-tick streak so we emit anomaly events
        // only after the price has actually stalled — single-tick blips
        // due to RateLimiter denials are normal and not worth screaming
        // about. Threshold = 2 ticks ≈ 20s without any DS pair update.
        val newStreak = (zeroPriceStreak[pos.mint] ?: 0) + 1
        zeroPriceStreak[pos.mint] = newStreak
        if (newStreak == 2) {
            try {
                ForensicLogger.lifecycle(
                    "PRICE_STALE_LIVE_POSITION",
                    "mint=${pos.mint.take(10)} symbol=${pos.symbol} streakTicks=$newStreak entry=$entry",
                )
                // V5.9.765 — EMERGENT priority 5 anomaly emission. The
                // existing forensics_events array in the export was empty
                // despite obvious faults; emitting this anomaly under a
                // dedicated tag means the exporter picks it up.
                ForensicLogger.lifecycle(
                    "LIVE_POSITION_PRICE_ZERO",
                    "mint=${pos.mint.take(10)} symbol=${pos.symbol} ticksWithoutPrice=$newStreak",
                )
            } catch (_: Throwable) {}
        }
    }

    private fun reconcileOne(
        pos: HostWalletTokenTracker.TrackedTokenPosition,
        walletTokens: Map<String, Pair<Double, Int>>,
    ) {
        val balance = walletTokens[pos.mint]?.first ?: 0.0
        try {
            ForensicLogger.lifecycle(
                "RECONCILER_OPEN_CHECKED",
                "mint=${pos.mint.take(10)} symbol=${pos.symbol} balance=$balance trackerStatus=${pos.status.name}",
            )
        } catch (_: Throwable) {}

        // Case 1: wallet says zero → close tracker as confirmed-zero.
        // No signature is recorded because we can't prove which tx removed
        // the balance. Tracker is taken out of the open set so the bot
        // stops trying to sell phantom inventory.
        if (balance <= 1e-9) {
            val updated = HostWalletTokenTracker.markUnheldByAntiChoke(
                pos.mint, "SellReconciler: wallet balance zero"
            )
            if (updated) {
                try {
                    ForensicLogger.lifecycle(
                        "SELL_VERIFY_BALANCE_ZERO",
                        "mint=${pos.mint.take(10)} symbol=${pos.symbol} closedByReconciler=true",
                    )
                } catch (_: Throwable) {}
            }
            SellJobRegistry.markLanded(pos.mint, signature = null)
            return
        }

        // Case 2: wallet has balance, an in-flight SellJob exists. The
        // SellJobRegistry.isLockedAndFresh() returns false if the lock
        // has stretched past LOCK_TTL_MS — in that case it ALSO emits the
        // SELL_LOCK_STALE_FORCE_RELEASED forensic and demotes the job to
        // FAILED_RETRYABLE so the next bot-loop pass can re-engage.
        val locked = SellJobRegistry.isLockedAndFresh(pos.mint)
        if (locked) return  // genuinely in-flight, leave it alone

        // Case 3: wallet has balance, NO fresh lock. Re-queue so the bot
        // loop's exit path picks the position up again on its next tick.
        try {
            ForensicLogger.lifecycle(
                "RECONCILER_EXIT_REQUEUED",
                "mint=${pos.mint.take(10)} symbol=${pos.symbol} balance=$balance",
            )
        } catch (_: Throwable) {}
        // V5.9.779 — also invoke the sellTrigger callback so the bot
        // loop doesn't need to find the mint in status.tokens. BotService
        // wires this to executor.requestSell with a TokenState rehydrated
        // from HostWalletTokenTracker if needed.
        try {
            sellTrigger?.invoke(pos.mint, pos.symbol ?: "?", balance)
        } catch (e: Throwable) {
            ErrorLogger.warn("SellReconciler", "sellTrigger threw: ${e.message?.take(80)}")
        }
    }
}
