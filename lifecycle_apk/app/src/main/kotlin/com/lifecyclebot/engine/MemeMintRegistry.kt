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
    private const val PERSIST_DEBOUNCE_MS = 5_000L
    private const val MINT_RETENTION_MS = 14L * 24 * 60 * 60_000L  // 14 days for vetted mints

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
        appCtx = context.applicationContext
        restoreFromDisk()
        ErrorLogger.info(TAG, "init: ${registry.size} meme mints loaded from persistent registry")
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
        return "Total: ${registry.size} · +$today today · 14d retention"
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

    @Synchronized
    private fun saveToDisk() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            for (m in registry.values) {
                arr.put(JSONObject().apply {
                    put("mint", m.mint)
                    put("symbol", m.symbol)
                    put("name", m.name)
                    put("source", m.source)
                    put("firstSeenMs", m.firstSeenMs)
                    put("lastSeenMs", m.lastSeenMs)
                    put("sightings", m.sightings)
                    if (m.gradeAtFirstSeen.isNotBlank()) put("grade", m.gradeAtFirstSeen)
                })
            }
            val file = File(ctx.filesDir, PERSIST_FILE)
            file.writeText(arr.toString())
            ErrorLogger.info(TAG, "💾 persisted ${arr.length()} meme mints (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveToDisk failed: ${e.message}")
        }
    }

    @Synchronized
    private fun restoreFromDisk() {
        val ctx = appCtx ?: return
        val file = File(ctx.filesDir, PERSIST_FILE)
        if (!file.exists()) return
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
            // Clean up anything older than 14d on the way in.
            evictStale()
            ErrorLogger.info(TAG, "📂 restored $loaded meme mints from disk")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "restoreFromDisk failed: ${e.message}")
        }
    }
}
