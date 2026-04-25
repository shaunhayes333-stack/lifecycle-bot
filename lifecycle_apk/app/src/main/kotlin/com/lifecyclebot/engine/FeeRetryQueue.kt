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
    private const val MAX_RETRIES  = 5
    private const val MAX_AGE_MS   = 24 * 60 * 60 * 1000L  // 24 hours — drop stale fees

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

        for (i in 0 until arr.length()) {
            val json = arr.getJSONObject(i)
            val entry = jsonToEntry(json)

            // Drop stale entries (>24h)
            if (now - entry.createdMs > MAX_AGE_MS) {
                ErrorLogger.warn("FeeRetryQueue", "🗑 Dropping stale fee (>24h): ${entry.amountSol.fmt(5)} SOL → ${entry.toAddress}")
                continue
            }

            // Drop entries that have exceeded retry limit
            if (entry.retryCount >= MAX_RETRIES) {
                ErrorLogger.error("FeeRetryQueue", "🗑 Dropping fee (${MAX_RETRIES} retries exhausted): ${entry.amountSol.fmt(5)} SOL → ${entry.toAddress}")
                continue
            }

            // Attempt retry
            try {
                wallet.sendSol(entry.toAddress, entry.amountSol)
                ErrorLogger.info("FeeRetryQueue", "✅ Retry success: ${entry.amountSol.fmt(5)} SOL → ${entry.toAddress} (attempt ${entry.retryCount + 1})")
                // Fee sent — don't re-add to remaining
            } catch (e: Exception) {
                ErrorLogger.warn("FeeRetryQueue", "⚠ Retry failed (${entry.retryCount + 1}/${MAX_RETRIES}): ${e.message?.take(60)}")
                remaining.put(entryToJson(entry.copy(retryCount = entry.retryCount + 1)))
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
