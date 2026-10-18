package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — SELL ROUTE SPINE (P0-E: catastrophic sell routing and confirmation).
 *
 * 5.0.8142 measured catastrophic trigger -> broadcast at up to 8,724 ms, with repeated route retries, no-signature
 * failures and CATCH bouncing between a catastrophic exit and balance-proof waiting — and nothing said WHICH stage of
 * the sell the time went to, or at which stage an attempt died. ExitStageTiming7876 times phase-to-phase gaps per
 * stop class; it has no notion of the sell's spine, of a failure stage, or of retries spent waiting for the lease.
 *
 * One live exit is one spine, from its trigger to its canonical close:
 *
 *   TRIGGER -> CLOSE_LEASE -> TOKEN_BALANCE -> VENUE_RESOLVE -> QUOTE_BUILD -> SIGN -> BROADCAST -> RPC_ACK
 *           -> CHAIN_FINALITY -> WALLET_DELTA -> CANONICAL_CLOSE
 *
 * [Timer8144] is pure: every mark closes the stage it names and is timed from the previous mark, so the per-stage
 * durations always add up to (last mark - trigger) — retries, proof waits and lease waits included (a failed attempt's
 * remaining time is charged to the stage it failed in; the wait until the next lease is charged to CLOSE_LEASE).
 * Latencies are reported separately: queue (CLOSE_LEASE), balance, quote (VENUE_RESOLVE + QUOTE_BUILD), sign,
 * broadcast (BROADCAST + RPC_ACK) and finality (CHAIN_FINALITY + WALLET_DELTA + CANONICAL_CLOSE), plus trigger ->
 * first broadcast.
 *
 * Feeds: requestSell (trigger, lease, result), LiveTradeLogStore SELL phases, SignedTxLedger8085 states and
 * SellFinalizationCoordinator (wallet delta, canonical close). Measurement only: nothing here admits, delays or routes
 * a sell.
 */
object SellRouteSpine8144 {

    enum class Stage8144 {
        TRIGGER, CLOSE_LEASE, TOKEN_BALANCE, VENUE_RESOLVE, QUOTE_BUILD, SIGN, BROADCAST, RPC_ACK,
        CHAIN_FINALITY, WALLET_DELTA, CANONICAL_CLOSE,
    }

    private val STAGES_8144 = Stage8144.values()

    /** The stage after [s] on the spine (CANONICAL_CLOSE is the last). */
    fun next8144(s: Stage8144): Stage8144 = STAGES_8144[(s.ordinal + 1).coerceAtMost(STAGES_8144.size - 1)]

    /** Pure per-exit stage timer. */
    class Timer8144(
        val mint: String,
        val reason: String,
        val triggerAtMs: Long,
        val emergency: Boolean,
        val positionId: String = "",
    ) {
        private val ms = LongArray(STAGES_8144.size)
        private val hits = IntArray(STAGES_8144.size)
        @Volatile var lastMarkAtMs: Long = triggerAtMs
            private set
        @Volatile var lastStage: Stage8144 = Stage8144.TRIGGER
            private set
        @Volatile var broadcastAtMs: Long = 0L
            private set
        @Volatile var failedStage: Stage8144? = null
            private set
        @Volatile var failureCause: String = ""
            private set
        @Volatile var failures: Int = 0
            private set
        /** True when the last [fail] was a new failure (a stage was marked since the previous one), not an echo of it. */
        @Volatile var lastFailureFresh: Boolean = false
            private set
        private var marksSinceFailure = 1

        /** Close [stage] at [nowMs]; the time since the previous mark is charged to it. TRIGGER cannot be re-marked. */
        @Synchronized
        fun mark(stage: Stage8144, nowMs: Long): Boolean {
            if (stage == Stage8144.TRIGGER) return false
            val at = nowMs.coerceAtLeast(lastMarkAtMs)
            ms[stage.ordinal] += at - lastMarkAtMs
            hits[stage.ordinal] += 1
            marksSinceFailure += 1
            lastMarkAtMs = at
            lastStage = stage
            if (stage == Stage8144.BROADCAST && broadcastAtMs == 0L) broadcastAtMs = at
            return true
        }

        /**
         * An attempt failed at [stage] (default: the stage in progress, i.e. the one after the last mark). The time
         * since the previous mark is charged to the failing stage; the spine stays open for the next attempt.
         */
        @Synchronized
        fun fail(cause: String, nowMs: Long, stage: Stage8144? = null): Stage8144 {
            val s = stage?.takeIf { it != Stage8144.TRIGGER } ?: next8144(lastStage)
            val at = nowMs.coerceAtLeast(lastMarkAtMs)
            ms[s.ordinal] += at - lastMarkAtMs
            lastMarkAtMs = at
            lastFailureFresh = marksSinceFailure > 0
            marksSinceFailure = 0
            if (lastFailureFresh) {
                failedStage = s
                failureCause = cause.take(60)
                failures += 1
            }
            return s
        }

        @Synchronized fun durationMs(stage: Stage8144): Long = ms[stage.ordinal]
        @Synchronized fun reached(stage: Stage8144): Boolean = hits[stage.ordinal] > 0
        /** Sum of every stage: always equals lastMarkAtMs - triggerAtMs. */
        @Synchronized fun accountedMs(): Long = ms.sum()

        fun queueMs(): Long = durationMs(Stage8144.CLOSE_LEASE)
        fun balanceMs(): Long = durationMs(Stage8144.TOKEN_BALANCE)
        fun quoteMs(): Long = durationMs(Stage8144.VENUE_RESOLVE) + durationMs(Stage8144.QUOTE_BUILD)
        fun signMs(): Long = durationMs(Stage8144.SIGN)
        fun broadcastMs(): Long = durationMs(Stage8144.BROADCAST) + durationMs(Stage8144.RPC_ACK)
        fun finalityMs(): Long =
            durationMs(Stage8144.CHAIN_FINALITY) + durationMs(Stage8144.WALLET_DELTA) + durationMs(Stage8144.CANONICAL_CLOSE)
        /** Trigger -> first broadcast, or -1 when nothing was broadcast yet. */
        fun triggerToBroadcastMs(): Long = if (broadcastAtMs > 0L) broadcastAtMs - triggerAtMs else -1L

        fun line(): String =
            "${mint.take(6)} ${if (emergency) "EMRG" else "NORM"} t2b=${triggerToBroadcastMs()} queue=${queueMs()} " +
                "bal=${balanceMs()} quote=${quoteMs()} sign=${signMs()} bcast=${broadcastMs()} fin=${finalityMs()} " +
                "last=${lastStage.name} fail=${failedStage?.name ?: "-"}(${failures})${if (failureCause.isNotBlank()) ":$failureCause" else ""}"
    }

