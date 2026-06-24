package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max

/**
 * ScannerSourceBrain — V5.0.4097 (per-source intake AGI brain)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0 mandate: "find any fix any lane starvation. if the scanners
 * need brains give them one and wire them into the stack."
 *
 * What this brain does:
 *   • Tracks per-source outcome (win count, loss count, sum-of-pnl) using the
 *     same Bernoulli/logistic update vocabulary as UnifiedPolicyHead +
 *     UnifiedExitPolicyHead, so the AGI swarm shares one mental model of
 *     "authority graduation".
 *   • Outputs an `intakeMultiplier(source)` in [0.40, 1.80] that the scanner
 *     loop uses to ORDER and WEIGHT scan calls. Sources that produce winners
 *     get prioritised; sources that bleed get demoted (never disabled — soft
 *     shape only, doctrine-compliant).
 *   • Detects lane starvation (BLUECHIP / DIP_HUNTER / QUALITY ≤ 5 evals in
 *     the last cycle window) and applies a temporary boost to established-
 *     asset-feeding sources for the next cycle.
 *
 * Authority tiers mirror UnifiedPolicyHead:
 *   BOOTSTRAP      < 20 samples → neutral 1.0
 *   ADVISORY       ≥ 20         → narrow shape [0.80, 1.20]
 *   LEARNED        ≥ 60         → wider     [0.60, 1.40]
 *   AUTHORITATIVE  ≥ 150        → widest    [0.40, 1.80]
 *
 * NOT a hard gate — never zeroes a source, never blacklists. The scanner
 * still runs every source on every cycle; the brain only re-orders them
 * and adjusts how aggressively their results boost the candidate score.
 *
 * Persisted via SharedPreferences ("scanner_source_brain"). Fail-open.
 */
object ScannerSourceBrain {

    private const val TAG = "ScannerSourceBrain"
    private const val PREFS = "scanner_source_brain"
    private const val KEY = "state_v1"

    private const val BOOT_THRESHOLD = 20L
    private const val LEARNED_THRESHOLD = 60L
    private const val AUTH_THRESHOLD = 150L

    enum class AuthorityTier { BOOTSTRAP, ADVISORY, LEARNED, AUTHORITATIVE }

    private data class SourceStats(
        var wins: Long = 0L,
        var losses: Long = 0L,
        var pnlSumPct: Double = 0.0,
        var lastUpdated: Long = 0L,
    ) {
        fun samples(): Long = wins + losses
        fun winRate(): Double {
            val n = samples()
            return if (n > 0) wins.toDouble() / n else 0.5
        }
        fun avgPnlPct(): Double {
            val n = samples()
            return if (n > 0) pnlSumPct / n else 0.0
        }
    }

