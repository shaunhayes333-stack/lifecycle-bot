package com.lifecyclebot.engine.truth

/** Persistent rug learning requires a temporal collapse on the SAME chain pool, not two disagreeing API fields.
 * These observations come only from OnChainDepth's decoded account reads. No API price or synthetic candle enters here.
 * Entry safety and protective exits retain their own authority while this proof is unavailable.
 */
object RugChainEvidence8957 {
    internal const val WINDOW_MS = 300_000L
    internal const val FRESH_MS = 15_000L
    private const val MAX_POOLS = 512
    private const val MAX_READS = 8
    internal data class Observation(val atMs: Long, val priceSol: Double, val quoteSol: Double)
    data class Proof(val priceDropPct: Double, val liquidityDropPct: Double)
    private val books = LinkedHashMap<String, ArrayDeque<Observation>>()

    @Synchronized fun record(mint: String, pool: String, priceSol: Double, quoteSol: Double, atMs: Long) {
        if (mint.isBlank() || pool.isBlank() || atMs <= 0L ||
            !priceSol.isFinite() || priceSol <= 0.0 || !quoteSol.isFinite() || quoteSol <= 0.0) return
        val key = "$mint|$pool"
        if (key !in books && books.size >= MAX_POOLS) books.remove(books.keys.first())
        val rows = books.getOrPut(key) { ArrayDeque() }
        if (rows.lastOrNull()?.atMs?.let { atMs <= it } == true) return
        while (rows.firstOrNull()?.let { atMs - it.atMs > WINDOW_MS } == true) rows.removeFirst()
        rows.addLast(Observation(atMs, priceSol, quoteSol))
        while (rows.size > MAX_READS) rows.removeFirst()
    }

    @Synchronized fun proof(mint: String, pool: String, nowMs: Long): Proof? {
        val rows = books["$mint|$pool"] ?: return null
        val current = rows.lastOrNull() ?: return null
        if (nowMs - current.atMs !in 0L..FRESH_MS) return null
        val before = rows.firstOrNull { old ->
            current.atMs - old.atMs in 1L..WINDOW_MS &&
                current.priceSol <= old.priceSol * 0.50 && current.quoteSol <= old.quoteSol * 0.15
        } ?: return null
        return Proof((1.0 - current.priceSol / before.priceSol) * 100.0,
            (1.0 - current.quoteSol / before.quoteSol) * 100.0)
    }

    @Synchronized internal fun resetForTest() { books.clear() }
}
