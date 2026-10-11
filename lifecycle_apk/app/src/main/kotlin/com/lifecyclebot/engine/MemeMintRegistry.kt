package com.lifecyclebot.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.495z25 — Meme Mint Registry (parallel to DynamicAltTokenRegistry).
 *
 * Operator: "we need to do the same for memes that make the watchlist and
 * scanner as well. persistent token mint registry to avoid having to reload
 * data constantly and for better consistency across trades."
 *
 * The meme-lane scanner historically dumped freshly-discovered pump.fun mints
 * into `BotStatus.tokens` (in-memory only). On every restart that universe was
 * gone — the bot had to re-discover everything from PumpPortal WS / Birdeye
 * trending / DexScreener boosts. That inconsistency meant winning candidates
 * sometimes vanished from the universe between sessions.
 *
 * This registry is **append-only metadata** about meme mints the scanner has
 * vetted. It does NOT store live price, position, or execution state — those
 * remain in `status.tokens` because they're hot-path. What it DOES store:
 *
 *   • mint, symbol, name, source ("pumpfun" / "birdeye_trending" / etc.)
 *   • firstSeenMs, lastSeenMs (touch updated when scanner re-confirms)
 *   • optional gradeAtFirstSeen ("FRESH_PUMP" / "TRENDING" / "DRY_LIQUIDITY")
 *
 * Persisted to `filesDir/meme_mint_registry.json` (debounced 5s save).
 * 1-hour-staleness for placeholder symbols, 14-day retention for mints with
 * any verified scanner sighting.
 */
object MemeMintRegistry {

    private const val TAG = "MemeMintRegistry"
    private const val PERSIST_FILE = "meme_mint_registry.json"
    // V5.0.8033 — the registry was the heap. 5.0.8031's census: meme_mint_registry.json = 23,371 KB on disk (14 days of
    // every pump.fun mint, unbounded), the heap at 511/512 MB, OOM after 4 h. The whole map lived in memory and was
    // re-serialised into ONE string (2x the file in UTF-16, plus a JSONObject per row) every 5 seconds a mint was
    // touched. Now: at most [MAX_MINTS_8033] mints (most recently seen kept), 3 days, saved at most once a minute and
    // streamed row by row; a file too big to parse safely is set aside instead of loaded.
    private const val PERSIST_DEBOUNCE_MS = 60_000L
    private const val MINT_RETENTION_MS = 3L * 24 * 60 * 60_000L
    const val MAX_MINTS_8033 = 5_000
    const val MAX_RESTORE_BYTES_8033 = 4L * 1024 * 1024

    data class MemeMint(
        val mint: String,
        val symbol: String,
        val name: String,
        val source: String,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var sightings: Int,
        val gradeAtFirstSeen: String,
    )

    private val registry = ConcurrentHashMap<String, MemeMint>(2048)
    @Volatile private var appCtx: Context? = null
    private val persistDirty = AtomicBoolean(false)
    @Volatile private var persistJob: Job? = null
    private val persistLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── public API ──────────────────────────────────────────────────────────

    /** Hydrate from disk on bot start. Idempotent. */
    fun init(context: Context) {
        // V5.9.1036 — operator V5.9.1034b ANR triage: restoreFromDisk parses
        // the persisted 2500+ mint registry (511KB JSON) on the caller's
        // thread and was the #2 main-thread blocker at boot (2185ms ANR
        // sample). Hydrate the appCtx synchronously (required for the
        // synchronous-touch APIs to schedule saves) but parse the registry
        // off-main. isKnown / touch / touchBulk all guard against a missing
        // ConcurrentHashMap entry by behaving as a fresh first-sighting, so
        // pre-restore scanner ticks degrade safely to "unknown mint".
        appCtx = context.applicationContext
        scope.launch {
            try {
                restoreFromDisk()
                ErrorLogger.info(TAG, "init: ${registry.size} meme mints loaded from persistent registry (off-main)")
            } catch (e: Throwable) {
                ErrorLogger.warn(TAG, "init restoreFromDisk error: ${e.message}")
            }
        }
    }

    fun count(): Int = registry.size

    /** True iff this mint has been observed by the scanner before. */
    fun isKnown(mint: String): Boolean = registry.containsKey(mint)

    fun getAll(): List<MemeMint> = registry.values.toList()
    fun get(mint: String): MemeMint? = registry[mint]

    /**
     * Touch a mint observed by the scanner. If new, registers it; otherwise
     * bumps `lastSeenMs` and `sightings`. Triggers a debounced save.
     */
    fun touch(mint: String, symbol: String, name: String, source: String, grade: String = "") {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = registry[mint]
        if (existing == null) {
            registry[mint] = MemeMint(
                mint = mint, symbol = symbol, name = name, source = source,
                firstSeenMs = now, lastSeenMs = now, sightings = 1,
                gradeAtFirstSeen = grade,
            )
        } else {
            existing.lastSeenMs = now
            existing.sightings += 1
        }
        scheduleSave()
    }

