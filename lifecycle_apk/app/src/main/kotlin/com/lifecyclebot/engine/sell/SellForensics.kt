package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1524 — Canonical sell-path forensic counters (operator spec item 7).
 * Pure counters, no behaviour. Behaviour lives in PumpFunDirectApi (amount/
 * decimal/slippage validation), SellRouteErrorClassifier (400 vs temporary)
 * and PendingSellQueue (admission control).
 */
object SellForensics {
    private const val TAG = "SellForensics"

    const val SELL_PAYLOAD_VALIDATED          = "SELL_PAYLOAD_VALIDATED"
    const val SELL_PAYLOAD_INVALID            = "SELL_PAYLOAD_INVALID"
    const val SELL_AMOUNT_CANONICAL_RAW       = "SELL_AMOUNT_CANONICAL_RAW"
    const val SELL_AMOUNT_CANONICAL_UI        = "SELL_AMOUNT_CANONICAL_UI"
    const val SELL_ROUTE_DIRECT_REJECTED_400  = "SELL_ROUTE_DIRECT_REJECTED_400"
    const val SELL_ROUTE_REBUILT_AFTER_400    = "SELL_ROUTE_REBUILT_AFTER_400"
    const val SELL_FAILOVER_IMMEDIATE         = "SELL_FAILOVER_IMMEDIATE"
    const val SELL_RETRY_TEMPORARY_ONLY       = "SELL_RETRY_TEMPORARY_ONLY"
    const val SELL_RETRY_BLOCKED_BAD_PAYLOAD  = "SELL_RETRY_BLOCKED_BAD_PAYLOAD"

    // V5.0.3746 — BALANCE_UNKNOWN requeue-loop fix (operator spec items 1-10).
    // BALANCE_UNKNOWN is a proof wait, NOT an active sell. These counters keep
    // proof-wait visibility OUT of SELL_RETRY_TEMPORARY_ONLY/SELL_DUPLICATE_SUPPRESSED
    // so the doctor can detect a leaked lease independently from a real route retry.
    const val SELL_WAITING_BALANCE_PROOF      = "SELL_WAITING_BALANCE_PROOF"
    const val BALANCE_PROOF_POLL_SCHEDULED    = "BALANCE_PROOF_POLL_SCHEDULED"
    const val BALANCE_PROOF_STILL_UNKNOWN     = "BALANCE_PROOF_STILL_UNKNOWN"
    const val BALANCE_PROOF_READY             = "BALANCE_PROOF_READY"
    const val ZERO_BALANCE_CONFIRMED          = "ZERO_BALANCE_CONFIRMED"
    const val BALANCE_WAIT_MERGE              = "BALANCE_WAIT_MERGE"
    // Live-sell lifecycle counters (operator spec item 8): truthful execution
    // status that does not conflate proof-waits with route failures.
    const val EXEC_LIVE_SELL_WAITING_BALANCE_PROOF       = "EXEC_LIVE_SELL_WAITING_BALANCE_PROOF"
    const val EXEC_LIVE_SELL_ROUTE_STARTED               = "EXEC_LIVE_SELL_ROUTE_STARTED"
    const val EXEC_LIVE_SELL_ROUTE_FAILED_NO_SIGNATURE   = "EXEC_LIVE_SELL_ROUTE_FAILED_NO_SIGNATURE"
    const val EXEC_LIVE_SELL_BROADCAST_SIGNATURE         = "EXEC_LIVE_SELL_BROADCAST_SIGNATURE"
    const val EXEC_LIVE_SELL_FINALIZED                   = "EXEC_LIVE_SELL_FINALIZED"
    const val EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED      = "EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED"
    const val EXEC_LIVE_SELL_FAILED_TERMINAL             = "EXEC_LIVE_SELL_FAILED_TERMINAL"

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun inc(counter: String, detail: String = "") {
        counters.computeIfAbsent(counter) { AtomicLong(0) }.incrementAndGet()
        try { ForensicLogger.lifecycle(counter, detail) } catch (_: Throwable) {}
        if (detail.isNotBlank()) ErrorLogger.info(TAG, "$counter | $detail")
    }

    fun get(counter: String): Long = counters[counter]?.get() ?: 0L
    fun snapshot(): Map<String, Long> = counters.mapValues { it.value.get() }
    fun resetForTest() = counters.clear()
}
