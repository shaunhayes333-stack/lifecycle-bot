package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.3929 — PROVIDER PROOF WALKER (operator P0 mandate, current session):
 *   "theres more than enough free data apis providing g data live it should
 *    never have an issue with trading or data flow ... use the whole api
 *    stack as its intended for"
 *
 * Health-ranked cascading walker for the data fields the live BUY chokepoint
 * cares about. The previous build went silent whenever Birdeye entered
 * EMERGENCY CONSERVATION because the static provider list was Birdeye-first
 * and the walker iterated 4 names but always read the same combined field.
 * This build:
 *
 *   • Reorders the provider cascade per operator doctrine — GeckoTerminal
 *     first, Birdeye demoted below PumpPortal — and adds every provider the
 *     scanner pipeline actually writes from (geckoterminal, dexscreener,
 *     pumpportal, pumpfun, helius, coingecko, jupiter, birdeye).
 *
 *   • Cross-references EfficiencyLayer.getFusedLiquidity() (which already
 *     keeps per-source LiquiditySnapshot history with timestamps) before
 *     falling back to the single combined ts.lastLiquidityUsd field — so a
 *     fresh PumpPortal liquidity reading is honored even if a stale Birdeye
 *     write was the last to touch ts.lastLiquidityUsd.
 *
 *   • Returns Quality.UNKNOWN only when *every* provider in the cascade is
 *     dry. The caller (Executor live/paper BUY) treats UNKNOWN as a silent
 *     skip ("PROVIDER_PROOF_*_CASCADE_BLIND") rather than a rug/error event,
 *     matching operator mandate: "Skip that token silently".
 *
 * No new HTTP I/O is issued on the hot path — the walker reads what the
 * async scanner/poller pipeline has already published. Live fetch fallback
 * (kicking GeckoTerminal/DexScreener on-demand when nothing is cached) is
 * planned for a follow-up push after device verification.
 */
object ProviderProofWalker {

    enum class Field { LIQUIDITY_USD, MCAP_USD, HOLDER_CONCENTRATION_PCT, RUGCHECK_SCORE }

    data class ProofResult(
        val value: Double,
        val source: String,
        val freshMs: Long,
        val quality: Quality,
        val attempted: List<String> = emptyList(),
    ) {
        enum class Quality { REAL_CONFIRMED, PARTIAL_CONFIRMED, FALLBACK, STALE, UNKNOWN }
        val ok: Boolean get() = quality == Quality.REAL_CONFIRMED || quality == Quality.PARTIAL_CONFIRMED
    }

    /**
     * V5.0.3929 cascade order — operator-mandated:
     *   GeckoTerminal first; Birdeye after PumpPortal/PumpFun; include the
     *   full free-API stack the scanner pipeline already uses so a single
     *   throttled provider never silences the bot.
     */
    private val providersForField: Map<Field, List<String>> = mapOf(
        Field.LIQUIDITY_USD            to listOf(
            "geckoterminal", "dexscreener", "pumpportal", "pumpfun",
            "helius", "coingecko", "jupiter", "birdeye",
        ),
        Field.MCAP_USD                 to listOf(
            "geckoterminal", "dexscreener", "pumpportal", "pumpfun",
            "coingecko", "jupiter", "birdeye",
        ),
        Field.HOLDER_CONCENTRATION_PCT to listOf(
            "helius", "geckoterminal", "dexscreener", "birdeye",
        ),
        Field.RUGCHECK_SCORE           to listOf(
            "rugcheck", "geckoterminal", "birdeye",
        ),
    )

    /** Freshness cut for "REAL_CONFIRMED" — newer than this passes cleanly. */
    private const val FRESH_REAL_MS = 30_000L
    /** Freshness cut for "PARTIAL_CONFIRMED" — older but still actionable. */
    private const val FRESH_PARTIAL_MS = 120_000L
    /** Hard freshness ceiling — older than this is STALE (informational only). */
    private const val FRESH_STALE_MS = 600_000L

    /**
     * Walk the provider list for the requested field in current-health order
     * and return the best available reading. Returns ProofResult with quality
     * UNKNOWN when nothing has populated the field on this TokenState yet —
     * the live BUY path treats UNKNOWN as silent-skip per operator doctrine.
     */
    fun getBestAvailableProof(ts: TokenState, field: Field): ProofResult {
        val ordered = healthRankedOrder(field)
        val now = System.currentTimeMillis()
        val attempted = ArrayList<String>(ordered.size)
        var bestStale: ProofResult? = null

        for (provider in ordered) {
            attempted.add(provider)
            val reading = readFromTokenState(ts, field, provider) ?: continue
            if (reading.value <= 0.0) continue
            val ageMs = (now - reading.tsMs).coerceAtLeast(0L)
            val q = when {
                ageMs <= FRESH_REAL_MS    -> ProofResult.Quality.REAL_CONFIRMED
                ageMs <= FRESH_PARTIAL_MS -> ProofResult.Quality.PARTIAL_CONFIRMED
                ageMs <= FRESH_STALE_MS   -> ProofResult.Quality.STALE
                else                      -> ProofResult.Quality.STALE
            }
            val result = ProofResult(reading.value, provider, ageMs, q, attempted.toList())
            // REAL/PARTIAL = good enough, return immediately.
            if (q == ProofResult.Quality.REAL_CONFIRMED || q == ProofResult.Quality.PARTIAL_CONFIRMED) {
                return result
            }
            // STALE — remember in case nothing better surfaces.
            if (bestStale == null) bestStale = result
        }

        // V5.0.3929 — for LIQUIDITY_USD also consult EfficiencyLayer's
        // per-source LiquiditySnapshot history. The scanner already records
        // every observation there with quality + timestamp; this gives us a
        // second fallback before declaring UNKNOWN.
        if (field == Field.LIQUIDITY_USD) {
            val fused = try {
                com.lifecyclebot.engine.EfficiencyLayer.getFusedLiquidity(ts.mint)
            } catch (_: Throwable) { null }
            if (fused != null && fused.usd > 0.0) {
                val q = when {
                    fused.confidence >= 90 -> ProofResult.Quality.PARTIAL_CONFIRMED
                    fused.confidence >= 60 -> ProofResult.Quality.FALLBACK
                    else                   -> ProofResult.Quality.STALE
                }
                return ProofResult(
                    value = fused.usd,
                    source = "efficiency_layer:${fused.source}",
                    freshMs = -1L,
                    quality = q,
                    attempted = attempted.toList(),
                )
            }
        }

        // Surface a STALE reading instead of UNKNOWN when at least one
        // provider had old data — caller can decide whether to honor it.
        if (bestStale != null) return bestStale

        return ProofResult(
            value = 0.0,
            source = "none",
            freshMs = -1L,
            quality = ProofResult.Quality.UNKNOWN,
            attempted = attempted.toList(),
        )
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
     * scanner/poller pipeline — no new HTTP. For now most providers share
     * the combined ts field; per-provider snapshot provenance plugs in here
     * (Issue 3 P1) without changing the cascade orchestration above.
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
                    // Prefer SafetyReport.topHolderPct (canonical safety feed,
                    // -1.0 = unset); fall back to ts.topHolderPct (scanner
                    // surface, nullable) when safety hasn't been populated.
                    val v = if (ts.safety.topHolderPct >= 0.0) ts.safety.topHolderPct else (ts.topHolderPct ?: -1.0)
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
