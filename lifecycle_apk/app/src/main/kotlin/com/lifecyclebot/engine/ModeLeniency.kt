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
        // V5.9.1346 — OPERATOR DIRECTIVE: the paper→live switch must be a DIRECT
        // handoff. Live runs the SAME learning brain and the SAME soft entry gates
        // as paper; the ONLY things that change in live are (a) real-wallet routing
        // and (b) the unconditional HARD RULES, which live in entirely separate code
        // paths and are NOT controlled by this flag:
        //   • -15% hard-floor stop-loss (ExitManager, unconditional)
        //   • zero-liquidity HARD_FAIL (HardRugPreFilter — still fires when lenient)
        //   • liquidity-drain heuristic (≥2 hits + ≥40% drop/60s OR <$800)
        //   • FDG hard veto whitelist + real on-chain wallet reconciliation
        //
        // The previous logic only made live lenient once the bot had a "proven edge"
        // (≥300 trades at ≥50% WR). During the bootstrap/learning phase WR sits in the
        // 20-35% band BY DESIGN (see PERFORMANCE_DOCTRINE), so that bar could never be
        // met and flipping to live silently dropped the bot into LIVE-STRICT — higher
        // confidence floors, stricter score/phase/cooldown gates, a $3k (vs $1.5k)
        // toxic-breaker liquidity floor — so it "did nothing". That defeated the whole
        // point of the switch. Live now mirrors paper's soft gates unconditionally.
        //
        // NOTE: this only selects paper-like SOFT thresholds (score/phase/cooldown/
        // conf-floor/liquidity-floor-for-bonding-curve). It never bypasses the hard
        // anti-rug / anti-drain / stop-loss safety above.
        return true
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
        // V5.9.1346 — live now always mirrors paper's soft gates (direct handoff).
        else -> "LIVE-DIRECT"
    }
}
