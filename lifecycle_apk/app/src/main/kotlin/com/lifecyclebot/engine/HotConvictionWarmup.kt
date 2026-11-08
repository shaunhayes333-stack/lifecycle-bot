package com.lifecyclebot.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6282 — Watchlist Boot Warmup.
 *
 * Persists a small hot-list of top-conviction mints so cold boots don't
 * start at watchlist=0 and starve for the first ~90s while scanners
 * spin up. On boot, the hydrator reads the persisted list and re-seeds
 * the intake queue via BotService.admitProtectedMemeIntake (mirroring
 * the existing MEME_REGISTRY_RESTORE path).
 *
 * Conviction ranking (top-N, N=50):
 *   Mints with recent net-positive PnL in TokenWinMemory that are not held.
 *   V5.0.8633 — superseded layers 1 (live-held wallet mints, LIVE_HELD) and 3 (high processCount watchlist rows,
 *   WATCHLIST_HIGH_TOUCH): held coins go to the exit supervisor only, never back to the buy lanes (audit_8629 D8).
 *
 * Stored as a plain JSON file in filesDir; capped at 50 entries; entries
 * older than 24h are dropped on the next snapshot pass. Idempotent —
 * safe to snapshot every 60s from the bot loop.
 */
object HotConvictionWarmup {

    private const val TAG = "HotConvictionWarmup"
    private const val PERSIST_FILE = "hot_conviction_warmup.json"
    private const val MAX_ENTRIES = 50
    private const val ENTRY_TTL_MS = 24L * 60 * 60_000L  // 24h
    private const val SNAPSHOT_DEBOUNCE_MS = 30_000L

    data class HotMint(
        val mint: String,
        val symbol: String,
        val source: String,
        val convictionScore: Double,
        val lastSeenMs: Long,
        val liquidityUsdEstimate: Double,
        val mcapUsdEstimate: Double = 0.0,   // V5.0.8400 — captured with the token; warmup intake passed NaN before
    )

