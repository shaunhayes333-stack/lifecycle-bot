package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeModeAuthority
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6488 — LANE/MODE LOSING-STREAK OBSERVER.
 *
 * This component is telemetry only. The former implementation was a second
 * global executable-entry authority: three losses in any paper/live lane made
 * FinalExecutionPermit veto every BUY for every other lane. That duplicated
 * ExecutableEntryAuthority6450 and caused cross-lane/cross-mode shutdowns.
 *
 * Canonical lane-local shaping now lives only in ExecutableEntryAuthority6450
 * immediately before capital reservation. True hard safety remains unchanged.
 */
object LosingStreakReflex6439 {

    private data class State(
        val losses: AtomicInteger = AtomicInteger(0),
        val wins: AtomicInteger = AtomicInteger(0),
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        @Volatile var lastLossMint: String = "",
    )

    private val states = ConcurrentHashMap<String, State>()
    private val totalTrips = AtomicLong(0L)
    private val legacyVetoCalls = AtomicLong(0L)

    private fun normalizeMode(raw: String?): String = when {
        raw.equals("live", true) -> "LIVE"
        raw.equals("paper", true) -> "PAPER"
        RuntimeModeAuthority.isLive() -> "LIVE"
        else -> "PAPER"
    }

    private fun normalizeLane(raw: String?): String = raw?.trim()?.uppercase()
        ?.takeIf { it.isNotBlank() } ?: "UNKNOWN"

    private fun key(mode: String?, lane: String?): String = "${normalizeMode(mode)}|${normalizeLane(lane)}"

    fun onTradeClosed(realizedSolDelta: Double, mint: String, mode: String, lane: String) {
        val k = key(mode, lane)
        val state = states.computeIfAbsent(k) { State() }
        val losing = CapitalPreservationCreed6439.isLosingBehaviour(realizedSolDelta)
        if (losing) {
            val n = state.losses.incrementAndGet()
            state.wins.set(0)
            state.lastLossMint = mint
            if (n >= CapitalPreservationCreed6439.MAX_CONSECUTIVE_LOSSES) {
                state.cooldownUntilMs.set(
                    System.currentTimeMillis() + CapitalPreservationCreed6439.CONSECUTIVE_LOSS_COOLDOWN_MS
                )
                totalTrips.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "LOSING_STREAK_COHORT_OBSERVED_6488",
                        "cohort=$k losses=$n lastLossMint=${mint.take(12)} deltaSol=${"%.5f".format(realizedSolDelta)} action=lane_shaping_authority_only",
                    )
                    PipelineHealthCollector.labelInc("LOSING_STREAK_COHORT_OBSERVED_6488")
                } catch (_: Throwable) {}
            }
        } else {
            state.losses.set(0)
            state.wins.incrementAndGet()
            state.cooldownUntilMs.set(0L)
        }
    }

    /** Compatibility overload for old replay/tests; attributed to current mode/UNKNOWN lane. */
    fun onTradeClosed(realizedSolDelta: Double, mint: String) =
        onTradeClosed(realizedSolDelta, mint, normalizeMode(null), "UNKNOWN")

    fun cooldownRemainingSec(lane: String, mode: String = normalizeMode(null)): Long {
        val remMs = (states[key(mode, lane)]?.cooldownUntilMs?.get() ?: 0L) - System.currentTimeMillis()
        return if (remMs <= 0L) 0L else remMs / 1000L
    }

    fun consecutiveLossesNow(lane: String, mode: String = normalizeMode(null)): Int =
        states[key(mode, lane)]?.losses?.get() ?: 0

    /**
     * Legacy API is deliberately non-authoritative. It can never veto a BUY.
     * Kept only so stale external callers fail open while emitting telemetry.
     */
    @Deprecated("Use ExecutableEntryAuthority6450 lane/mode shaping")
    fun shouldBlockNewBuys(): Boolean {
        legacyVetoCalls.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LEGACY_GLOBAL_STREAK_VETO_NEUTRALIZED_6488") } catch (_: Throwable) {}
        return false
    }

    fun cooldownRemainingSec(): Long = states.values.maxOfOrNull {
        ((it.cooldownUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)) / 1000L
    } ?: 0L

    fun consecutiveLossesNow(): Int = states.values.maxOfOrNull { it.losses.get() } ?: 0
    fun consecutiveWinsNow(): Int = states.values.maxOfOrNull { it.wins.get() } ?: 0

    fun reset() { states.clear() }

    fun statusLine(): String {
        val hottest = states.entries.maxByOrNull { it.value.losses.get() }
        return "cohorts=${states.size} hottest=${hottest?.key ?: "none"}:${hottest?.value?.losses?.get() ?: 0} " +
            "cooldownRemSec=${cooldownRemainingSec()} totalTrips=${totalTrips.get()} legacyVetoCalls=${legacyVetoCalls.get()}"
    }
}
