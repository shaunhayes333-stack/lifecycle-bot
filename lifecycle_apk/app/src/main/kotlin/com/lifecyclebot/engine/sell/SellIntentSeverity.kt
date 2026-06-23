package com.lifecyclebot.engine.sell

/**
 * SellIntentSeverity — V5.0.4103 (Wave C of P0 sell-failure patch)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator severity ranking (P0 patch §7):
 *   100  RUG_EXIT / DEV_DUMP / HONEYPOT / WALLET_DRAIN
 *   90   RAPID_CATASTROPHE_STOP
 *   80   STRICT_STOP_LOSS / HARD_FLOOR
 *   70   EXIT_RESCUE
 *   60   STALE_FEED_EVICT (with valid price proof)
 *   50   RECOVERY_MANAGEMENT
 *   40   TRAILING_STOP
 *   30   PARTIAL_TP
 *   10   DUST_SWEEP
 *
 * Used by:
 *   • CloseLease.raiseIntent() — escalate priority on the existing lease
 *     instead of spawning a second worker.
 *   • Executor blockIfSellInFlight punch-through (a higher-severity reason
 *     arriving for an in-flight low-severity sell punches through and
 *     proceeds with the new intent).
 *   • Buy-freeze logic (severity ≥ 70 active = block fresh buys).
 */
object SellIntentSeverity {

    fun forReason(reason: String): Int {
        val r = reason.uppercase()
        return when {
            r.contains("RUG") || r.contains("HONEYPOT") || r.contains("DEV_DUMP") ||
                r.contains("WALLET_DRAIN") || r.contains("RUG_DRAIN") -> 100
            r.contains("RAPID_CATASTROPHE") || r.contains("CATASTROPHE") -> 90
            r.contains("STRICT_SL") || r.contains("HARD_FLOOR") || r.contains("HARD_STOP") ||
                r.contains("STOP_LOSS") -> 80
            r.contains("EXIT-RESCUE") || r.contains("EXIT_RESCUE") ||
                r.contains("EXIT-DRAIN-RESCUE") || r.contains("PANIC_DRAIN") -> 70
            r.contains("STALE_FEED_EVICT") -> 60
            r.contains("RECOVERY") || r.contains("RECOVERED") || r.contains("ORPHAN") -> 50
            r.contains("TRAIL") -> 40
            r.contains("PARTIAL") || r.contains("TP") || r.contains("PROFIT") -> 30
            r.contains("DUST") -> 10
            else -> 50  // unknown reason gets recovery-class default
        }
    }

    /** True when the severity is in the "emergency" band that should
     *  block fresh live buys per operator §9 buy-freeze rules. */
    fun isEmergencyBand(severity: Int): Boolean = severity >= 70

    fun isEmergencyBand(reason: String): Boolean = isEmergencyBand(forReason(reason))
}
