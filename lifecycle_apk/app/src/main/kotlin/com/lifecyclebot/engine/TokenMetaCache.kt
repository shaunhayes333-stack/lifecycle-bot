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
        // V5.0.6908 §EVERYTHING_NEEDED_FOR_PROPER_PRICE_TRACKING.
        // Token decimals are not cosmetic metadata — they are the unit in
        // which every price is denominated. OpenPnlSanity's §6701
        // decimal-scale discontinuity guard (the authority that catches a
        // raw-vs-UI token pricing mismatch, i.e. a mark that jumped by
        // 10^decimals) takes tokenDecimals as input, and when it is absent
        // it degrades to GUESSING 6 or 9 and only for sub-micro entries.
        // A token restored from archive with no decimals therefore arrives
        // with the unit guard weakened. Archive it like the mint address:
        // once known, never re-fetched, and shared across the hive.
        var decimals: Int = -1,
        // V5.0.6908 §ARCHIVED_UNTIL_INTERACTED_WITH_AGAIN.
        // Wallclock of the last time AATE actually executed against this
        // mint (buy/sell/partial), as opposed to merely observing it in a
        // scan. Retention is keyed on interaction, not on scan recency:
        // pruneStale and evictColdSoft both filter purely on lastSeenMs and
        // hitCount, so a token the bot traded and then stopped scanning was
        // eligible for deletion in 7 days — taking its pool address, dex,
        // decimals and creation time with it, and forcing a full rebuild
        // (and a fresh provider spend) if the bot ever touched it again.
        var lastInteractedMs: Long = 0L,
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
                "hit_count INTEGER NOT NULL DEFAULT 0," +
                "decimals INTEGER NOT NULL DEFAULT -1," +
                "last_interacted_ms INTEGER NOT NULL DEFAULT 0" +
                ");"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meta_last_seen ON token_meta(last_seen_ms DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meta_hit_count ON token_meta(hit_count DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meta_interacted ON token_meta(last_interacted_ms DESC);")
    }

    /**
     * V5.0.6908 §THE_ARCHIVE_MUST_SURVIVE_ITS_OWN_SCHEMA_CHANGES.
     *
     * This used to be `DROP TABLE token_meta; onCreate(db)` — every single
     * DB_VERSION bump silently destroyed the entire accumulated token
     * archive. That is the exact opposite of a durable store that "saves
     * data rebuilding across the hive": the first schema change threw away
     * every pool address, dex, creation time and pair URL the fleet had ever
     * paid a provider call to learn, and the bot then re-earned all of it
     * from scratch at full CU + latency cost.
     *
     * Additive-only migration, the same pattern CollectiveSchema already
     * uses for the hive-side table. Each statement is idempotent and each is
     * tried independently, so a column that already exists (or a partially
     * applied prior upgrade) cannot abort the rest of the migration and
     * cannot cost us the table.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db) // CREATE TABLE IF NOT EXISTS — no-op on an existing archive
        val additive = listOf(
            "ALTER TABLE token_meta ADD COLUMN decimals INTEGER NOT NULL DEFAULT -1",
            "ALTER TABLE token_meta ADD COLUMN last_interacted_ms INTEGER NOT NULL DEFAULT 0",
        )
        var applied = 0
        for (stmt in additive) {
            try { db.execSQL(stmt); applied++ } catch (_: Throwable) { /* already present */ }
        }
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_meta_interacted ON token_meta(last_interacted_ms DESC);")
        } catch (_: Throwable) {}
        ErrorLogger.info(
            TAG,
            "onUpgrade $oldVersion->$newVersion applied=$applied/${additive.size} additive columns; archive preserved",
        )
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // A downgrade must not destroy the archive either. Newer columns are
        // simply unread by older code; SQLiteOpenHelper's default behaviour
        // here is to throw, which would make the store unopenable.
        ErrorLogger.warn(TAG, "onDowngrade $oldVersion->$newVersion ignored; archive preserved")
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
                    "creation_time_ms, first_seen_ms, last_seen_ms, hit_count, " +
                    "decimals, last_interacted_ms " +
                    // V5.0.6908 — interacted rows load first and are never
                    // truncated by the row cap: a token the bot actually traded
                    // is the one whose archived pool/dex/decimals we most need
                    // back, and it is exactly the row that stops being scanned
                    // and so sorts last under lastSeenMs alone.
                    "FROM token_meta ORDER BY (last_interacted_ms > 0) DESC, " +
                    "last_interacted_ms DESC, last_seen_ms DESC LIMIT ?",
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
                        decimals = c.getInt(17),
                        lastInteractedMs = c.getLong(18),
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
        // V5.0.6908 — token decimals. Archived once, never re-fetched.
        decimals: Int? = null,
        // V5.0.6908 — true only when AATE EXECUTED against this mint
        // (buy/sell/partial), not when it merely observed it. Stamps the
        // retention exemption; observation alone must not, or every scanned
        // mint would become permanently unprunable.
        interacted: Boolean = false,
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
        // Decimals are immutable for an SPL mint, so a known value is never
        // overwritten by a later unknown one — and a CHANGE is a genuine
        // integrity event worth surfacing rather than silently accepting,
        // because a decimals flip is precisely what §6701 reads as a unit
        // discontinuity in the price feed.
        if (decimals != null && decimals >= 0 && decimals != e.decimals) {
            if (e.decimals >= 0) {
                try {
                    PipelineHealthCollector.labelInc("TOKEN_META_DECIMALS_CONFLICT_6908")
                    ErrorLogger.warn(TAG, "decimals conflict mint=${key.take(10)} archived=${e.decimals} offered=$decimals (keeping archived)")
                } catch (_: Throwable) {}
            } else {
                e.decimals = decimals; changed = true
            }
        }
        if (interacted) { e.lastInteractedMs = now; changed = true }
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
                            put("decimals", e.decimals)
                            put("last_interacted_ms", e.lastInteractedMs)
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

    /**
     * Reap rows older than ageMs that were seen fewer than minHitsToKeep times.
     *
     * V5.0.6908 §ARCHIVED_UNTIL_INTERACTED_WITH_AGAIN — a mint AATE has
     * actually executed against is exempt, in memory and in SQL. Retention
     * was keyed purely on scan recency, which inverted the priority: a token
     * the bot bought, closed and stopped scanning went cold immediately and
     * was deleted in 7 days, while thousands of never-traded scanner rows
     * that happened to keep reappearing were kept. The traded row is the one
     * whose pool/dex/decimals/creation time we most want on the next
     * encounter, and the one whose loss costs a full provider rebuild.
     */
    fun pruneStale(ageMs: Long = 7L * 24L * 3600_000L, minHitsToKeep: Long = 5L): Int {
        var removed = 0
        val cutoff = System.currentTimeMillis() - ageMs
        val victims = live.values.asSequence()
            .filter { it.lastInteractedMs <= 0L && it.lastSeenMs < cutoff && it.hitCount < minHitsToKeep }
            .map { it.mint }
            .toList()
        for (m in victims) { live.remove(m); dirty.remove(m); removed++ }
        synchronized(writeLock) {
            try {
                val db = writableDatabase
                db.delete("token_meta",
                    "last_interacted_ms <= 0 AND last_seen_ms < ? AND hit_count < ?",
                    arrayOf(cutoff.toString(), minHitsToKeep.toString()))
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "pruneStale failed: ${t.message}")
            }
        }
        if (live.size > MAX_LIVE_ROWS) {
            // Interacted rows are kept ahead of the cap, then the warmest of
            // the rest fill the remainder.
            val interacted = live.values.asSequence().filter { it.lastInteractedMs > 0L }.map { it.mint }.toSet()
            val room = (MAX_LIVE_ROWS - interacted.size).coerceAtLeast(0)
            val keep = interacted + live.values.asSequence()
                .filter { it.lastInteractedMs <= 0L }
                .sortedByDescending { it.lastSeenMs }
                .take(room)
                .map { it.mint }
                .toSet()
            val drops = live.keys.filter { it !in keep }
            for (m in drops) { live.remove(m); dirty.remove(m); removed++ }
        }
        if (removed > 0) ErrorLogger.info(TAG, "pruneStale removed $removed rows")
        return removed
    }

    /**
     * V5.9.1470 (spec item 10) — SOFT COLD EVICTION. The hard 50k cap rarely fires;
     * the operator wants cold rows evicted sooner once the live set grows past a soft
     * threshold so the cache stays warm-and-relevant rather than bloated. Evicts the
     * coldest (oldest lastSeen) low-hit rows down toward softMax. NEVER touches the
     * protected 500-token scanner intake pool (that is GlobalTradeRegistry, a different
     * store) and never drops high-hit (frequently-reused) rows. Best-effort, memory-only
     * plus a cheap DB delete; safe to call on the 60s flush tick.
     */
    fun evictColdSoft(softMax: Int = 2500, minHitsToKeep: Long = 3L): Int {
        if (live.size <= softMax) return 0
        val excess = live.size - softMax
        // Candidates: low-hit rows only, coldest first. High-hit rows are sticky.
        val victims = live.values.asSequence()
            // V5.0.6908 — never evict a mint AATE has executed against. Same
            // exemption as pruneStale; this path runs on the 60s flush tick
            // with softMax=2500, so it was by far the likelier of the two to
            // delete a traded token's archived pool/dex/decimals.
            .filter { it.lastInteractedMs <= 0L && it.hitCount < minHitsToKeep }
            .sortedBy { it.lastSeenMs }
            .take(excess)
            .map { it.mint }
            .toList()
        if (victims.isEmpty()) return 0
        for (m in victims) { live.remove(m); dirty.remove(m) }
        synchronized(writeLock) {
            try {
                val db = writableDatabase
                val chunk = victims.joinToString(",") { "'" + it.replace("'", "") + "'" }
                if (chunk.isNotBlank()) db.execSQL("DELETE FROM token_meta WHERE mint IN ($chunk)")
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "evictColdSoft db delete failed: ${t.message}")
            }
        }
        ErrorLogger.info(TAG, "evictColdSoft removed ${victims.size} cold rows (live=${live.size} softMax=$softMax)")
        return victims.size
    }

    data class Snapshot(
        val liveRows: Int,
        val dirtyRows: Int,
        val totalReadHits: Long,
        val totalReadMisses: Long,
        val totalWrites: Long,
        val hitRatePct: Double,
        // V5.0.6908 — archive completeness. decimalsKnown answers "is the
        // unit guard actually armed for the rows we hold?"; interactedRows
        // answers "how much of the archive is protected from eviction?".
        val decimalsKnown: Int = 0,
        val interactedRows: Int = 0,
        val pairAddressKnown: Int = 0,
    )

    fun snapshot(): Snapshot {
        val hits = totalReadHits.get()
        val misses = totalReadMisses.get()
        val denom = (hits + misses).coerceAtLeast(1L)
        var decimalsKnown = 0
        var interactedRows = 0
        var pairKnown = 0
        for (e in live.values) {
            if (e.decimals >= 0) decimalsKnown++
            if (e.lastInteractedMs > 0L) interactedRows++
            if (e.pairAddress.isNotBlank()) pairKnown++
        }
        return Snapshot(
            liveRows = live.size,
            dirtyRows = dirty.size,
            totalReadHits = hits,
            totalReadMisses = misses,
            totalWrites = totalWrites.get(),
            hitRatePct = hits * 100.0 / denom,
            decimalsKnown = decimalsKnown,
            interactedRows = interactedRows,
            pairAddressKnown = pairKnown,
        )
    }

    companion object {
        private const val TAG = "TokenMetaCache"
        private const val DB_NAME = "lifecycle_token_meta.db"
        // V5.0.6908 — 1 -> 2 adds decimals + last_interacted_ms. Safe to bump
        // now that onUpgrade migrates additively instead of dropping the table.
        private const val DB_VERSION = 2
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