    /** Bulk-touch helper for scanner cycles that produce many mints at once. */
    fun touchBulk(mints: List<MemeMint>) {
        if (mints.isEmpty()) return
        val now = System.currentTimeMillis()
        for (m in mints) {
            val existing = registry[m.mint]
            if (existing == null) {
                registry[m.mint] = m.copy(firstSeenMs = now, lastSeenMs = now, sightings = 1)
            } else {
                existing.lastSeenMs = now
                existing.sightings += 1
            }
        }
        scheduleSave()
    }

    /**
     * Evict mints that haven't been seen for MINT_RETENTION_MS. Returns count
     * removed. Called from the discovery loop / on bot start.
     */
    fun evictStale(): Int {
        val cutoff = System.currentTimeMillis() - MINT_RETENTION_MS
        var removed = 0
        registry.entries.removeIf { (_, m) ->
            val drop = m.lastSeenMs < cutoff
            if (drop) removed++
            drop
        }
        if (removed > 0) {
            scheduleSave()
            ErrorLogger.info(TAG, "evictStale: removed $removed (>14d unseen) | remaining ${registry.size}")
        }
        return removed
    }

    /** Stats line for the universe tile / forensics export. */
    fun stats(): String {
        val now = System.currentTimeMillis()
        val today = registry.values.count { now - it.firstSeenMs < 24 * 60 * 60_000L }
        return "Total: ${registry.size} · +$today today · 3d retention · cap $MAX_MINTS_8033"
    }

    // ─── persistence ─────────────────────────────────────────────────────────

    private fun scheduleSave() {
        if (appCtx == null) return
        persistDirty.set(true)
        synchronized(persistLock) {
            if (persistJob?.isActive == true) return
            persistJob = scope.launch {
                try {
                    delay(PERSIST_DEBOUNCE_MS)
                    if (persistDirty.compareAndSet(true, false)) saveToDisk()
                } catch (_: Throwable) {}
            }
        }
    }

    /** V5.0.8033 — keep the [MAX_MINTS_8033] most recently seen mints. Returns how many were dropped. */
    fun capToMax8033(): Int {
        val over = registry.size - MAX_MINTS_8033
        if (over <= 0) return 0
        val drop = registry.values.sortedBy { it.lastSeenMs }.take(over).map { it.mint }
        drop.forEach { registry.remove(it) }
        return drop.size
    }

    @Synchronized
    private fun saveToDisk() {
        val ctx = appCtx ?: return
        try {
            capToMax8033()
            val file = File(ctx.filesDir, PERSIST_FILE)
            var n = 0
            // Streamed one row at a time: never one string the size of the whole file.
            file.bufferedWriter().use { w ->
                w.write("[")
                for (m in registry.values) {
                    if (n > 0) w.write(",")
                    w.write(JSONObject().apply {
                        put("mint", m.mint)
                        put("symbol", m.symbol)
                        put("name", m.name)
                        put("source", m.source)
                        put("firstSeenMs", m.firstSeenMs)
                        put("lastSeenMs", m.lastSeenMs)
                        put("sightings", m.sightings)
                        if (m.gradeAtFirstSeen.isNotBlank()) put("grade", m.gradeAtFirstSeen)
                    }.toString())
                    n++
                }
                w.write("]")
            }
            ErrorLogger.info(TAG, "💾 persisted $n meme mints (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveToDisk failed: ${e.message}")
        }
    }

    @Synchronized
    private fun restoreFromDisk() {
        val ctx = appCtx ?: return
        val file = File(ctx.filesDir, PERSIST_FILE)
        if (!file.exists()) return
        // V5.0.8033 — a file this size is the pre-8033 unbounded registry: parsing it alone fills the heap. It is a
        // cache of metadata (the scanner re-discovers live mints), so it is set aside, not loaded.
        if (file.length() > MAX_RESTORE_BYTES_8033) {
            try { file.renameTo(File(ctx.filesDir, "$PERSIST_FILE.oversize_8033")) || file.delete() } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("MEME_MINT_REGISTRY_OVERSIZE_SET_ASIDE_8033") } catch (_: Throwable) {}
            ErrorLogger.warn(TAG, "restoreFromDisk: ${file.length() / 1024}KB > cap — set aside, starting fresh")
            return
        }
        try {
            val arr = JSONArray(file.readText())
            var loaded = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mint = o.optString("mint", "").trim()
                if (mint.isBlank()) continue
                registry[mint] = MemeMint(
                    mint = mint,
                    symbol = o.optString("symbol", ""),
                    name = o.optString("name", ""),
                    source = o.optString("source", "restored"),
                    firstSeenMs = o.optLong("firstSeenMs", System.currentTimeMillis()),
                    lastSeenMs = o.optLong("lastSeenMs", System.currentTimeMillis()),
                    sightings = o.optInt("sightings", 1),
                    gradeAtFirstSeen = o.optString("grade", ""),
                )
                loaded++
            }
            // Clean up anything older than the retention, then keep the most recent [MAX_MINTS_8033].
            evictStale()
            capToMax8033()
            ErrorLogger.info(TAG, "📂 restored $loaded meme mints from disk")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "restoreFromDisk failed: ${e.message}")
        }
    }
}
