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

    /**
     * V5.0.7188 §FIVE_ORGANS_SAY_CORROBORATED_AND_ONLY_ONE_MEANS_IT.
     *
     * An audit of every place this codebase decides "is this mark
     * corroborated" found five incompatible definitions:
     *
     *   ParallelMarkFanout7088.merge7088   >=2 INDEPENDENT FEEDS agree within 2%
     *   MARK_QUOTE_7060 (BotService)       tick vs mcap provenance, ONE feed
     *   CanonicalMarkResolution7059        raw tick vs mcap-implied, ONE feed
     *   DataLegitimacyAuthority7077        price ~= mcap/onChainSupply, ONE feed
     *   MarkAuthorityIntegrityGate6496     source NAME + provenance class, no count
     *
     * Only the first means what the word means. So
     * `MARK_QUOTE_7060_CORROBORATED = 7454` on the 5.0.7186 run is NOT 7454
     * multi-feed marks — it is one feed's number checked against its own
     * market cap. The real figure from the fan-out is
     * corroborated=2840 / singleSource=4437: most marks have exactly one
     * source, and nothing downstream can tell.
     *
     * That is why a CRYPTO_SPOT position printed +360% in 1.4 seconds. A lone
     * feed's tick had nothing to contradict it, and by the time it reached the
     * exit engine it was labelled REST_LIVE — indistinguishable from a
     * six-feed agreement.
     *
     * This guard is the seam, because it is the ONLY choke point BOTH mark
     * paths already pass through — the Solana fan-out via BotService and the
     * cross-asset router's three branches — and `isFresh` is what the exit
     * feed actually consults. Carrying the count here gives every consumer a
     * corroboration verdict without a new bus and without a second fan-out.
     *
     * DELIBERATELY NOT ENFORCED YET. Raising the bar to >=3 sources today
     * would price roughly 5-7% of marks instead of 36%, and with 39 open
     * positions needing marks to exit that reproduces the 5.0.6993 failure
     * (tens of thousands of exit evals, zero stops). Widen the feeds first —
     * V5.0.7188 revives Helius DAS, a sixth feed that had never returned a
     * quote — then measure `corroboration7188` on device, then decide a bar.
     * Measure, then gate. Never both in one build.
     */
    data class Quote(
        val mint: String,
        val priceUsd: Double,
        val source: Provenance,
        val stampedAtMs: Long,
        /** How many independent feeds answered for this mint. 1 = single source. */
        val sourceCount7188: Int = 1,
        /** How many of those agreed within the fan-out's tolerance. */
        val agreeingCount7188: Int = 1,
    ) {
        /** True only when independent feeds actually agreed — not a derivation check. */
        val corroborated7188: Boolean get() = agreeingCount7188 >= 2
    }

    private val quotes = java.util.concurrent.ConcurrentHashMap<String, Quote>()
    private val notes = AtomicLong(0L)
    // V5.0.7188 — the measurement that has to exist before any corroboration
    // bar can be set. These count INDEPENDENT FEEDS, unlike the four
    // derivation-based "corroborated" counters elsewhere in the app.
    private val corroboratedNotes7188 = AtomicLong(0L)
    private val singleSourceNotes7188 = AtomicLong(0L)
    private val tripleNotes7188 = AtomicLong(0L)
    private val staleReads = AtomicLong(0L)
    private val missingReads = AtomicLong(0L)
    private val freshReads = AtomicLong(0L)

    /**
     * V5.0.7188 — [sourceCount7188] / [agreeingCount7188] default to 1 so every
     * existing caller compiles unchanged and is recorded honestly as what it
     * is: a single-source stamp. Callers that DO know better (the six-feed
     * fan-out) pass the real numbers. Nothing is refused on the count yet —
     * see the Quote docstring for why measurement precedes the bar.
     */
    fun note(
        mint: String,
        priceUsd: Double,
        source: Provenance,
        quoteAgeMs: Long = 0L,
        sourceCount7188: Int = 1,
        agreeingCount7188: Int = 1,
    ) {
        if (mint.isBlank() || !priceUsd.isFinite() || priceUsd <= 0.0) return
        notes.incrementAndGet()
        val stampedAt = System.currentTimeMillis() - quoteAgeMs.coerceAtLeast(0L)
        val sc = sourceCount7188.coerceAtLeast(1)
        val ac = agreeingCount7188.coerceIn(1, sc)
        if (ac >= 2) corroboratedNotes7188.incrementAndGet() else singleSourceNotes7188.incrementAndGet()
        if (ac >= 3) tripleNotes7188.incrementAndGet()
        quotes[mint] = Quote(mint, priceUsd, source, stampedAt, sc, ac)
    }

    // V5.0.7188 — no per-mint corroboration accessor is added here on purpose.
    // The measurement this build needs reaches the operator through
    // statusLine()'s corrob2/ge3/single1 counters, which are already wired.
    // An unused getter "for future consumers" is the exact shape ci/
    // new_dead_code.py exists to reject, and it rejected this one. The reader
    // arrives in the commit that enforces a bar, not before it.

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
        "fresh=${freshReads.get()} stale=${staleReads.get()} missing=${missingReads.get()} " +
        // V5.0.7188 — independent-feed corroboration, measured not enforced.
        // single1 is the honest count of marks backed by ONE feed; ge3 is what
        // a three-party bar would actually admit today. Set the bar only when
        // ge3 is a workable share of the book.
        "corrob2=${corroboratedNotes7188.get()} ge3=${tripleNotes7188.get()} " +
        "single1=${singleSourceNotes7188.get()}"
}
