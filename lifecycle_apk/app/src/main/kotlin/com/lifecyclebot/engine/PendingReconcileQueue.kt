/*
 * V5.9.495z4 — Background reconciliation queue (operator spec Step 7).
 *
 * When a sell or buy fails to verify within the synchronous 45-second
 * post-broadcast watchdog window, we register the signature here.
 * This queue then re-checks the on-chain state at +2 min, +5 min, and
 * +10 min using the authoritative TradeVerifier path
 * (getSignatureStatuses + getTransaction tx-parse).
 *
 * If the chain ever returns LANDED for the sig, we emit a
 * SELL_RECONCILE_LANDED / BUY_RECONCILE_LANDED forensic event — and
 * because LiveTradeLogStore now latches terminal-good states, any
 * earlier SELL_STUCK / BUY_PHANTOM / SELL_VERIFY_INCONCLUSIVE_PENDING
 * is functionally retired (they cannot be re-emitted afterwards).
 *
 * If the chain returns FAILED_CONFIRMED, we emit SELL_FAILED_CONFIRMED.
 *
 * If after the 10-minute checkpoint the sig is still INCONCLUSIVE, we
 * leave the entry in INCONCLUSIVE_PENDING and stop polling — the
 * operator forensics tile will show the trade as inconclusive (rather
 * than falsely claiming it is stuck or phantom).
 *
 * The queue is in-memory only; it does not survive process restarts,
 * which is fine — by the time the app is killed and restored, the
 * fresh wallet read on next start will pick up any settled state.
 */
package com.lifecyclebot.engine

import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object PendingReconcileQueue {
    private const val TAG = "PendingReconcile"

    /** Checkpoints relative to registration time. Operator spec: 2 / 5 / 10 min. */
    private val CHECKPOINTS_MS = longArrayOf(120_000L, 300_000L, 600_000L)

    private data class Entry(
        val sig: String,
        val mint: String,
        val symbol: String,
        val tradeKey: String,
        val side: String,            // "SELL" or "BUY"
        val traderTag: String?,
        val registeredAt: Long,
        val wallet: SolanaWallet,
    )

    /** sig → entry, used to deduplicate registrations from multiple watchdogs on the same trade. */
    private val pending = ConcurrentHashMap<String, Entry>()

    fun registerSell(
        sig: String,
        mint: String,
        symbol: String,
        tradeKey: String,
        traderTag: String?,
        wallet: SolanaWallet,
    ) {
        registerInternal(Entry(sig, mint, symbol, tradeKey, "SELL", traderTag, System.currentTimeMillis(), wallet))
    }

    fun registerBuy(
        sig: String,
        mint: String,
        symbol: String,
        tradeKey: String,
        traderTag: String?,
        wallet: SolanaWallet,
    ) {
        registerInternal(Entry(sig, mint, symbol, tradeKey, "BUY", traderTag, System.currentTimeMillis(), wallet))
    }

    private fun registerInternal(e: Entry) {
        if (e.sig.isBlank() || e.sig.startsWith("PHANTOM_")) return
        if (LiveTradeLogStore.isTerminallyResolved(e.tradeKey, e.sig)) return
        if (pending.putIfAbsent(e.sig, e) != null) return  // already scheduled
        LiveTradeLogStore.log(
            e.tradeKey, e.mint, e.symbol, e.side,
            LiveTradeLogStore.Phase.SELL_RECONCILE_SCHEDULED,
            "📅 Reconcile scheduled at +2/+5/+10 min for ${e.side} sig=${e.sig.take(16)}",
            sig = e.sig, traderTag = e.traderTag,
        )
        scheduleChecks(e)
    }

    private fun scheduleChecks(e: Entry) {
        GlobalScope.launch(Dispatchers.IO) {
            for (cp in CHECKPOINTS_MS) {
                val sleepFor = (e.registeredAt + cp - System.currentTimeMillis()).coerceAtLeast(0L)
                if (sleepFor > 0) delay(sleepFor)
                if (LiveTradeLogStore.isTerminallyResolved(e.tradeKey, e.sig)) {
                    pending.remove(e.sig)
                    return@launch
                }
                if (runOneCheck(e, cp)) {
                    pending.remove(e.sig)
                    return@launch
                }
            }
            // All three checkpoints passed without authoritative resolution.
            pending.remove(e.sig)
            ErrorLogger.debug(TAG, "exhausted reconcile checkpoints for sig=${e.sig.take(16)} — leaving in INCONCLUSIVE_PENDING")
        }
    }

    /** Returns true if the entry was authoritatively resolved (success or confirmed failure). */
    private fun runOneCheck(e: Entry, checkpointMs: Long): Boolean {
        return try {
            when (e.side) {
                "SELL" -> {
                    val r = TradeVerifier.verifySell(e.wallet, e.sig, e.mint, timeoutMs = 8_000L)
                    when (r.outcome) {
                        TradeVerifier.Outcome.LANDED -> {
                            LiveTradeLogStore.log(
                                e.tradeKey, e.mint, e.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_RECONCILE_LANDED,
                                "✅ Reconciled at +${checkpointMs / 60_000}min: SELL LANDED rawConsumed=${r.rawTokenConsumed} solReceived=${r.solReceivedLamports} lam${if (r.tokenAccountClosedFullExit) " (ATA closed)" else ""}",
                                sig = e.sig, tokenAmount = r.uiTokenConsumed, traderTag = e.traderTag,
                            )
                            true
                        }
                        TradeVerifier.Outcome.FAILED_CONFIRMED -> {
                            LiveTradeLogStore.log(
                                e.tradeKey, e.mint, e.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_FAILED_CONFIRMED,
                                "🚨 Reconciled at +${checkpointMs / 60_000}min: SELL FAILED_CONFIRMED meta.err=${r.txErr}",
                                sig = e.sig, traderTag = e.traderTag,
                            )
                            true
                        }
                        else -> false
                    }
                }
                "BUY" -> {
                    val r = TradeVerifier.verifyBuy(e.wallet, e.sig, e.mint, timeoutMs = 8_000L)
                    when (r.outcome) {
                        TradeVerifier.Outcome.LANDED -> {
                            LiveTradeLogStore.log(
                                e.tradeKey, e.mint, e.symbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_RECONCILE_LANDED,
                                "✅ Reconciled at +${checkpointMs / 60_000}min: BUY LANDED rawDelta=${r.rawTokenDelta} ui=${"%.4f".format(r.uiTokenDelta)} solSpent=${r.solSpentLamports} lam",
                                sig = e.sig, tokenAmount = r.uiTokenDelta, traderTag = e.traderTag,
                            )
                            true
                        }
                        TradeVerifier.Outcome.FAILED_CONFIRMED -> {
                            LiveTradeLogStore.log(
                                e.tradeKey, e.mint, e.symbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_FAILED,
                                "🚨 Reconciled at +${checkpointMs / 60_000}min: BUY FAILED_CONFIRMED meta.err=${r.txErr}",
                                sig = e.sig, traderTag = e.traderTag,
                            )
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "checkpoint reconcile threw: ${t.message?.take(80)}")
            false
        }
    }

    fun pendingCount(): Int = pending.size
}
