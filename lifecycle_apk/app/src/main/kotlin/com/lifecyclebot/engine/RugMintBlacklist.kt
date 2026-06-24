package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4132 — RUG-MINT BLACKLIST
 * ════════════════════════════════════════════════════════════════════════════
 * Operator: "USWR -100% with FIVE re-entries — no cooldown on RUGGED mints."
 *
 * Mints that lose ≥50% within 10 minutes of entry are blacklisted for 24h.
 * Any later intake of the same mint within that window is hard-rejected.
 *
 * SharedPreferences persistence + memory cache. Entries auto-expire.
 */
object RugMintBlacklist {

    private const val PREFS_NAME = "rug_mint_blacklist"
    private const val KEY_ENTRIES = "entries"
    private const val RUG_PNL_PCT  = -50.0        // ≤ -50% within window
    private const val RUG_WINDOW_MS = 10 * 60_000L // 10 min
    private const val BLACKLIST_TTL_MS = 24 * 60 * 60_000L // 24 h

    private val cache = ConcurrentHashMap<String, Long>() // mint → expiresAtMs
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            restore(p)
        } catch (_: Throwable) {}
    }

    private fun restore(p: SharedPreferences) {
        try {
            val raw = p.getString(KEY_ENTRIES, null) ?: return
            val now = System.currentTimeMillis()
            cache.clear()
            raw.split(';').forEach { tok ->
                if (tok.isBlank()) return@forEach
                val parts = tok.split('|')
                if (parts.size != 2) return@forEach
                val mint = parts[0]
                val expiry = parts[1].toLongOrNull() ?: return@forEach
                if (expiry > now) cache[mint] = expiry
            }
        } catch (_: Throwable) {}
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val now = System.currentTimeMillis()
            val raw = cache.entries
                .filter { it.value > now }
                .joinToString(";") { "${it.key}|${it.value}" }
            p.edit().putString(KEY_ENTRIES, raw).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Record a close. Blacklists the mint iff pnlPct ≤ RUG_PNL_PCT and
     * holdMs ≤ RUG_WINDOW_MS.
     */
    fun recordClose(mint: String, pnlPct: Double, holdMs: Long) {
        try {
            if (mint.isBlank()) return
            if (pnlPct.isNaN() || pnlPct.isInfinite()) return
            if (pnlPct > RUG_PNL_PCT) return
            if (holdMs > RUG_WINDOW_MS) return
            val expiry = System.currentTimeMillis() + BLACKLIST_TTL_MS
            cache[mint] = expiry
            persist()
        } catch (_: Throwable) {}
    }

    /** True iff the mint is currently rug-blacklisted. Auto-prunes expired entries. */
    fun isBlacklisted(mint: String): Boolean {
        if (mint.isBlank()) return false
        val expiry = cache[mint] ?: return false
        val now = System.currentTimeMillis()
        if (expiry <= now) { cache.remove(mint); return false }
        return true
    }

    fun size(): Int = cache.size

    /** Operator hook for manual override. */
    fun clear() { cache.clear(); persist() }
}
