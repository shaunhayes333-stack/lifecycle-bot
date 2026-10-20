package com.lifecyclebot.engine.truth

/**
 * V5.0.8239 §THE_SHADOW_BOOK_LEARNS_FROM_REAL_PRICES.
 *
 * 8241 snapshot, 221 s of uptime: shadow closes=8 wins=0 avgPnlPct=-29.79 — eight "losses" of ~30% inside four minutes,
 * on coins the live gate had just refused for incomplete data. Those are not trades that lost; they are entries booked
 * at a price nobody observed (stale scanner print, synthetic seed, UNKNOWN provenance) and marked seconds later from the
 * real chain. Every one of them was fed to the learners as a loss. Two pure rules, applied in the Executor's shadow book:
 *  - an open needs an OBSERVED basis (a named non-synthetic source, fresh);
 *  - a first mark that contradicts the basis by more than a real coin moves in that time re-bases the entry instead of
 *    booking a phantom stop — the book then records what the coin did from a price that existed.
 */
object ShadowBasisTruth8239 {
    private const val FRESH_MS_8239 = 45_000L
    private const val REBASE_WINDOW_MS_8239 = 90_000L
    private const val REBASE_GAP_PCT_8239 = 20.0
    private val UNOBSERVED_SOURCES = listOf("", "UNKNOWN", "SYNTH", "SEED", "DEFAULT", "CACHE", "TOKEN_MAP", "INTAKE_SEED", "ESTIMATE")

    /** Pure: may a shadow position open at a price with this provenance? */
    fun observedBasis8239(priceUsd: Double, source: String, priceAgeMs: Long): Boolean {
        if (!priceUsd.isFinite() || priceUsd <= 0.0) return false
        if (priceAgeMs !in 0L..FRESH_MS_8239) return false
        val s = source.trim().uppercase()
        return UNOBSERVED_SOURCES.none { it.isEmpty() && s.isEmpty() || it.isNotEmpty() && s.contains(it) }
    }

    /** Pure: within the window, a first mark this far below (or above) the basis is a basis error, not a move. */
    fun basisContradicted8239(entryPrice: Double, markPrice: Double, heldMs: Long): Boolean {
        if (!entryPrice.isFinite() || !markPrice.isFinite() || entryPrice <= 0.0 || markPrice <= 0.0) return false
        if (heldMs !in 0L..REBASE_WINDOW_MS_8239) return false
        val gapPct = kotlin.math.abs(markPrice / entryPrice - 1.0) * 100.0
        return gapPct >= REBASE_GAP_PCT_8239
    }
}
