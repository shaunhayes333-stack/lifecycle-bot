package com.lifecyclebot.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Request
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8802 — Pyth's Core upgrade (26 Aug 2026): Hermes (hermes.pyth.network) now requires an API key, sent as
 * "Authorization: Bearer <key>"; unauthenticated requests fail with authentication errors. Every Hermes call the bot makes
 * (PythOracle, PythHermesStream, LiveReadinessChecker) carries the owner's key when one is saved (encrypted, like the
 * wallet). Without a key a call is made at most once every [NO_KEY_PROBE_MS] (to notice if Pyth serves it again) instead
 * of failing on every scan; the report counts what Pyth answered.
 */
object PythAuth8802 {
    private const val FILE = "pyth_secure_8802"
    private const val NO_KEY_PROBE_MS = 30L * 60_000L
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var key: String? = null
    @Volatile private var loaded = false
    @Volatile private var lastProbeMs = 0L
    private val ok = AtomicLong(); private val authFailed = AtomicLong(); private val otherFailed = AtomicLong(); private val skipped = AtomicLong()
    @Volatile private var lastCode = 0

    private fun prefs(ctx: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val mk = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(ctx.applicationContext, FILE, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).also { prefs = it }
        }
    }

    private fun current(): String? {
        if (!loaded) {
            loaded = true
            try { com.lifecyclebot.AATEApp.appContextOrNull()?.let { key = prefs(it).getString("api_key", "")?.trim()?.ifBlank { null } } } catch (_: Throwable) {}
        }
        return key
    }

    fun hasKey(): Boolean = current() != null

    /** Saves the owner's key (blank removes it). */
    fun save(ctx: Context, apiKey: String): Boolean = try {
        val k = apiKey.trim()
        prefs(ctx).edit().putString("api_key", k).apply()
        key = k.ifBlank { null }; loaded = true
        true
    } catch (_: Throwable) { false }

    /** Pure: may a Hermes call go out now? Always with a key; without one, once per probe window. */
    fun mayCall(hasKey: Boolean, lastProbeMs: Long, nowMs: Long): Boolean = hasKey || nowMs - lastProbeMs >= NO_KEY_PROBE_MS

    /** True when a Hermes call may go out now (records the probe when there is no key). */
    fun shouldCall(nowMs: Long = System.currentTimeMillis()): Boolean {
        val k = hasKey()
        if (!mayCall(k, lastProbeMs, nowMs)) { skipped.incrementAndGet(); return false }
        if (!k) lastProbeMs = nowMs
        return true
    }

    /** [b] with the owner's key, when saved. */
    fun apply(b: Request.Builder): Request.Builder = current()?.let { b.header("Authorization", "Bearer $it") } ?: b

    /** Every Hermes answer's status code. */
    fun observe(code: Int) {
        lastCode = code
        when {
            code in 200..299 -> ok.incrementAndGet()
            code == 401 || code == 403 -> authFailed.incrementAndGet()
            else -> otherFailed.incrementAndGet()
        }
    }

    fun statusLine(): String = "pythAuth8802[key=${if (hasKey()) "saved" else "none"} ok=${ok.get()} authFailed=${authFailed.get()} " +
        "otherFailed=${otherFailed.get()} skippedNoKey=${skipped.get()} lastCode=$lastCode rule=Hermes needs an API key since 26 Aug 2026 " +
        "(Authorization: Bearer); without one the bot probes once every ${NO_KEY_PROBE_MS / 60_000} min]"
}
