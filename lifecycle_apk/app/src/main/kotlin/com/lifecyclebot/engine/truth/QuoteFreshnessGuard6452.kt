package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6452 §P0-#10 — QUOTE FRESHNESS / PROVENANCE GUARD.
 *
 * OPERATOR MANDATE:
 *   "Enforce quote provenance/freshness before execution, PnL,
 *    classification or learning."
 *
 * DESIGN
 * ──────
 * Compact per-mint freshness record. Callers stamp `note(mint, priceUsd,
 * source, quoteAgeMs)` when they observe a fresh quote from a provider.
 * Consumers ask `isFresh(mint, maxAgeMs)` before using a mark for
 * execution / PnL / classification. `stale=true` bumps a counter and
 * emits QUOTE_STALE_6452 with the offending age so operator can see
 * which mints are drifting.
 *
 * Deliberately does NOT auto-block callers — it is an audit + gate
 * primitive; each consumer decides whether stale means "skip" (execution
 * / PnL) or "still admissible" (informational display).
 */
object QuoteFreshnessGuard6452 {

    enum class Provenance { WS_LIVE, REST_LIVE, CACHED, DERIVED, UNKNOWN }

    data class Quote(val mint: String, val priceUsd: Double, val source: Provenance, val stampedAtMs: Long)

    private val quotes = java.util.concurrent.ConcurrentHashMap<String, Quote>()
    private val notes = AtomicLong(0L)
    private val staleReads = AtomicLong(0L)
    private val missingReads = AtomicLong(0L)
    private val freshReads = AtomicLong(0L)

    fun note(mint: String, priceUsd: Double, source: Provenance, quoteAgeMs: Long = 0L) {
        if (mint.isBlank() || !priceUsd.isFinite() || priceUsd <= 0.0) return
        notes.incrementAndGet()
        val stampedAt = System.currentTimeMillis() - quoteAgeMs.coerceAtLeast(0L)
        quotes[mint] = Quote(mint, priceUsd, source, stampedAt)
    }

    /** Returns true iff we have a quote for `mint` that is at most
     *  `maxAgeMs` old and came from a live provider. Derived/unknown
     *  provenance is refused. */
    fun isFresh(mint: String, maxAgeMs: Long = 30_000L): Boolean {
        val q = quotes[mint]
        if (q == null) {
            missingReads.incrementAndGet()
            try { PipelineHealthCollector.labelInc("QUOTE_MISSING_6452") } catch (_: Throwable) {}
            return false
        }
        val age = System.currentTimeMillis() - q.stampedAtMs
        if (age > maxAgeMs || q.source == Provenance.DERIVED || q.source == Provenance.UNKNOWN) {
            staleReads.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "QUOTE_STALE_6452",
                    "mint=${mint.take(10)} ageMs=$age maxAgeMs=$maxAgeMs source=${q.source}",
                )
                PipelineHealthCollector.labelInc("QUOTE_STALE_6452")
            } catch (_: Throwable) {}
            return false
        }
        freshReads.incrementAndGet()
        return true
    }

    fun lastPrice(mint: String): Quote? = quotes[mint]

    fun statusLine(): String = "quotes=${quotes.size} notes=${notes.get()} " +
        "fresh=${freshReads.get()} stale=${staleReads.get()} missing=${missingReads.get()}"
}