    /** Pure: the spine stage a LiveTradeLogStore SELL phase / SignedTxLedger8085 state closes, or null. */
    fun stageForPhase8144(phase: String): Stage8144? = when (phase) {
        "SELL_BALANCE_CHECK", "SELL_BALANCE_LIVE_RAW", "SELL_BALANCE_TRACKER_RAW", "SELL_AMOUNT_RAW" -> Stage8144.TOKEN_BALANCE
        "SELL_ROUTE_SELECTED", "SELL_ROUTE_SKIPPED", "VENUE_RESOLVED_8144" -> Stage8144.VENUE_RESOLVE
        "SELL_QUOTE_OK", "SELL_QUOTE_FAIL", "SELL_TX_BUILT", "SELL_PUMPPORTAL_BUILD", "SELL_JUPITER_V2_ORDER" -> Stage8144.QUOTE_BUILD
        "TX_SIGNED", "SIGNED" -> Stage8144.SIGN
        "TX_SUBMIT_ATTEMPTED", "SUBMIT_ATTEMPTED" -> Stage8144.BROADCAST
        "TX_RPC_ACCEPTED", "ACCEPTED", "SELL_PUMPPORTAL_ACCEPTED", "SELL_JUPITER_V2_EXECUTE" -> Stage8144.RPC_ACK
        "SELL_TX_CONFIRMED", "TX_CHAIN_CONFIRMED", "SELL_CONFIRMED", "CONFIRMED" -> Stage8144.CHAIN_FINALITY
        "SELL_TX_PARSE_OK", "SELL_VERIFY_TOKEN_GONE", "SELL_VERIFY_SOL_RETURNED", "SELL_SOL_DELTA",
        "SELL_TOKEN_CONSUMED", "SELL_TOKEN_ACCOUNT_CLOSED_SUCCESS" -> Stage8144.WALLET_DELTA
        else -> null
    }

    /**
     * Pure: a failure phase / state and the stage it fails at. TRIGGER stands for "the stage in progress".
     * Null when [phase] is not a failure.
     */
    fun failureForPhase8144(phase: String): Stage8144? = when (phase) {
        "SELL_FAILED_CONFIRMED", "SELL_TX_ERR_CONFIRMED", "FAILED_ON_CHAIN", "EXPIRED_PROVEN", "SELL_STUCK" -> Stage8144.CHAIN_FINALITY
        "SUBMIT_REJECTED" -> Stage8144.BROADCAST
        "SELL_ROUTE_FAILED_NO_SIGNATURE", "SELL_FAILED" -> Stage8144.TRIGGER
        else -> null
    }

    // ── registry ─────────────────────────────────────────────────────────────

