package com.lifecyclebot.engine

/**
 * V5.9.495z31 — Snipe-mode age gate.
 *
 * Operator-reported bug: UI mode says "Snipe: Token < 15 min old" but
 * the pipeline keeps processing 87h / 68h / 1732h / 6072h / 13338h
 * old tokens at the hot path. Fix:
 *
 *   - Add a hard age check at the hot-path entry.
 *   - Old tokens are NOT discarded — they're shunted to the
 *     background scan. The hot path simply refuses to spend on them.
 *
 * Configurable so non-snipe modes still work as today.
 */
object SnipeAgeGate {

    /** When snipe mode is enabled, ages above this are background-only. */
    const val SNIPE_MAX_AGE_MIN: Long = 15

    enum class Decision {
        SNIPE_AGE_PASS,           // age within threshold, hot path OK
        SNIPE_AGE_REJECT,         // age over threshold, hot path NO
        BACKGROUND_ONLY_OLD_TOKEN // explicit shunt to background scan
    }

    /**
     * @param ageMinutes      candidate age in minutes (use Long.MAX_VALUE if unknown)
     * @param snipeModeOn     true when the user has snipe mode active
     */
    fun evaluate(ageMinutes: Long, snipeModeOn: Boolean): Decision {
        if (!snipeModeOn) return Decision.SNIPE_AGE_PASS
        if (ageMinutes <= SNIPE_MAX_AGE_MIN) return Decision.SNIPE_AGE_PASS
        return Decision.SNIPE_AGE_REJECT
    }

    /** Convenience for callers that want to know whether to shove the
     *  token to background scans rather than drop it entirely. */
    fun shuntToBackground(ageMinutes: Long, snipeModeOn: Boolean): Boolean =
        snipeModeOn && ageMinutes > SNIPE_MAX_AGE_MIN
}
