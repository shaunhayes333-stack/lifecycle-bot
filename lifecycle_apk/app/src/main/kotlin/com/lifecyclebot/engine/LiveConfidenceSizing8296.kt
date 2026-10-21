package com.lifecyclebot.engine

/**
 * V5.0.8296 — SmartSizer's live performance multiplier is fed by LIVE closes only.
 *
 * Two paper-to-live leaks sized real orders on paper results: (1) with fewer than 10 live trades the live perf
 * multiplier read FluidLearning's PAPER win rate and could grow a live order 1.15x-1.30x; (2) every paper-to-live
 * switch with an empty live ring seeded the live win/loss ring and streaks from paper, so paper wins read as a live
 * hot streak. A paper record may still only shrink a live order (conservative); it never grows one, and the live
 * ring is never seeded from paper. Paper memory itself is untouched (nothing is reset).
 */
object LiveConfidenceSizing8296 {
    /** Pure: the paper-fallback multiplier [paperMult] as applied to a LIVE order — at most 1.0. */
    fun paperFallbackLiveMult8296(paperMult: Double): Double =
        if (!paperMult.isFinite() || paperMult <= 0.0) 1.0 else minOf(1.0, paperMult)

    /** Pure: would the legacy switch have seeded live memory from paper (now refused; reported once)? */
    fun paperSeedRefused8296(liveEmpty: Boolean, paperAvailable: Boolean): Boolean = liveEmpty && paperAvailable
}
