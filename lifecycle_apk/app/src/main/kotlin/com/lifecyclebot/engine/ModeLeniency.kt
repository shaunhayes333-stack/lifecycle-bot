package com.lifecyclebot.engine

/**
 * ModeLeniency — single source of truth for "should this gate be lenient?".
 *
 * The user's explicit, repeated requirement: **live mode must perform
 * exactly like paper mode** once the bot has demonstrated a paper track
 * record. Over the months a dozen different files accumulated subtle
 * "in live, be stricter" branches (stricter cooldowns, higher confidence
 * floors, pattern-entry requirements, etc.) that collectively made the
 * scanner/pipeline behave very differently in live vs paper — defeating
 * the seamless paper→live handoff.
 *
 * Rather than chase each file per-bug, every gate that affects trading
 * throughput / decision-quality now routes through [useLenientGates]:
 *
 *   val lenient = ModeLeniency.useLenientGates(cfg.paperMode)
 *   val threshold = if (lenient) PAPER_THRESHOLD else LIVE_THRESHOLD
 *
 * This way the only place that decides "is this a strict-live run or a
 * proven-edge / paper run?" is here. Future refactors can't silently
 * re-introduce a live-only nerf.
 *
 * Leniency is true when:
 *   • We're actually in paper mode, OR
 *   • The bot has a proven edge in its history (≥300 trades at ≥50% WR
 *     via [TradeHistoryStore.getProvenEdgeCached]).
 *
 * This helper does NOT touch wallet routing — live trades still use the
 * real wallet for real money; only the gates/filters around trade
 * decisions are made paper-like.
 */
object ModeLeniency {

    /** Fast path used by every trading gate. */
    @JvmStatic
    fun useLenientGates(isPaperMode: Boolean): Boolean {
        if (isPaperMode) return true
        // V5.9.612: if the bot is choking, soften live gates immediately so
        // it can regain trade flow and gather data. This never bypasses hard
        // anti-rug / anti-drain safety checks; it only selects paper-like soft
        // thresholds for score/phase/cooldown style gates.
        if (AntiChokeManager.isSoftening()) return true
        // V5.9.58: Check BOTH the local TradeHistoryStore AND the 30-Day
        // Proof Run. Users with a running proof (e.g. 1549 trades at 59%
        // WR) were seeing their "live-proven" flag stay off forever
        // because TradeHistoryStore is fed by the meme bot only and the
        // Markets/Alts traders feed RunTracker30D via FluidLearningAI.
        // Either proof-of-edge counts.
        return try {
            if (TradeHistoryStore.getProvenEdgeCached().hasProvenEdge) return true
            val totalTrades = RunTracker30D.totalTrades
            val start = RunTracker30D.startBalance
            val cur = RunTracker30D.currentBalance
            // Use positive return as a proven-edge proxy when the run has
            // enough volume — we don't have a cached lifetime win rate on
            // RunTracker30D, but a ≥300-trade run in positive territory is
            // plenty of evidence the bot's edge is real.
            if (totalTrades >= 300 && start > 0 && cur >= start) return true
            false
        } catch (_: Throwable) {
            false
        }
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
        useLenientGates(false) -> "LIVE-PROVEN"
        else -> "LIVE-STRICT"
    }
}
