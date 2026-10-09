package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7911 — Cortex v11: the Constitution's self-checking prover (v1 §2.5) and
 * per-decision feature provenance (v1 §2.1).
 *
 * Invariants run once a minute on the ExitRegret7752 clock; a violation is a
 * NAMED alarm (count + one forensic line per subject per hour), not a log line:
 *
 *   I1_LIVE_POSITION_BLIND      a live open position has no mark (token or
 *                               canonical registry) younger than 5 minutes
 *   I2_LIVE_POSITION_NO_ENTRY   a live open position without an entry price
 *                               (its stop and P&L are undefined)
 *   I3_CORTEX_BACKLOG           the Cortex worker queue is near its cap
 *   I4_PENDING_NEAR_CAP         a learner's pending-label store is near its cap
 *
 * Provenance: for each assessment the key features are classified OBSERVED /
 * UNKNOWN / STALE (price, liquidity, market cap, holders, safety report), and
 * the scoreboard shows the rates per feature, so "missing read as zero" is
 * visible rather than silent.
 */
object CortexInvariants7911 {
    private const val CHECK_EVERY_MS = 60_000L
    private const val BLIND_MS = 5L * 60_000L
    private const val SAFETY_STALE_MS = 10L * 60_000L

    enum class State { OBSERVED, UNKNOWN, STALE }

    private val alarms = ConcurrentHashMap<String, AtomicLong>()
    private val lastEmit = ConcurrentHashMap<String, Long>()
    private val provenance = ConcurrentHashMap<String, LongArray>()   // feature -> [observed, unknown, stale]
    @Volatile private var lastCheckMs = 0L

    private fun alarm(id: String, subject: String, detail: String, nowMs: Long) {
        alarms.computeIfAbsent(id) { AtomicLong(0) }.incrementAndGet()
        val k = "$id|$subject"
        val last = lastEmit[k] ?: 0L
        if (nowMs - last < 3_600_000L) return
        lastEmit[k] = nowMs
        if (lastEmit.size > 2_000) lastEmit.entries.removeIf { nowMs - it.value > 3_600_000L }
        try {
            PipelineHealthCollector.labelInc("CORTEX_INVARIANT_$id")
            ForensicLogger.lifecycle("CORTEX_INVARIANT_7911", "id=$id subject=${subject.take(16)} $detail")
        } catch (_: Throwable) {}
    }

    /** Pure: classify one feature. */
    fun stateOf(known: Boolean, ageMs: Long, staleAfterMs: Long): State = when {
        !known -> State.UNKNOWN
        ageMs > staleAfterMs -> State.STALE
        else -> State.OBSERVED
    }

    /** Records the provenance of an assessment's key features. */
    fun recordProvenance(ts: TokenState, nowMs: Long) {
        try {
            val registryAt = try { com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(ts.mint)?.timestampMs ?: 0L } catch (_: Throwable) { 0L }
            val priceAt = maxOf(ts.lastPriceUpdate, registryAt)
            note("PRICE", stateOf(ts.lastPrice > 0.0 && priceAt > 0L, nowMs - priceAt, 180_000L))
            note("LIQUIDITY", stateOf(ts.lastLiquidityUsd > 0.0, 0L, Long.MAX_VALUE))
            note("MCAP", stateOf(ts.lastMcap > 0.0, 0L, Long.MAX_VALUE))
            note("HOLDERS", stateOf(ts.holderDataResolved || ts.safety.topHolderPct >= 0.0, 0L, Long.MAX_VALUE))
            val sAt = ts.safety.checkedAt
            note("SAFETY", stateOf(sAt > 0L, nowMs - sAt, SAFETY_STALE_MS))
        } catch (_: Throwable) {}
    }

    private fun note(feature: String, s: State) {
        val a = provenance.computeIfAbsent(feature) { LongArray(3) }
        synchronized(a) { a[s.ordinal]++ }
    }

    /** Run the invariants (rate-limited to once a minute). */
    fun check(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastCheckMs < CHECK_EVERY_MS) return
        lastCheckMs = nowMs
        try {
            val tokens = try { synchronized(com.lifecyclebot.engine.BotService.status.tokens) { com.lifecyclebot.engine.BotService.status.tokens.toMap() } } catch (_: Throwable) { emptyMap() }
            // V5.0.7948 — the invariant reads the inventory the exit system protects: the
            // canonical LIVE exit scope, Solana-priced rows only. It read every TokenState
            // whose projected position said "open, not paper", which also counts trader-owned
            // off-chain rows (priced by their own feeds, never by a Solana mark) and stale
            // projections of closed positions — 5.0.7947: I1=19 while the exit feed saw 5/5
            // positions marked.
            val scope = try {
                com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.protectiveExitScope7809("live")
                    .filterNot { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.isTraderOwnedOffChainMarket7819(it) }
            } catch (_: Throwable) { emptyList() }
            for (cp in scope) {
                val ts = tokens[cp.mint]
                val registryAt = try { com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(cp.mint)?.timestampMs ?: 0L } catch (_: Throwable) { 0L }
                val crossAssetMarked = try { CrossAssetCortex7931.priceFor(cp.mint, nowMs) != null } catch (_: Throwable) { false }
                val freshest = maxOf(ts?.lastPriceUpdate ?: 0L, registryAt)
                if (!crossAssetMarked && (freshest <= 0L || nowMs - freshest > BLIND_MS)) {
                    alarm("I1_LIVE_POSITION_BLIND", cp.mint, "sym=${cp.symbol} lane=${cp.lane} markAgeSec=${if (freshest > 0L) (nowMs - freshest) / 1000 else -1}", nowMs)
                }
                if (!((ts?.position?.entryPrice ?: 0.0) > 0.0) && !(cp.entryPriceUsd > 0.0)) alarm("I2_LIVE_POSITION_NO_ENTRY", cp.mint, "sym=${cp.symbol} lane=${cp.lane}", nowMs)
            }
            val backlog = Cortex7885.queuedTasks()
            if (backlog > 200) alarm("I3_CORTEX_BACKLOG", "pool", "queued=$backlog", nowMs)
            val pending = Cortex7885.pendingCount()
            if (pending > 7_000) alarm("I4_PENDING_NEAR_CAP", "cortex", "pending=$pending", nowMs)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "alarms=[${alarms.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }}] " +
            "provenance=[${provenance.entries.sortedBy { it.key }.joinToString(" ") { (f, a) ->
                val t = a.sum().coerceAtLeast(1L)
                "$f:obs${a[0] * 100 / t}%/unk${a[1] * 100 / t}%/stale${a[2] * 100 / t}%"
            }.ifBlank { "-" }}]"
}
