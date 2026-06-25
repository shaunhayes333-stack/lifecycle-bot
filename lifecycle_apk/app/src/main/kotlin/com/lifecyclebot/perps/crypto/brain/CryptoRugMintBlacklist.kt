package com.lifecyclebot.perps.crypto.brain

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4151 — CRYPTO RUG-MINT BLACKLIST (ISOLATED)
 * ════════════════════════════════════════════════════════════════════════════
 * Operator-mandated crypto/perps trader discipline parity with the meme
 * RugMintBlacklist. Lives in a fully isolated SharedPreferences file
 * (`crypto_rug_mint_blacklist`) so crypto closes NEVER touch meme blacklist
 * state and vice versa. Keyed on `assetKey` (the CryptoFinalBuyCandidate
 * identity), not on Solana mint, since crypto alts come from multiple chains
 * + CEX venues that don't share Solana mint addresses.
 *
 * Same envelope as the meme version: ≤-50% PnL within 10 min of entry →
 * blacklist for 24h. Tunable via constants if alts need a different cadence.
 */
object CryptoRugMintBlacklist {

    private const val PREFS_NAME = "crypto_rug_mint_blacklist"
    private const val KEY_ENTRIES = "entries"
    private const val RUG_PNL_PCT  = -50.0
    private const val RUG_WINDOW_MS = 10 * 60_000L
    private const val BLACKLIST_TTL_MS = 24 * 60 * 60_000L

    private val cache = ConcurrentHashMap<String, Long>() // assetKey → expiresAtMs
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
                val key = parts[0]
                val expiry = parts[1].toLongOrNull() ?: return@forEach
                if (expiry > now) cache[key] = expiry
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
     * Record a close. Blacklists the assetKey iff pnlPct ≤ -50% and held ≤ 10 min.
     */
    fun recordClose(assetKey: String, pnlPct: Double, holdMs: Long) {
        try {
            if (assetKey.isBlank()) return
            if (pnlPct.isNaN() || pnlPct.isInfinite()) return
            if (pnlPct > RUG_PNL_PCT) return
            if (holdMs > RUG_WINDOW_MS) return
            val expiry = System.currentTimeMillis() + BLACKLIST_TTL_MS
            cache[assetKey] = expiry
            persist()
        } catch (_: Throwable) {}
    }

    fun isBlacklisted(assetKey: String): Boolean {
        if (assetKey.isBlank()) return false
        val expiry = cache[assetKey] ?: return false
        val now = System.currentTimeMillis()
        if (expiry <= now) { cache.remove(assetKey); return false }
        return true
    }

    fun size(): Int = cache.size

    fun clear() { cache.clear(); persist() }
}
