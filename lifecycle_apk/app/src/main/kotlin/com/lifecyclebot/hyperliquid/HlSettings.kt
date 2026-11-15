package com.lifecyclebot.hyperliquid

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * V5.0.8795 — the owner's Hyperliquid settings, in their own encrypted store (the same Keystore-backed
 * EncryptedSharedPreferences the trading wallet uses; it fails closed, never to plaintext).
 *  - account: the main Hyperliquid address (positions and account value are read for it);
 *  - agentKey: the private key of an API wallet the owner approved on Hyperliquid (it can trade, never withdraw);
 *  - live: real orders on; maxNotionalUsd: the most the book may hold in total; dailyStopPct: the day's loss that
 *    flattens everything and switches live off until the owner switches it on again.
 */
object HlSettings {
    data class Config(val account: String, val agentKey: String, val testnet: Boolean, val live: Boolean,
                      val maxNotionalUsd: Double, val dailyStopPct: Double)

    private const val FILE = "hl_secure_8795"
    const val DEFAULT_MAX_NOTIONAL_USD = 50.0
    const val DEFAULT_DAILY_STOP_PCT = 10.0
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val p = EncryptedSharedPreferences.create(ctx.applicationContext, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            cached = p
            return p
        }
    }

    fun read(ctx: Context): Config? = try {
        val p = prefs(ctx)
        Config(p.getString("account", "").orEmpty(), p.getString("agent_key", "").orEmpty(), p.getBoolean("testnet", false),
            p.getBoolean("live", false), p.getFloat("max_notional_usd", DEFAULT_MAX_NOTIONAL_USD.toFloat()).toDouble(),
            p.getFloat("daily_stop_pct", DEFAULT_DAILY_STOP_PCT.toFloat()).toDouble())
    } catch (_: Throwable) { null }

    /** Saves [c]; a blank key keeps the saved one (the screen never shows a saved key back). */
    fun write(ctx: Context, c: Config): Boolean = try {
        val p = prefs(ctx)
        val key = c.agentKey.trim().ifBlank { p.getString("agent_key", "").orEmpty() }
        p.edit().putString("account", c.account.trim()).putString("agent_key", key).putBoolean("testnet", c.testnet)
            .putBoolean("live", c.live).putFloat("max_notional_usd", c.maxNotionalUsd.toFloat())
            .putFloat("daily_stop_pct", c.dailyStopPct.toFloat()).apply()
        true
    } catch (_: Throwable) { false }

    /** Pure: why live trading may not run with [c], or null when the settings allow it. */
    fun liveRefusal(c: Config?): String? = when {
        c == null -> "SETTINGS_UNREADABLE"
        !c.live -> "LIVE_OFF"
        !HlSigner.validAddress(c.account) -> "NO_ACCOUNT_ADDRESS"
        !HlSigner.validKey(c.agentKey) -> "NO_API_WALLET_KEY"
        !(c.maxNotionalUsd > 0.0) -> "NO_BUDGET"
        else -> null
    }
}
