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

    /** TTL for snipe mode (seconds). */
    const val SNIPE_TTL_SEC: Long = 60
    /** TTL for non-snipe modes (seconds). */
    const val NORMAL_TTL_SEC: Long = 600

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
     */
    fun sweepStale(snipeModeOn: Boolean): Int {
        val ttlMs = (if (snipeModeOn) SNIPE_TTL_SEC else NORMAL_TTL_SEC) * 1000L
        val cutoff = System.currentTimeMillis() - ttlMs
        var removed = 0
        for ((k, v) in entries) {
            if (v.ts < cutoff) {
                entries.remove(k, v)
                removed++
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
