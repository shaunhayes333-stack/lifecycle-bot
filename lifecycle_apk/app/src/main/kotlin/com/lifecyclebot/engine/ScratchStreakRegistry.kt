package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.4160 — SCRATCH-STREAK REGISTRY (per-lane, isolated by tag)
 * ════════════════════════════════════════════════════════════════════════════
 * Centralised counter that detects the "self-reinforcing scratch loop" trap
 * where a lane keeps cutting trades flat (|pnlPct| < scratch threshold) before
 * any real move develops. The flat exits feed the lane's history with all-zero
 * outcomes, which then biases the lane's adaptive gates toward MORE flat
 * exits — a closed feedback loop that produces volume but no PnL signal.
 *
 * Operator dump 2026-06-26 01:02 showed MOONSHOT in this exact trap:
 *   17 trades, W/L/S = 0/0/17, every close in [-2%, +5%] flat zone.
 *
 * Each trader feeds outcomes via [recordOutcome] and reads the streak via
 * [streakFor] before its FLAT_EXIT / TIME_EXIT_MAXHOLD gate. When the streak
 * is ≥ 4, the caller extends its exit window (typical: 2× the flat cap, or
 * suppress the early flat path for one cycle) so the trade gets the runway
 * to find direction. Self-correcting: any non-scratch close resets the
 * counter and the gate reverts to normal cadence.
 *
 * ISOLATION: state is keyed on lane-tag string. Meme lanes (MOONSHOT,
 * SHITCOIN, EXPRESS, BLUECHIP, QUALITY, MANIPULATED) and crypto lanes
 * (CRYPTO_SPOT, CRYPTO_LEV) never collide because their tags differ. Zero
 * persistence — counters reset on bot restart, which is the correct
 * recovery behaviour (a fresh boot deserves a fresh chance).
 */
object ScratchStreakRegistry {

    /** A close with |pnlPct| < this magnitude counts as a "scratch". */
    private const val SCRATCH_PNL_MAGNITUDE_PCT = 1.0

    /** Streak threshold at which callers should extend their exit windows. */
    const val TRAP_STREAK_THRESHOLD = 4

    private val streaks = ConcurrentHashMap<String, AtomicInteger>()

    private fun key(lane: String): String = lane.uppercase().ifBlank { "UNKNOWN" }

    /**
     * Feed one closed-trade outcome. Increments the lane's streak if the
     * close is in the scratch zone; resets to 0 on any clear win or loss.
     */
    fun recordOutcome(lane: String, pnlPct: Double) {
        try {
            if (pnlPct.isNaN() || pnlPct.isInfinite()) return
            val counter = streaks.getOrPut(key(lane)) { AtomicInteger(0) }
            if (kotlin.math.abs(pnlPct) < SCRATCH_PNL_MAGNITUDE_PCT) {
                counter.incrementAndGet()
            } else {
                counter.set(0)
            }
        } catch (_: Throwable) {}
    }

    /** Current consecutive-scratch count for the lane. */
    fun streakFor(lane: String): Int = streaks[key(lane)]?.get() ?: 0

    /** True when the lane has crossed the trap threshold. */
    fun isInTrap(lane: String): Boolean = streakFor(lane) >= TRAP_STREAK_THRESHOLD

    /** Manual reset (operator override / new session). */
    fun reset(lane: String) {
        streaks[key(lane)]?.set(0)
    }
}
