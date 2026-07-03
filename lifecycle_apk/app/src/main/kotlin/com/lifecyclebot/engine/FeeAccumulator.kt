package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.network.SolanaWallet
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

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
 * FLUSH_THRESHOLD = 1.0 SOL total across all destination buckets. Once the
 * onboard ledger reaches 1 SOL, every destination bucket is flushed/distributed.
 * The accumulator is consulted on every fee retry queue drain (already
 * runs once per scan cycle in BotService), so live fees flush automatically
 * with no extra threading.
 */
object FeeAccumulator {

    private const val PREFS_NAME = "fee_accumulator"
    private const val KEY_BUCKETS = "pending_buckets"
    // V5.0.6058 — daily scheduled flush (operator: 9pm AEST daily).
    // Uses Australia/Sydney zone so DST is handled automatically
    // (AEST=UTC+10 winter, AEDT=UTC+11 summer). Stamps yyyyDDD of the
    // local calendar day we last flushed so we fire exactly once per
    // local day even if tryFlush() is called every scan cycle.
    private const val KEY_LAST_SCHEDULED_FLUSH_YYYYDDD = "last_scheduled_flush_yyyyddd"
    private const val SCHEDULED_FLUSH_TZ_ID = "Australia/Sydney"
    private const val SCHEDULED_FLUSH_HOUR_LOCAL = 21   // 9pm local (AEST/AEDT)

    /** Default flush threshold for TOTAL pending fees across all destination wallets. */
    private const val DEFAULT_FLUSH_THRESHOLD_SOL = 1.0
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
        val totalPending = keys.sumOf { buckets.optDouble(it, 0.0).coerceAtLeast(0.0) }
        // V5.0.6058 — DAILY SCHEDULED FLUSH (operator directive: 9pm
        // AEST daily). Bypass the 1.0-SOL threshold if we're at/after
        // 9pm local Sydney time and we haven't already flushed today.
        // Fees accumulate up-to daily instead of trickling as tiny
        // per-trade sends but never linger longer than a day.
        val scheduledFire = scheduledFlushDue(p)
        if (totalPending < flushThresholdSol && !scheduledFire) return 0.0
        if (scheduledFire) {
            ErrorLogger.warn("FeeAccumulator",
                "⏰ SCHEDULED_DAILY_FLUSH firing — 9pm ${SCHEDULED_FLUSH_TZ_ID} rollover, " +
                "totalPending=${totalPending.fmt(5)} SOL (threshold=${flushThresholdSol} SOL bypassed)")
        }

        for (dest in keys) {
            val accrued = buckets.optDouble(dest, 0.0)
            if (accrued <= 0.0) continue

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
                    "✅ Flushed ${accrued.fmt(5)} SOL → $dest (totalPending=${totalPending.fmt(5)} SOL threshold=${flushThresholdSol} SOL)")
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
        // V5.0.6058 — stamp today's local yyyyDDD (Sydney) after a
        // scheduled fire so the same day cannot re-fire on next scan.
        if (scheduledFire) {
            try { p.edit().putInt(KEY_LAST_SCHEDULED_FLUSH_YYYYDDD, currentSydneyYyyyDdd()).apply() } catch (_: Throwable) {}
        }
        return totalSent
    }

    /**
     * V5.0.6058 — Returns true if we've crossed 9pm Sydney local time
     * TODAY and haven't yet flushed for today. yyyyDDD stamp prevents
     * double-firing when tryFlush() is called every scan cycle.
     */
    private fun scheduledFlushDue(p: SharedPreferences): Boolean {
        return try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone(SCHEDULED_FLUSH_TZ_ID))
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour < SCHEDULED_FLUSH_HOUR_LOCAL) return false
            val todayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            val lastKey = p.getInt(KEY_LAST_SCHEDULED_FLUSH_YYYYDDD, 0)
            todayKey != lastKey
        } catch (_: Throwable) { false }
    }

    private fun currentSydneyYyyyDdd(): Int {
        return try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone(SCHEDULED_FLUSH_TZ_ID))
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        } catch (_: Throwable) { 0 }
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
