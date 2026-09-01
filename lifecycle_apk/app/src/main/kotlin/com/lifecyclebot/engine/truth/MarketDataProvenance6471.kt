package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6471 §P0 (items 16-20) — MARKET DATA PROVENANCE AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6470 evidence):
 *
 *   "6470 shows many unrelated entries sharing:
 *      price=0.050250000
 *      mcap=50000000
 *    and MINT_ENTRY_MARKET_SNAPSHOT_POOL_SENTINEL fired 18 times.
 *
 *    Any placeholder, sentinel, synthetic, guessed, fallback-default
 *    or cache-template price/liquidity/mcap MUST carry
 *    provenance=NON_AUTHORITATIVE.
 *
 *    NON_AUTHORITATIVE market data may keep token visible / feed
 *    shadow telemetry / request provider refresh, but MUST NOT
 *    satisfy liquidity proof / cause BLUECHIP or QUALITY
 *    classification / raise score / satisfy FDG / calculate
 *    executable quantity / create canonical entry snapshot / enter
 *    learner terminal truth."
 *
 * DESIGN
 * ──────
 *   fun classify(price, mcap, liquidity, source, poolAddress) :
 *       Provenance ∈ { AUTHORITATIVE, NON_AUTHORITATIVE_SENTINEL,
 *                      NON_AUTHORITATIVE_MISSING }
 *
 *   fun isExecutable(provenance)  ⇒ Boolean  (source of truth
 *       for the "is this data allowed to authorize a trade?" question)
 *
 * Detectors:
 *   1. Exact template tuple hits (0.05025 / 50m / 5m — 6470 field data).
 *   2. Zero/NaN/negative values.
 *   3. Pool address is a sentinel string ("MINT_ROUTE:...", "UNKNOWN", "").
 *   4. Source string is "UNKNOWN" / "fallback" / "cache_default".
 */
object MarketDataProvenance6471 {

    enum class Provenance {
        AUTHORITATIVE,
        NON_AUTHORITATIVE_SENTINEL,
        NON_AUTHORITATIVE_MISSING,
    }

    private val classified = AtomicLong(0L)
    private val sentinelHits = AtomicLong(0L)
    private val missingHits = AtomicLong(0L)
    private val executableBlocked = AtomicLong(0L)
    private val sentinelCoalesced6615 = AtomicLong(0L)
    private data class SentinelState6615(
        val mint: String,
        val sentinelFingerprint: String,
        val sentinelReason: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val lastProcessedAt: Long,
        val occurrenceCount: Long,
    )
    private val sentinelStates6615 = ConcurrentHashMap<String, SentinelState6615>()
    private const val SENTINEL_HEARTBEAT_MS_6615 = 5_000L

    // 6470 field-data template tuple. If a mint's (price, mcap, liquidity)
    // matches any of these known synthetic defaults, the tuple is a template.
    private data class TemplateTuple(val price: Double, val mcap: Double, val liquidity: Double)
    private val KNOWN_TEMPLATES = listOf(
        TemplateTuple(0.050250000, 50_000_000.0, 5_000_000.0),
    )
    private const val TEMPLATE_EPSILON = 1e-6

    // Source / pool tokens that mean "no real provider answered"
    private val SENTINEL_POOL_PREFIXES = listOf(
        "MINT_ROUTE:", "UNKNOWN", "PLACEHOLDER", "SENTINEL",
    )
    private val SENTINEL_SOURCES = setOf(
        "UNKNOWN", "FALLBACK", "CACHE_DEFAULT", "CACHE_TEMPLATE", "SYNTHETIC",
    )

    fun classify(
        price: Double,
        mcap: Double,
        liquidity: Double,
        source: String,
        poolAddress: String,
        identity: String = "",
    ): Provenance {
        classified.incrementAndGet()
        val pool = poolAddress.trim()
        val identityKey6615 = identity.trim().ifBlank {
            if (pool.startsWith("MINT_ROUTE:", ignoreCase = true)) pool.substringAfter(':').trim()
            else pool.ifBlank { source.trim().uppercase().ifBlank { "UNKNOWN" } }
        }
        if (!price.isFinite() || price <= 0.0) return recordMissing("price_invalid")
        if (!liquidity.isFinite() || liquidity < 0.0) return recordMissing("liquidity_invalid")
        if (KNOWN_TEMPLATES.any {
                kotlin.math.abs(price - it.price) < TEMPLATE_EPSILON &&
                    kotlin.math.abs(mcap - it.mcap) < 1.0 &&
                    kotlin.math.abs(liquidity - it.liquidity) < 1.0
            }) return recordSentinel(identityKey6615, "template_tuple($price/$mcap/$liquidity)")
        if (pool.isBlank()) return recordMissing("pool_blank")
        if (SENTINEL_POOL_PREFIXES.any { pool.startsWith(it, ignoreCase = true) })
            return recordSentinel(identityKey6615, "pool_prefix($pool)")
        val src = source.trim().uppercase()
        if (src.isBlank()) return recordMissing("source_blank")
        if (src in SENTINEL_SOURCES) return recordSentinel(identityKey6615, "source($src)")
        clearSentinel6615(identityKey6615)
        return Provenance.AUTHORITATIVE
    }

