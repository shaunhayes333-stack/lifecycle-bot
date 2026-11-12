package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
        val failedAtMs: Long = 0L,
    )

    /**
     * A BUY's failure count outlives its backoff for this long, so the backoff escalates per mint (5 s, 15 s, 45 s, 5 min).
     * The count was pruned the moment each backoff ended and deleted on every non-network failure (slippage 6001, no route),
     * so every retry of a mint started again at failure 1: the ladder never reached its cap.
     */
    private const val BUY_FAILURE_MEMORY_MS = 10 * 60_000L
    /** From this many failures the mint's backoff binds every processor (a changed route no longer resets the cap). */
    private const val BUY_CAP_FAILURES = 4

    private fun remembered(st: State, nowMs: Long): Boolean =
        st.side.equals("BUY", true) && st.failures > 0 && nowMs - st.failedAtMs < BUY_FAILURE_MEMORY_MS

    private val states = ConcurrentHashMap<String, State>()
    /** A lease is an individual attempt, not a reusable processor identity. */
    private val leaseSequence = AtomicLong(0L)

    private fun now() = System.currentTimeMillis()
    private fun key(generation: Long, mode: String, side: String, mint: String, processor: String): String =
        "${generation}:${mode.uppercase()}:${side.uppercase()}:${mint}:${processor.uppercase()}:${leaseSequence.incrementAndGet()}"

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
            val expired = expiry <= nowMs && !remembered(st, nowMs)
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
        return states.values.count { maxOf(it.activeUntilMs, it.backoffUntilMs) - n < 0L && !remembered(it, n) }
    }

    /**
     * V5.0.6315 — SUPERVISOR ATOMIC FORCE-RELEASE (§15).
     *
     * Force-drop every lease/backoff for the given mint (both BUY and SELL,
     * all processors, all generations). Called by supervisorForceReleaseLeaseAndCancel
     * so a wedged worker cancel is atomically mirrored by the exec-lease
     * map. Prevents the classic supervisor-timeout leak where the coroutine
     * was cancelled but the mint's exec lease continued blocking retries
     * for 5 minutes.
     */
    fun forceReleaseForMint(mint: String, reason: String): Int {
        if (mint.isBlank()) return 0
        var dropped = 0
        states.entries.removeIf { e ->
            if (e.value.mint == mint) {
                dropped++
                try {
                    emit(
                        "SUPERVISOR_FORCE_RELEASE_ATOMIC_OK",
                        e.value.side,
                        e.value.mint,
                        e.value.symbol,
                        "reason=$reason processor=${e.value.lastProcessor}",
                    )
                } catch (_: Throwable) {}
                true
            } else false
        }
        if (dropped == 0) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SUPERVISOR_FORCE_RELEASE_NO_LEASE_6315")
            } catch (_: Throwable) {}
        } else {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SUPERVISOR_FORCE_RELEASE_ATOMIC_OK")
            } catch (_: Throwable) {}
        }
        return dropped
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

    @Synchronized
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
            if (st.backoffUntilMs > n && (st.lastProcessor.equals(processor, true) || st.failures >= BUY_CAP_FAILURES)) {
                emit("EXEC_RETRY_BACKOFF_ACTIVE", side, mint, symbol, "processor=$processor failures=${st.failures} ttlMs=${st.backoffUntilMs - n} last=${st.lastReason}")
                if (side.equals("BUY", true)) count8702(buyBackoffHits8702, st.lastReason)
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
        if (side.equals("BUY", ignoreCase = true)) try { PendingLiveBuy8094.note8094(mint) } catch (_: Throwable) {}  // V5.0.8094
        emit("${side.uppercase()}_NON_TERMINAL_RELEASE", side, mint, symbol, "reason=$reason")
        emit("EXEC_LEASE_CLEARED", side, mint, symbol, "terminal=NON_TERMINAL reason=$reason")
    }

    fun terminalFail(key: String, side: String, mint: String, symbol: String, reason: String, processor: String) {
        val n = now()
        pruneExpired(n)
        val old = states[key]
        val failures = (old?.failures ?: 0) + 1
        if (!isRetryableTerminal(reason) && !side.equals("BUY", true)) {
            states.remove(key)
            emit("${side.uppercase()}_TERMINAL_FAIL", side, mint, symbol, "reason=$reason processor=$processor failures=$failures backoffMs=0 deterministic=true")
            emit("EXEC_LEASE_CLEARED", side, mint, symbol, "terminal=FAIL_NO_BACKOFF reason=$reason")
            return
        }
        if (side.equals("BUY", true)) { count8702(buyFails8702, reason); noteBuyTerminal8726(n, mint, symbol, processor, reason) }
        val backoff = backoffFor(failures, side)
        if (old != null) {
            states[key] = old.copy(
                activeUntilMs = 0L,
                backoffUntilMs = n + backoff,
                failures = failures,
                lastProcessor = processor,
                lastReason = reason,
                symbol = symbol,
                failedAtMs = n,
            )
        }
        emit("${side.uppercase()}_TERMINAL_FAIL", side, mint, symbol, "reason=$reason processor=$processor failures=$failures backoffMs=$backoff")
        emit("EXEC_RETRY_BACKOFF_SET", side, mint, symbol, "reason=$reason processor=$processor failures=$failures backoffMs=$backoff")
    }

    fun isActiveKey6514(key: String): Boolean {
        val n = now(); pruneExpired(n)
        return key.isNotBlank() && states[key]?.activeUntilMs?.let { it > n } == true
    }

    fun activeBuyLeases(): Int { val n = now(); pruneExpired(n); return states.values.count { it.side.equals("BUY", true) && it.activeUntilMs > n } }
    fun activeSellLeases(): Int { val n = now(); pruneExpired(n); return states.values.count { it.side.equals("SELL", true) && it.activeUntilMs > n } }
    /**
     * V5.0.8717 — how long a LIVE buy of [mint] by [processor] would still be refused by the backoff a failed attempt set
     * (0 = none). The same test [acquire] applies, read without acquiring: admission asks it before a ticket is spent.
     */
    fun buyBackoffLeftMs8717(mint: String, processor: String, nowMs: Long = now()): Long =
        states.values.filter { it.side.equals("BUY", true) && it.mode.equals("LIVE", true) && it.mint == mint && it.backoffUntilMs > nowMs &&
            (it.lastProcessor.equals(processor, true) || it.failures >= BUY_CAP_FAILURES) }
            .maxOfOrNull { it.backoffUntilMs - nowMs } ?: 0L

    fun activeBackoffs(): Int { val n = now(); pruneExpired(n); return states.values.count { it.backoffUntilMs > n } }

    fun formatForReport(): String {
        val n = now()
        pruneExpired(n)
        val buy = activeBuyLeases()
        val sell = activeSellLeases()
        val backs = activeBackoffs()
        val top = states.values.sortedByDescending { maxOf(it.activeUntilMs, it.backoffUntilMs) }.take(5)
            .joinToString(" · ") { st -> "${st.side}:${st.mint.take(6)}:${st.lastProcessor}:f${st.failures}:ttl=${maxOf(st.activeUntilMs, st.backoffUntilMs) - n}ms" }
        return "buy=$buy sell=$sell backoff=$backs" + (if (top.isNotBlank()) " | $top" else "") +
            " buyFails8702{${line8702(buyFails8702)}} backoffEndedTicketAfter8702{${line8702(buyBackoffHits8702)}}"
    }

    // ── V5.0.8726 — each failed LIVE buy, with the facts it failed on ──
    // 8720: six tickets, six terminal failures, and the report held two reason names and two counts. Which coin, which
    // pool, what lock the provider reported, how far the quote had moved against which cap: none of it survived, so the
    // owner's "trace each of the six failures" could not be answered from the capture. The last [BUY_TERMINALS_KEPT_8726]
    // are kept with the coin's safety and pool facts at that moment and the chase check's own numbers.
    private const val BUY_TERMINALS_KEPT_8726 = 16
    private val buyTerminals8726 = java.util.ArrayDeque<String>()

    /** Pure: one kept line. */
    internal fun buyTerminalLine8726(atMs: Long, mint: String, symbol: String, processor: String, reason: String, facts: String): String =
        "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(atMs))} ${mint.take(10)} ${symbol.take(12)} $processor ${reason.take(140)} | ${facts.ifBlank { "-" }}"

    private fun noteBuyTerminal8726(atMs: Long, mint: String, symbol: String, processor: String, reason: String) {
        val facts = try {
            val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]
            val chase = try { com.lifecyclebot.engine.truth.EntryEconomics8329.lastChaseDetail8726(mint) } catch (_: Throwable) { null }
            if (ts == null) chase.orEmpty() else {
                val sf = ts.safety
                val ageMin = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.launchAgeMs7767(ts, atMs)?.let { it / 60_000L } } catch (_: Throwable) { null }
                "lpLock=${sf.lpLockPct} safetyAgeS=${if (sf.checkedAt > 0L) (atMs - sf.checkedAt) / 1000L else -1L} tier=${sf.tier} pool=${ts.tokenMap.poolAddress.ifBlank { ts.pairAddress }.take(10)} " +
                    "liq=${ts.lastLiquidityUsd.toLong()} mcap=${ts.lastMcap.toLong()} ageMin=${ageMin ?: "?"} priceAgeS=${if (ts.lastPriceUpdate > 0L) (atMs - ts.lastPriceUpdate) / 1000L else -1L}" +
                    (if (chase != null) " chase[$chase]" else "")
            }
        } catch (_: Throwable) { "" }
        synchronized(buyTerminals8726) {
            buyTerminals8726.addLast(buyTerminalLine8726(atMs, mint, symbol, processor, reason, facts))
            while (buyTerminals8726.size > BUY_TERMINALS_KEPT_8726) buyTerminals8726.removeFirst()
        }
    }

    /** The report's block: the last failed live buys, oldest first. */
    fun buyTerminalsForReport8726(): String = synchronized(buyTerminals8726) {
        if (buyTerminals8726.isEmpty()) "none" else buyTerminals8726.joinToString("\n    ", prefix = "\n    ")
    }

    // ── V5.0.8702 — which failed buy set each backoff ──
    // 8689: 30 sealed LIVE tickets, 5 buys. 9 tickets ended on a lease backoff (BUY_LEASE_BACKOFF_ENDS_TICKET_8545) set by
    // an earlier failed attempt on the same coin, and 18 BUY_TERMINAL_FAILs were counted with no reason beside them.
    private val buyFails8702 = ConcurrentHashMap<String, AtomicLong>()
    private val buyBackoffHits8702 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: a failure reason's family — its first two ':' segments, without the per-coin detail after them. */
    internal fun reasonFamily8702(reason: String): String =
        reason.trim().split(':').take(2).joinToString(":").replace(Regex("[^A-Za-z0-9_:]"), "_").take(64).ifBlank { "UNNAMED" }

    private fun count8702(into: ConcurrentHashMap<String, AtomicLong>, reason: String) {
        if (into.size > 200) return
        into.computeIfAbsent(reasonFamily8702(reason)) { AtomicLong(0) }.incrementAndGet()
    }

    private fun line8702(from: ConcurrentHashMap<String, AtomicLong>): String =
        from.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }

    private fun emit(tag: String, side: String, mint: String, symbol: String, detail: String) {
        try { PipelineHealthCollector.labelInc(tag) } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle(tag, "side=$side mint=${mint.take(10)} symbol=$symbol $detail") } catch (_: Throwable) {}
    }
}
