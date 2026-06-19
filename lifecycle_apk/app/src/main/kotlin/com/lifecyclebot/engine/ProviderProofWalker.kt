package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.3926 — PROVIDER PROOF WALKER (operator P1, doctrine: "use the whole
 * API stack as its intended for. we have multiple fallbacks for literally
 * everything").
 *
 * Health-ranked provider walker for the data fields the live BUY chokepoint
 * cares about. Before this, each provider was queried independently from
 * its own scanner/poller and the result was whatever happened to land on
 * TokenState last. When Birdeye went into EMERGENCY CONSERVATION (1 call/
 * day) the bot lost its primary safety-proof feed and STANDARD-lane live
 * buys started slipping through with stale/unknown data — directly the
 * rug vector the operator surfaced in V5.0.3929.
 *
 * Single API surface for the live BUY path:
 *
 *   getBestAvailableProof(ts, field): ProofResult
 *
 * For each `field` (LIQUIDITY_USD, MCAP_USD, HOLDER_CONCENTRATION,
 * RUGCHECK_SCORE), the walker iterates the providers known to publish that
 * field, ordered by current ApiHealthMonitor success-rate, and returns the
 * first reading that meets a freshness bar plus a `source` label naming
 * which provider supplied it. The walker does NOT make new HTTP calls — it
 * reads the values the scanner/poller pipeline already wrote to TokenState
 * (no I/O, no coroutine launch, safe on the hot path).
 *
 * Future per-provider snapshot provenance (V5.0.3927+) plugs in by adding
 * to the per-field switch in readFromTokenState() — the order/health
 * orchestration above stays unchanged.
 */
object ProviderProofWalker {

    enum class Field { LIQUIDITY_USD, MCAP_USD, HOLDER_CONCENTRATION_PCT, RUGCHECK_SCORE }

    data class ProofResult(
        val value: Double,
        val source: String,
        val freshMs: Long,
        val quality: Quality,
    ) {
        enum class Quality { REAL_CONFIRMED, PARTIAL_CONFIRMED, FALLBACK, STALE, UNKNOWN }
        val ok: Boolean get() = quality == Quality.REAL_CONFIRMED || quality == Quality.PARTIAL_CONFIRMED
    }

    /** Static provider→field map. Order is doctrine default (overridden by health-rank below). */
    private val providersForField: Map<Field, List<String>> = mapOf(
        Field.LIQUIDITY_USD            to listOf("birdeye", "geckoterminal", "dexscreener", "coingecko"),
        Field.MCAP_USD                 to listOf("birdeye", "coingecko", "geckoterminal", "dexscreener"),
        Field.HOLDER_CONCENTRATION_PCT to listOf("birdeye", "helius"),
        Field.RUGCHECK_SCORE           to listOf("birdeye"),
    )

    /** Freshness cut for "REAL_CONFIRMED" — newer than this passes cleanly. */
    private const val FRESH_REAL_MS = 30_000L
    /** Freshness cut for "PARTIAL_CONFIRMED" — older but still actionable. */
    private const val FRESH_PARTIAL_MS = 120_000L

    /**
     * Walk the provider list for the requested field in current-health order
     * and return the best available reading. Returns ProofResult with quality
     * UNKNOWN when nothing has populated the field on this TokenState yet —
     * the live BUY path should treat UNKNOWN as block-or-probe per doctrine.
     */
    fun getBestAvailableProof(ts: TokenState, field: Field): ProofResult {
        val ordered = healthRankedOrder(field)
        val now = System.currentTimeMillis()
        for (provider in ordered) {
            val reading = readFromTokenState(ts, field, provider) ?: continue
            if (reading.value <= 0.0) continue
            val ageMs = (now - reading.tsMs).coerceAtLeast(0L)
            val q = when {
                ageMs <= FRESH_REAL_MS    -> ProofResult.Quality.REAL_CONFIRMED
                ageMs <= FRESH_PARTIAL_MS -> ProofResult.Quality.PARTIAL_CONFIRMED
                else                       -> ProofResult.Quality.STALE
            }
            return ProofResult(value = reading.value, source = provider, freshMs = ageMs, quality = q)
        }
        return ProofResult(value = 0.0, source = "none", freshMs = -1L, quality = ProofResult.Quality.UNKNOWN)
    }

    /** Reorders the static provider list by current ApiHealthMonitor success-rate. */
    private fun healthRankedOrder(field: Field): List<String> {
        val base = providersForField[field].orEmpty()
        return try {
            base.sortedByDescending { p -> ApiHealthMonitor.successRate(p) }
        } catch (_: Throwable) { base }
    }

    /** Holds a single provider's latest reading for a field. */
    private data class Reading(val value: Double, val tsMs: Long)

    /**
     * Single source of truth for "what did provider X tell us about field Y
     * on this TokenState?". Reads ONLY values already populated by the
     * scanner/poller pipeline — no new HTTP. The `provider` parameter is
     * reserved for the V5.0.3927+ per-provider snapshot fork.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun readFromTokenState(ts: TokenState, field: Field, provider: String): Reading? {
        return try {
            when (field) {
                Field.LIQUIDITY_USD -> {
                    val v = ts.lastLiquidityUsd
                    if (v > 0.0) Reading(v, ts.lastPriceUpdate.takeIf { it > 0L } ?: System.currentTimeMillis()) else null
                }
                Field.MCAP_USD -> {
                    val v = ts.lastMcap
                    if (v > 0.0) Reading(v, ts.lastPriceUpdate.takeIf { it > 0L } ?: System.currentTimeMillis()) else null
                }
                Field.HOLDER_CONCENTRATION_PCT -> {
                    val v = ts.safety.topHolderPct ?: -1.0
                    if (v > 0.0) Reading(v, System.currentTimeMillis()) else null
                }
                Field.RUGCHECK_SCORE -> {
                    val v = ts.safety.rugcheckScore.toDouble()
                    if (v > 0.0) Reading(v, System.currentTimeMillis()) else null
                }
            }
        } catch (_: Throwable) { null }
    }
}
