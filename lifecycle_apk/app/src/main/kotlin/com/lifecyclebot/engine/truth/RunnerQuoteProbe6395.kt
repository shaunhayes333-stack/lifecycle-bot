package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6395 — RUNNER QUOTE PROBES.
 *
 * When displayMarkPnlPct > 30%:
 *   * request executable quotes for 25% / 50% / 100% fractions
 *   * deduplicate probes per mint for a short TTL
 *   * NEVER create three independent sell leases
 *   * select the best risk-adjusted executable outcome
 *   * store quote time, provider, route, price impact, output lamports, expiry
 *   * refresh the selected quote before broadcast if expired
 *
 * When displayMarkPnlPct > 100%:
 *   * prioritise the probe above ordinary scanner work
 *   * partial-profit rule permitted iff partial quote is executable
 *   * trail the remainder using executable net value
 *   * do not wait for the broad loop if the runner monitor holds a valid quote
 */
object RunnerQuoteProbe6395 {

    const val PROBE_TRIGGER_PCT: Double = 30.0
    const val PRIORITY_TRIGGER_PCT: Double = 100.0
    const val PROBE_DEDUP_TTL_MS: Long = 4_000L
    const val QUOTE_STALE_MS: Long = 8_000L
    val PROBE_FRACTIONS: List<Double> = listOf(0.25, 0.50, 1.00)

    data class Quote(
        val mint: String,
        val fractionPct: Double,
        val quotedOutLamports: Long,
        val networkFeeLamports: Long,
        val priorityFeeLamports: Long,
        val jitoTipLamports: Long,
        val applicationFeeLamports: Long,
        val priceImpactPct: Double,
        val route: String,
        val provider: String,
        val quotedAtMs: Long,
        val expiresAtMs: Long,
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
        fun executableNetLamports(): Long =
            (quotedOutLamports - networkFeeLamports - priorityFeeLamports -
             jitoTipLamports - applicationFeeLamports).coerceAtLeast(0L)
    }

    data class ProbeDecision(
        val shouldProbe: Boolean,
        val priority: Priority,
        val reason: String,
        val fractionsRequested: List<Double>,
    ) { enum class Priority { NONE, NORMAL, HIGH } }

    data class Selection(
        val chosen: Quote?,
        val allProbes: List<Quote>,
        val reason: String,
    )

    private val lastProbeAt = ConcurrentHashMap<String, Long>()
    private val activeProbes = ConcurrentHashMap<String, MutableList<Quote>>()

    val probesInitiated = AtomicLong(0L)
    val probesDeduplicated = AtomicLong(0L)

    fun evaluate(mint: String, displayMarkPnlPct: Double, nowMs: Long = System.currentTimeMillis()): ProbeDecision {
        if (displayMarkPnlPct < PROBE_TRIGGER_PCT)
            return ProbeDecision(false, ProbeDecision.Priority.NONE, "BELOW_PROBE_TRIGGER", emptyList())
        val last = lastProbeAt[mint]
        if (last != null && nowMs - last < PROBE_DEDUP_TTL_MS) {
            probesDeduplicated.incrementAndGet()
            return ProbeDecision(false, ProbeDecision.Priority.NONE, "DEDUP_TTL_ACTIVE", emptyList())
        }
        lastProbeAt[mint] = nowMs
        probesInitiated.incrementAndGet()
        val priority = if (displayMarkPnlPct >= PRIORITY_TRIGGER_PCT)
            ProbeDecision.Priority.HIGH else ProbeDecision.Priority.NORMAL
        return ProbeDecision(true, priority,
            if (priority == ProbeDecision.Priority.HIGH) "RUNNER_PRIORITY_100PCT" else "RUNNER_PROBE_30PCT",
            PROBE_FRACTIONS)
    }

    /** Record a quote result from the executor for a fraction. */
    @Synchronized
    fun recordQuote(q: Quote) {
        activeProbes.getOrPut(q.mint) { mutableListOf() }.add(q)
    }

    /** Pick the best executable outcome by net lamports. */
    fun selectBest(mint: String, nowMs: Long = System.currentTimeMillis()): Selection {
        val quotes = activeProbes[mint].orEmpty().filter { !it.isExpired(nowMs) }
        val chosen = quotes.maxByOrNull { it.executableNetLamports() }
        return Selection(chosen, quotes,
            if (chosen == null) "NO_EXECUTABLE_QUOTE" else "BEST_NET_LAMPORTS")
    }

    fun clearProbes(mint: String) {
        activeProbes.remove(mint); lastProbeAt.remove(mint)
    }

    internal fun clearAllForTest() {
        activeProbes.clear(); lastProbeAt.clear()
        probesInitiated.set(0L); probesDeduplicated.set(0L)
    }
}