    private val stats = ConcurrentHashMap<String, SourceStats>()
    private val starvationBoostUntilMs = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var ctx: Context? = null
    @Volatile private var loaded = false

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        loaded = true
        ErrorLogger.info(TAG, "🧠 brain init: ${stats.size} sources tracked")
    }

    /** Called by Executor at journal close. Mirrors ScannerLearning.recordTrade. */
    fun recordOutcome(source: String, pnlPct: Double) {
        if (!loaded) return
        val key = normalise(source)
        val s = stats.getOrPut(key) { SourceStats() }
        synchronized(s) {
            if (pnlPct > 0.0) s.wins++ else s.losses++
            s.pnlSumPct += pnlPct
            s.lastUpdated = System.currentTimeMillis()
        }
        save()
    }

    fun authority(source: String): AuthorityTier {
        val s = stats[normalise(source)] ?: return AuthorityTier.BOOTSTRAP
        val n = s.samples()
        return when {
            n >= AUTH_THRESHOLD     -> AuthorityTier.AUTHORITATIVE
            n >= LEARNED_THRESHOLD  -> AuthorityTier.LEARNED
            n >= BOOT_THRESHOLD     -> AuthorityTier.ADVISORY
            else                    -> AuthorityTier.BOOTSTRAP
        }
    }

    /** Multiplier in [0.40, 1.80] depending on tier + winRate.
     *  Caller multiplies its baseline priority by this. */
    fun intakeMultiplier(source: String): Double {
        val key = normalise(source)
        val s = stats[key] ?: return checkStarvationBoost(key, 1.0)
        val n = s.samples()
        if (n < BOOT_THRESHOLD) return checkStarvationBoost(key, 1.0)
        val wr = s.winRate()
        // Smooth s-curve around 0.5 (random) ‒ wr=0.7 yields ~+0.3 above 1.0
        val centred = (wr - 0.5) * 2.0  // [-1, +1]
        val (floor, cap) = when (authority(key)) {
            AuthorityTier.AUTHORITATIVE -> 0.40 to 1.80
            AuthorityTier.LEARNED       -> 0.60 to 1.40
            AuthorityTier.ADVISORY      -> 0.40 to 1.20  // V5.0.4123: was 0.80 floor, too generous for 4% WR sources
            AuthorityTier.BOOTSTRAP     -> 1.00 to 1.00
        }
        val raw = 1.0 + centred * 0.6
        return checkStarvationBoost(key, raw.coerceIn(floor, cap))
    }

    /** When BLUECHIP/DIP_HUNTER/QUALITY are starving, pin established-asset
     *  feeders at a 1.5x boost for the next 3 cycles regardless of brain tier. */
    fun signalStarvation(established: Boolean) {
        if (!established) return
        val now = System.currentTimeMillis()
        // Boost duration: 60s window
        val boostUntil = now + 60_000L
        ESTABLISHED_FEEDERS.forEach { src ->
            val key = normalise(src)
            starvationBoostUntilMs.getOrPut(key) { AtomicLong(0L) }.set(boostUntil)
        }
        try {
            ErrorLogger.warn(
                TAG,
                "⚠ STARVATION DETECTED: boosting ${ESTABLISHED_FEEDERS.size} established-asset sources for 60s"
            )
            // Narrate into sentience family so the personality reflects on it
            SentienceOrchestrator.noteRuntimeEvent(
                "SCANNER_STARVATION_BOOST",
                "established_feeders_boosted=${ESTABLISHED_FEEDERS.size} window_ms=60000",
                "WARN"
            )
        } catch (_: Throwable) {}
    }

    private fun checkStarvationBoost(key: String, baseline: Double): Double {
        val until = starvationBoostUntilMs[key]?.get() ?: 0L
        return if (until > System.currentTimeMillis()) {
            (baseline * 1.5).coerceIn(0.4, 1.8)
        } else baseline
    }

    /** Brain telemetry summary for the unified report. */
    fun summary(): String {
        if (stats.isEmpty()) return "ScannerSourceBrain: bootstrap (no samples yet)"
        val sb = StringBuilder("ScannerSourceBrain (V5.0.4097): ${stats.size} sources\n")
        stats.entries.sortedByDescending { it.value.samples() }.take(12).forEach { (src, s) ->
            val n = s.samples()
            val tier = authority(src).name
            val wr = (s.winRate() * 100).toInt()
            val mult = intakeMultiplier(src)
            sb.append("  $src n=$n WR=${wr}% mult=${"%.2f".format(mult)} tier=$tier\n")
        }
        return sb.toString().trimEnd()
    }

    /** Tier counts for UI pill rendering. */
    fun tierCounts(): Map<AuthorityTier, Int> {
        val out = mutableMapOf<AuthorityTier, Int>()
        AuthorityTier.values().forEach { out[it] = 0 }
        stats.keys.forEach { out[authority(it)] = (out[authority(it)] ?: 0) + 1 }
        return out
    }

    private fun normalise(s: String): String = s.trim().uppercase().ifBlank { "UNKNOWN" }

    private fun save() {
        val c = ctx ?: return
        try {
            val obj = JSONObject()
            stats.forEach { (k, v) ->
                synchronized(v) {
                    obj.put(k, JSONObject().apply {
                        put("w", v.wins)
                        put("l", v.losses)
                        put("p", v.pnlSumPct)
                        put("t", v.lastUpdated)
                    })
                }
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, obj.toString())
                .apply()
        } catch (_: Throwable) { }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return
            val obj = JSONObject(raw)
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                stats[k] = SourceStats(
                    wins = o.optLong("w", 0L),
                    losses = o.optLong("l", 0L),
                    pnlSumPct = o.optDouble("p", 0.0),
                    lastUpdated = o.optLong("t", 0L),
                )
            }
        } catch (_: Throwable) { }
    }

    /** Sources known to feed established Solana mid/large-cap assets. */
    private val ESTABLISHED_FEEDERS = setOf(
        "COINGECKO_ESTABLISHED",
        "DEX_TRENDING",
        "BIRDEYE_TRENDING",
        "BIRDEYE_MARKETS",
        "DEX_GAINERS",
        "GECKO_TOP_VOLUME",
    )
}
