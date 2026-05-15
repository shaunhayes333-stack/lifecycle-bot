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

    enum class Trader { MEME, CRYPTO_ALT, MARKETS_STOCKS, PERPS, PROJECT_SNIPER, CYCLIC, SHADOW_PAPER }

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
     * Canonical "Meme-trader is the LIVE active lane" predicate.
     * Used at the top of every paper / sniper / cyclic / shadow path
     * to refuse execution when only Meme is enabled in LIVE mode.
     */
    fun isMemeLiveOnly(): Boolean {
        val s = enabled.get()
        val memeOnly = s.size == 1 && Trader.MEME in s
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        return memeOnly && live
    }
}
