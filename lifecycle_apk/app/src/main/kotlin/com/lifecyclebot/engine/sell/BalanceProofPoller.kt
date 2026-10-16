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
        // V5.0.8141 — a settled frozen holding whose wait is gone still thaws when a wallet read sees it unfrozen.
        try { com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.sweepThaw8141() } catch (_: Throwable) {}
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
        if (frozenHold8136(entry)) return
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
                // V5.0.8144 — a positive balance of a sub-ledger frozen holding is its thaw: the same position is restored first.
                try { com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.onBalanceProven8144(entry.mint) } catch (_: Throwable) {}
                val reason = entry.desiredExitReason
                val symbol = entry.symbol
                val mint = entry.mint
                BalanceProofWaitState.clear(mint, "BALANCE_PROOF_READY")
                // Hand back to BotService — it will re-enqueue into PendingSellQueue
                // as ACTIVE_SELL_READY (the existing requestSell path now has a
                // verified-amount RPC reading available).
                try { onProofReady?.invoke(mint, symbol, reason) } catch (_: Throwable) {}
            }
            is SellAmountAuthority.Resolution.Zero -> onZeroRead7733(entry, resolution.source.name)
            is SellAmountAuthority.Resolution.Unknown -> {
                // V5.0.7733 — the header above promised "mint absent from a
                // NON-empty map = one zero read". resolve() has returned Unknown
                // for that read since 3749, so this branch polled forever
                // (5.0.7732: BALANCE_PROOF_STILL_UNKNOWN=24, EkDGB5fb never
                // closed). The authority now records the absence when the
                // snapshot was complete; two such reads at least twenty seconds
                // apart, outside the fresh-buy grace, are the zero read.
                if (absentFromCompleteIsZeroRead7733(entry)) onZeroRead7733(entry, "ABSENT_FROM_COMPLETE_SNAPSHOT_7733")
                else BalanceProofWaitState.scheduleNextPoll(entry.mint)
            }
        }
    }

    private const val ABSENT_COMPLETE_MIN_READS_7733 = 2
    private const val ABSENT_COMPLETE_MIN_SPAN_MS_7733 = 20_000L
    /** Same indexer grace the tracker's absent-mint ladder uses. */
    private const val FRESH_BUY_GRACE_MS_7733 = 180_000L

    private fun absentFromCompleteIsZeroRead7733(entry: BalanceProofWaitState.Wait): Boolean {
        val a: SellAmountAuthority.AbsentFromComplete7733 = SellAmountAuthority.absentFromCompleteSnapshot7733(entry.mint) ?: return false
        if (a.count < ABSENT_COMPLETE_MIN_READS_7733 || a.lastAtMs - a.firstAtMs < ABSENT_COMPLETE_MIN_SPAN_MS_7733) return false
        val now = System.currentTimeMillis()
        val buyAt = try { com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(entry.mint)?.buyTimeMs ?: 0L } catch (_: Throwable) { 0L }
        val freshBuy = (buyAt > 0L && now - buyAt < FRESH_BUY_GRACE_MS_7733) || SellAmountAuthority.hasFreshTxParse7733(entry.mint, now)
        if (freshBuy) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BALANCE_PROOF_ABSENT_DEFERRED_FRESH_BUY_7733") } catch (_: Throwable) {}
            return false
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BALANCE_PROOF_ABSENT_COMPLETE_ZERO_READ_7733")
            ForensicLogger.lifecycle("BALANCE_PROOF_ABSENT_COMPLETE_ZERO_READ_7733",
                "mint=${entry.mint.take(10)} symbol=${entry.symbol} absentReads=${a.count} spanMs=${a.lastAtMs - a.firstAtMs} " +
                "action=count_as_zero_read_two_close_the_row")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.8136 — the mint's token account is frozen: the wallet read omits it, so "absent" here is the freeze, not an
     * empty wallet. Hold the wait (no zero read, no close, no route) and re-check slowly until a wallet read sees a thaw.
     */
    private fun frozenHold8136(entry: BalanceProofWaitState.Wait): Boolean {
        val frozen = FrozenHoldingGuard8136.isFrozen8136(entry.mint)
        if (FrozenHoldingGuard8136.zeroFinalityAllowed8136(frozen)) {
            // V5.0.8141 — the thaw path: a FROZEN_HELD_UNSELLABLE holding is a normal position again (normal sell re-opens).
            try { com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.onThaw8141(entry.mint) } catch (_: Throwable) {}
            FrozenHoldingGuard8136.clear8136(entry.mint)
            return false
        }
        BalanceProofWaitState.scheduleFrozenRecheck8136(entry.mint, FrozenHoldingGuard8136.FROZEN_RECHECK_MS_8136)
        if (FrozenHoldingGuard8136.noteHeld8136(entry.mint)) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BALANCE_PROOF_FROZEN_HELD_NOT_ZERO_8136")
                ForensicLogger.lifecycle("BALANCE_PROOF_FROZEN_HELD_NOT_ZERO_8136",
                    "mint=${entry.mint.take(10)} symbol=${entry.symbol} reason=${entry.desiredExitReason} " +
                    "action=hold_no_zero_close_no_ghost_exit recheckMs=${FrozenHoldingGuard8136.FROZEN_RECHECK_MS_8136}")
            } catch (_: Throwable) {}
        }
        return true
    }

    private fun onZeroRead7733(entry: BalanceProofWaitState.Wait, source: String) {
        // V5.0.8136 — last line: a zero/absent read never finalises a frozen holding.
        if (frozenHold8136(entry)) return
        // V5.0.8144 — nor one the frozen-holdings sub-ledger still holds while no wallet read since the restart has shown
        // its account (the freeze hides it): hold and re-check slowly. A positive balance read still proves a thaw.
        if (try { com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.heldUnproven8144(entry.mint) } catch (_: Throwable) { false }) {
            BalanceProofWaitState.scheduleFrozenRecheck8136(entry.mint, FrozenHoldingGuard8136.FROZEN_RECHECK_MS_8136)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BALANCE_PROOF_SUBLEDGER_HELD_NOT_ZERO_8144") } catch (_: Throwable) {}
            return
        }
        val streak = BalanceProofWaitState.recordZeroRead(entry.mint)
        BalanceProofWaitState.scheduleNextPoll(entry.mint)
        if (streak >= 2) {
            val reason = entry.desiredExitReason
            val symbol = entry.symbol
            val mint = entry.mint
            try {
                ForensicLogger.lifecycle("ZERO_BALANCE_CONFIRMED",
                    "mint=${mint.take(10)} symbol=$symbol consecutiveZeroReads=$streak " +
                    "source=$source action=close_verified_no_broadcast")
                SellForensics.inc(SellForensics.ZERO_BALANCE_CONFIRMED,
                    "mint=${mint.take(10)} symbol=$symbol")
                SellForensics.inc(SellForensics.EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED,
                    "mint=${mint.take(10)} symbol=$symbol")
            } catch (_: Throwable) {}
            BalanceProofWaitState.clear(mint, "ZERO_BALANCE_CONFIRMED")
            try { onZeroConfirmed?.invoke(mint, symbol, reason) } catch (_: Throwable) {}
        }
    }
}
