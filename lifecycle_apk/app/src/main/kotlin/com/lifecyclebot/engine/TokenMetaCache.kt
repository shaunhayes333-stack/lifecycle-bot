package com.lifecyclebot.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.948 — TokenMetaCache.
 *
 * Persistent disk-backed metadata cache for tokens the bot has seen
 * before. Survives APK restarts so the bot doesn't re-pay (CU + latency)
 * to rediscover pool addresses, symbols, logo URLs, creation timestamps,
 * and slow-moving snapshots like liquidity / mcap on warm boot.
 *
 * Hot read path goes through an in-memory ConcurrentHashMap. Writes are
 * batched into SQLite by a background flush every 60s + on shutdown.
 * Best-effort: a miss/failure NEVER blocks the hot scanner loop.
 *
 * Doctrine: #20 (architectural contract — never gate execution),
 * #87.13 (bot staying alive). No paid endpoint is ever called from here.
 */
class TokenMetaCache private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Entry(
        val mint: String,
        var symbol: String = "",
        var name: String = "",
        var pairAddress: String = "",
        var pairUrl: String = "",
        var logoUrl: String = "",
        var lastPriceSource: String = "",
        var lastPricePoolAddr: String = "",
        var lastPriceDex: String = "",
        var lastPrice: Double = 0.0,
        var lastMcap: Double = 0.0,
        var lastLiquidityUsd: Double = 0.0,
        var lastFdv: Double = 0.0,
        var creationTimeMs: Long = 0L,
        var firstSeenMs: Long = 0L,
        var lastSeenMs: Long = 0L,
        var hitCount: Long = 0L,
    )

    private val live = ConcurrentHashMap<String, Entry>(8192)
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    private val writeLock = Any()
    private val loaded = AtomicBoolean(false)
    private val totalReadHits = AtomicLong(0L)
    private val totalReadMisses = AtomicLong(0L)
    private val totalWrites = AtomicLong(0L)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try { db.enableWriteAheadLogging() } catch (_: Throwable) {}
        try { db.execSQL("PRAGMA busy_timeout = 3000;") } catch (_: Throwable) {}
        try { db.execSQL("PRAGMA synchronous = NORMAL;") } catch (_: Throwable) {}
        try { db.execSQL("PRAGMA temp_store = MEMORY;") } catch (_: Throwable) {}
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS token_meta (" +
                "mint TEXT PRIMARY KEY," +
                "symbol TEXT NOT NULL DEFAULT ''," +
                "name TEXT NOT NULL DEFAULT ''," +
                "pair_address TEXT NOT NULL DEFAULT ''," +
                "pair_url TEXT NOT NULL DEFAULT ''," +
                "logo_url TEXT NOT NULL DEFAULT ''," +
                "last_price_source TEXT NOT NULL DEFAULT ''," +
                "last_price_pool_addr TEXT NOT NULL DEFAULT ''," +
                "last_price_dex TEXT NOT NULL DEFAULT ''," +
                "last_price REAL NOT NULL DEFAULT 0," +
                "last_mcap REAL NOT NULL DEFAULT 0," +
                "last_liquidity_usd REAL NOT NULL DEFAULT 0," +
                "last_fdv REAL NOT NULL DEFAULT 0," +
                "creation_time_ms INTEGER NOT NULL DEFAULT 0," +
                "first_seen_ms INTEGER NOT NULL DEFAULT 0," +
                "last_seen_ms INTEGER NOT NULL DEFAULT 0," +
                "hit_count INTEGER NOT NULL DEFAULT 0" +
                ");"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meta_last_seen ON token_meta(last_seen_ms DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meta_hit_count ON token_meta(hit_count DESC);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS token_meta;")
        onCreate(db)
    }

    /**
     * Bulk-load the persistent table into memory. Idempotent — only the
     * first call does work. Returns number of rows hydrated.
     */
    fun warmStart(maxRows: Int = 50_000): Int {
        if (!loaded.compareAndSet(false, true)) return live.size
        var hydrated = 0
        try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT mint, symbol, name, pair_address, pair_url, logo_url, " +
                    "last_price_source, last_price_pool_addr, last_price_dex, " +
                    "last_price, last_mcap, last_liquidity_usd, last_fdv, " +
                    "creation_time_ms, first_seen_ms, last_seen_ms, hit_count " +
                    "FROM token_meta ORDER BY last_seen_ms DESC LIMIT ?",
                arrayOf(maxRows.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val mint = com.lifecyclebot.data.CanonicalMint.normalize(c.getString(0) ?: continue)
                    if (mint.isEmpty()) continue
                    val e = Entry(
                        mint = mint,
                        symbol = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        pairAddress = c.getString(3) ?: "",
                        pairUrl = c.getString(4) ?: "",
                        logoUrl = c.getString(5) ?: "",
                        lastPriceSource = c.getString(6) ?: "",
                        lastPricePoolAddr = c.getString(7) ?: "",
                        lastPriceDex = c.getString(8) ?: "",
                        lastPrice = c.getDouble(9),
                        lastMcap = c.getDouble(10),
                        lastLiquidityUsd = c.getDouble(11),
                        lastFdv = c.getDouble(12),
                        creationTimeMs = c.getLong(13),
                        firstSeenMs = c.getLong(14),
                        lastSeenMs = c.getLong(15),
                        hitCount = c.getLong(16),
                    )
                    live[mint] = e
                    hydrated++
                }
            }
            ErrorLogger.info(TAG, "warmStart hydrated $hydrated rows from disk")
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "warmStart failed: ${t.message}")
        }
        return hydrated
    }

    /** Hot path read. Returns null on miss. Never touches disk. */
    fun lookup(mint: String): Entry? {
        val key = com.lifecyclebot.data.CanonicalMint.normalize(mint)
        if (key.isEmpty()) return null
        val hit = live[key]
        if (hit != null) { totalReadHits.incrementAndGet(); return hit }
        totalReadMisses.incrementAndGet()
        return null
    }

    /**
     * Register a snapshot. Cheap — writes only to memory + marks dirty.
     * Null/blank fields are IGNORED so partial updates don't stomp richer
     * data from prior sources.
     */
    fun register(
        mint: String,
        symbol: String? = null,
        name: String? = null,
        pairAddress: String? = null,
        pairUrl: String? = null,
        logoUrl: String? = null,
        lastPriceSource: String? = null,
        lastPricePoolAddr: String? = null,
        lastPriceDex: String? = null,
        lastPrice: Double? = null,
        lastMcap: Double? = null,
        lastLiquidityUsd: Double? = null,
        lastFdv: Double? = null,
        creationTimeMs: Long? = null,
    ) {
        val key = com.lifecyclebot.data.CanonicalMint.normalize(mint)
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val e = live.computeIfAbsent(key) { Entry(mint = key, firstSeenMs = now) }
        var changed = false
        if (symbol != null && symbol.isNotBlank() && symbol != e.symbol) { e.symbol = symbol; changed = true }
        if (name != null && name.isNotBlank() && name != e.name) { e.name = name; changed = true }
        if (pairAddress != null && pairAddress.isNotBlank() && pairAddress != e.pairAddress) { e.pairAddress = pairAddress; changed = true }
        if (pairUrl != null && pairUrl.isNotBlank() && pairUrl != e.pairUrl) { e.pairUrl = pairUrl; changed = true }
        if (logoUrl != null && logoUrl.isNotBlank() && logoUrl != e.logoUrl) { e.logoUrl = logoUrl; changed = true }
        if (lastPriceSource != null && lastPriceSource.isNotBlank() && lastPriceSource != e.lastPriceSource) { e.lastPriceSource = lastPriceSource; changed = true }
        if (lastPricePoolAddr != null && lastPricePoolAddr.isNotBlank() && lastPricePoolAddr != e.lastPricePoolAddr) { e.lastPricePoolAddr = lastPricePoolAddr; changed = true }
        if (lastPriceDex != null && lastPriceDex.isNotBlank() && lastPriceDex != e.lastPriceDex) { e.lastPriceDex = lastPriceDex; changed = true }
        if (lastPrice != null && lastPrice > 0.0 && lastPrice != e.lastPrice) { e.lastPrice = lastPrice; changed = true }
        if (lastMcap != null && lastMcap > 0.0 && lastMcap != e.lastMcap) { e.lastMcap = lastMcap; changed = true }
        if (lastLiquidityUsd != null && lastLiquidityUsd > 0.0 && lastLiquidityUsd != e.lastLiquidityUsd) { e.lastLiquidityUsd = lastLiquidityUsd; changed = true }
        if (lastFdv != null && lastFdv > 0.0 && lastFdv != e.lastFdv) { e.lastFdv = lastFdv; changed = true }
        if (creationTimeMs != null && creationTimeMs > 0L && creationTimeMs != e.creationTimeMs) { e.creationTimeMs = creationTimeMs; changed = true }
        e.lastSeenMs = now
        e.hitCount += 1L
        if (changed || (e.hitCount % FLUSH_EVERY_N_HITS == 0L)) dirty.add(key)
    }

    /** Persist all dirty rows. Safe from any thread. Returns rows flushed. */
    fun flushNow(): Int {
        if (dirty.isEmpty()) return 0
        val snapshot = HashSet(dirty)
        dirty.removeAll(snapshot)
        var written = 0
        synchronized(writeLock) {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    for (mint in snapshot) {
                        val e = live[mint] ?: continue
                        val cv = ContentValues().apply {
                            put("mint", e.mint)
                            put("symbol", e.symbol)
                            put("name", e.name)
                            put("pair_address", e.pairAddress)
                            put("pair_url", e.pairUrl)
                            put("logo_url", e.logoUrl)
                            put("last_price_source", e.lastPriceSource)
                            put("last_price_pool_addr", e.lastPricePoolAddr)
                            put("last_price_dex", e.lastPriceDex)
                            put("last_price", e.lastPrice)
                            put("last_mcap", e.lastMcap)
                            put("last_liquidity_usd", e.lastLiquidityUsd)
                            put("last_fdv", e.lastFdv)
                            put("creation_time_ms", e.creationTimeMs)
                            put("first_seen_ms", e.firstSeenMs)
                            put("last_seen_ms", e.lastSeenMs)
                            put("hit_count", e.hitCount)
                        }
                        db.insertWithOnConflict("token_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        written++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    try { db.endTransaction() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "flushNow failed (${snapshot.size} rows): ${t.message}")
                dirty.addAll(snapshot)
                return 0
            }
        }
        if (written > 0) totalWrites.addAndGet(written.toLong())
        return written
    }

    /** Reap rows older than ageMs that were seen fewer than minHitsToKeep times. */
    fun pruneStale(ageMs: Long = 7L * 24L * 3600_000L, minHitsToKeep: Long = 5L): Int {
        var removed = 0
        val cutoff = System.currentTimeMillis() - ageMs
        val victims = live.values.asSequence()
            .filter { it.lastSeenMs < cutoff && it.hitCount < minHitsToKeep }
            .map { it.mint }
            .toList()
        for (m in victims) { live.remove(m); dirty.remove(m); removed++ }
        synchronized(writeLock) {
            try {
                val db = writableDatabase
                db.delete("token_meta",
                    "last_seen_ms < ? AND hit_count < ?",
                    arrayOf(cutoff.toString(), minHitsToKeep.toString()))
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "pruneStale failed: ${t.message}")
            }
        }
        if (live.size > MAX_LIVE_ROWS) {
            val keep = live.values.asSequence()
                .sortedByDescending { it.lastSeenMs }
                .take(MAX_LIVE_ROWS)
                .map { it.mint }
                .toSet()
            val drops = live.keys.filter { it !in keep }
            for (m in drops) { live.remove(m); dirty.remove(m); removed++ }
        }
        if (removed > 0) ErrorLogger.info(TAG, "pruneStale removed $removed rows")
        return removed
    }

    data class Snapshot(
        val liveRows: Int,
        val dirtyRows: Int,
        val totalReadHits: Long,
        val totalReadMisses: Long,
        val totalWrites: Long,
        val hitRatePct: Double,
    )

    fun snapshot(): Snapshot {
        val hits = totalReadHits.get()
        val misses = totalReadMisses.get()
        val denom = (hits + misses).coerceAtLeast(1L)
        return Snapshot(
            liveRows = live.size,
            dirtyRows = dirty.size,
            totalReadHits = hits,
            totalReadMisses = misses,
            totalWrites = totalWrites.get(),
            hitRatePct = hits * 100.0 / denom,
        )
    }

    companion object {
        private const val TAG = "TokenMetaCache"
        private const val DB_NAME = "lifecycle_token_meta.db"
        private const val DB_VERSION = 1
        private const val MAX_LIVE_ROWS = 50_000
        private const val FLUSH_EVERY_N_HITS = 32L

        @Volatile private var INSTANCE: TokenMetaCache? = null

        /** Non-throwing snapshot for telemetry. Returns null if cache not yet initialized. */
        fun snapshotIfPresent(): Snapshot? = INSTANCE?.snapshot()

        fun get(ctx: Context): TokenMetaCache {
            val existing = INSTANCE
            if (existing != null) return existing
            return synchronized(this) {
                val again = INSTANCE
                if (again != null) {
                    again
                } else {
                    val fresh = TokenMetaCache(ctx)
                    INSTANCE = fresh
                    // V5.9.953 — eager warmStart on first get(). Pre-V5.9.953 the
                    // BotService kicked warmStart on an async Thread, but by the
                    // time the thread won the SQLite read race, ~1600 scanner-side
                    // lookups had already missed and re-paid CU+latency on data
                    // we already had on disk. The pipeline-health dump showed
                    // 0% hit rate / 1607 misses / 3390 writes — pure churn.
                    // Eager warm: idempotent (loaded.compareAndSet), 50-200ms cold
                    // SQLite open, runs ONCE on first get() call (which is off
                    // the main thread because it's invoked from the BotService
                    // coroutine context). Acceptable trade for permanent persistence.
                    try { fresh.warmStart() } catch (_: Throwable) { /* fail-open */ }
                    fresh
                }
            }
        }
    }
}
