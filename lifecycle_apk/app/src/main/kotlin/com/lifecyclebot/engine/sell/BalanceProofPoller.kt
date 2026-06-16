package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.3746 — BALANCE PROOF POLLER (operator spec items 2, 5, 10).
 *
 * Non-blocking, single-flight poller per mint. While BalanceProofWaitState
 * holds a mint, this is the ONLY worker permitted to touch its sell intent.
 * No close lease, no sell lock, no PendingSellQueue ACTIVE_SELL_READY job
 * is ever created or held during a proof wait.
 *
 * Poll outcomes per mint:
 *   • SellAmountAuthority.resolve → Confirmed  → BALANCE_PROOF_READY:
 *       clear wait, schedule an ACTIVE sell retry (PendingSellQueue.add)
 *       with the position's stored desired exit reason. The next botLoop
 *       tick picks it up under a freshly acquired CloseLease.
 *   • Resolution.Zero (mint absent from a NON-empty RPC map). One read =
 *       evidence; TWO consecutive reads = ZERO_BALANCE_CONFIRMED → close
 *       the position without broadcasting (the wallet is empty).
 *   • Resolution.Unknown (RPC empty map) → schedule next poll w/ backoff.
 *
 * Backoff schedule is owned by BalanceProofWaitState.backoffFor(attempt).
 */
object BalanceProofPoller {
    private const val TICK_INTERVAL_MS: Long = 2_000L
    private val jobRef = AtomicReference<Job?>(null)
    @Volatile private var wallet: SolanaWallet? = null
    @Volatile private var paperMode: Boolean = true
    @Volatile var totalTicks: Long = 0L
    @Volatile var lastTickAtMs: Long = 0L

    // Callback to BotService for ZERO_BALANCE_CONFIRMED closes (no broadcast).
    @Volatile private var onZeroConfirmed: ((mint: String, symbol: String, reason: String) -> Unit)? = null
    // Callback to BotService for BALANCE_PROOF_READY (re-enqueue active sell).
    @Volatile private var onProofReady: ((mint: String, symbol: String, reason: String) -> Unit)? = null

    fun start(
        scope: CoroutineScope,
        isPaperMode: Boolean,
        hostWallet: SolanaWallet?,
        onProofReady: ((mint: String, symbol: String, reason: String) -> Unit)? = null,
        onZeroConfirmed: ((mint: String, symbol: String, reason: String) -> Unit)? = null,
    ) {
        paperMode = isPaperMode
        wallet = hostWallet
        if (onProofReady != null) this.onProofReady = onProofReady
        if (onZeroConfirmed != null) this.onZeroConfirmed = onZeroConfirmed
        jobRef.get()?.cancel()
        if (isPaperMode || hostWallet == null) {
            jobRef.set(null)
            return
        }
        val newJob = scope.launch(Dispatchers.IO) {
            ErrorLogger.info("BalanceProofPoller", "🔭 BalanceProofPoller started (2s tick, LIVE)")
            try {
                ForensicLogger.lifecycle("BALANCE_PROOF_POLLER_START", "intervalMs=$TICK_INTERVAL_MS")
            } catch (_: Throwable) {}
            while (isActive) {
                try { tickOnce() } catch (e: Throwable) {
                    ErrorLogger.warn("BalanceProofPoller", "tick error: ${e.message}")
                }
                delay(TICK_INTERVAL_MS)
            }
        }
        jobRef.set(newJob)
    }

    fun stop() {
        jobRef.get()?.cancel()
        jobRef.set(null)
    }

    private fun tickOnce() {
        totalTicks += 1
        lastTickAtMs = System.currentTimeMillis()
        val w = wallet ?: return
        val due = BalanceProofWaitState.all().filter { BalanceProofWaitState.dueForPoll(it.mint) }
        if (due.isEmpty()) return
        for (entry in due) {
            try { pollOne(entry, w) } catch (e: Throwable) {
                ErrorLogger.warn("BalanceProofPoller",
                    "poll error mint=${entry.mint.take(10)}: ${e.message}")
            }
        }
    }

    private fun pollOne(entry: BalanceProofWaitState.Wait, w: SolanaWallet) {
        val resolution = try {
            SellAmountAuthority.resolve(entry.mint, w)
        } catch (e: Throwable) {
            ErrorLogger.warn("BalanceProofPoller",
                "resolve threw mint=${entry.mint.take(10)}: ${e.message}")
            BalanceProofWaitState.scheduleNextPoll(entry.mint)
            return
        }
        when (resolution) {
            is SellAmountAuthority.Resolution.Confirmed -> {
                try {
                    ForensicLogger.lifecycle("BALANCE_PROOF_READY",
                        "mint=${entry.mint.take(10)} symbol=${entry.symbol} " +
                        "rawAmount=${resolution.rawAmount} decimals=${resolution.decimals} " +
                        "source=${resolution.source} desiredExitReason=${entry.desiredExitReason}")
                    SellForensics.inc(SellForensics.BALANCE_PROOF_READY,
                        "mint=${entry.mint.take(10)} symbol=${entry.symbol}")
                } catch (_: Throwable) {}
                try { SellAmountAuthority.recordProofReady(entry.mint, resolution.rawAmount, resolution.decimals, resolution.source) } catch (_: Throwable) {}
                // Reset zero-streak (we just saw a positive balance).
                BalanceProofWaitState.resetZeroReads(entry.mint)
                val reason = entry.desiredExitReason
                val symbol = entry.symbol
                val mint = entry.mint
                BalanceProofWaitState.clear(mint, "BALANCE_PROOF_READY")
                // Hand back to BotService — it will re-enqueue into PendingSellQueue
                // as ACTIVE_SELL_READY (the existing requestSell path now has a
                // verified-amount RPC reading available).
                try { onProofReady?.invoke(mint, symbol, reason) } catch (_: Throwable) {}
            }
            is SellAmountAuthority.Resolution.Zero -> {
                val streak = BalanceProofWaitState.recordZeroRead(entry.mint)
                BalanceProofWaitState.scheduleNextPoll(entry.mint)
                if (streak >= 2) {
                    val reason = entry.desiredExitReason
                    val symbol = entry.symbol
                    val mint = entry.mint
                    try {
                        ForensicLogger.lifecycle("ZERO_BALANCE_CONFIRMED",
                            "mint=${mint.take(10)} symbol=$symbol consecutiveZeroReads=$streak " +
                            "source=${resolution.source} action=close_verified_no_broadcast")
                        SellForensics.inc(SellForensics.ZERO_BALANCE_CONFIRMED,
                            "mint=${mint.take(10)} symbol=$symbol")
                        SellForensics.inc(SellForensics.EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED,
                            "mint=${mint.take(10)} symbol=$symbol")
                    } catch (_: Throwable) {}
                    BalanceProofWaitState.clear(mint, "ZERO_BALANCE_CONFIRMED")
                    try { onZeroConfirmed?.invoke(mint, symbol, reason) } catch (_: Throwable) {}
                }
            }
            is SellAmountAuthority.Resolution.Unknown -> {
                BalanceProofWaitState.scheduleNextPoll(entry.mint)
            }
        }
    }
}
