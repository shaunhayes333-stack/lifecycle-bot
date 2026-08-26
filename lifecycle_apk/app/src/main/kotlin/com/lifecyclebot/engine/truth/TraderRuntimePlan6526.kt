package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.EnabledTraderAuthority

/**
 * V5.0.6526 §TRADER_RUNTIME_PLAN — one immutable derivation of the
 * runtime trader plan from a BotConfig snapshot.
 *
 * Operator source-level audit (Feb 2026):
 *   > "Create one TraderRuntimePlan in BotService and derive setEnabled,
 *   >  authority publication, startup and watchdog restart from that
 *   >  same immutable plan. Delete the separate memeOnlyUiMode,
 *   >  marketsOn, marketsLaneOn, cryptoUniverseDoctrine6015 interpretations."
 *
 * The historic drift was that reapply() and startBot() each independently
 * derived the same set of booleans (marketsOn, marketsLaneOn,
 * cryptoUniverseOn, cryptoUniverseOnAtStart, memeOnlyUiMode, ...) from
 * BotConfig via slightly different expressions. Both then called
 * setEnabled(...) on every trader and published an ad-hoc trader set to
 * EnabledTraderAuthority. When any of the expressions drifted, downstream
 * gates disagreed and we ended up with:
 *   - CryptoAltTrader.setEnabled(true) while the published authority
 *     omitted CRYPTO_ALT
 *   - TokenizedStockTrader.setEnabled(false) while marketsLaneOn=true and
 *     startBot() called start() anyway.
 *
 * TraderRuntimePlan6526 is the single source of the plan. Callers derive
 * once, then reference the plan for setEnabled / publish / start decisions.
 */
data class TraderRuntimePlan6526(
    val paperMode: Boolean,
    val memeOn: Boolean,
    val memeOnlyUiMode: Boolean,
    val marketsToggleOn: Boolean,
    val marketsKillSwitch: Boolean,
    val marketsLaneOn: Boolean,
    val perpsOn: Boolean,
    val stocksOn: Boolean,
    val commoditiesOn: Boolean,
    val metalsOn: Boolean,
    val forexOn: Boolean,
    val cryptoAltsToggle: Boolean,
    val cryptoUniverseOn: Boolean,
    val marketLanesQuarantined: Boolean,
) {
    // V5.0.6533 — PAPER_MODE=LEARN_EVERYTHING is resolved here once. Raw stale
    // sub-toggles cannot disagree with startup/reapply/watchdog authority.
    val paperLearnEverything6533: Boolean get() = paperMode && marketsLaneOn
    val stocksEffective: Boolean get() = marketsLaneOn && (paperLearnEverything6533 || stocksOn) && !marketLanesQuarantined
    val forexEffective: Boolean get() = marketsLaneOn && (paperLearnEverything6533 || forexOn) && !marketLanesQuarantined
    val commoditiesEffective: Boolean get() = marketsLaneOn && (paperLearnEverything6533 || commoditiesOn)
    val metalsEffective: Boolean get() = marketsLaneOn && (paperLearnEverything6533 || metalsOn)
    val perpsEffective: Boolean get() = marketsLaneOn && (paperLearnEverything6533 || perpsOn)

    /** The canonical enabled set to publish to EnabledTraderAuthority. */
    fun enabledTraderSet(): Set<EnabledTraderAuthority.Trader> {
        val s = mutableSetOf<EnabledTraderAuthority.Trader>()
        if (memeOn) {
            s += EnabledTraderAuthority.Trader.MEME
            s += EnabledTraderAuthority.Trader.SHITCOIN
            s += EnabledTraderAuthority.Trader.MOONSHOT
            s += EnabledTraderAuthority.Trader.EXPRESS
            s += EnabledTraderAuthority.Trader.QUALITY
            s += EnabledTraderAuthority.Trader.TREASURY
            s += EnabledTraderAuthority.Trader.CASHGEN
            s += EnabledTraderAuthority.Trader.BLUECHIP
            s += EnabledTraderAuthority.Trader.MANIPULATED
            s += EnabledTraderAuthority.Trader.DIP_HUNTER
            s += EnabledTraderAuthority.Trader.PROJECT_SNIPER
        }
        if (cryptoUniverseOn) s += EnabledTraderAuthority.Trader.CRYPTO_ALT
        if ((stocksEffective || commoditiesEffective || metalsEffective || forexEffective) && !marketLanesQuarantined) {
            s += EnabledTraderAuthority.Trader.MARKETS_STOCKS
        }
        if (perpsEffective) s += EnabledTraderAuthority.Trader.PERPS
        // V5.0.6073 SHADOW ALWAYS-ON — shadow paper runs behind BOTH modes.
        s += EnabledTraderAuthority.Trader.SHADOW_PAPER
        return s.toSet()
    }

    companion object {
        /**
         * Sole factory. All callers derive the plan here so drift is
         * physically impossible. `marketsLaneOnFn` is injected so
         * BotService.isMarketsLaneEnabled(cfg) remains the single
         * definition of that predicate (paper-mode gate + kill-switch).
         */
        fun from(
            cfg: BotConfig,
            marketsKill: Boolean,
            marketsLaneOnFn: (BotConfig) -> Boolean,
        ): TraderRuntimePlan6526 {
            val memeOn = cfg.memeTraderEnabled
            val memeOnlyUiMode = cfg.tradingMode == 0 || (cfg.tradingMode == 2 && memeOn)
            val marketsToggleOn = cfg.marketsTraderEnabled
            val marketsLaneOn = !marketsKill && marketsLaneOnFn(cfg)
            // V5.0.6015 doctrine: Crypto Universe is an isolated sidecar to
            // the MEME runtime. Enabled when meme is on, OR (markets lane on
            // AND cryptoAlts toggle on) OR (marketsToggleOn + cryptoAlts).
            val cryptoUniverseOn = memeOn || ((marketsLaneOn || (marketsToggleOn && cfg.cryptoAltsEnabled)) && cfg.cryptoAltsEnabled)
            val quarantined = try {
                EnabledTraderAuthority.marketLanesQuarantined()
            } catch (_: Throwable) { false }
            return TraderRuntimePlan6526(
                paperMode = cfg.paperMode,
                memeOn = memeOn,
                memeOnlyUiMode = memeOnlyUiMode,
                marketsToggleOn = marketsToggleOn,
                marketsKillSwitch = marketsKill,
                marketsLaneOn = marketsLaneOn,
                perpsOn = cfg.perpsEnabled,
                stocksOn = cfg.stocksEnabled,
                commoditiesOn = cfg.commoditiesEnabled,
                metalsOn = cfg.metalsEnabled,
                forexOn = cfg.forexEnabled,
                cryptoAltsToggle = cfg.cryptoAltsEnabled,
                cryptoUniverseOn = cryptoUniverseOn,
                marketLanesQuarantined = quarantined,
            )
        }
    }
}
