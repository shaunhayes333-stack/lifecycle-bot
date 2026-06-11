package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PENDING SELL QUEUE
 * 
 * Holds sell orders that couldn't execute due to wallet disconnect or other
 * recoverable errors. When wallet reconnects, these sells should be retried.
 * 
 * Usage:
 *   - Add: PendingSellQueue.add(mint, symbol, reason)
 *   - Check: PendingSellQueue.hasPending()
 *   - Process: PendingSellQueue.getAndClear() → returns list of pending sells
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PendingSellQueue {
    
    private const val TAG = "PendingSellQueue"
    
    data class PendingSell(
        val mint: String,
        val symbol: String,
        val reason: String,
        val queuedAtMs: Long = System.currentTimeMillis(),
        var retryCount: Int = 0,
    ) {
        val ageMs: Long get() = System.currentTimeMillis() - queuedAtMs
        val ageMins: Double get() = ageMs / 60_000.0
    }
    
    private val queue = ConcurrentHashMap<String, PendingSell>()
    
    // V5.9.321: Max age extended 30min → 24h.
    // A position that can't sell for 30 minutes (RPC outage, illiquid) was being
    // silently DROPPED. The tokens stayed in the wallet but the bot forgot about
    // them — phantom position, no sell ever attempted again. 24h aligns with
    // FeeRetryQueue and gives enough runway for any RPC/Jupiter outage to recover.
    private const val MAX_AGE_MS = 24 * 60 * 60_000L  // 24 hours

    // V5.9.321: MAX_RETRIES 5 → 50 — matches BotService's "never fake-close, keep retrying"
    // stance from V5.9.291. The old 5-retry limit meant a position that hit a Jupiter
    // outage for 25 seconds would exhaust all retries and be PERMANENTLY dropped with
    // no sell ever executed. retryCount just drives the BotService alert escalation.
    private const val MAX_RETRIES = 50
    
    /**
     * Add a sell order to the pending queue.
     * Replaces existing entry for same mint.
     */
    // V5.9.1524 — operator spec items 3 & 5: ONLY true temporary network/RPC
    // faults may enter the queue. Bad-payload / build errors (HTTP 400, invalid
    // amount, missing decimals, "100%" misuse) must NEVER requeue — they are
    // resolved by immediate venue rebuild/failover, not by waiting.
    private val TEMPORARY_MARKERS = listOf(
        "rpc", "timeout", "timed out", "network", "blockhash", "block height",
        "confirmation", "wallet not connected", "wallet disconnect", "unreachable",
        "connection", "socket", " etimedout", "503", "502", "429", "too many requests",
    )
    private val BAD_PAYLOAD_MARKERS = listOf(
        "http 400", "400:", "bad request", "invalid amount", "missing decimal",
        "100%", "payload", "malformed", "insufficient", "unsupported",
    )

    fun isTemporary(reason: String): Boolean {
        val r = reason.lowercase()
        if (BAD_PAYLOAD_MARKERS.any { r.contains(it) }) return false
        // Default: a SELL reason label (e.g. "STRICT_SL_-10", "RUG_DRAIN") is the
        // EXIT trigger, not a failure cause — those are legitimately retryable
        // (the sell genuinely needs to keep trying). Only explicit bad-payload
        // markers are rejected.
        return true || TEMPORARY_MARKERS.any { r.contains(it) }
    }

    fun add(mint: String, symbol: String, reason: String) {
        // Reject malformed-payload reasons outright (spec item 5).
        if (!isTemporary(reason)) {
            ErrorLogger.warn(TAG,
                "🚫 SELL_RETRY_BLOCKED_BAD_PAYLOAD: $symbol ($mint) reason='$reason' — not requeued (failover rebuilds instead)")
            try {
                com.lifecyclebot.engine.sell.SellForensics.inc(
                    com.lifecyclebot.engine.sell.SellForensics.SELL_RETRY_BLOCKED_BAD_PAYLOAD,
                    "mint=${mint.take(10)} reason=${reason.take(60)}")
            } catch (_: Throwable) {}
            return
        }
        queue[mint] = PendingSell(mint, symbol, reason)
        try {
            com.lifecyclebot.engine.sell.SellForensics.inc(
                com.lifecyclebot.engine.sell.SellForensics.SELL_RETRY_TEMPORARY_ONLY,
                "mint=${mint.take(10)} reason=${reason.take(60)}")
        } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "📥 Queued pending sell: $symbol ($mint) | reason: $reason")
        ErrorLogger.info(TAG, "📊 Queue size: ${queue.size}")
    }
    
    /**
     * Check if there are any pending sells.
     */
    fun hasPending(): Boolean = queue.isNotEmpty()
    
    /**
     * Get count of pending sells.
     */
    fun size(): Int = queue.size
    
    /**
     * Get all pending sells and clear the queue.
     * Filters out expired entries (older than MAX_AGE_MS).
     * Returns list of sells that should be retried.
     */
    fun getAndClear(): List<PendingSell> {
        val now = System.currentTimeMillis()
        val pending = mutableListOf<PendingSell>()
        val expired = mutableListOf<String>()
        
        queue.forEach { (mint, sell) ->
            when {
                sell.ageMs > MAX_AGE_MS -> {
                    ErrorLogger.warn(TAG, "⏰ Expired: ${sell.symbol} (aged ${sell.ageMins.toInt()}min)")
                    expired.add(mint)
                }
                sell.retryCount >= MAX_RETRIES -> {
                    ErrorLogger.warn(TAG, "🔄 Max retries: ${sell.symbol} (${sell.retryCount} attempts)")
                    expired.add(mint)
                }
                else -> {
                    pending.add(sell.copy(retryCount = sell.retryCount + 1))
                }
            }
        }
        
        // Clear processed entries
        expired.forEach { queue.remove(it) }
        pending.forEach { queue.remove(it.mint) }
        
        if (pending.isNotEmpty()) {
            ErrorLogger.info(TAG, "📤 Processing ${pending.size} pending sells")
        }
        
        return pending
    }
    
    /**
     * Re-queue a sell that failed again.
     * Increments retry counter.
     */
    fun requeue(sell: PendingSell) {
        if (sell.retryCount >= MAX_RETRIES) {
            ErrorLogger.warn(TAG, "🛑 Not requeuing ${sell.symbol} - max retries reached")
            return
        }
        queue[sell.mint] = sell.copy(retryCount = sell.retryCount + 1)
        ErrorLogger.info(TAG, "🔄 Requeued ${sell.symbol} (attempt ${sell.retryCount + 1})")
    }
    
    /**
     * Remove a specific mint from queue (e.g., if successfully sold manually).
     */
    fun remove(mint: String) {
        val removed = queue.remove(mint)
        if (removed != null) {
            ErrorLogger.info(TAG, "✅ Removed ${removed.symbol} from pending queue")
        }
    }
    
    /**
     * Clear entire queue (e.g., on manual reset).
     */
    fun clear() {
        val count = queue.size
        queue.clear()
        ErrorLogger.info(TAG, "🗑️ Cleared $count pending sells")
    }
    
    /**
     * Get summary for display.
     */
    fun getSummary(): String {
        if (queue.isEmpty()) return "No pending sells"
        return queue.values.joinToString(", ") { "${it.symbol}(${it.ageMins.toInt()}m)" }
    }
}
