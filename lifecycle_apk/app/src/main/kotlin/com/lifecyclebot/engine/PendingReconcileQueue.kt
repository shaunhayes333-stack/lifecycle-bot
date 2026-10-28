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
 * V5.0.8418: outcomes are BOOKED (ReceiptBooking8418); a restart re-queues confirmed-unbooked owned rows.
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
                if (runOneCheck(e, cp, cp == CHECKPOINTS_MS.last())) {
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
    private fun runOneCheck(e: Entry, checkpointMs: Long, lastCheckpoint8418: Boolean = false): Boolean {
        return try {
            // V5.0.8418 — the receipt is BOOKED to the signature's owner (ReceiptBooking8418), not only logged.
            val row8418 = try { com.lifecyclebot.network.SignedTxLedger8085.get(e.sig) } catch (_: Throwable) { null }
            val owner8418 = row8418?.positionId.orEmpty()
            val attempt8418 = row8418?.intentId.orEmpty()
            val outcome8418 = when (e.side) {
                "SELL" -> ReceiptBooking8418.onSellReceipt8418(owner8418, e.mint, e.sig,
                    try { TradeVerifier.verifySell(e.wallet, e.sig, e.mint, timeoutMs = 8_000L) } catch (_: Throwable) { null },
                    lastCheckpoint8418, ReceiptBooking8418.LiveBooks8418)
                "BUY" -> ReceiptBooking8418.onBuyReceipt8418(owner8418, attempt8418, e.mint, e.sig,
                    try { TradeVerifier.verifyBuy(e.wallet, e.sig, e.mint, timeoutMs = 8_000L) } catch (_: Throwable) { null },
                    lastCheckpoint8418, ReceiptBooking8418.LiveBooks8418)
                else -> ReceiptBooking8418.Outcome8418.NO_EFFECT_NO_RECEIPT
            }
            try {
                PipelineHealthCollector.labelInc("RECONCILE_RECEIPT_${e.side}_${outcome8418.name}_8418")
                if (outcome8418 != ReceiptBooking8418.Outcome8418.PENDING) ForensicLogger.lifecycle("RECONCILE_RECEIPT_BOOKING_8418",
                    "side=${e.side} mint=${e.mint.take(10)} sig=${e.sig.take(16)} owner=${owner8418.take(32)} attempt=${attempt8418.take(40)} " +
                        "outcome=${outcome8418.name} checkpointMin=${checkpointMs / 60_000}")
            } catch (_: Throwable) {}
            if (outcome8418 == ReceiptBooking8418.Outcome8418.PENDING) return false
            if (outcome8418 == ReceiptBooking8418.Outcome8418.NO_EFFECT_NO_RECEIPT || outcome8418 == ReceiptBooking8418.Outcome8418.NO_EFFECT_FAILED ||
                outcome8418 == ReceiptBooking8418.Outcome8418.NO_EFFECT_OWNER_GONE) {
                val phase8418 = when {
                    outcome8418 != ReceiptBooking8418.Outcome8418.NO_EFFECT_FAILED -> LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING
                    e.side == "SELL" -> LiveTradeLogStore.Phase.SELL_FAILED_CONFIRMED
                    else -> LiveTradeLogStore.Phase.BUY_FAILED
                }
                LiveTradeLogStore.log(e.tradeKey, e.mint, e.symbol, e.side, phase8418,
                    "⏹ Reconcile terminal ${outcome8418.name}: no change to any position (sig=${e.sig.take(16)})", sig = e.sig, traderTag = e.traderTag)
                return true
            }
            // BOOKED / DUPLICATE / ENTRY_PROOF_PATH: the receipt proved the landing (latches the trade log terminal-good).
            LiveTradeLogStore.log(
                e.tradeKey, e.mint, e.symbol, e.side,
                if (e.side == "SELL") LiveTradeLogStore.Phase.SELL_RECONCILE_LANDED else LiveTradeLogStore.Phase.BUY_RECONCILE_LANDED,
                "✅ Reconciled at +${checkpointMs / 60_000}min: ${e.side} LANDED — receipt ${outcome8418.name}",
                sig = e.sig, traderTag = e.traderTag,
            )
            true
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "checkpoint reconcile threw: ${t.message?.take(80)}")
            false
        }
    }

    fun pendingCount(): Int = pending.size
}
