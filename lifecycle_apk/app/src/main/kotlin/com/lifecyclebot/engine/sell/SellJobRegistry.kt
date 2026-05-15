package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.764 — EMERGENT CRITICAL items A + B + D + E (sell-job state machine).
 *
 * Operator forensics_20260515_151634.json showed:
 *   - HIM full sell started qty=122210.605807, retries dropped to qty=1331
 *   - CABAL full sell started qty=7330.54695, retries dropped to qty=88
 *   - last_sell_signature empty (no sell ever landed)
 *   - sell-lock spammed SELL_BLOCKED_ALREADY_IN_PROGRESS
 *   - reconciler.totalChecked = 0
 *
 * Per spec, a SellJob captures the FULL exit intent (requested qty,
 * authoritative wallet qty at start, mode, reason) and a proper status
 * lifecycle. The lock is conceptually a tuple (mint, status) — only the
 * BUILDING / BROADCASTING / CONFIRMING / VERIFYING states are "in flight".
 * IDLE / LANDED / FAILED_RETRYABLE / FAILED_FINAL are unlocked.
 *
 * Counter emissions hook into PipelineHealthCollector via the existing
 * ForensicLogger lifecycle / phase pipes — the new keys are pinned in
 * the collector so they always render in dumps.
 */

/** Per-job lifecycle status. */
enum class SellJobStatus {
    IDLE,
    BUILDING,
    BROADCASTING,
    CONFIRMING,
    VERIFYING,
    LANDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
}

/**
 * FULL_EXIT for emergency / hard-stop / rug-drain / manual sells. Quantity
 * for these is ALWAYS the fresh on-chain wallet balance (item B).
 * PARTIAL_EXIT for staged take-profit slices that intentionally sell only
 * a fraction of the position (PARTIAL_TAKE_PROFIT, PROFIT_LOCK).
 */
enum class SellJobMode { FULL_EXIT, PARTIAL_EXIT }

data class SellJob(
    val mint: String,
    val symbol: String,
    val reason: String,
    @Volatile var requestedQty: Long,
    @Volatile var authoritativeWalletQtyAtStart: Long,
    val mode: SellJobMode,
    val startedAtMs: Long,
    @Volatile var lastAttemptAtMs: Long,
    @Volatile var signature: String? = null,
    @Volatile var status: SellJobStatus = SellJobStatus.IDLE,
    @Volatile var attemptCount: Int = 0,
) {
    /** True only while the executor is mid-broadcast / mid-verify. */
    fun isInFlight(): Boolean = when (status) {
        SellJobStatus.BUILDING,
        SellJobStatus.BROADCASTING,
        SellJobStatus.CONFIRMING,
        SellJobStatus.VERIFYING -> true
        else -> false
    }
}

object SellJobRegistry {
    /** Hard TTL on an in-flight lock. Tuned to 60 s per EMERGENT spec.
     *  RPC verify rounds can take up to ~45 s in worst case (operator
     *  evidence: SELL_LOCK_STALE_FORCE_RELEASED ageMs 44989 / 48334). */
    const val LOCK_TTL_MS: Long = 60_000L

    private val jobs = ConcurrentHashMap<String, SellJob>()

