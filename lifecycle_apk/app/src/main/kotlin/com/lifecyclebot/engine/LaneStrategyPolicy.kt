package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.3691 — bridges the honest LaneStrategyEvaluator replay into runtime.
 *
 * TuningActivity is intentionally read-only, but the bot still needs a canonical
 * consumer for the same replay result. This object caches best-per-lane replay
 * profiles and exposes soft runtime actions: exit-shape override and entry size
 * shaping. It never disables a lane; NO_TRADE becomes micro-probe execution so
 * learning continues without betting principal.
 */
object LaneStrategyPolicy {
    private const val CACHE_TTL_MS = 30_000L
    @Volatile private var lastRefreshMs: Long = 0L
    @Volatile private var cached: Map<String, LaneStrategyEvaluator.LaneResult> = emptyMap()

    private fun normalize(lane: String): String = lane.uppercase()
        .replace("-", "_")
        .replace(" ", "_")
        .let {
            when {
                it.startsWith("MOONSHOT") -> "MOONSHOT"
                it.startsWith("BLUE_CHIP") -> "BLUECHIP"
                it.startsWith("BLUECHIP") -> "BLUECHIP"
                it.startsWith("QUALITY") -> "QUALITY"
                it.startsWith("SHITCOIN") -> "SHITCOIN"
                it.startsWith("STANDARD") -> "STANDARD"
                it.startsWith("PRESALE") -> "PRESALE_SNIPE"
                it.startsWith("PROJECT") -> "PROJECT_SNIPER"
                it.startsWith("EXPRESS") -> "EXPRESS"
                else -> it.substringBefore("_").ifBlank { it }
            }
        }

    private fun snapshot(): Map<String, LaneStrategyEvaluator.LaneResult> {
        val now = System.currentTimeMillis()
        val c = cached
        if (c.isNotEmpty() && now - lastRefreshMs < CACHE_TTL_MS) return c
        return try {
            val fresh = LaneStrategyEvaluator.bestPerLane().mapKeys { normalize(it.key) }
            cached = fresh
            lastRefreshMs = now
            fresh
        } catch (_: Throwable) { c }
    }

    fun bestProfile(lane: String): String = snapshot()[normalize(lane)]?.profile ?: "CURRENT_ACTUAL"

    fun executionWeight(lane: String): Double = when (bestProfile(lane)) {
        "NO_TRADE" -> 0.10
        else -> 1.0
    }

    fun exitOverride(ts: TokenState, pnlPct: Double): ModeSpecificExits.ExitRecommendation? {
        val lane = normalize(ts.position.tradingMode.ifBlank { ts.source })
        val profile = bestProfile(lane)
        if (profile == "CURRENT_ACTUAL" || profile == "NO_TRADE") return null
        val peak = ts.position.peakGainPct
        fun rec(exit: Boolean, pct: Double, reason: String, urgency: ModeSpecificExits.ExitUrgency, stop: Double? = null) =
            ModeSpecificExits.ExitRecommendation(exit, pct, "REPLAY_${profile}: $reason", urgency, stop, null)
        return when (profile) {
            "FLOOR_-15_LETRUN" -> when {
                pnlPct <= -15.0 -> rec(true, 100.0, "hard floor hit ${"%.1f".format(pnlPct)}%", ModeSpecificExits.ExitUrgency.IMMEDIATE)
                peak > 0.0 && pnlPct <= peak - 15.0 -> rec(true, 100.0, "runner trail bank peak=${"%.1f".format(peak)} pnl=${"%.1f".format(pnlPct)}", ModeSpecificExits.ExitUrgency.URGENT)
                else -> rec(false, 0.0, "let-run floor/trail active peak=${"%.1f".format(peak)} pnl=${"%.1f".format(pnlPct)}", ModeSpecificExits.ExitUrgency.TRAIL, ts.position.entryPrice * 0.85)
            }
            "FLOOR_-15_TRAIL25" -> when {
                pnlPct <= -15.0 -> rec(true, 100.0, "hard floor hit ${"%.1f".format(pnlPct)}%", ModeSpecificExits.ExitUrgency.IMMEDIATE)
                peak > 0.0 && pnlPct <= peak - 25.0 -> rec(true, 100.0, "wide runner trail bank peak=${"%.1f".format(peak)} pnl=${"%.1f".format(pnlPct)}", ModeSpecificExits.ExitUrgency.URGENT)
                else -> rec(false, 0.0, "wide let-run trail active peak=${"%.1f".format(peak)} pnl=${"%.1f".format(pnlPct)}", ModeSpecificExits.ExitUrgency.TRAIL, ts.position.entryPrice * 0.85)
            }
            "TIGHT_STOP_-5" -> if (pnlPct <= -5.0) rec(true, 100.0, "tight stop hit", ModeSpecificExits.ExitUrgency.IMMEDIATE) else null
            "EARLY_TP_+30" -> when {
                pnlPct >= 30.0 -> rec(true, 100.0, "early full TP reached", ModeSpecificExits.ExitUrgency.NORMAL)
                pnlPct <= -10.0 -> rec(true, 100.0, "early-TP profile stop hit", ModeSpecificExits.ExitUrgency.IMMEDIATE)
                else -> null
            }
            else -> null
        }
    }
}
