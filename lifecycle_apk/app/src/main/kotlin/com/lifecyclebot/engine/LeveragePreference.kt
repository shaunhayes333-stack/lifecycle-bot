package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * V5.9.369 — single source of truth for the SPOT/LEVERAGE toggle.
 *
 * User report: leverage trades weren't firing on Markets. Audit found
 * two bugs in the per-trader preferLeverage approach:
 *
 *   1. Each trader had its own AtomicBoolean(false), so app restart
 *      always defaulted back to SPOT. UI showed "LEVERAGE 5x" badge
 *      because that's a separate UI flag (showSpotOnly).
 *   2. The toggle button only flipped preferLeverage on the currently
 *      selected tab — switching to Forex or Commod and trying to use
 *      leverage there did nothing because their flag was still false.
 *
 * V5.9.369 fix: this object holds the user's preference per asset class,
 * persists it across restarts via SharedPreferences, and provides
 * apply-to-all helpers so the UI can flip every trader's preference at
 * once. Each trader still owns its AtomicBoolean (avoids invasive
 * refactor) but reads the persisted default from this helper at boot.
 */
object LeveragePreference {

    private const val PREFS_NAME = "leverage_preference_v1"
    private const val KEY_PREFIX = "prefer_leverage_"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Asset class identifiers used as suffix on the prefs key. */
    enum class AssetClass(val key: String, val defaultLeverage: Boolean) {
        CRYPTO(   "CRYPTO",     true),   // CryptoAltTrader (BTC/ETH/SOL/etc.)
        STOCKS(   "STOCKS",     true),   // TokenizedStockTrader
        FOREX(    "FOREX",      true),
        METALS(   "METALS",     true),
        COMMOD(   "COMMOD",     true),
    }

    /** Read persisted preference for a given asset class (defaults to leverage on). */
    fun isLeveragePreferred(asset: AssetClass): Boolean =
        prefs?.getBoolean(KEY_PREFIX + asset.key, asset.defaultLeverage) ?: asset.defaultLeverage

    /** Persist preference for a single asset class. */
    fun setLeveragePreferred(asset: AssetClass, value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PREFIX + asset.key, value)?.apply()
    }

    /**
     * Apply a SPOT-or-LEVERAGE choice to every Markets-layer trader at
     * once + persist. Called by the UI's SPOT/LEVERAGE button so a single
     * tap mirrors everywhere instead of only the currently selected tab.
     */
    fun applyToAllTraders(useLeverage: Boolean) {
        AssetClass.values().forEach { setLeveragePreferred(it, useLeverage) }
        try { com.lifecyclebot.perps.CryptoAltTrader.setPreferLeverage(useLeverage) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.TokenizedStockTrader.setPreferLeverage(useLeverage) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.CommoditiesTrader.setPreferLeverage(useLeverage) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.MetalsTrader.setPreferLeverage(useLeverage) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.ForexTrader.setPreferLeverage(useLeverage) } catch (_: Exception) {}
        ErrorLogger.info("LeveragePreference", "🛠 Applied ${if (useLeverage) "LEVERAGE" else "SPOT"} to all 5 Markets-layer traders + persisted")
    }

    /**
     * Restore each trader's preferLeverage from persisted prefs at boot.
     * Called by BotService.onCreate after the traders have been initialised.
     */
    fun restoreAllTraders() {
        try { com.lifecyclebot.perps.CryptoAltTrader.setPreferLeverage(isLeveragePreferred(AssetClass.CRYPTO)) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.TokenizedStockTrader.setPreferLeverage(isLeveragePreferred(AssetClass.STOCKS)) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.CommoditiesTrader.setPreferLeverage(isLeveragePreferred(AssetClass.COMMOD)) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.MetalsTrader.setPreferLeverage(isLeveragePreferred(AssetClass.METALS)) } catch (_: Exception) {}
        try { com.lifecyclebot.perps.ForexTrader.setPreferLeverage(isLeveragePreferred(AssetClass.FOREX)) } catch (_: Exception) {}
        ErrorLogger.info("LeveragePreference", "🔄 Restored leverage preferences from prefs")
    }
}
