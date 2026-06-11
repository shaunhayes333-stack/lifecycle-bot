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
