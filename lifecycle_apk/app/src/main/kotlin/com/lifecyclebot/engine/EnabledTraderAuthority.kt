package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.779 — EMERGENT MEME-ONLY toggle isolation authority.
 *
 * Operator forensics 5.0.2709: Project Sniper, CyclicTradeEngine paper,
 * shadow-paper, and CryptoUniverse side engines all kept executing while
 * only Meme Trader was enabled. Per-call-site `if (cfg.cryptoAltsEnabled)`
 * checks scattered through BotService.kt and the trader objects allowed
 * regressions on every config flip.
 *
 * This object is the **single atomic source of truth** for "which traders
 * are enabled right now". BotService publishes the enabled set at
 * startBot(), each scanner/engine/paper-path/sniper queries it on the
 * top of every tick, paper writers query it before any TradeHistoryStore
 * write, and stopBot() clears it.
 *
 * Pair with `RuntimeModeAuthority` (LIVE / PAPER atomic mode) — together
 * they define the runtime execution surface.
 *
 *  ┌──────────────────────┬────────────────────────────────┐
 *  │ Authority            │ Question it answers            │
 *  ├──────────────────────┼────────────────────────────────┤
 *  │ RuntimeModeAuthority │ Are we paper or live?          │
 *  │ EnabledTraderAuthority │ Which traders may execute?  │
 *  └──────────────────────┴────────────────────────────────┘
 *
 * The two are intentionally decoupled — a single trader (MEME) can run in
 * either mode; a single mode (LIVE) can host multiple traders. The combo
 * `(EnabledTraderAuthority.isEnabled(MEME) && RuntimeModeAuthority.isLive())`
 * is the canonical "MEME LIVE" predicate.
 */
object EnabledTraderAuthority {

    enum class Trader {
        MEME,
        SHITCOIN,
        MOONSHOT,
        EXPRESS,
        QUALITY,
        TREASURY,
        CASHGEN,
        BLUECHIP,
        MANIPULATED,
        DIP_HUNTER,
        PROJECT_SNIPER,
        CYCLIC,
        CRYPTO_ALT,
        MARKETS_STOCKS,
        PERPS,
        SHADOW_PAPER,
    }

    // V5.9.1446 — STOCKS/FOREX QUARANTINE (operator directive 2026-06-09).
    // 5.0.3448 expectancy: Stocks n=82 WR=0% PnL=-12,381 SOL, Forex n=6 WR=0%.
    // These non-meme market lanes bleed on TIME_CAP_2H exits and distort the
    // whole P&L view by 4 orders of magnitude vs the meme spine. Hard-quarantine
    // their BUY path in code (overrides the persisted stocks_enabled/forex_enabled
    // prefs so it takes effect on the live device without a UI toggle). Open
    // positions still exit and still record outcomes to the learner — this only
    // stops NEW market entries. Flip to false to lift the quarantine.
    @JvmStatic val MARKET_LANES_QUARANTINED: Boolean = true

    /**
     * The published, atomic enabled set. Default empty so any path that
     * runs before BotService.startBot() publishes (e.g. cold-start
     * scanners) is implicitly disabled. Replaced wholesale on every
     * publish — readers see either the previous full set or the new
     * one, never a half-written set.
     */
    private val enabled = AtomicReference<Set<Trader>>(emptySet())

    /** Publish the canonical enabled set. Idempotent. */
    fun publish(set: Set<Trader>) {
        enabled.set(set.toSet())
        try {
            ForensicLogger.lifecycle(
                "ENABLED_TRADERS_PUBLISHED",
                "set=${set.joinToString(",") { it.name }}",
            )
        } catch (_: Throwable) {}
        try {
            val disabled = Trader.values().filterNot { it in set }
            ErrorLogger.info(
                "EnabledTraderAuthority",
                "ENABLED_TRADERS=[${set.joinToString(",") { it.name }}] " +
                    "DISABLED_TRADERS=[${disabled.joinToString(",") { it.name }}]",
            )
        } catch (_: Throwable) {}
    }

    /** Clear the set — bot stop. */
    fun clear() {
        enabled.set(emptySet())
        try {
            ForensicLogger.lifecycle("ENABLED_TRADERS_CLEARED", "set=")
        } catch (_: Throwable) {}
    }

    fun isEnabled(t: Trader): Boolean = t in enabled.get()
    fun snapshot(): Set<Trader> = enabled.get()
    fun snapshotStr(): String = enabled.get().joinToString(",") { it.name }

    /**
     * V5.0.3682 — RESTORED canonical meme-live-only predicate.
     *
     * Operator audit: previously hard-coded to `false` to satisfy the
     * autonomous agenic doctrine, which broke meme-only isolation
     * (CYCLIC / SNIPER / MARKETS kept evaluating every meme candidate
     * → laneEval/intake exploded → executor never finished → live buys
     * starved). Now reads the published trader set as wallet-truth: the
     * MEME lane is the only enabled trader when the operator's UI/config
     * has selected MEMES_ONLY (the publish() call in BotService.startBot
     * is the single source of truth that puts it there).
     *
     * Pair with `RuntimeModeAuthority.isLive()` for the canonical
     * "MEME LIVE ONLY" predicate.
     */
    fun isMemeLiveOnly(): Boolean {
        val set = enabled.get()
        // V5.0.3969 — internal meme specialists are part of the MEME runtime,
        // not external markets/perps leakage. Publishing QUALITY/TREASURY/
        // BLUECHIP/etc makes them visible/activated and opens top-level gates
        // such as PROJECT_SNIPER, but must NOT make isMemeLiveOnly() false and
        // reopen market/perp fanout. Strip internal meme layers before testing
        // whether the external runtime is still MEME-only.
        val internalMemeLayers = setOf(
            Trader.SHITCOIN, Trader.MOONSHOT, Trader.EXPRESS, Trader.QUALITY,
            Trader.TREASURY, Trader.CASHGEN, Trader.BLUECHIP, Trader.MANIPULATED,
            Trader.DIP_HUNTER, Trader.PROJECT_SNIPER,
        )
        val laneSet = set - Trader.CRYPTO_ALT - internalMemeLayers
        return laneSet.size == 1 && Trader.MEME in laneSet
    }
}