    private const val MAX_AGE_MS_8144 = 10L * 60_000L
    private val open8144 = ConcurrentHashMap<String, Timer8144>()

    private class Agg8144 {
        var n = 0L; var sumMs = 0L; var maxMs = 0L
        @Synchronized fun add(v: Long) { if (v < 0L) return; n++; sumMs += v; if (v > maxMs) maxMs = v }
        @Synchronized fun text(): String = if (n == 0L) "-" else "avg${sumMs / n}/max${maxMs}"
    }

    private val latency8144 = ConcurrentHashMap<String, Agg8144>()     // "EMRG|queue"
    private val failures8144 = ConcurrentHashMap<String, AtomicLong>()  // "EMRG|STAGE"
    private val outcomes8144 = ConcurrentHashMap<String, AtomicLong>()  // "EMRG|CONFIRMED"
    private val recent8144 = java.util.ArrayDeque<String>()

    private fun cls(t: Timer8144) = if (t.emergency) "EMRG" else "NORM"

    private fun live8144(mint: String, nowMs: Long): Timer8144? {
        val t = open8144[mint] ?: return null
        if (nowMs - t.triggerAtMs > MAX_AGE_MS_8144) {
            open8144.remove(mint, t)
            outcomes8144.computeIfAbsent("${cls(t)}|STALE") { AtomicLong() }.incrementAndGet()
            return null
        }
        return t
    }

