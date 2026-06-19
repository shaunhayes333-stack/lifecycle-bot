package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.network.SolanaWallet
import org.json.JSONObject

/**
 * V5.0.3920 — FEE ACCUMULATOR (operator request: "on the micro fee sizes
 * store them on board the app and send when they accumulated maybe?
 * like accumulated a sol then send?")
 *
 * Solana network base fee + priority fee + rent-exemption checks make
 * sub-$0.10 fee transfers economically broken: a 0.000005 SOL fee share
 * would cost ~0.000005 SOL just to send. The lowered FEE_SEND_MIN_SOL
 * in 3919 made the fees ATTEMPT to send, but most are still rejected by
 * the network for being below rent-exemption minimums, lost to base
 * priority fees, or simply uneconomic.
 *
 * Solution: instead of sending tiny per-trade fees, accumulate them in
 * a per-destination on-device ledger (SharedPreferences JSON), and flush
 * the WHOLE bucket as a single transfer once it crosses FLUSH_THRESHOLD.
 * One transfer per ~hundreds of trades, base fee is then <0.05% of the
 * batched amount, and no fees are ever silently lost.
 *
 * FLUSH_THRESHOLD = 0.01 SOL (~$1.50 — sensible default, can be tuned).
 * The accumulator is consulted on every fee retry queue drain (already
 * runs once per scan cycle in BotService), so live fees flush automatically
 * with no extra threading.
 */
object FeeAccumulator {

    private const val PREFS_NAME = "fee_accumulator"
    private const val KEY_BUCKETS = "pending_buckets"

    /** Default flush threshold per destination wallet. Tune via setFlushThresholdSol(). */
    private const val DEFAULT_FLUSH_THRESHOLD_SOL = 0.01
    /** Wallet must keep this much SOL after a flush (rent + gas headroom). */
    private const val MIN_WALLET_RESERVE_SOL = 0.005

    @Volatile private var flushThresholdSol: Double = DEFAULT_FLUSH_THRESHOLD_SOL
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setFlushThresholdSol(sol: Double) {
        if (sol > 0.0) flushThresholdSol = sol
    }

    fun getFlushThresholdSol(): Double = flushThresholdSol

    /**
     * Record a pending fee share. The amount is added to the bucket keyed by
     * destination address. Returns the new accrued balance for diagnostics.
     * Self-loops (destination == sender) are silently dropped — caller is
     * responsible for redirecting before calling accrue().
     */
    fun accrue(toAddress: String, amountSol: Double, tag: String): Double {
        if (amountSol <= 0.0) return 0.0
        val p = prefs ?: run {
            ErrorLogger.error("FeeAccumulator", "⚠ Not initialized — fee lost: ${amountSol.fmt(6)} SOL → $toAddress ($tag)")
            return 0.0
        }
        val buckets = loadBuckets(p)
        val prior = buckets.optDouble(toAddress, 0.0)
        val updated = prior + amountSol
        buckets.put(toAddress, updated)
        p.edit().putString(KEY_BUCKETS, buckets.toString()).apply()
        ErrorLogger.debug("FeeAccumulator",
            "🪙 accrue ${amountSol.fmt(6)} SOL → $toAddress ($tag) | bucket=${updated.fmt(5)} SOL")
        return updated
    }

    /**
     * Attempt to flush every bucket that has crossed the threshold. Wallet
     * balance is checked once; flushes that would drop balance below
     * MIN_WALLET_RESERVE_SOL are deferred to the next cycle. Returns the
     * total SOL successfully transferred this drain.
     */
    fun tryFlush(wallet: SolanaWallet): Double {
        val p = prefs ?: return 0.0
        val buckets = loadBuckets(p)
        if (buckets.length() == 0) return 0.0

        val selfPk = try { wallet.publicKeyB58 } catch (_: Throwable) { "" }
        var balance = try { wallet.getSolBalance() } catch (_: Throwable) { 0.0 }
        var totalSent = 0.0
        var changed = false

        val keys = buckets.keys().asSequence().toList()
        for (dest in keys) {
            val accrued = buckets.optDouble(dest, 0.0)
            if (accrued < flushThresholdSol) continue

            // Self-loop guard — accrue() should already prevent this, but
            // double-check in case the configured fee wallet was rotated.
            if (dest.equals(selfPk, ignoreCase = false)) {
                ErrorLogger.warn("FeeAccumulator",
                    "⛔ Flush skipped: bucket destination $dest equals wallet self. ${accrued.fmt(5)} SOL stranded — fix fee wallet config.")
                continue
            }
            if (balance < accrued + MIN_WALLET_RESERVE_SOL) {
                ErrorLogger.warn("FeeAccumulator",
                    "⏸ Flush deferred: wallet=${balance.fmt(5)} SOL < accrued=${accrued.fmt(5)} SOL + reserve=${MIN_WALLET_RESERVE_SOL}. Retry next cycle.")
                continue
            }
            try {
                wallet.sendSol(dest, accrued)
                ErrorLogger.warn("FeeAccumulator",
                    "✅ Flushed ${accrued.fmt(5)} SOL → $dest (threshold=${flushThresholdSol} SOL)")
                buckets.remove(dest)
                balance -= accrued
                totalSent += accrued
                changed = true
            } catch (e: Exception) {
                // Send failed — leave bucket intact, fall back to FeeRetryQueue
                // so the next drain cycle retries with backoff.
                ErrorLogger.warn("FeeAccumulator",
                    "❌ Flush failed ${accrued.fmt(5)} SOL → $dest: ${e.message} — handing off to FeeRetryQueue")
                try { FeeRetryQueue.enqueue(dest, accrued, "accumulator_flush_retry") } catch (_: Throwable) {}
                buckets.remove(dest)
                changed = true
            }
        }
        if (changed) p.edit().putString(KEY_BUCKETS, buckets.toString()).apply()
        return totalSent
    }

    /** Operator diagnostics. Returns "dest=balance|dest=balance" snapshot. */
    fun snapshot(): String {
        val p = prefs ?: return "uninitialized"
        val buckets = loadBuckets(p)
        if (buckets.length() == 0) return "empty"
        val sb = StringBuilder()
        val keys = buckets.keys().asSequence().toList()
        for ((i, k) in keys.withIndex()) {
            if (i > 0) sb.append("|")
            sb.append("${k.take(6)}…=${buckets.optDouble(k, 0.0).fmt(5)}")
        }
        return sb.toString()
    }

    /** Returns the total SOL currently held in all buckets. */
    fun totalPendingSol(): Double {
        val p = prefs ?: return 0.0
        val buckets = loadBuckets(p)
        var total = 0.0
        val keys = buckets.keys().asSequence().toList()
        for (k in keys) total += buckets.optDouble(k, 0.0)
        return total
    }

    private fun loadBuckets(p: SharedPreferences): JSONObject {
        val raw = p.getString(KEY_BUCKETS, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
    }

    private fun Double.fmt(dp: Int) = "%.${dp}f".format(this)
}
