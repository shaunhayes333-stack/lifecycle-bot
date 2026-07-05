package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6121 — DailyCompoundTargeter
 *
 * OPERATOR DIRECTIVE: "daily 2x-5x compounding target. $100 to a million
 * should be piss easy."
 *
 * Explicit balance targeting. Session-start SOL balance snapshot is
 * anchor. Current balance is compared against pace-to-target:
 *
 *   target multiplier   × session-start   = target balance for the day
 *
 * We compute PACE = (currentBalance - startBalance) / (targetBalance -
 * startBalance) × elapsedFractionOfDay(0..1).
 *
 *   pace >= 1.0    → AHEAD (protect): size × 0.85, entry threshold +5
 *   pace 0.6..1.0  → ON PACE: size × 1.00, entry threshold +0
 *   pace 0.3..0.6  → SLIGHTLY BEHIND: size × 1.10, entry threshold -3
 *   pace 0.0..0.3  → BEHIND: size × 1.20, entry threshold -5
 *   pace < 0.0     → LOSING (session negative): size × 0.70,
 *                    entry threshold +10 (tighten, don't chase)
 *
 * The target multiplier defaults to 2.0× (conservative daily 2x) and is
 * overridable via ConfigStore key "compound.target.daily.mult" (2.0..5.0).
 *
 * Session boundary = midnight UTC — resets automatically once per day.
 *
 * Fail-open: any error yields neutral (1.0 size, 0 threshold delta).
 */
object DailyCompoundTargeter {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val DEFAULT_TARGET_MULT = 2.0
    private const val MAX_TARGET_MULT = 5.0
    private const val MIN_TARGET_MULT = 1.2

    // ── State ───────────────────────────────────────────────────────────
    private val sessionStartSol = AtomicLong(0L)          // millisol × 1000 → double preserved
    private val sessionStartMs = AtomicLong(0L)

    data class CompoundVerdict(
        val sizeMult: Double,
        val entryScoreDelta: Int,
        val pace: Double,
        val state: String,       // AHEAD/ON_PACE/BEHIND/LOSING
    )

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Called once at bot start (or automatically on first evaluate call
     * that detects new day). Snapshots the balance so we can compute pace.
     */
    fun snapshotSessionStart(currentSol: Double) {
        val nowMs = System.currentTimeMillis()
        val encoded = java.lang.Double.doubleToRawLongBits(currentSol.coerceAtLeast(0.0))
        sessionStartSol.set(encoded)
        sessionStartMs.set(nowMs)
        try {
            ForensicLogger.lifecycle(
                "COMPOUND_SESSION_ANCHOR_6121",
                "startSol=${"%.4f".format(currentSol)} atMs=$nowMs",
            )
        } catch (_: Throwable) {}
    }

    /**
     * Evaluate the pace of the current session vs. the daily target.
     * Returns size multiplier + entry-score delta for FDG to apply.
     */
    fun evaluate(currentSol: Double, targetMultOverride: Double? = null): CompoundVerdict {
        return try {
            maybeRolloverSession(currentSol)

            val start = java.lang.Double.longBitsToDouble(sessionStartSol.get())
            if (start <= 0.0) {
                snapshotSessionStart(currentSol)
                return CompoundVerdict(1.0, 0, 0.0, "BOOTSTRAP")
            }

            val target = (targetMultOverride ?: DEFAULT_TARGET_MULT).coerceIn(MIN_TARGET_MULT, MAX_TARGET_MULT)
            val targetBalance = start * target
            val gainNeeded = targetBalance - start
            if (gainNeeded <= 0.0) return CompoundVerdict(1.0, 0, 0.0, "NO_TARGET")

            val gainSoFar = currentSol - start
            val startMs = sessionStartMs.get()
            val elapsedMs = System.currentTimeMillis() - startMs
            val dayMs = 24L * 60L * 60L * 1000L
            val elapsedFrac = (elapsedMs.toDouble() / dayMs.toDouble()).coerceIn(0.0, 1.0)
            if (elapsedFrac < 0.02) return CompoundVerdict(1.0, 0, 0.0, "BOOTSTRAP")

            // Pace: >1.0 means ahead-of-schedule; <0.0 means net negative.
            val expectedGain = gainNeeded * elapsedFrac
            val pace = if (expectedGain != 0.0) gainSoFar / expectedGain else 0.0

            val (sizeMult, thresholdDelta, state) = when {
                gainSoFar < 0.0            -> Triple(0.70, +10, "LOSING")
                pace >= 1.0                -> Triple(0.85, +5,  "AHEAD")
                pace >= 0.6                -> Triple(1.00,  0,  "ON_PACE")
                pace >= 0.3                -> Triple(1.10, -3,  "SLIGHTLY_BEHIND")
                else                       -> Triple(1.20, -5,  "BEHIND")
            }

            CompoundVerdict(sizeMult, thresholdDelta, pace, state)
        } catch (_: Throwable) { CompoundVerdict(1.0, 0, 0.0, "ERROR") }
    }

    // ── Internal ────────────────────────────────────────────────────────
    private fun maybeRolloverSession(currentSol: Double) {
        val startMs = sessionStartMs.get()
        val nowMs = System.currentTimeMillis()
        val startDayEpoch = startMs / (24L * 60L * 60L * 1000L)
        val nowDayEpoch = nowMs / (24L * 60L * 60L * 1000L)
        if (startMs > 0L && nowDayEpoch > startDayEpoch) {
            try {
                ForensicLogger.lifecycle(
                    "COMPOUND_SESSION_ROLLOVER_6121",
                    "prevStartSol=${"%.4f".format(java.lang.Double.longBitsToDouble(sessionStartSol.get()))} " +
                    "newStartSol=${"%.4f".format(currentSol)} elapsedDays=${nowDayEpoch - startDayEpoch}",
                )
            } catch (_: Throwable) {}
            snapshotSessionStart(currentSol)
        }
    }
}
