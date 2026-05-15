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

    /** Total ticks executed since service start — surfaced for debugging. */
    @Volatile var totalTicks: Long = 0L
    /** Cumulative open positions inspected across all ticks. */
    @Volatile var totalChecked: Long = 0L

    /** Idempotent start. Caller (BotService) re-invokes whenever mode or
     *  wallet changes; we restart cleanly. */
    fun start(scope: CoroutineScope, isPaperMode: Boolean, hostWallet: SolanaWallet?) {
        paperMode = isPaperMode
        wallet = hostWallet
        // Cancel any prior loop.
        jobRef.get()?.cancel()
        if (isPaperMode || hostWallet == null) {
            jobRef.set(null)
            return
        }
        val newJob = scope.launch(Dispatchers.IO) {
            ErrorLogger.info("SellReconciler", "🩹 SellReconciler started (10s tick, LIVE mode)")
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
        jobRef.getAndSet(null)?.cancel()
    }

    /** Single reconciliation pass. Public for unit-tests / manual nudges. */
    suspend fun tickOnce() {
        if (paperMode) return
        val w = wallet ?: return
        val open = HostWalletTokenTracker.getOpenTrackedPositions()
        totalTicks++
        totalChecked += open.size.toLong()
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
            try { w.getTokenAccountsWithDecimals() } catch (_: Throwable) { emptyMap() }
        }

        for (pos in open) {
            try { reconcileOne(pos, tokens) } catch (e: Throwable) {
                ErrorLogger.warn("SellReconciler", "reconcileOne(${pos.mint.take(10)}): ${e.message}")
            }
        }
        SellJobRegistry.pruneTerminal()
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
    }
}
