package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1375 — RE-ENTRY LOCKOUT AFTER STOP-LOSS (P0 spec #6).
 *
 * THE PROBLEM (operator 5.0.3362): same mint loops BUY -> STOP_LOSS -> BUY within
 * seconds, re-entering a token that just stopped us out and re-stopping. Pure bleed.
 *
 * THE RULE (operator): after any STOP_LOSS / RAPID_ENTRY_PROTECT_STOP /
 * v8_distribution loss, the same mint + symbolFamily enters re-entry lockout for at
 * least 10 minutes, OR until a new ATH/peak is confirmed, OR until liquidity/volume
 * regime materially changes. No immediate re-buy loops on the same mint.
 *
 * This implementation enforces the floor that is cheap and unambiguous: a 10-minute
 * time lock keyed by BOTH mint and symbolFamily. The "new ATH" / "regime change"
 * early-release is exposed via clearOnNewPeak() so an upstream breakout detector can
 * lift the lock sooner — until one calls it, the 10-min floor holds.
 *
 * Pure, in-memory, thread-safe, fail-open: any error => NOT locked (never starve the
 * trader on a telemetry glitch — doctrine). Lockout NEVER blocks learning: it is
 * checked only inside the executable-open gate, downstream of all learning paths.
 */
object ReEntryLockout {

    const val LOCKOUT_MS = 10 * 60_000L  // operator minimum: 10 minutes

    // Stop-loss exit reasons that arm the lockout (substring match, case-insensitive).
    private val LOCKING_REASONS = listOf(
        "STOP_LOSS",
        "RAPID_ENTRY_PROTECT_STOP",
        "V8_DISTRIBUTION",
        "HARD_FLOOR",
        "DISTRIBUTION_STOP",
        // V5.9.1505 — operator: "just re-enters the same shit on repeat."
        // The catastrophe/hard-stop and managed-exit reasons were NOT locking
        // re-entry, so a mint that just clawed -23% (CLAWED CATASTROPHE) or was
        // exit-managed for liquidity collapse re-qualified next tick and bought
        // again. These are exactly the closes that MUST cool down.
        "HARD_STOP",
        "CATASTROPHE",
        "CLAW",
        "RAPID_STOP",
        "EXIT_MANAGED",
        "LIQUIDITY_COLLAPSE",
        "GHOST_REAP",
        "MANUAL_SWAP",
    )

    private data class Lock(val untilMs: Long, val reason: String, val armedAtMs: Long)

    private val byMint = ConcurrentHashMap<String, Lock>()
    private val byFamily = ConcurrentHashMap<String, Lock>()

    private fun isLockingReason(exitReason: String): Boolean {
        val r = exitReason.uppercase()
        return LOCKING_REASONS.any { r.contains(it) }
    }

    /** Call on every position close. Arms the lockout only for stop-loss-type exits. */
    fun onClose(mint: String, symbolFamily: String, exitReason: String, pnlPct: Double) {
        try {
            // Only arm on a genuine stop-style LOSS. A stop reason with a positive
            // pnl (rare trailing-stop-in-profit) is a WIN — do not lock those.
            if (!isLockingReason(exitReason)) return
            // A trailing-stop that closed in PROFIT is a win — don't lock it.
            // But ghost/manual/exit-managed closes report pnl=0 with no realized
            // figure; those must still arm (they are dead-position cleanups).
            val r = exitReason.uppercase()
            val isCleanupClose = r.contains("GHOST_REAP") || r.contains("MANUAL_SWAP") || r.contains("EXIT_MANAGED")
            if (pnlPct > 0.0 && !isCleanupClose) return
            val now = System.currentTimeMillis()
            val lock = Lock(now + LOCKOUT_MS, exitReason.take(40), now)
            if (mint.isNotBlank()) byMint[mint.trim()] = lock
            if (symbolFamily.isNotBlank()) byFamily[symbolFamily.trim().uppercase()] = lock
            try {
                ForensicLogger.lifecycle(
                    "REENTRY_LOCKOUT_ARMED",
                    "mint=${mint.take(10)} family=${symbolFamily.take(16)} reason=${exitReason.take(40)} pnl=${"%.1f".format(pnlPct)}% ttlMs=$LOCKOUT_MS"
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) { /* fail-open */ }
    }

    /** True if mint OR its symbol-family is currently locked out. */
    fun isLocked(mint: String, symbolFamily: String): Boolean = lockReason(mint, symbolFamily) != null

    /** Returns the active lock reason (with remaining ms), or null if not locked. */
    fun lockReason(mint: String, symbolFamily: String): String? {
        return try {
            val now = System.currentTimeMillis()
            byMint.entries.removeIf { it.value.untilMs <= now }
            byFamily.entries.removeIf { it.value.untilMs <= now }
            val m = byMint[mint.trim()]?.takeIf { it.untilMs > now }
            val f = byFamily[symbolFamily.trim().uppercase()]?.takeIf { it.untilMs > now }
            val hit = m ?: f ?: return null
            val remSec = ((hit.untilMs - now) / 1000L).coerceAtLeast(0)
            "REENTRY_LOCKOUT_${hit.reason}_${remSec}s"
        } catch (_: Throwable) { null }  // fail-open
    }

    // V5.9.1466 — ADAPTIVE FAMILY LOCKOUT (spec item 9). The flat 10-min family
    // lock treats every mint of a stopped-out family identically. The spec wants:
    //   • SAME mint that stopped out  → keep the FULL lockout (no fast re-buy loop).
    //   • DIFFERENT mint, same family → allow a SHORTER lock when the new candidate
    //     shows materially STRONGER confirmation (it is not the same rug retrying).
    // Reentry quality control belongs to FDG/promotion (operator note), so this only
    // RELAXES the family lock for genuinely stronger different-mint candidates; the
    // same-mint lock and all FDG/-15% protections are untouched. Fail-open.
    private const val ADAPTIVE_FAMILY_FLOOR_MS = 3 * 60_000L   // shorter floor for a stronger different-mint
    private const val ADAPTIVE_STRONG_CONF = 45.0              // candidate conf >= this = "stronger confirmation"

    data class LockDecision(
        val reason: String,
        val sameMint: Boolean,
        val familyOnly: Boolean,
        val adaptiveFamily: Boolean,
        val remainingSec: Long,
    )

    /**
     * Adaptive lock check used by the executable-open gate.
     * V5.0.6036 exposes whether the hit is SAME_MINT or FAMILY_ONLY so execution
     * can keep the true repeat-bleed block while soft-allowing family-wide volume
     * amputations as telemetry/size-shaping surfaces.
     */
    fun lockDecisionAdaptive(mint: String, symbolFamily: String, candidateConf: Double): LockDecision? {
        return try {
            val now = System.currentTimeMillis()
            byMint.entries.removeIf { it.value.untilMs <= now }
            byFamily.entries.removeIf { it.value.untilMs <= now }
            val m = byMint[mint.trim()]?.takeIf { it.untilMs > now }
            // Same mint that stopped out → ALWAYS the full lock (no fast re-buy loop).
            if (m != null) {
                val remSec = ((m.untilMs - now) / 1000L).coerceAtLeast(0)
                return LockDecision("REENTRY_LOCKOUT_${m.reason}_${remSec}s", sameMint = true, familyOnly = false, adaptiveFamily = false, remainingSec = remSec)
            }
            val f = byFamily[symbolFamily.trim().uppercase()]?.takeIf { it.untilMs > now } ?: return null
            // Different mint of a locked family. If it shows materially stronger
            // confirmation, only enforce the SHORTER adaptive floor measured from when
            // the family lock was armed; otherwise report the full family lock.
            if (candidateConf >= ADAPTIVE_STRONG_CONF) {
                val adaptiveUntil = f.armedAtMs + ADAPTIVE_FAMILY_FLOOR_MS
                if (now >= adaptiveUntil) {
                    try { ForensicLogger.lifecycle("REENTRY_LOCKOUT_ADAPTIVE_RELEASE", "mint=${mint.take(10)} family=${symbolFamily.take(16)} conf=${"%.0f".format(candidateConf)} (stronger different-mint past ${ADAPTIVE_FAMILY_FLOOR_MS/60000}m floor)") } catch (_: Throwable) {}
                    return null
                }
                val remSec = ((adaptiveUntil - now) / 1000L).coerceAtLeast(0)
                return LockDecision("REENTRY_LOCKOUT_${f.reason}_ADAPTIVE_${remSec}s", sameMint = false, familyOnly = true, adaptiveFamily = true, remainingSec = remSec)
            }
            val remSec = ((f.untilMs - now) / 1000L).coerceAtLeast(0)
            LockDecision("REENTRY_LOCKOUT_${f.reason}_${remSec}s", sameMint = false, familyOnly = true, adaptiveFamily = false, remainingSec = remSec)
        } catch (_: Throwable) { null }
    }

    fun lockReasonAdaptive(mint: String, symbolFamily: String, candidateConf: Double): String? =
        lockDecisionAdaptive(mint, symbolFamily, candidateConf)?.reason

    /**
     * Early release: an upstream breakout/peak detector calls this when the mint
     * confirms a NEW ATH after the loss, satisfying the operator's "until new
     * ATH/peak confirmation" release condition.
     */
    fun clearOnNewPeak(mint: String, symbolFamily: String, why: String = "NEW_PEAK") {
        try {
            val hadMint = byMint.remove(mint.trim()) != null
            val hadFam = if (symbolFamily.isNotBlank()) byFamily.remove(symbolFamily.trim().uppercase()) != null else false
            if (hadMint || hadFam) {
                try { ForensicLogger.lifecycle("REENTRY_LOCKOUT_CLEARED", "mint=${mint.take(10)} family=${symbolFamily.take(16)} why=$why") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun resetForTests() { byMint.clear(); byFamily.clear() }

    fun activeCount(): Int = try {
        val now = System.currentTimeMillis()
        byMint.values.count { it.untilMs > now }
    } catch (_: Throwable) { 0 }
}
