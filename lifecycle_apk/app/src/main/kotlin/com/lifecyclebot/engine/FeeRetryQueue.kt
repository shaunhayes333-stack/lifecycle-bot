package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.network.SolanaWallet
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.9.226 — Bug #7 Fix: Fee Retry Queue
 *
 * When a fee sendSol() fails due to network hiccup, the fee entry is stored
 * in SharedPreferences. At the start of each bot cycle (drainFeeQueue()),
 * pending fees are retried in order.
 *
 * This prevents fees from being silently lost on transient RPC failures.
 */
object FeeRetryQueue {

    private const val PREFS_NAME  = "fee_retry_queue"
    private const val KEY_ENTRIES  = "pending_fees"
    private const val MAX_RETRIES  = 20  // V5.9.309: 5→20 — drained fees were being lost in seconds
    private const val MAX_AGE_MS   = 24 * 60 * 60 * 1000L  // 24 hours — drop stale fees
    private const val MIN_BALANCE_FOR_FEE = 0.005  // V5.9.309: skip retry if wallet too low; defer rather than retry-fail

    data class FeeEntry(
        val toAddress: String,
        val amountSol: Double,
        val reason: String,
        val createdMs: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Enqueue a failed fee for later retry */
    fun enqueue(toAddress: String, amountSol: Double, reason: String) {
        val p = prefs ?: run {
            ErrorLogger.error("FeeRetryQueue", "⚠ Not initialized — fee lost: ${amountSol.fmt(5)} SOL → $toAddress")
            return
        }
        // V5.9.495z29 — operator spec item 7. Self-loop guard. If the
        // fee destination is somehow the bot's own wallet (e.g. the
        // configured fee wallet was rotated to the operator's own
        // address), enqueueing a transfer to self causes a permanent
        // "Account loaded twice" failure on every retry. Drop early
        // with a clear log so the operator sees the misconfiguration.
        try {
            val selfPk = WalletManager.getWallet()?.publicKeyB58
            if (selfPk != null && selfPk.equals(toAddress, ignoreCase = false)) {
                ErrorLogger.warn("FeeRetryQueue",
                    "⛔ Skipping self-loop fee transfer ${amountSol.fmt(5)} SOL → self ($reason). " +
                    "Check fee wallet configuration.")
                return
            }
        } catch (_: Throwable) { /* best-effort guard */ }
        val entry = FeeEntry(toAddress, amountSol, reason)
        val arr = loadArray(p)
        arr.put(entryToJson(entry))
        p.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        ErrorLogger.warn("FeeRetryQueue", "📥 Queued failed fee: ${amountSol.fmt(5)} SOL → $toAddress ($reason)")
    }

    /**
     * Drain the queue — call at the start of each bot scan cycle.
     * Attempts to resend each pending fee using the provided wallet.
     */
    fun drainFeeQueue(wallet: SolanaWallet) {
        val p = prefs ?: return
        val arr = loadArray(p)
        if (arr.length() == 0) return

        val remaining = JSONArray()
        val now = System.currentTimeMillis()

        // V5.0.7694 — this queue no longer destroys money. Stale, exhausted and
        // "non-retryable" entries go back to the FeeAccumulator bucket for their
        // destination, where the rent-exemption hold (the actual cause of the
        // "insufficient funds for rent" preflight failures that were classified
        // non-retryable here) lets them accrue until one transfer can land. If
        // the accumulator is not initialised the entry simply stays queued.
        fun returnToBucket7694(entry: FeeEntry, why: String): Boolean {
            // A self-addressed entry is not a fee anyone can receive; it is the
            // one case that is correctly discarded (accrue() would refuse it too).
            val selfPk7694 = try { wallet.publicKeyB58 } catch (_: Throwable) { "" }
            if (selfPk7694.isNotBlank() && selfPk7694 == entry.toAddress) {
                ErrorLogger.warn("FeeRetryQueue", "⛔ Discarding self-addressed fee entry ($why): ${entry.amountSol.fmt(5)} SOL → self")
                return true
            }
            if (!FeeAccumulator.isInitialized7694()) return false
            return try {
                val bucket = FeeAccumulator.accrue(entry.toAddress, entry.amountSol, "retry_queue_returned_7694_$why")
                if (bucket > 0.0) {
                    PipelineHealthCollector.labelInc("FEE_RETRY_RETURNED_TO_BUCKET_7694")
                    ErrorLogger.warn("FeeRetryQueue", "↩ Returned to bucket ($why): ${entry.amountSol.fmt(5)} SOL → ${entry.toAddress} (bucket=${bucket.fmt(5)})")
                    true
                } else false
            } catch (_: Throwable) { false }
        }

        for (i in 0 until arr.length()) {
            val json = arr.getJSONObject(i)
            val entry = jsonToEntry(json)

            // Stale entries (>24h) and exhausted retries return to the bucket
            // (V5.0.7694); they used to be dropped.
            if (now - entry.createdMs > MAX_AGE_MS) {
                if (returnToBucket7694(entry, "stale")) continue
                remaining.put(entryToJson(entry))
                continue
            }
            if (entry.retryCount >= MAX_RETRIES) {
                if (returnToBucket7694(entry, "retries_exhausted")) continue
                remaining.put(entryToJson(entry))
                continue
            }

            // V5.9.309: WALLET BALANCE GATE — defer retry rather than burn one if wallet too low.
            // Otherwise transient mid-swap states drained the retry counter to 0 in seconds and
            // the fee was permanently lost. Now we keep the retry slot until the wallet
            // can actually afford the fee + tx fee.
            val walletSol = try { wallet.getSolBalance() } catch (_: Exception) { 0.0 }
            if (walletSol < (entry.amountSol + MIN_BALANCE_FOR_FEE)) {
                ErrorLogger.debug("FeeRetryQueue", "⏸ Deferring fee (wallet=${walletSol.fmt(5)}<need=${(entry.amountSol+MIN_BALANCE_FOR_FEE).fmt(5)}): ${entry.amountSol.fmt(5)} SOL → ${entry.toAddress}")
                remaining.put(entryToJson(entry))  // keep in queue with same retry count
                continue
            }

            // V5.9.495z6 — operator spec I: classify errors as retryable
            // vs non-retryable. AccountNotFound / insufficient funds /
            // closed token account / blockhash not found / already
            // processed / invalid account data are PERMANENT failures —
            // burning 18/19/20 retry slots on them is pointless and
            // crowds out genuinely transient retries.
            val nonRetryablePatterns = listOf(
                "accountnotfound", "account not found",
                "insufficient", "insufficientfunds",
                "token account closed", "ata closed",
                "blockhash not found", "blockhashnotfound",
                "already processed",
                "invalid account data",
                "owner mismatch",
                // V5.9.495z29 — operator spec item 7. "Account loaded twice"
                // means the tx had the same account in two writable slots
                // (typically because the destination matched the source).
                // Burning retry slots on this is pointless.
                "account loaded twice",
                "loaded twice",
            )

            // Attempt retry
            try {
                wallet.sendSol(entry.toAddress, entry.amountSol)
                ErrorLogger.info("FeeRetryQueue", "✅ Retry success: ${entry.amountSol.fmt(5)} SOL → ${entry.toAddress} (attempt ${entry.retryCount + 1})")
                // Fee sent — don't re-add to remaining
            } catch (e: Exception) {
                val errMsg = (e.message ?: "").lowercase()
                val nonRetryable = nonRetryablePatterns.any { it in errMsg }
                if (nonRetryable) {
                    // V5.0.7694 — "insufficient" here was usually the DESTINATION's
                    // rent-exemption preflight on a 0.0001 SOL transfer, not a real
                    // dead end. Return the amount to its bucket; the accumulator
                    // holds it until a single transfer can fund the account.
                    val returned7694 = returnToBucket7694(entry, "non_retryable")
                    ErrorLogger.warn(
                        "FeeRetryQueue",
                        "🛑 NON_RETRYABLE error — ${if (returned7694) "returned to bucket" else "kept queued"} (${entry.amountSol.fmt(5)} SOL → ${entry.toAddress}): ${e.message?.take(80)}"
                    )
                    LiveTradeLogStore.log(
                        tradeKey = "FEE_${entry.toAddress.take(8)}",
                        mint = "", symbol = "FEE",
                        side = "FEE",
                        phase = LiveTradeLogStore.Phase.FEE_RETRY_CANCELLED_NON_RETRYABLE,
                        message = "🛑 Fee retry non-retryable: ${e.message?.take(60)} → ${if (returned7694) "returned to bucket" else "kept queued"} ${entry.amountSol.fmt(5)} SOL",
                        solAmount = entry.amountSol,
                    )
                    if (!returned7694) remaining.put(entryToJson(entry))
                } else {
                    ErrorLogger.warn("FeeRetryQueue", "⚠ Retry failed (${entry.retryCount + 1}/${MAX_RETRIES}): ${e.message?.take(60)}")
                    remaining.put(entryToJson(entry.copy(retryCount = entry.retryCount + 1)))
                }
            }
        }

        p.edit().putString(KEY_ENTRIES, remaining.toString()).apply()
    }

    fun pendingCount(): Int = prefs?.let { loadArray(it).length() } ?: 0

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun loadArray(p: SharedPreferences): JSONArray {
        val raw = p.getString(KEY_ENTRIES, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun entryToJson(e: FeeEntry) = JSONObject().apply {
        put("to", e.toAddress)
        put("sol", e.amountSol)
        put("reason", e.reason)
        put("created", e.createdMs)
        put("retries", e.retryCount)
    }

    private fun jsonToEntry(j: JSONObject) = FeeEntry(
        toAddress  = j.getString("to"),
        amountSol  = j.getDouble("sol"),
        reason     = j.optString("reason", "unknown"),
        createdMs  = j.getLong("created"),
        retryCount = j.getInt("retries"),
    )

    private fun Double.fmt(dp: Int) = "%.${dp}f".format(this)
}
