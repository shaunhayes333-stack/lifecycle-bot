package com.lifecyclebot.perps.crypto.brain

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.1442 — Isolated brain state for the CRYPTO universe.
 *
 * Operator mandate: "transfer the current memetrader full architecture into
 * the crypto universe… this cannot touch the memetrader! not at all."
 *
 * This file is the single state container for every crypto-universe AI
 * sub-module under [com.lifecyclebot.perps.crypto.brain]. Everything lives
 * in its own SharedPreferences namespace ([PREFS_NAME]) so a meme-side
 * `getSharedPreferences("aate_v3", ...)` and a crypto-side
 * `getSharedPreferences("crypto_brain_v1", ...)` are byte-isolated on disk.
 *
 * Why isolated state matters
 * ──────────────────────────
 * Meme V3 AIs are Kotlin `object` singletons. If crypto calls into
 * `ShitCoinTraderAI.evaluate(...)` the outcome rolls into the same
 * learner the memetrader trains — that polluted the meme brain even
 * though no meme code changed.
 *
 * The crypto brain has its OWN copies of the same architectural patterns
 * (Fluid maturity arc, BehaviorAI sentiment/tilt, LosingPatternMemory
 * danger buckets, TacticSwitcher rotation, LaneExitTuner, Canonical
 * outcome reconciliation) — same logic, different state.
 *
 * Contract for callers
 * ────────────────────
 * 1. Call [init] once from CryptoAltTrader.init().
 * 2. After mutating state, call [save] (debounced internally).
 * 3. Read/write only through the typed accessors exposed by the sister
 *    modules (CryptoFluidLearning, CryptoBehavior, …). Direct prefs
 *    poking from CryptoAltTrader is forbidden — keep the boundary clean.
 */
object CryptoBrainState {

    private const val TAG = "CryptoBrainState"
    private const val PREFS_NAME = "crypto_brain_v1"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var initialized: Boolean = false
    private val lastSaveMs = AtomicLong(0L)
    private val saveDebounceMs = 1_500L

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            initialized = true
            try { ErrorLogger.info(TAG, "🧬 Crypto brain isolated state initialised — ns=$PREFS_NAME") } catch (_: Throwable) {}
            // Eager-load each sub-module so its in-memory mirrors the persisted blob.
            try { CryptoFluidLearning.loadFrom(this) } catch (_: Throwable) {}
            try { CryptoBehavior.loadFrom(this) } catch (_: Throwable) {}
            try { CryptoLosingPatternMemory.loadFrom(this) } catch (_: Throwable) {}
            try { CryptoTacticSwitcher.loadFrom(this) } catch (_: Throwable) {}
            try { CryptoLaneExitTuner.loadFrom(this) } catch (_: Throwable) {}
            try { CryptoCanonicalLearning.loadFrom(this) } catch (_: Throwable) {}
        }
    }

    fun isReady(): Boolean = initialized && prefs != null

    /** Debounced save — multiple calls within 1.5s collapse into one write. */
    fun save() {
        if (!isReady()) return
        val now = System.currentTimeMillis()
        if (now - lastSaveMs.get() < saveDebounceMs) return
        lastSaveMs.set(now)
        saveNow()
    }

    /** Force a synchronous flush. Use sparingly. */
    fun saveNow() {
        val p = prefs ?: return
        try {
            val ed = p.edit()
            CryptoFluidLearning.writeTo(this, ed)
            CryptoBehavior.writeTo(this, ed)
            CryptoLosingPatternMemory.writeTo(this, ed)
            CryptoTacticSwitcher.writeTo(this, ed)
            CryptoLaneExitTuner.writeTo(this, ed)
            CryptoCanonicalLearning.writeTo(this, ed)
            ed.apply()
        } catch (e: Throwable) {
            try { ErrorLogger.warn(TAG, "saveNow failed: ${e.message}") } catch (_: Throwable) {}
        }
    }

    // ── Typed prefs accessors used by the brain sub-modules ───────────────────
    fun getLong(key: String, default: Long): Long = prefs?.getLong(key, default) ?: default
    fun getInt(key: String, default: Int): Int = prefs?.getInt(key, default) ?: default
    fun getDouble(key: String, default: Double): Double = java.lang.Double.longBitsToDouble(
        prefs?.getLong(key, java.lang.Double.doubleToRawLongBits(default)) ?: java.lang.Double.doubleToRawLongBits(default)
    )
    fun getString(key: String, default: String): String = prefs?.getString(key, default) ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = prefs?.getBoolean(key, default) ?: default
}
