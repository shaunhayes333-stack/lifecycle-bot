package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TokenLifecycleTracker
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z43 — operator spec item E.
 *
 * Forensics export showed `reconciler.totalChecked = 0` even with 14 open
 * positions — the live wallet state was never being reconciled into bot
 * state after buys/sells. Result: every host_tracker position had
 * `last_sell_signature = ""` and many had `currentPriceUsd = 0`.
 *
 * This worker is the canonical "pull on-chain → push into trackers" loop.
 * It runs:
 *   • on bot start
 *   • after every buy verification (caller invokes scheduleAfterBuy)
 *   • after every sell broadcast (caller invokes scheduleAfterSell)
 *   • on a fixed periodic tick (caller invokes onCycleTick)
 *
 * Updates:
 *   • TokenLifecycleTracker.onTokenLanded(mint, walletUiAmount)
 *     — lazy-creates the record if missing, transitions BUY_CONFIRMED → HELD,
 *       updates currentWalletTokenQty.
 *   • Increments totalChecked / totalUpdated / lastRunMs counters.
 *
 * NEVER closes a live position based on a paper-mode signal — that's
 * operator spec item E ("PAPER_EXIT must never close or confirm a live
 * host wallet position"). Reconciler ONLY transitions on chain-confirmed
 * data.
 */
object LiveWalletReconciler {

    private const val TAG = "LiveWalletReconciler"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Operator-facing telemetry (drives the forensics export) ────────────
    private val totalChecked = AtomicInteger(0)
    private val totalUpdated = AtomicInteger(0)
    private val totalRuns    = AtomicInteger(0)
    private val lastRunMs    = AtomicLong(0L)
    private val lastSellSig  = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastBuySig   = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun totalChecked(): Int  = totalChecked.get()
    fun totalUpdated(): Int  = totalUpdated.get()
    fun totalRuns(): Int     = totalRuns.get()
    fun lastRunMs(): Long    = lastRunMs.get()
    fun lastSellSignature(mint: String): String = lastSellSig[mint] ?: ""
    fun lastBuySignature(mint: String): String  = lastBuySig[mint]  ?: ""

    fun recordSellSignature(mint: String, sig: String) {
        if (mint.isNotBlank() && sig.isNotBlank()) lastSellSig[mint] = sig
    }
    fun recordBuySignature(mint: String, sig: String) {
        if (mint.isNotBlank() && sig.isNotBlank()) lastBuySig[mint] = sig
    }

    /** Trigger one reconciliation pass. Non-blocking — runs on IO. */
    fun reconcileNow(wallet: SolanaWallet?, reason: String) {
        val w = wallet ?: return
        scope.launch {
            try { reconcileBlocking(w, reason) }
            catch (e: Throwable) { ErrorLogger.warn(TAG, "reconcile threw: ${e.message}") }
        }
    }

    /** Synchronous variant used by tests + bot-start path. */
    fun reconcileBlocking(wallet: SolanaWallet, reason: String): Int {
        totalRuns.incrementAndGet()
        lastRunMs.set(System.currentTimeMillis())
        val balances: Map<String, Pair<Double, Int>> = try {
            wallet.getTokenAccountsWithDecimals()
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "wallet read failed during reconcile: ${e.message}")
            return 0
        }
        if (balances.isEmpty()) {
            // Operator spec: empty map ≠ wallet empty. Skip; do not zero.
            ErrorLogger.warn(TAG, "🟡 reconcile($reason): RPC returned empty map — skipped, NO state change.")
            return 0
        }
        try { com.lifecyclebot.engine.HostWalletTokenTracker.applyWalletSnapshot(balances) } catch (_: Throwable) {}
        // V5.9.495z45 — operator forensics_20260508_143519 spec item G:
        // re-evaluate UI safety flags after every successful wallet snapshot.
        try { LiveSafetyFlags.reevaluate(balances) } catch (_: Throwable) {}
        var updated = 0
        for ((mint, pair) in balances) {
            totalChecked.incrementAndGet()
            val (uiAmount, _) = pair
            if (uiAmount <= 0.0) continue
            try {
                // Lazy-create if missing — ALL live wallet tokens must be tracked.
                if (TokenLifecycleTracker.get(mint) == null) {
                    TokenLifecycleTracker.autoImportFromWallet(
                        mint = mint, symbol = mint.take(4),
                        walletUiAmount = uiAmount, venue = "reconciler",
                    )
                    updated++
                } else {
                    TokenLifecycleTracker.onTokenLanded(mint, uiAmount)
                    updated++
                }
                val price = try {
                    com.lifecyclebot.perps.DynamicAltTokenRegistry.refreshPriceForMintBlocking(mint).takeIf { it > 0.0 }
                        ?: com.lifecyclebot.network.DexscreenerApi().getBestPair(mint)?.candle?.priceUsd?.takeIf { it > 0.0 }
                        ?: 0.0
                } catch (_: Throwable) { 0.0 }
                if (price > 0.0) {
                    com.lifecyclebot.engine.HostWalletTokenTracker.recordPriceUpdate(mint, price, 0.0)
                }
            } catch (e: Throwable) {
                ErrorLogger.warn(TAG, "reconcile update failed for ${mint.take(8)}…: ${e.message}")
            }
        }
        totalUpdated.addAndGet(updated)
        ErrorLogger.info(TAG,
            "🔄 reconcile($reason): checked=${balances.size} updated=$updated runs=${totalRuns.get()}")
        return updated
    }
}
