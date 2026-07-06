package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6124h — MoonshotHoldMode
 *
 * OPERATOR DIRECTIVE ($ANSEM screenshot): "$120 at 10k mcap → $3.4M in 8
 * days. Buy them early and hold to millions."
 *
 * The bot's default exit logic (dynamic fluid stop, +20% TP, max-hold
 * timers) is optimized for scalp profits, which is exactly WRONG for the
 * ANSEM-style 28,000x moonshot. This module implements the runner-mode
 * exit override:
 *
 * ACTIVATION
 *   • Position mint is in FreshLaunchHunter.moonshotEntries, OR
 *   • Position has ever reached peakGainPct >= +200%
 *
 * BEHAVIOR WHEN ACTIVE
 *   • Suppress normal exits (fluid TP, chop-scalp exits, max-hold timers)
 *   • Only exit on ONE condition:
 *       - Price drops >= 50% from rolling peak (trailing SL)
 *       - AND the position has already been up +100% (never trigger on
 *         a naked drawdown from entry)
 *   • Peak is rolled every time we see a new high — persistent per-mint
 *     so restart doesn't lose it.
 *
 * INTEGRATION SURFACE
 *   • Executor.runManageOnly / normal exit paths call `shouldSuppressExit`
 *     with the current pnlPct + peakPct.
 *   • Executor calls `updatePeak` on every pnlPct read to advance peak.
 *   • On final close, `onPositionClosed(mint)` releases the registry entry.
 *
 * FAIL-OPEN: any error → returns false = normal exit path unchanged.
 */
object MoonshotHoldMode {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val ACTIVATION_PEAK_PCT = 200.0     // +200% = auto-activate
    private const val TRAILING_STOP_DROP_PCT = 50.0   // exit if 50% below peak
    private const val MIN_PROFIT_BEFORE_TRAIL = 100.0 // trail only after +100%

    // ── State ───────────────────────────────────────────────────────────
    private data class HoldState(
        @Volatile var peakPct: Double,
        @Volatile var activatedAtMs: Long,
    )

    private val positions = ConcurrentHashMap<String, HoldState>()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Called on every pnlPct read for a live position. Updates peak, activates
     * moonshot hold if peak crosses the threshold. Returns the current
     * peak-so-far for the position.
     */
    fun updatePeak(mint: String, pnlPct: Double): Double {
        return try {
            val state = positions.computeIfAbsent(mint) { HoldState(peakPct = pnlPct, activatedAtMs = 0L) }
            synchronized(state) {
                if (pnlPct > state.peakPct) state.peakPct = pnlPct
                if (state.activatedAtMs == 0L) {
                    val moonshotEntry = try { FreshLaunchHunter.isMoonshotEntry(mint) } catch (_: Throwable) { false }
                    if (moonshotEntry || state.peakPct >= ACTIVATION_PEAK_PCT) {
                        state.activatedAtMs = System.currentTimeMillis()
                        try {
                            ForensicLogger.lifecycle(
                                "MOONSHOT_HOLD_ACTIVATED_6124h",
                                "mint=${mint.take(10)} peakPct=${"%.1f".format(state.peakPct)}% " +
                                "source=${if (moonshotEntry) "fresh_launch" else "peak_trigger"}",
                            )
                            PipelineHealthCollector.labelInc("MOONSHOT_HOLD_ACTIVATED_6124h")
                        } catch (_: Throwable) {}
                    }
                }
                state.peakPct
            }
        } catch (_: Throwable) { pnlPct }
    }

    /**
     * The main exit-suppression predicate. Executor calls this whenever it's
     * about to close a position on a NON-CATASTROPHIC exit reason (fluid TP,
     * chop-scalp, max-hold, etc.). If this returns true, Executor must SKIP
     * the exit and keep holding.
     *
     * Catastrophic exits (rug detected, hard-veto reasons, wallet drain) MUST
     * NOT consult this predicate — they always execute regardless of moonshot
     * state.
     *
     * @param mint      position's canonical mint
     * @param pnlPct    current unrealized PnL%
     * @return true = suppress the exit (keep holding), false = normal exit
     */
    fun shouldSuppressExit(mint: String, pnlPct: Double): Boolean {
        return try {
            val state = positions[mint] ?: return false
            if (state.activatedAtMs == 0L) return false

            // Trailing-stop check: exit only if price dropped ≥ 50% from peak
            // AND position has already been up ≥ +100% (so we never trail on
            // a fresh drawdown).
            val peak = state.peakPct
            if (peak < MIN_PROFIT_BEFORE_TRAIL) {
                // Not enough profit yet — keep holding (nothing to protect).
                return true
            }
            val dropFromPeak = peak - pnlPct
            if (dropFromPeak >= TRAILING_STOP_DROP_PCT) {
                // Trailing stop hit — allow exit
                try {
                    ForensicLogger.lifecycle(
                        "MOONSHOT_TRAILING_STOP_6124h",
                        "mint=${mint.take(10)} peak=${"%.1f".format(peak)}% now=${"%.1f".format(pnlPct)}% " +
                        "drop=${"%.1f".format(dropFromPeak)}%",
                    )
                    PipelineHealthCollector.labelInc("MOONSHOT_TRAILING_STOP_6124h")
                } catch (_: Throwable) {}
                return false
            }

            // Suppress the normal exit — keep holding
            try { PipelineHealthCollector.labelInc("MOONSHOT_HOLD_SUPPRESSED_6124h") } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    fun isActive(mint: String): Boolean =
        try { (positions[mint]?.activatedAtMs ?: 0L) > 0L } catch (_: Throwable) { false }

    fun peakPct(mint: String): Double =
        try { positions[mint]?.peakPct ?: 0.0 } catch (_: Throwable) { 0.0 }

    fun onPositionClosed(mint: String) {
        try {
            positions.remove(mint)
            FreshLaunchHunter.onPositionClosed(mint)
        } catch (_: Throwable) {}
    }

    fun snapshot(): Map<String, Pair<Double, Boolean>> {
        return try {
            positions.mapValues { (_, s) -> s.peakPct to (s.activatedAtMs > 0L) }
        } catch (_: Throwable) { emptyMap() }
    }
}
