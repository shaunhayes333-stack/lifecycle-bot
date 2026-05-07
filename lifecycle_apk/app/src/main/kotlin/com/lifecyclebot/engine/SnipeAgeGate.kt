package com.lifecyclebot.engine

/**
 * V5.9.495z31 / z32 — Snipe-mode age gate.
 *
 * **Operator override (z32):** "we shouldn't block at 39% confidence
 * we should wait to see if it changes. we block far too quickly and
 * purge tokens way too quickly". So this gate NEVER discards a
 * candidate. It only re-classifies stale tokens to the BACKGROUND
 * lane so they keep being evaluated at lower priority while fresh
 * tokens dominate the snipe hot path.
 *
 * Configurable so non-snipe modes still work as today.
 */
object SnipeAgeGate {

    /** When snipe mode is enabled, ages above this go to background lane. */
    const val SNIPE_MAX_AGE_MIN: Long = 15

    enum class Decision {
        SNIPE_AGE_PASS,           // age within threshold, hot path OK
        BACKGROUND_ONLY_OLD_TOKEN // re-classified to background scan, NOT purged
    }

    /**
     * @param ageMinutes      candidate age in minutes (use Long.MAX_VALUE if unknown)
     * @param snipeModeOn     true when the user has snipe mode active
     */
    fun evaluate(ageMinutes: Long, snipeModeOn: Boolean): Decision {
        if (!snipeModeOn) return Decision.SNIPE_AGE_PASS
        if (ageMinutes <= SNIPE_MAX_AGE_MIN) return Decision.SNIPE_AGE_PASS
        return Decision.BACKGROUND_ONLY_OLD_TOKEN
    }

    /**
     * Convenience evaluator that ALSO records a defer-tracker event
     * when the candidate is reclassified to background. Caller passes
     * a symbol so the Meme tab tile can show context.
     */
    fun evaluateAndTrack(symbol: String, ageMinutes: Long, snipeModeOn: Boolean): Decision {
        val d = evaluate(ageMinutes, snipeModeOn)
        if (d == Decision.BACKGROUND_ONLY_OLD_TOKEN) {
            try {
                DeferActivityTracker.record(
                    DeferActivityTracker.Kind.BACKGROUND_CLASSED, symbol
                )
            } catch (_: Throwable) { /* best-effort */ }
        }
        return d
    }

    /** Convenience for callers that want to know whether to shove the
     *  token to background scans rather than drop it entirely. */
    fun shuntToBackground(ageMinutes: Long, snipeModeOn: Boolean): Boolean =
        snipeModeOn && ageMinutes > SNIPE_MAX_AGE_MIN
}
