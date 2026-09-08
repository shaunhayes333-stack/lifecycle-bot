package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PENDING SELL QUEUE
 *
 * Holds sell orders that couldn't execute due to wallet disconnect or other
 * recoverable errors. When wallet reconnects, these sells should be retried.
 *
 * V5.0.6702 — EXIT LIVENESS AUTHORITY
 * An OPEN position may not disappear from retry authority because an arbitrary
 * age/retry counter expired. Retry metadata is diagnostic only. A pending sell
 * remains retryable until a terminal close authority proves the position closed.
 * This repairs the old contradiction where comments said "never fake-close,
 * keep retrying" while MAX_AGE/MAX_RETRIES silently dropped the sell anyway.
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

    // V5.0.6702 — retained only as telemetry thresholds. They MUST NOT evict an
    // open position from retry authority. The old implementation expired after
    // 24h or 50 retries and could leave a held token with no future sell owner.
    private const val LEGACY_AGE_ALERT_MS = 24 * 60 * 60_000L
    private const val LEGACY_RETRY_ALERT = 50

    // V5.9.1524 — ONLY true temporary network/RPC faults may enter the queue.
    // Bad-payload/build errors are rebuilt/failovered immediately instead.
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
        // A strategy exit label is an exit trigger, not a transport failure.
        // Treat it as retryable unless it is explicitly a malformed payload.
        return true || TEMPORARY_MARKERS.any { r.contains(it) }
    }

    /**
     * V5.0.6702 — mode-aware terminal proof.
     * PAPER retries must not be suppressed by LIVE close state for the same mint.
     * The previous queue unconditionally consulted LivePositionCloseAuthority,
     * which allowed cross-mode close metadata to erase a paper retry.
     */
    private fun terminalForRuntime6702(mint: String): Boolean {
        val paper = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        return if (paper) {
            try { PaperPositionCloseAuthority.stateOf("PAPER", mint) == PaperPositionCloseAuthority.State.CLOSED }
            catch (_: Throwable) { false }
        } else {
            try {
                com.lifecyclebot.engine.sell.LivePositionCloseAuthority.isTerminalOrClosing(mint) ||
                    PositionCloseLedger.isClosed(mint)
            } catch (_: Throwable) { false }
        }
    }

    /**
     * Add or refresh a sell order. V5.0.6702 preserves queuedAt/retryCount so a
     * repeated exit signal cannot reset retry telemetry back to zero forever.
     */
    fun add(mint: String, symbol: String, reason: String) {
        if (terminalForRuntime6702(mint)) {
            queue.remove(mint)
            try {
                ForensicLogger.lifecycle(
                    "PENDING_SELL_SUPPRESSED_TERMINAL_6702",
                    "mint=${mint.take(10)} symbol=$symbol reason=$reason",
                )
            } catch (_: Throwable) {}
            return
        }
        if (!isTemporary(reason)) {
            ErrorLogger.warn(
                TAG,
                "🚫 SELL_RETRY_BLOCKED_BAD_PAYLOAD: $symbol ($mint) reason='$reason' — not requeued (failover rebuilds instead)",
            )
            try {
                com.lifecyclebot.engine.sell.SellForensics.inc(
                    com.lifecyclebot.engine.sell.SellForensics.SELL_RETRY_BLOCKED_BAD_PAYLOAD,
                    "mint=${mint.take(10)} reason=${reason.take(60)}",
                )
            } catch (_: Throwable) {}
            return
        }

        queue.compute(mint) { _, existing ->
            if (existing == null) PendingSell(mint, symbol, reason)
            else existing.copy(
                symbol = symbol.ifBlank { existing.symbol },
                reason = reason.ifBlank { existing.reason },
                queuedAtMs = existing.queuedAtMs,
                retryCount = existing.retryCount,
            )
        }
        try {
            com.lifecyclebot.engine.sell.SellForensics.inc(
                com.lifecyclebot.engine.sell.SellForensics.SELL_RETRY_TEMPORARY_ONLY,
                "mint=${mint.take(10)} reason=${reason.take(60)}",
            )
        } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "📥 Pending sell owned: $symbol ($mint) | reason: $reason | queue=${queue.size}")
    }

    fun hasPending(): Boolean = queue.isNotEmpty()
    fun size(): Int = queue.size

    /**
     * Return pending sells for an active retry pass.
     *
     * V5.0.6702 — no age/retry eviction. Only terminal proof removes a mint.
     * Processing still removes the entry temporarily so the caller owns one
     * attempt; a failed attempt must call [requeue].
     */
    fun getAndClear(): List<PendingSell> {
        val pending = mutableListOf<PendingSell>()
        val terminal = mutableListOf<String>()

        queue.forEach { (mint, sell) ->
            if (terminalForRuntime6702(mint)) {
                terminal.add(mint)
                try {
                    ForensicLogger.lifecycle(
                        "PENDING_SELL_PURGED_TERMINAL_6702",
                        "mint=${mint.take(10)} symbol=${sell.symbol} retries=${sell.retryCount}",
                    )
                } catch (_: Throwable) {}
            } else {
                val next = sell.copy(retryCount = sell.retryCount + 1)
                pending.add(next)
                if (sell.ageMs >= LEGACY_AGE_ALERT_MS || next.retryCount >= LEGACY_RETRY_ALERT) {
                    try {
                        PipelineHealthCollector.labelInc("PENDING_SELL_PERSISTED_BEYOND_LEGACY_LIMIT_6702")
                        ForensicLogger.lifecycle(
                            "PENDING_SELL_PERSISTED_BEYOND_LEGACY_LIMIT_6702",
                            "mint=${mint.take(10)} symbol=${sell.symbol} ageMin=${sell.ageMins.toInt()} retries=${next.retryCount} action=KEEP_UNTIL_TERMINAL",
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        terminal.forEach { queue.remove(it) }
        pending.forEach { queue.remove(it.mint) }

        if (pending.isNotEmpty()) {
            ErrorLogger.info(TAG, "📤 Processing ${pending.size} pending sells")
        }
        return pending
    }

    /**
     * Requeue a failed attempt. Retry count is already incremented by
     * [getAndClear], so do not increment a second time here.
     */
    fun requeue(sell: PendingSell) {
        if (terminalForRuntime6702(sell.mint)) {
            queue.remove(sell.mint)
            try {
                ForensicLogger.lifecycle(
                    "PENDING_SELL_REQUEUE_SUPPRESSED_TERMINAL_6702",
                    "mint=${sell.mint.take(10)} symbol=${sell.symbol} retry=${sell.retryCount}",
                )
            } catch (_: Throwable) {}
            return
        }
        queue[sell.mint] = sell
        ErrorLogger.info(TAG, "🔄 Requeued ${sell.symbol} (attempt ${sell.retryCount})")
    }

    fun remove(mint: String) {
        val removed = queue.remove(mint)
        if (removed != null) {
            ErrorLogger.info(TAG, "✅ Removed ${removed.symbol} from pending queue")
        }
    }

    fun clear() {
        val count = queue.size
        queue.clear()
        ErrorLogger.info(TAG, "🗑️ Cleared $count pending sells")
    }

    fun getSummary(): String {
        if (queue.isEmpty()) return "No pending sells"
        return queue.values.joinToString(", ") { "${it.symbol}(${it.ageMins.toInt()}m,r${it.retryCount})" }
    }
}