    private fun recordSentinel(identity: String, reason: String): Provenance {
        sentinelHits.incrementAndGet()
        // V5.0.6626 §RUNTIME_LOOP_UNCHOKE §1 — coalesced hot-label
        // increment. Under 290k hits/uptime the direct labelInc call
        // was compounding Main-thread frame gaps; the coalescer flushes
        // once per second while preserving counter accuracy.
        try { HotLabelCoalescer6626.inc6626("MARKET_DATA_SENTINEL_6471") } catch (_: Throwable) {}
        val now = System.currentTimeMillis()
        val fingerprint = "$identity|$reason"
        var transition = false
        var heartbeatCount = 0L
        var firstAt = now
        sentinelStates6615.compute(identity) { _, prior ->
            if (prior == null || prior.sentinelFingerprint != fingerprint) {
                transition = true
                SentinelState6615(identity, fingerprint, reason, now, now, now, 1L)
            } else {
                val count = prior.occurrenceCount + 1L
                firstAt = prior.firstSeenAt
                if (now - prior.lastProcessedAt >= SENTINEL_HEARTBEAT_MS_6615) heartbeatCount = count
                prior.copy(
                    lastSeenAt = now,
                    lastProcessedAt = if (heartbeatCount > 0L) now else prior.lastProcessedAt,
                    occurrenceCount = count,
                )
            }
        }
        if (!transition) sentinelCoalesced6615.incrementAndGet()
        try {
            when {
                transition -> ForensicLogger.lifecycle(
                    "MARKET_DATA_SENTINEL_6471",
                    "mint=${identity.take(18)} reason=$reason transition=ACTIVE occurrences=1",
                )
                heartbeatCount > 0L -> ForensicLogger.lifecycle(
                    "MARKET_DATA_SENTINEL_COALESCED_6615",
                    "mint=${identity.take(18)} reason=$reason occurrences=$heartbeatCount windowMs=${now - firstAt}",
                )
            }
        } catch (_: Throwable) {}
        return Provenance.NON_AUTHORITATIVE_SENTINEL
    }

    private fun clearSentinel6615(identity: String) {
        val prior = sentinelStates6615.remove(identity) ?: return
        try {
            ForensicLogger.lifecycle(
                "MARKET_DATA_SENTINEL_CLEARED_6615",
                "mint=${identity.take(18)} reason=${prior.sentinelReason} occurrences=${prior.occurrenceCount}",
            )
        } catch (_: Throwable) {}
    }

    private fun recordMissing(reason: String): Provenance {
        missingHits.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MARKET_DATA_MISSING_6471") } catch (_: Throwable) {}
        return Provenance.NON_AUTHORITATIVE_MISSING
    }

    /**
     * Source of truth for the "is this data allowed to authorize a trade?" question.
     * The mandate demands NON_AUTHORITATIVE data MUST NOT calculate executable quantity,
     * satisfy FDG, or create a canonical entry snapshot.
     */
    fun isExecutable(p: Provenance): Boolean {
        val ok = p == Provenance.AUTHORITATIVE
        if (!ok) {
            executableBlocked.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARKET_DATA_EXECUTABLE_BLOCKED_6471") } catch (_: Throwable) {}
        }
        return ok
    }

    /** Convenience: classify + return true only when executable. */
    fun isExecutable(
        price: Double, mcap: Double, liquidity: Double,
        source: String, poolAddress: String,
    ): Boolean = isExecutable(classify(price, mcap, liquidity, source, poolAddress))

    fun statusLine(): String =
        "classified=${classified.get()} sentinelHits=${sentinelHits.get()} " +
            "missingHits=${missingHits.get()} executableBlocked=${executableBlocked.get()} " +
            "sentinelActive=${sentinelStates6615.size} sentinelCoalesced=${sentinelCoalesced6615.get()}"

    internal fun resetForTest() {
        classified.set(0L); sentinelHits.set(0L)
        missingHits.set(0L); executableBlocked.set(0L)
        sentinelCoalesced6615.set(0L); sentinelStates6615.clear()
    }
}
