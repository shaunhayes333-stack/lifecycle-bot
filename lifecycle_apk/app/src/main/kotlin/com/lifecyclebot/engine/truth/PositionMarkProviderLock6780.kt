package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6780 §PER_MINT_STICKY_PROVIDER — proper F6 rebuild after V5.0.6779 rollback.
 *
 * Operator forensic on V5.0.6777 (F6 v1 shipped): promoting DexScreener to primary
 * changed the token-decimal handling on the mark/sell path relative to the buy
 * path. Buy stored quantity at Birdeye-native decimals; sell read DexScreener-
 * native decimals for the same mint → phantom 14x quantity mismatch → phantom
 * -99% catastrophic marks → forced sells at real losses. WR collapsed 42% → 4.3%.
 *
 * Root cause of the decimal skew: swapping the primary mark provider MID-POSITION.
 * Fix at source (this authority): the FIRST successful provider that resolved the
 * BUY-side price for a mint becomes STICKY for that mint's entire position
 * lifecycle. All subsequent mark/sell queries for that mint prefer the same
 * source first; other providers stay available as fallback if the sticky source
 * genuinely fails. Provider fallback ordering can now safely respond to health
 * signals (see F6 v2 reintroduction) because per-mint the source is guaranteed
 * consistent buy → mark → sell.
 *
 * Scope: mint-keyed (canonical Solana mint address). Non-Solana (base|0x*, ETH,
 * BTC, etc.) is ignored — those flow through PriceAggregator symbol identity
 * which does not have the same decimal representation problem.
 *
 * Lifecycle:
 *   - record(mint, source)   on first successful BUY price resolve
 *   - preferredSource(mint)  read on every subsequent mark/sell fetch
 *   - clear(mint)            on terminal SELL (or automatic TTL sweep at 24h)
 */
object PositionMarkProviderLock6780 {

    private data class Entry(val source: String, val sinceMs: Long)

    // ConcurrentHashMap is sufficient — writes are monotonic per mint (recorded
    // once on buy, cleared on sell) and reads are common but idempotent.
    private val locks = ConcurrentHashMap<String, Entry>()
    private val recordCount = AtomicLong(0L)
    private val readCount = AtomicLong(0L)
    private val readHitCount = AtomicLong(0L)
    private val clearCount = AtomicLong(0L)

    // 24h auto-clear guard against zombies (positions that never receive a
    // terminal SELL because of a data pipeline failure).
    private const val LOCK_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /**
     * Record the source that successfully resolved a mint's BUY-side price.
     * Idempotent — first recorded source wins for the position lifecycle.
     * No-op for blank or "STALE_..." sources.
     */
    fun record(mint: String?, source: String?) {
        if (mint.isNullOrBlank() || source.isNullOrBlank()) return
        if (source.startsWith("STALE_") || source == "UNKNOWN") return
        val key = mint.trim()
        recordCount.incrementAndGet()
        locks.putIfAbsent(key, Entry(source, System.currentTimeMillis()))
    }

    /**
     * Preferred source for a mint's mark/sell reads, or null if the mint
     * has no lock (falls through to the default provider ordering).
     */
    fun preferredSource(mint: String?): String? {
        if (mint.isNullOrBlank()) return null
        val e = locks[mint.trim()] ?: return null
        readCount.incrementAndGet()
        // TTL sweep: expire stale locks so a truly abandoned mint doesn't
        // pin the provider forever.
        if (System.currentTimeMillis() - e.sinceMs > LOCK_MAX_AGE_MS) {
            locks.remove(mint.trim(), e)
            return null
        }
        readHitCount.incrementAndGet()
        return e.source
    }

    /**
     * Clear the lock on terminal SELL. Idempotent — safe to call for a mint
     * that was never locked.
     */
    fun clear(mint: String?) {
        if (mint.isNullOrBlank()) return
        if (locks.remove(mint.trim()) != null) clearCount.incrementAndGet()
    }

    /** Diagnostic health line for pipeline snapshots. */
    fun healthLine6780(): String {
        return "POSITION_MARK_PROVIDER_LOCK_6780 locked=${locks.size} " +
            "records=${recordCount.get()} reads=${readCount.get()} " +
            "readHits=${readHitCount.get()} clears=${clearCount.get()}"
    }

    internal fun resetForTest() {
        locks.clear()
        recordCount.set(0L); readCount.set(0L); readHitCount.set(0L); clearCount.set(0L)
    }
}