    private val entries = ConcurrentHashMap<String, HotMint>()
    @Volatile private var appCtx: Context? = null
    private val dirty = AtomicBoolean(false)
    @Volatile private var lastPersistMs: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Hydrate persisted list from disk. Idempotent. Call from BotService.onCreate.
     *  V5.0.6282 — synchronous restore: caller reads getAll() immediately after
     *  init() for boot warmup seeding, so we can't defer to a coroutine here.
     *  File is tiny (max 50 entries, ~10KB JSON) so a synchronous read is fine.
     */
    fun init(context: Context) {
        appCtx = context.applicationContext
        try {
            restore()
            ErrorLogger.info(TAG, "V5.0.6282 init: ${entries.size} hot-conviction mints loaded")
            try { PipelineHealthCollector.labelInc("HOT_CONVICTION_WARMUP_LOAD_6282") } catch (_: Throwable) {}
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "V5.0.6282 restore error: ${t.message}")
        }
    }

    /** Read-only snapshot of the current hot-conviction list, sorted by convictionScore desc. */
    fun getAll(): List<HotMint> {
        pruneExpired()
        return entries.values.sortedByDescending { it.convictionScore }
    }

    fun count(): Int = entries.size

    /** V5.0.8633 — D8: warmup layers that are never buy candidates (a held coin is the exit supervisor's; high-touch is stale repeats). */
    private val NOT_CANDIDATE_SOURCES_8633: Set<String> = setOf("LIVE_HELD", "WATCHLIST_HIGH_TOUCH")

    /** Pure: a warmup row may be re-offered to intake only from a candidate layer, and never while its coin is held. */
    fun reseedEligible8633(source: String, held: Boolean): Boolean =
        !held && source.trim().uppercase() !in NOT_CANDIDATE_SOURCES_8633

    /** The boot re-seed list: [getAll] minus held coins and non-candidate layers (a pre-8633 file still carries them for 24 h). */
    fun reseedCandidates8633(isHeld: (String) -> Boolean): List<HotMint> =
        getAll().filter { reseedEligible8633(it.source, isHeld(it.mint)) }

    /** Every open position (live or paper): the exit supervisor owns it, so it is no warmup candidate. */
    private fun heldMintsNow8633(): Set<String> = try {
        BotService.status.openPositions.map { it.mint }.toSet()
    } catch (_: Throwable) { emptySet() }

    /**
     * Capture a fresh snapshot of the top-conviction mints from the live
     * bot state and persist it. Called every ~60s while running.
     */
    fun captureSnapshotAsync() {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < SNAPSHOT_DEBOUNCE_MS && !dirty.get()) return
        scope.launch {
            try {
                captureSnapshotBlocking()
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "V5.0.6282 snapshot error: ${t.message}")
            }
        }
    }

    private fun captureSnapshotBlocking() {
        val candidates = mutableMapOf<String, HotMint>()
        val now = System.currentTimeMillis()

        // V5.0.8633 — D8: superseded Layer 1 (live-held wallet positions as LIVE_HELD, conviction 1000) and Layer 3
        // (GlobalTradeRegistry watchlist rows ranked by processCount as WATCHLIST_HIGH_TOUCH). A held coin belongs to
        // the exit supervisor (HeldPositionSupervisor7246), which wallet reconcile / position restore hand it to on
        // boot; re-offering it to the buy lanes ran -21.6% at 5 min (8622). High-touch = the most-repeated, oldest
        // rows (median -3.3%). Only recent winners not currently held are remembered.
        val held8633 = heldMintsNow8633()

        // Layer 2 — TokenWinMemory recent winners.
        try {
            val winners = TokenWinMemory.getAllSaneWinners()
            for (w in winners.take(40)) {
                if (w.mint.isBlank()) continue
                if (w.mint in held8633) continue
                if (candidates.containsKey(w.mint)) continue
                val score = 200.0 + (w.pnlPercent.coerceIn(-500.0, 5_000.0))
                candidates[w.mint] = HotMint(
                    mint = w.mint,
                    symbol = w.symbol.ifBlank { w.mint.take(6) },
                    source = "WIN_MEMORY",
                    convictionScore = score,
                    lastSeenMs = w.timestamp.takeIf { it > 0L } ?: now,
                    liquidityUsdEstimate = w.entryLiquidity,
                    mcapUsdEstimate = w.entryMcap,
                )
            }
        } catch (_: Throwable) {}

        // Prune expired candidates.
        val fresh = candidates.filterValues { (now - it.lastSeenMs) < ENTRY_TTL_MS }

        // Merge with existing entries — keep highest conviction per mint.
        for ((mint, hot) in fresh) {
            val prev = entries[mint]
            if (prev == null || hot.convictionScore >= prev.convictionScore) {
                entries[mint] = hot
            }
        }

        // Trim to MAX_ENTRIES by conviction score.
        if (entries.size > MAX_ENTRIES) {
            val toKeep = entries.values.sortedByDescending { it.convictionScore }.take(MAX_ENTRIES).map { it.mint }.toSet()
            val toRemove = entries.keys.filter { it !in toKeep }
            for (k in toRemove) entries.remove(k)
        }

        pruneExpired()
        dirty.set(true)
        persist()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val expired = entries.entries.filter { (now - it.value.lastSeenMs) > ENTRY_TTL_MS }.map { it.key }
        for (k in expired) entries.remove(k)
    }

    private fun persist() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            for (h in entries.values) {
                arr.put(
                    JSONObject().apply {
                        put("mint", h.mint)
                        put("symbol", h.symbol)
                        put("source", h.source)
                        put("convictionScore", h.convictionScore)
                        put("lastSeenMs", h.lastSeenMs)
                        put("liquidityUsdEstimate", h.liquidityUsdEstimate)
                        put("mcapUsdEstimate", h.mcapUsdEstimate)
                    }
                )
            }
            val file = File(ctx.filesDir, PERSIST_FILE)
            file.writeText(JSONObject().put("entries", arr).toString())
            lastPersistMs = System.currentTimeMillis()
            dirty.set(false)
            try { PipelineHealthCollector.labelInc("HOT_CONVICTION_WARMUP_PERSIST_6282") } catch (_: Throwable) {}
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "V5.0.6282 persist failed: ${t.message}")
        }
    }

    private fun restore() {
        val ctx = appCtx ?: return
        val file = File(ctx.filesDir, PERSIST_FILE)
        if (!file.exists()) return
        val text = try { file.readText() } catch (_: Throwable) { return }
        if (text.isBlank()) return
        val root = try { JSONObject(text) } catch (_: Throwable) { return }
        val arr = root.optJSONArray("entries") ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mint = o.optString("mint").trim()
            if (mint.isBlank()) continue
            val lastSeen = o.optLong("lastSeenMs", now)
            if ((now - lastSeen) > ENTRY_TTL_MS) continue
            if (!reseedEligible8633(o.optString("source"), held = false)) continue  // V5.0.8633 — legacy LIVE_HELD / high-touch rows
            entries[mint] = HotMint(
                mint = mint,
                symbol = o.optString("symbol").ifBlank { mint.take(6) },
                source = o.optString("source").ifBlank { "restored" },
                convictionScore = o.optDouble("convictionScore", 100.0),
                lastSeenMs = lastSeen,
                liquidityUsdEstimate = o.optDouble("liquidityUsdEstimate", 0.0),
                mcapUsdEstimate = o.optDouble("mcapUsdEstimate", 0.0),
            )
        }
    }

    fun clear() {
        entries.clear()
        persist()
    }
}
