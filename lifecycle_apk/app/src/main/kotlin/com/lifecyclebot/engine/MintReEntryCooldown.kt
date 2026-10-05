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
        // V5.0.7807 — B10: a stop-out re-entry is released by a held reclaim
        // above this price, not by the clock (Field Manual L145).
        val stopPrice: Double = 0.0,
        val isStop: Boolean = false,
    )

    private val cooldowns = ConcurrentHashMap<String, CooldownEntry>()

    private const val CATASTROPHIC_COOLDOWN_MS: Long = 600_000L   // 10m
    private const val LOSS_COOLDOWN_MS: Long = 180_000L           // 3m
    private const val SCRATCH_COOLDOWN_MS: Long = 45_000L         // 45s
    private const val CATASTROPHIC_PNL_PCT: Double = -20.0
    // V5.0.7693 — a position the bot itself culled as dead money (flat for
    // 20+ min, no price feed, no new high) was back in the wallet ten minutes
    // later. 5.0.7691 live tape: TREASURY bought CTPoyC 02:44, STALE_FLAT_CULL
    // 03:06, bought it again 03:16, STALE_FLAT_CULL again 03:37 — two round
    // trips of fees on a token the bot had just classified as not moving. A
    // cull is a judgement that the mint has nothing for us right now; it must
    // outlast the 45s scratch cooldown.
    private const val FLAT_CULL_COOLDOWN_MS: Long = 30L * 60_000L     // 30m

    fun onFinalisedClose(mint: String, exitReason: String, pnlPct: Double) {
        if (mint.isBlank()) return
        val reasonU = exitReason.uppercase()
        val catastrophic = pnlPct <= CATASTROPHIC_PNL_PCT ||
            reasonU.contains("CATASTROPHIC") || reasonU.contains("RUG") ||
            reasonU.contains("HARD_BACKSTOP") || reasonU.contains("HARD_FLOOR")
        val flatCull7693 = reasonU.contains("STALE_FLAT_CULL") ||
            reasonU.contains("DEAD_MONEY_CULL") || reasonU.contains("DEAD_TOKEN_NO_PRICE")
        val cooldownMs = when {
            catastrophic -> CATASTROPHIC_COOLDOWN_MS
            flatCull7693 -> FLAT_CULL_COOLDOWN_MS
            pnlPct <= -1.0 -> LOSS_COOLDOWN_MS
            pnlPct < 1.0 -> SCRATCH_COOLDOWN_MS
            else -> 0L
        }
        if (cooldownMs <= 0L) {
            cooldowns.remove(mint)
            return
        }
        val now = System.currentTimeMillis()
        val isStop7807 = !flatCull7693 && (catastrophic || pnlPct <= -1.0)
        cooldowns[mint] = CooldownEntry(
            armedAtMs = now,
            expiresAtMs = now + cooldownMs,
            exitReason = exitReason,
            pnlPct = pnlPct,
            stopPrice = if (isStop7807) stopOutPrice7807(mint) else 0.0,
            isStop = isStop7807,
        )
        reentryTheses7807.remove(mint)
        try {
            val bucket = when {
                catastrophic -> "CATASTROPHIC"
                flatCull7693 -> "FLAT_CULL"
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
        if (entry.isStop && entry.stopPrice > 0.0) return stopReentryBlock7807(mint, entry, now)
        if (now >= entry.expiresAtMs) {
            cooldowns.remove(mint)
            return null
        }
        val remainingS = ((entry.expiresAtMs - now) / 1000L).coerceAtLeast(0L)
        return "cooldown remaining ${remainingS}s (last exit ${entry.exitReason} pnl=${"%.1f".format(entry.pnlPct)}%)"
    }

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7807 — B10 RE-ENTRY AFTER A STOP = SWEEP AND RECLAIM.
    //
    // Field Manual L145/L146: "Invalidation: below the sweep low or the
    // failed-reclaim pivot ... Failure mode: buying every new low and
    // inventing a sweep explanation before an actual reclaim exists." The old
    // rule re-opened a stopped-out mint as soon as a 180 s timer ran out, on
    // no new evidence. Now: at least REENTRY_MIN_MS_7807 since the stop (the
    // catastrophic 600 s where it applied), then a HELD reclaim — at least
    // RECLAIM_MIN_MARKS_7807 consecutive fresh marks above the stop-out price
    // spanning RECLAIM_MIN_SPAN_MS_7807 — and the executor separately demands
    // a fresh TradePlan7739 plan (its new invalidation). The prior losing
    // thesis is kept and the re-entry labelled REENTRY_AFTER_STOP_7807. A new
    // positionId is allocated by ExecutorCanonicalMirror6442.allocatePositionId
    // because the stopped position's canonical row is no longer live-cycle.
    // ─────────────────────────────────────────────────────────────────────
    const val REENTRY_MIN_MS_7807: Long = 2L * 60_000L
    private const val RECLAIM_MIN_MARKS_7807 = 3
    private const val RECLAIM_MIN_SPAN_MS_7807: Long = 60_000L
    private const val RECLAIM_FRESH_MS_7807: Long = 30_000L
    private const val STOP_THESIS_TTL_MS_7807: Long = 24L * 60L * 60_000L
    private const val REENTRY_THESIS_WINDOW_MS_7807: Long = 10L * 60_000L

    private val reentryTheses7807 = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Pure: true when the trailing run of marks after [sinceMs] is entirely
     * above [stopPrice], has >= 3 marks, spans >= 60 s, and its last mark is
     * fresh (<= 30 s old).
     */
    fun heldReclaim7807(marks: List<Pair<Long, Double>>, stopPrice: Double, sinceMs: Long, nowMs: Long): Boolean {
        if (!stopPrice.isFinite() || stopPrice <= 0.0) return false
        val after = marks.filter { it.first > sinceMs && it.first <= nowMs + 5_000L && it.second.isFinite() && it.second > 0.0 }
            .sortedBy { it.first }
        if (after.isEmpty()) return false
        var count = 0
        var firstTs = 0L
        for (i in after.indices.reversed()) {
            if (after[i].second > stopPrice) { count++; firstTs = after[i].first } else break
        }
        if (count < RECLAIM_MIN_MARKS_7807) return false
        val lastTs = after.last().first
        if (nowMs - lastTs > RECLAIM_FRESH_MS_7807) return false
        return lastTs - firstTs >= RECLAIM_MIN_SPAN_MS_7807
    }

    private fun marksFor7807(mint: String): List<Pair<Long, Double>> {
        return try {
            val ts = BotService.status.tokens[mint] ?: return emptyList()
            synchronized(ts) { ts.history.toList() }.map { it.ts to it.priceUsd }
        } catch (_: Throwable) { emptyList() }
    }

    private fun stopOutPrice7807(mint: String): Double = try {
        val ts = BotService.status.tokens[mint]
        val last = ts?.let { t -> synchronized(t) { t.history.lastOrNull()?.priceUsd } }
        when {
            last != null && last.isFinite() && last > 0.0 -> last
            ts != null && ts.lastPrice.isFinite() && ts.lastPrice > 0.0 -> ts.lastPrice
            else -> 0.0
        }
    } catch (_: Throwable) { 0.0 }

    private fun stopReentryBlock7807(mint: String, entry: CooldownEntry, now: Long): String? {
        val elapsed = now - entry.armedAtMs
        val catastrophic = entry.expiresAtMs - entry.armedAtMs >= CATASTROPHIC_COOLDOWN_MS
        val minWait = if (catastrophic) CATASTROPHIC_COOLDOWN_MS else REENTRY_MIN_MS_7807
        if (elapsed < minWait) {
            return "stop re-entry wait ${((minWait - elapsed) / 1000L).coerceAtLeast(0L)}s (last exit ${entry.exitReason} pnl=${"%.1f".format(entry.pnlPct)}%)"
        }
        if (elapsed > STOP_THESIS_TTL_MS_7807) {
            cooldowns.remove(mint)
            return null
        }
        if (!heldReclaim7807(marksFor7807(mint), entry.stopPrice, entry.armedAtMs, now)) {
            try { PipelineHealthCollector.labelInc("REENTRY_AWAITING_HELD_RECLAIM_7807") } catch (_: Throwable) {}
            return "stop re-entry awaiting held reclaim above stopPx=${entry.stopPrice} (last exit ${entry.exitReason} pnl=${"%.1f".format(entry.pnlPct)}%)"
        }
        cooldowns.remove(mint)
        reentryTheses7807[mint] = ("exit=${entry.exitReason.take(60)} pnl=${"%.1f".format(entry.pnlPct)}% stopPx=${entry.stopPrice} " +
            "stoppedAgoS=${elapsed / 1000L}") to now
        try {
            PipelineHealthCollector.labelInc("REENTRY_HELD_RECLAIM_CONFIRMED_7807")
            ForensicLogger.lifecycle("REENTRY_HELD_RECLAIM_CONFIRMED_7807", "mint=${mint.take(10)} ${reentryTheses7807[mint]?.first}")
        } catch (_: Throwable) {}
        return null
    }

    /** The recorded losing thesis when this mint was just released by a held reclaim; else null. */
    fun reentryAfterStopThesis7807(mint: String, nowMs: Long = System.currentTimeMillis()): String? {
        val e = reentryTheses7807[mint] ?: return null
        if (nowMs - e.second > REENTRY_THESIS_WINDOW_MS_7807) {
            reentryTheses7807.remove(mint)
            return null
        }
        return e.first
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