    /**
     * Stamp an exit request. An emergency trigger, and any trigger whose exit already holds the lease, keeps its
     * earliest time; a non-emergency exit that has not reached the lease is re-stamped (a held / deferred exit is
     * not queue time). An emergency arriving on a softer exit's spine opens its own spine, and so does a new
     * position on the same mint (a spine never outlives its position).
     */
    fun trigger8144(mint: String, reason: String, positionId: String = "", nowMs: Long = System.currentTimeMillis()): Timer8144? {
        if (mint.isBlank()) return null
        val emergency = try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }
        val cur = live8144(mint, nowMs)
        if (cur != null && cur.positionId == positionId) {
            val keep = (cur.emergency || cur.reached(Stage8144.CLOSE_LEASE)) && !(emergency && !cur.emergency)
            if (keep) return cur
        }
        val t = Timer8144(mint, reason.take(80), nowMs, emergency, positionId)
        open8144[mint] = t
        if (open8144.size > 500) open8144.entries.removeIf { nowMs - it.value.triggerAtMs > MAX_AGE_MS_8144 }
        return t
    }

    fun mark8144(mint: String, stage: Stage8144, nowMs: Long = System.currentTimeMillis()): Boolean =
        live8144(mint, nowMs)?.mark(stage, nowMs) == true

    private fun fail8144(mint: String, cause: String, stage: Stage8144? = null, nowMs: Long = System.currentTimeMillis()): Stage8144? {
        val t = live8144(mint, nowMs) ?: return null
        val s = t.fail(cause, nowMs, stage)
        if (t.lastFailureFresh) failures8144.computeIfAbsent("${cls(t)}|${s.name}") { AtomicLong() }.incrementAndGet()
        return s
    }

    /** A LiveTradeLogStore SELL phase (or SignedTxLedger8085 state name) for [mint]. */
    fun onPhase8144(mint: String, phase: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || open8144.isEmpty()) return
        stageForPhase8144(phase)?.let { mark8144(mint, it, nowMs); return }
        val f = failureForPhase8144(phase) ?: return
        fail8144(mint, phase, if (f == Stage8144.TRIGGER) null else f, nowMs)
    }

    /** requestSell's result for this attempt (SellResult name). */
    fun onResult8144(mint: String, result: String, nowMs: Long = System.currentTimeMillis()) {
        when (result) {
            "CONFIRMED" -> finish8144(mint, "CONFIRMED", nowMs)
            "ALREADY_CLOSED", "FAILED_FATAL" -> finish8144(mint, result, nowMs)
            "WAITING_BALANCE_PROOF" -> fail8144(mint, "PROOF_WAIT", Stage8144.TOKEN_BALANCE, nowMs)
            "ROUTE_FAILED_NO_SIGNATURE", "FAILED_RETRYABLE", "NO_WALLET" -> fail8144(mint, result, null, nowMs)
            "PAPER_CONFIRMED" -> open8144.remove(mint)
            else -> {}
        }
    }

    /**
     * SellFinalizationCoordinator: the chain-proven wallet delta was read, and the canonical close did / did not
     * mutate. A partial slice ([terminal] false) only closes WALLET_DELTA; the spine stays open for the remainder.
     */
    fun onFinalized8144(mint: String, canonicalMutated: Boolean, terminal: Boolean = true, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || open8144.isEmpty()) return
        val t = live8144(mint, nowMs) ?: return
        if (!t.reached(Stage8144.WALLET_DELTA)) t.mark(Stage8144.WALLET_DELTA, nowMs)
        if (canonicalMutated && !terminal) return
        if (canonicalMutated) {
            t.mark(Stage8144.CANONICAL_CLOSE, nowMs)
            finish8144(mint, "CLOSED", nowMs)
        } else fail8144(mint, "CANONICAL_NOT_MUTATED", Stage8144.CANONICAL_CLOSE, nowMs)
    }

    /** Close the spine and fold it into the aggregates. */
    private fun finish8144(mint: String, outcome: String, nowMs: Long = System.currentTimeMillis()): Timer8144? {
        val t = open8144.remove(mint) ?: return null
        val c = cls(t)
        fun add(k: String, v: Long) = latency8144.computeIfAbsent("$c|$k") { Agg8144() }.add(v)
        add("trigger->broadcast", t.triggerToBroadcastMs())
        // V5.0.8223 — the paper exit simulator pays this measured latency (PaperExitLatency8223).
        if (outcome == "CONFIRMED" || outcome == "CLOSED") try {
            com.lifecyclebot.engine.truth.PaperExitLatency8223.noteLive8223(t.emergency, t.triggerToBroadcastMs())
        } catch (_: Throwable) {}
        add("queue", t.queueMs())
        add("balance", t.balanceMs())
        add("quote", t.quoteMs())
        add("sign", t.signMs())
        add("broadcast", t.broadcastMs())
        add("finality", t.finalityMs())
        add("total", (nowMs - t.triggerAtMs).coerceAtLeast(0L))
        outcomes8144.computeIfAbsent("$c|$outcome") { AtomicLong() }.incrementAndGet()
        synchronized(recent8144) {
            recent8144.addLast("$outcome ${t.line()}")
            while (recent8144.size > 4) recent8144.removeFirst()
        }
        if (t.emergency && outcome != "CONFIRMED" && outcome != "CLOSED") emit8144("SELL_ROUTE_SPINE_ABANDONED_8144", t, outcome)
        else if (t.emergency) emit8144("SELL_ROUTE_SPINE_CLOSED_8144", t, outcome)
        return t
    }

    private fun emit8144(label: String, t: Timer8144, outcome: String) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(label)
            com.lifecyclebot.engine.ForensicLogger.lifecycle(label, "outcome=$outcome reason=${t.reason.take(60)} ${t.line()}")
        } catch (_: Throwable) {}
    }

    fun timer8144(mint: String): Timer8144? = open8144[mint]

    /** V5.0.8153 — the open spine of [mint] is an emergency exit (the sell hot path reads its urgency from here). */
    fun emergencyOpen8153(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        mint.isNotBlank() && live8144(mint, nowMs)?.emergency == true

    private const val PENDING_WINDOW_MS_8153 = 120_000L

    /** V5.0.8153 — emergencies still working toward their first broadcast (background prewarm yields to them). */
    fun emergencyPending8153(nowMs: Long = System.currentTimeMillis()): Int =
        open8144.values.count { it.emergency && it.broadcastAtMs == 0L && nowMs - it.triggerAtMs in 0..PENDING_WINDOW_MS_8153 }

    /** Per class: trigger -> broadcast, the separated stage latencies, failures by stage, outcomes, the last spines. */
    fun statusLine8144(): String {
        val now = System.currentTimeMillis()
        open8144.entries.removeIf { now - it.value.triggerAtMs > MAX_AGE_MS_8144 }
        fun lat(c: String, k: String) = latency8144["$c|$k"]?.text() ?: "-"
        fun section(c: String): String {
            val fails = failures8144.entries.filter { it.key.startsWith("$c|") }.sortedByDescending { it.value.get() }
                .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" }
            val outs = outcomes8144.entries.filter { it.key.startsWith("$c|") }
                .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" }
            return "$c[t2b=${lat(c, "trigger->broadcast")} queue=${lat(c, "queue")} balance=${lat(c, "balance")} " +
                "quote=${lat(c, "quote")} sign=${lat(c, "sign")} broadcast=${lat(c, "broadcast")} finality=${lat(c, "finality")} " +
                "failStage={$fails} outcomes={$outs}]"
        }
        val recent = synchronized(recent8144) { recent8144.joinToString(" | ") }
        return "open=${open8144.size} ${section("EMRG")} ${section("NORM")} recent=[${recent.ifBlank { "-" }}]"
    }

    internal fun resetForTest8144() {
        open8144.clear(); latency8144.clear(); failures8144.clear(); outcomes8144.clear()
        synchronized(recent8144) { recent8144.clear() }
    }
}