    /** Create or refresh the job for a mint. If a job exists in a
     *  terminal status (LANDED / FAILED_FINAL) it is replaced. */
    fun getOrCreate(
        mint: String,
        symbol: String,
        reason: String,
        requestedQty: Long,
        walletQtyAtStart: Long,
        mode: SellJobMode,
    ): SellJob {
        val now = System.currentTimeMillis()
        val existing = jobs[mint]
        if (existing != null &&
            existing.status != SellJobStatus.LANDED &&
            existing.status != SellJobStatus.FAILED_FINAL) {
            existing.lastAttemptAtMs = now
            existing.attemptCount++
            // For emergency FULL_EXIT, ALWAYS refresh the wallet anchor and
            // bump requestedQty UPWARD (never downward — item E).
            if (mode == SellJobMode.FULL_EXIT) {
                if (walletQtyAtStart > existing.authoritativeWalletQtyAtStart) {
                    existing.authoritativeWalletQtyAtStart = walletQtyAtStart
                }
                if (walletQtyAtStart > existing.requestedQty) {
                    existing.requestedQty = walletQtyAtStart
                }
            }
            return existing
        }
        val job = SellJob(
            mint = mint,
            symbol = symbol,
            reason = reason,
            requestedQty = requestedQty,
            authoritativeWalletQtyAtStart = walletQtyAtStart,
            mode = mode,
            startedAtMs = now,
            lastAttemptAtMs = now,
        )
        jobs[mint] = job
        try {
            ForensicLogger.lifecycle(
                "SELL_JOB_CREATED",
                "mint=${mint.take(10)} symbol=$symbol reason=$reason mode=${mode.name} requestedQty=$requestedQty walletQty=$walletQtyAtStart",
            )
        } catch (_: Throwable) {}
        return job
    }

    fun get(mint: String): SellJob? = jobs[mint]

    fun transitionTo(mint: String, status: SellJobStatus): Boolean {
        val job = jobs[mint] ?: return false
        job.status = status
        try {
            val tag = when (status) {
                SellJobStatus.BUILDING       -> "SELL_TX_BUILT"
                SellJobStatus.BROADCASTING   -> "SELL_BROADCAST"
                SellJobStatus.CONFIRMING     -> "SELL_SIG_CONFIRMED"
                SellJobStatus.VERIFYING      -> "SELL_VERIFY_STARTED"
                SellJobStatus.LANDED         -> "SELL_CLOSED_TRACKER"
                SellJobStatus.FAILED_RETRYABLE -> "SELL_FAILED_RETRYABLE"
                SellJobStatus.FAILED_FINAL   -> "SELL_FAILED_FINAL"
                SellJobStatus.IDLE           -> "SELL_JOB_IDLE"
            }
            ForensicLogger.lifecycle(
                tag,
                "mint=${mint.take(10)} status=${status.name} attempts=${job.attemptCount}",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** Idempotent — used when the executor finishes a successful sell or
     *  when the reconciler finds an on-chain zero balance for the mint. */
    fun markLanded(mint: String, signature: String?) {
        val job = jobs[mint] ?: return
        job.signature = signature
        job.status = SellJobStatus.LANDED
        try {
            ForensicLogger.lifecycle(
                "SELL_CLOSED_TRACKER",
                "mint=${mint.take(10)} sig=${signature?.take(12) ?: "<none>"} attempts=${job.attemptCount}",
            )
        } catch (_: Throwable) {}
    }

    /** Returns true if the existing job is genuinely in-flight AND the lock
     *  has NOT yet timed out. False means caller is allowed to start a new
     *  attempt (either no job, or a job that should be force-released). */
    fun isLockedAndFresh(mint: String, now: Long = System.currentTimeMillis()): Boolean {
        val job = jobs[mint] ?: return false
        if (!job.isInFlight()) return false
        val age = now - job.lastAttemptAtMs
        if (age > LOCK_TTL_MS) {
            try {
                ErrorLogger.warn(
                    "SellJobRegistry",
                    "🔓 SELL_LOCK_STALE_FORCE_RELEASED mint=${mint.take(10)} ageMs=$age status=${job.status.name}",
                )
                ForensicLogger.lifecycle(
                    "SELL_LOCK_STALE_FORCE_RELEASED",
                    "mint=${mint.take(10)} ageMs=$age status=${job.status.name}",
                )
            } catch (_: Throwable) {}
            // Reset to FAILED_RETRYABLE so the next attempt re-engages.
            job.status = SellJobStatus.FAILED_RETRYABLE
            return false
        }
        return true
    }

    /** Removes terminal entries so the map doesn't grow unbounded. */
    fun pruneTerminal() {
        val it = jobs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.status == SellJobStatus.LANDED ||
                e.value.status == SellJobStatus.FAILED_FINAL) {
                if (System.currentTimeMillis() - e.value.lastAttemptAtMs > 5 * 60_000L) {
                    it.remove()
                }
            }
        }
    }

    /** Diagnostic snapshots used by the reconciler. */
    fun snapshot(): List<SellJob> = jobs.values.toList()
    fun activeCount(): Int = jobs.values.count { it.isInFlight() }
}

/**
 * EMERGENT item E — the hard safety assertion that prevents data loss.
 *
 * Operator forensics: HIM emergency retries used qty=1331 against a
 * wallet balance of 122210 (1%). CABAL retries used qty=88 against 7330
 * (1.2%). Both are catastrophic for a FULL_EXIT — they leave 98%+ of
 * the bag in the rug. This assertion blocks any emergency broadcast
 * whose qtyToSell is below 90% of the authoritative wallet balance and
 * forces a rebuild with the full wallet balance instead.
 *
 * Threshold of 0.90 chosen to accommodate dust (e.g., transfer-fee tokens
 * that leave 1% behind on every move) while still catching the 1-2%
 * corruption signature.
 */
object SellQtyGuard {
    /** Reasons that require a full-balance drain. Anything matching one of
     *  these substrings is treated as a FULL_EXIT — partial sells use a
     *  different code path entirely (Executor.partialSell). */
    private val FULL_EXIT_KEYWORDS = listOf(
        "RUG", "CATASTROPHE", "HARD_FLOOR", "HARD_STOP", "STARTUP_SWEEP",
        "FALLBACK_ORPHAN", "STRICT_SL", "TREASURY_STOP_LOSS",
        "STALE_LIVE_PRICE_RUG_ESCAPE", "MANUAL", "FULL_EXIT", "EMERGENCY",
        "STOP_LOSS", "DRAIN", "LIQUIDITY_COLLAPSE",
    )

