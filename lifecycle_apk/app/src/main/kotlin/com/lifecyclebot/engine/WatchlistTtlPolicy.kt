package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z31 — Watchlist TTL policy.
 *
 * Operator-reported bloat: watchlist climbing 65 → 71 → 73 → 74 → 75
 * with stale candidates being re-processed every loop, killing speed.
 *
 * This is a lightweight TTL tracker the watchlist owner can call
 * after each scan to drop stale entries:
 *
 *   - mark(symbol, scoreSnapshot) when (re)added
 *   - sweepStale() at end of each loop to remove ones older than TTL
 *   - dedupeByMint(...) is a helper for callers that want to dedupe
 *
 * In snipe mode, TTL is short (60s) so the hot list stays tight.
 */
object WatchlistTtlPolicy {

    /** TTL for snipe mode (seconds).
     *  V5.9.495z32 — operator directive: "we purge tokens way too
     *  quickly to find the max opportunities" — bumped from 60s →
     *  300s (5 min) so candidates that need a couple of ticks to
     *  ripen aren't dropped. */
    const val SNIPE_TTL_SEC: Long = 300
    /** TTL for non-snipe modes (seconds). z32 — bumped from 600s →
     *  1800s (30 min). */
    const val NORMAL_TTL_SEC: Long = 1800

    private data class Entry(val symbol: String, val ts: Long, val score: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun mark(symbol: String, score: Int) {
        entries[symbol.uppercase()] = Entry(symbol.uppercase(), System.currentTimeMillis(), score)
    }

    fun snapshot(): List<Pair<String, Int>> =
        entries.values.sortedByDescending { it.score }.map { it.symbol to it.score }

    /**
     * Removes any entries older than the given TTL. Returns the
     * number of expired entries.
     *
     * V5.9.663e — adaptive saturation sweep. The base TTL (300s snipe /
     * 1800s normal) is the operator's preferred "don't purge too quick"
     * pace, but when the watchlist is saturated (>= 200 entries, the
     * same threshold the scanner backpressure uses) we shorten TTL so
     * dead/stale tokens drain faster and make room for fresh candidates
     * the scanner is currently rate-limiting itself on. Pairs with the
     * SolanaMarketScanner backpressure: once watchlist drops back below
     * 150 the full TTL returns and full-speed scanning resumes.
     *
     *   size <  150          → full TTL (operator-preferred slow purge)
     *   size in [150, 200)   → 50% TTL  (early drain mode)
     *   size >= 200          → 25% TTL  (saturation drain mode)
     */
    fun sweepStale(snipeModeOn: Boolean): Int {
        val baseTtlMs = (if (snipeModeOn) SNIPE_TTL_SEC else NORMAL_TTL_SEC) * 1000L
        val sz = entries.size
        val ttlMs = when {
            sz >= 200 -> baseTtlMs / 4
            sz >= 150 -> baseTtlMs / 2
            else      -> baseTtlMs
        }
        val cutoff = System.currentTimeMillis() - ttlMs
        var removed = 0
        for ((k, v) in entries) {
            if (v.ts < cutoff) {
                if (entries.remove(k, v)) {
                    removed++
                    // V5.9.495z34 — surface expirations on the Meme tab
                    // tile so operators see candidates ageing out (vs.
                    // being deferred or background-classed).
                    try {
                        DeferActivityTracker.record(
                            DeferActivityTracker.Kind.EXPIRED, k
                        )
                    } catch (_: Throwable) { /* best-effort */ }
                }
            }
        }
        return removed
    }

    /** Caller-supplied dedupe by mint string. Keeps the highest-score
     *  entry for each unique mint and drops the rest. */
    fun dedupeByMint(mints: Map<String, String>): Int {
        // mints: symbol -> mint (caller resolves)
        val byMint = HashMap<String, Pair<String, Int>>()  // mint -> (symbol, score)
        for ((sym, mint) in mints) {
            val e = entries[sym.uppercase()] ?: continue
            val current = byMint[mint]
            if (current == null || current.second < e.score) {
                byMint[mint] = sym.uppercase() to e.score
            }
        }
        // Keep only the survivors
        val survivors = byMint.values.map { it.first }.toSet()
        var removed = 0
        for ((sym, _) in entries.toMap()) {
            if (sym !in survivors && sym in mints.keys.map { it.uppercase() }.toSet()) {
                entries.remove(sym)
                removed++
            }
        }
        return removed
    }

    fun size(): Int = entries.size
    fun clear() { entries.clear() }
}
