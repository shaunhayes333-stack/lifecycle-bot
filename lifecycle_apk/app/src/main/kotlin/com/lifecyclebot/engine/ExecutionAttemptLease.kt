package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.3895 — deterministic per-mint/per-side execution lease + retry backoff.
 *
 * This is deliberately provider/route agnostic and Helius-free. It stops one bad
 * mint from generating hundreds of live EXEC attempts while preserving sell
 * recoverability. Terminal outcomes clear the active lease; failures schedule a
 * side-specific backoff that can only be bypassed by a changed provider/route
 * processor key or expiry.
 */
object ExecutionAttemptLease {
    data class Verdict(
        val allowed: Boolean,
        val key: String,
        val reason: String = "",
        val backoffMs: Long = 0L,
    )

    private data class State(
        val activeUntilMs: Long,
        val backoffUntilMs: Long,
        val failures: Int,
        val lastProcessor: String,
        val lastReason: String,
        val generation: Long,
        val mode: String,
        val side: String,
        val mint: String,
        val symbol: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private val states = ConcurrentHashMap<String, State>()

    private fun now() = System.currentTimeMillis()
    private fun key(generation: Long, mode: String, side: String, mint: String, processor: String): String =
        "${generation}:${mode.uppercase()}:${side.uppercase()}:${mint}:${processor.uppercase()}"

    private fun baseKey(generation: Long, mode: String, side: String, mint: String): String =
        "${generation}:${mode.uppercase()}:${side.uppercase()}:${mint}"

    private fun isRetryableTerminal(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("NETWORK") || r.contains("TIMEOUT") || r.contains("HTTP_503") ||
            r.contains("HTTP_429") || r.contains("HTTP_5") || r.contains("PROVIDER_RETRY") ||
            r.contains("RPC") || r.contains("BLOCKHASH") || r.contains("RATE_LIMIT")
    }

    fun pruneExpired(nowMs: Long = now()): Int {
        var pruned = 0
        states.entries.removeIf { e ->
            val st = e.value
            val expiry = maxOf(st.activeUntilMs, st.backoffUntilMs)
            val expired = expiry <= nowMs
            if (expired) {
                pruned++
                emit(
                    "EXEC_LEASE_PRUNED_EXPIRED",
                    st.side,
                    st.mint,
                    st.symbol,
                    "lane=${st.lastProcessor} ttlMs=${expiry - nowMs} ageMs=${nowMs - st.createdAtMs}"
                )
            }
            expired
        }
        return pruned
    }

    fun visibleNegativeTtlCount(): Int {
        val n = now()
        pruneExpired(n)
        return states.values.count { maxOf(it.activeUntilMs, it.backoffUntilMs) - n < 0L }
    }

    private fun backoffFor(failures: Int, side: String): Long {
        if (side.equals("SELL", true)) return when {
            failures <= 1 -> 5_000L
            failures == 2 -> 15_000L
            failures == 3 -> 45_000L
            else -> 60_000L
        }
        return when {
            failures <= 1 -> 5_000L
            failures == 2 -> 15_000L
            failures == 3 -> 45_000L
            else -> 300_000L
        }
    }

    fun acquire(
        side: String,
        mint: String,
        symbol: String,
        processor: String,
        mode: String,
        generation: Long,
        leaseMs: Long = if (side.equals("SELL", true)) 90_000L else 30_000L,
    ): Verdict {
        val n = now()
        pruneExpired(n)
        val groupPrefix = baseKey(generation, mode, side, mint) + ":"
        val current = states.entries.firstOrNull { it.key.startsWith(groupPrefix) }?.let { it.key to it.value }
        if (current != null) {
            val (existingKey, st) = current
            if (st.activeUntilMs > n) {
                emit("EXEC_DUPLICATE_SUPPRESSED", side, mint, symbol, "processor=$processor active=${st.lastProcessor} ttlMs=${st.activeUntilMs - n}")
                return Verdict(false, existingKey, "ACTIVE_LEASE", st.activeUntilMs - n)
            }
            if (st.backoffUntilMs > n && st.lastProcessor.equals(processor, true)) {
                emit("EXEC_RETRY_BACKOFF_ACTIVE", side, mint, symbol, "processor=$processor failures=${st.failures} ttlMs=${st.backoffUntilMs - n} last=${st.lastReason}")
                return Verdict(false, existingKey, "BACKOFF_ACTIVE", st.backoffUntilMs - n)
            }
            if (st.activeUntilMs <= n) {
                emit("EXEC_LEASE_EXPIRED", side, mint, symbol, "processor=${st.lastProcessor} ageExpiredMs=${n - st.activeUntilMs}")
                states.remove(existingKey)
            }
        }
        val k = key(generation, mode, side, mint, processor)
        val previousFailures = current?.second?.failures ?: 0
        states[k] = State(
            activeUntilMs = n + leaseMs,
            backoffUntilMs = 0L,
            failures = previousFailures,
            lastProcessor = processor,
            lastReason = "ACTIVE",
            generation = generation,
            mode = mode,
            side = side,
            mint = mint,
            symbol = symbol,
            createdAtMs = n,
        )
        emit("EXEC_LEASE_SET", side, mint, symbol, "processor=$processor leaseMs=$leaseMs failures=$previousFailures")
        return Verdict(true, k)
    }

    fun terminalOk(key: String, side: String, mint: String, symbol: String, reason: String) {
        states.remove(key)
        emit("${side.uppercase()}_TERMINAL_OK", side, mint, symbol, reason)
        emit("EXEC_LEASE_CLEARED", side, mint, symbol, "terminal=OK reason=$reason")
    }

    fun releaseNonTerminal(key: String, side: String, mint: String, symbol: String, reason: String) {
        states.remove(key)
        emit("${side.uppercase()}_NON_TERMINAL_RELEASE", side, mint, symbol, "reason=$reason")
        emit("EXEC_LEASE_CLEARED", side, mint, symbol, "terminal=NON_TERMINAL reason=$reason")
    }

    fun terminalFail(key: String, side: String, mint: String, symbol: String, reason: String, processor: String) {
        val n = now()
        pruneExpired(n)
        val old = states[key]
        val failures = (old?.failures ?: 0) + 1
        if (!isRetryableTerminal(reason)) {
            states.remove(key)
            emit("${side.uppercase()}_TERMINAL_FAIL", side, mint, symbol, "reason=$reason processor=$processor failures=$failures backoffMs=0 deterministic=true")
            emit("EXEC_LEASE_CLEARED", side, mint, symbol, "terminal=FAIL_NO_BACKOFF reason=$reason")
            return
        }
        val backoff = backoffFor(failures, side)
        if (old != null) {
            states[key] = old.copy(
                activeUntilMs = 0L,
                backoffUntilMs = n + backoff,
                failures = failures,
                lastProcessor = processor,
                lastReason = reason,
                symbol = symbol,
            )
        }
        emit("${side.uppercase()}_TERMINAL_FAIL", side, mint, symbol, "reason=$reason processor=$processor failures=$failures backoffMs=$backoff")
        emit("EXEC_RETRY_BACKOFF_SET", side, mint, symbol, "reason=$reason processor=$processor failures=$failures backoffMs=$backoff")
    }

    fun activeBuyLeases(): Int { val n = now(); pruneExpired(n); return states.values.count { it.side.equals("BUY", true) && it.activeUntilMs > n } }
    fun activeSellLeases(): Int { val n = now(); pruneExpired(n); return states.values.count { it.side.equals("SELL", true) && it.activeUntilMs > n } }
    fun activeBackoffs(): Int { val n = now(); pruneExpired(n); return states.values.count { it.backoffUntilMs > n } }

    fun formatForReport(): String {
        val n = now()
        pruneExpired(n)
        val buy = activeBuyLeases()
        val sell = activeSellLeases()
        val backs = activeBackoffs()
        val top = states.values.sortedByDescending { maxOf(it.activeUntilMs, it.backoffUntilMs) }.take(5)
            .joinToString(" · ") { st -> "${st.side}:${st.mint.take(6)}:${st.lastProcessor}:f${st.failures}:ttl=${maxOf(st.activeUntilMs, st.backoffUntilMs) - n}ms" }
        return "buy=$buy sell=$sell backoff=$backs" + if (top.isNotBlank()) " | $top" else ""
    }

    private fun emit(tag: String, side: String, mint: String, symbol: String, detail: String) {
        try { PipelineHealthCollector.labelInc(tag) } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle(tag, "side=$side mint=${mint.take(10)} symbol=$symbol $detail") } catch (_: Throwable) {}
    }
}
