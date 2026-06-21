package com.lifecyclebot.engine

/**
 * ModeLeniency — single source of truth for "should this gate be lenient?".
 *
 * V5.0.4021 — paper mode can be lenient for learning exposure. Live mode uses
 * the same learning brain for scoring/sizing/tactics, but lenient admission,
 * cooldown, probation, and safety-ish gate bypasses are paper-only.
 *
 * Rather than chase each file per-bug, every gate that affects trading
 * throughput / decision-quality now routes through [useLenientGates]:
 *
 *   val lenient = ModeLeniency.useLenientGates(cfg.paperMode)
 *   val threshold = if (lenient) PAPER_THRESHOLD else LIVE_THRESHOLD
 *
 * This way future refactors cannot silently make live inherit paper-only
 * bypasses just because the global learning system is in wide-open/proven mode.
 *
 * Leniency is true only when we are actually in paper mode.
 */
object ModeLeniency {

    /** Fast path used by every trading gate. */
    @JvmStatic
    fun useLenientGates(isPaperMode: Boolean): Boolean {
        // V5.0.4021 — lenient gates are paper-only. Live can still use the same
        // learning brain for scoring/sizing/tactics, but it cannot lower admission,
        // cooldown, probation, or safety-ish floors through a paper/proven-edge bypass.
        return isPaperMode
    }

    /** Convenience for code paths that already have a BotConfig. */
    @JvmStatic
    fun useLenientGates(cfg: com.lifecyclebot.data.BotConfig): Boolean =
        useLenientGates(cfg.paperMode)

    /** Human label used in logs. */
    @JvmStatic
    fun label(isPaperMode: Boolean): String = when {
        isPaperMode -> "PAPER"
        AntiChokeManager.isSoftening() -> "LIVE-ANTI-CHOKE"
        else -> "LIVE-ADAPTIVE"
    }
}