    fun isFullExitReason(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        return FULL_EXIT_KEYWORDS.any { r.contains(it) }
    }

    /** Returns the qty to actually broadcast. If [qtyToSell] is < 90% of
     *  [authoritativeWalletQty] AND the reason is a full-exit kind, the
     *  function logs SELL_RETRY_QTY_CHANGED_BLOCKED + SELL_RETRY_FULL_BALANCE
     *  and returns the authoritative balance. Otherwise returns [qtyToSell]. */
    fun guard(
        mint: String,
        symbol: String,
        reason: String,
        qtyToSell: Long,
        authoritativeWalletQty: Long,
    ): Long {
        if (!isFullExitReason(reason)) return qtyToSell
        if (authoritativeWalletQty <= 0) return qtyToSell
        val ratio = qtyToSell.toDouble() / authoritativeWalletQty.toDouble()
        if (ratio >= 0.90) return qtyToSell
        try {
            ErrorLogger.warn(
                "SellQtyGuard",
                "🛡 SELL_RETRY_QTY_CHANGED_BLOCKED mint=${mint.take(10)} symbol=$symbol reason=$reason " +
                "qtyToSell=$qtyToSell walletQty=$authoritativeWalletQty ratio=${"%.4f".format(ratio)} — rebuilding with full wallet balance"
            )
            ForensicLogger.lifecycle(
                "SELL_RETRY_QTY_CHANGED_BLOCKED",
                "mint=${mint.take(10)} symbol=$symbol reason=$reason qtyToSell=$qtyToSell walletQty=$authoritativeWalletQty ratioPct=${"%.2f".format(ratio * 100)}",
            )
            ForensicLogger.lifecycle(
                "SELL_RETRY_FULL_BALANCE",
                "mint=${mint.take(10)} symbol=$symbol newQty=$authoritativeWalletQty prevQty=$qtyToSell",
            )
        } catch (_: Throwable) {}
        return authoritativeWalletQty
    }
}
