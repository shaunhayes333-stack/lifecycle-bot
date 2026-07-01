package com.lifecyclebot.engine

import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.4586 — DAILY COMPOUNDING TARGET TRACKER (operator P0 "Rule 4").
 *
 * Operator directive: "min daily target is 2x compound — 5x or better".
 *
 * This is a small, read-only-facing helper. It snapshots the wallet SOL
 * balance at the start of each UTC day and exposes progress against a
 * 2x / 5x compounding target. Consumers (LiveStrategyTuner, dashboards)
 * read `progressToTarget()` and `behindTargetPressure()` to boost winner
 * sizing when the bot is behind pace, without ever touching hard-safety
 * gates or vetoing individual trades.
 *
 * Doctrine adherence:
 *   • No hard-block, no veto. Read-only shaper.
 *   • Fail-open: any exception returns neutral (behindTargetPressure=1.0).
 *   • Persists day-open snapshot via LearningPersistence so a reboot
 *     mid-day does not reset progress.
 *   • Thread-safe via AtomicReference; hot-path reads are lock-free.
 */
object DailyCompoundingTracker {
    const val VERSION = "V5.0.4586_DAILY_COMPOUND_TRACKER"

    /** Operator daily compound floor. */
    const val TARGET_MULT_MIN = 2.0
    /** Operator daily compound stretch. */
    const val TARGET_MULT_STRETCH = 5.0

    private const val PERSIST_KEY = "DAILY_COMPOUND_TRACKER"

    private data class DayState(
        val dayEpochUtc: Long,          // days since epoch (UTC)
        val openWalletSol: Double,      // wallet SOL at first tick of that UTC day
        val peakWalletSol: Double,      // highest observed wallet since open
        val lastWalletSol: Double,      // most recent observation
        val updatedAtMs: Long,
    )

    private val stateRef = AtomicReference<DayState?>(null)
    @Volatile private var loadedFromDisk: Boolean = false

    private fun currentUtcDay(): Long =
        System.currentTimeMillis() / 86_400_000L

    /** Restore any prior day-open snapshot from disk. Safe to call multiple times. */
    private fun ensureLoaded() {
        if (loadedFromDisk) return
        synchronized(this) {
            if (loadedFromDisk) return
            loadedFromDisk = true
            try {
                val blob = LearningPersistence.load(PERSIST_KEY) ?: return
                val obj = org.json.JSONObject(blob)
                val day = obj.optLong("day", -1L)
                if (day <= 0L) return
                val restored = DayState(
                    dayEpochUtc = day,
                    openWalletSol = obj.optDouble("open", 0.0),
                    peakWalletSol = obj.optDouble("peak", 0.0),
                    lastWalletSol = obj.optDouble("last", 0.0),
                    updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                )
                stateRef.set(restored)
            } catch (_: Throwable) {}
        }
    }

    private fun persistAsync(s: DayState) {
        try {
            val obj = org.json.JSONObject()
                .put("day", s.dayEpochUtc)
                .put("open", s.openWalletSol)
                .put("peak", s.peakWalletSol)
                .put("last", s.lastWalletSol)
                .put("updatedAtMs", s.updatedAtMs)
            LearningPersistence.save(PERSIST_KEY, obj.toString())
        } catch (_: Throwable) {}
    }

    /**
     * Feed the tracker the latest wallet SOL balance. Cheap; O(1); safe
     * to call on the hot path. On UTC-day rollover we snapshot a fresh
     * open balance and reset progress.
     */
    fun update(currentWalletSol: Double) {
        if (!currentWalletSol.isFinite() || currentWalletSol < 0.0) return
        ensureLoaded()
        val today = currentUtcDay()
        val prev = stateRef.get()
        val now = System.currentTimeMillis()
        val next = if (prev == null || prev.dayEpochUtc != today) {
            DayState(
                dayEpochUtc = today,
                openWalletSol = currentWalletSol,
                peakWalletSol = currentWalletSol,
                lastWalletSol = currentWalletSol,
                updatedAtMs = now,
            )
        } else {
            prev.copy(
                peakWalletSol = maxOf(prev.peakWalletSol, currentWalletSol),
                lastWalletSol = currentWalletSol,
                updatedAtMs = now,
            )
        }
        stateRef.set(next)
        // Persist every ~30s (avoid IO spam on hot path).
        if (prev == null || prev.dayEpochUtc != today || (now - prev.updatedAtMs) > 30_000L) {
            persistAsync(next)
        }
    }

    /**
     * Progress ratio against the 2x floor. 0.0 = flat/no progress,
     * 1.0 = hit the 2x floor exactly, >1.0 = past the floor.
     * Returns 1.0 (neutral) if data is missing.
     */
    fun progressToTarget(): Double {
        return try {
            val s = stateRef.get() ?: return 1.0
            if (s.openWalletSol <= 0.0) return 1.0
            val currentMult = s.lastWalletSol / s.openWalletSol
            val gainMult = (currentMult - 1.0).coerceAtLeast(0.0)
            val floorGain = TARGET_MULT_MIN - 1.0
            (gainMult / floorGain).coerceIn(0.0, 5.0)
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * Returns a bounded "behind target" pressure multiplier (>=1.0).
     * When progress < 1.0 (behind 2x floor) the bot is coached to press
     * proven winners harder — up to +25% ceiling lift. When we're at or
     * past 2x, returns 1.0 (neutral: no artificial acceleration).
     */
    fun behindTargetPressure(): Double {
        return try {
            val p = progressToTarget()
            if (p >= 1.0) 1.0
            else (1.0 + (1.0 - p) * 0.25).coerceIn(1.0, 1.25)
        } catch (_: Throwable) { 1.0 }
    }

    fun statusLine(): String = try {
        val s = stateRef.get() ?: return "$VERSION: no snapshot yet"
        val mult = if (s.openWalletSol > 0.0) s.lastWalletSol / s.openWalletSol else 1.0
        val peakMult = if (s.openWalletSol > 0.0) s.peakWalletSol / s.openWalletSol else 1.0
        val progress = progressToTarget()
        "$VERSION open=${"%.4f".format(s.openWalletSol)} SOL last=${"%.4f".format(s.lastWalletSol)} peak=${"%.4f".format(s.peakWalletSol)} mult=${"%.2f".format(mult)}x peakMult=${"%.2f".format(peakMult)}x progress2x=${"%.0f".format(progress * 100)}% pressure=${"%.2f".format(behindTargetPressure())}"
    } catch (_: Throwable) { "$VERSION: unavailable" }
}
