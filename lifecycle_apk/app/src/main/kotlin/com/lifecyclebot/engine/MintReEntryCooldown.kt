package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6312 — MINT RE-ENTRY COOLDOWN (§21).
 *
 * Operator report showed 10 of 17 matched finalised trades closing within
 * 30 seconds and 14 of 17 within 90 seconds — brutal in/out churn on the
 * same mints with no meaningful structure change. This module tracks the
 * most recent finalised close per mint and blocks re-entry for a
 * configurable cooldown window sized by the exit severity:
 *
 *   catastrophic (< -20% or REASON contains CATASTROPHIC/RUG) → 600s
 *   loss         (0 > pnl ≥ -20%)                             → 180s
 *   scratch      (-1% < pnl < +1%)                            →  45s
 *   win          (pnl ≥ +1%)                                  →   0s (no cooldown)
 *
 * The check is O(1) and used by [LiveEntrySafetyHold]-style gates as an
 * advisory. Live callers must consult [shouldBlockReEntry] before
 * approving a fresh LIVE buy on the mint. Paper/shadow paths are NOT
 * cooled — this is a live-capital protection only.
 */
object MintReEntryCooldown {
    private data class CooldownEntry(
        val armedAtMs: Long,
        val expiresAtMs: Long,
        val exitReason: String,
        val pnlPct: Double,
    )

    private val cooldowns = ConcurrentHashMap<String, CooldownEntry>()

    private const val CATASTROPHIC_COOLDOWN_MS: Long = 600_000L   // 10m
    private const val LOSS_COOLDOWN_MS: Long = 180_000L           // 3m
    private const val SCRATCH_COOLDOWN_MS: Long = 45_000L         // 45s
    private const val CATASTROPHIC_PNL_PCT: Double = -20.0

    fun onFinalisedClose(mint: String, exitReason: String, pnlPct: Double) {
        if (mint.isBlank()) return
        val reasonU = exitReason.uppercase()
        val catastrophic = pnlPct <= CATASTROPHIC_PNL_PCT ||
            reasonU.contains("CATASTROPHIC") || reasonU.contains("RUG") ||
            reasonU.contains("HARD_BACKSTOP") || reasonU.contains("HARD_FLOOR")
        val cooldownMs = when {
            catastrophic -> CATASTROPHIC_COOLDOWN_MS
            pnlPct <= -1.0 -> LOSS_COOLDOWN_MS
            pnlPct < 1.0 -> SCRATCH_COOLDOWN_MS
            else -> 0L
        }
        if (cooldownMs <= 0L) {
            cooldowns.remove(mint)
            return
        }
        val now = System.currentTimeMillis()
        cooldowns[mint] = CooldownEntry(
            armedAtMs = now,
            expiresAtMs = now + cooldownMs,
            exitReason = exitReason,
            pnlPct = pnlPct,
        )
        try {
            val bucket = when {
                catastrophic -> "CATASTROPHIC"
                pnlPct <= -1.0 -> "LOSS"
                else -> "SCRATCH"
            }
            ForensicLogger.lifecycle(
                "MINT_REENTRY_COOLDOWN_ARMED",
                "mint=${mint.take(10)} bucket=$bucket cooldownMs=$cooldownMs exitReason=$exitReason pnlPct=${"%.1f".format(pnlPct)}%",
            )
            PipelineHealthCollector.labelInc("MINT_REENTRY_COOLDOWN_ARMED")
            PipelineHealthCollector.labelInc("MINT_REENTRY_COOLDOWN_ARMED_$bucket")
        } catch (_: Throwable) {}
    }

    /** Returns null if re-entry is allowed, else a human-readable reason. */
    fun shouldBlockReEntry(mint: String): String? {
        if (mint.isBlank()) return null
        val entry = cooldowns[mint] ?: return null
        val now = System.currentTimeMillis()
        if (now >= entry.expiresAtMs) {
            cooldowns.remove(mint)
            return null
        }
        val remainingS = ((entry.expiresAtMs - now) / 1000L).coerceAtLeast(0L)
        return "cooldown remaining ${remainingS}s (last exit ${entry.exitReason} pnl=${"%.1f".format(entry.pnlPct)}%)"
    }

    /** For visibility in Pipeline Health / debugging. */
    fun activeCount(): Int = cooldowns.size

    /** Manually clear (operator override or explicit structure-change flag). */
    fun clear(mint: String, reason: String) {
        if (cooldowns.remove(mint) != null) {
            try {
                ForensicLogger.lifecycle("MINT_REENTRY_STRUCTURE_CHANGED", "mint=${mint.take(10)} reason=$reason")
                PipelineHealthCollector.labelInc("MINT_REENTRY_STRUCTURE_CHANGED")
            } catch (_: Throwable) {}
        }
    }
}
