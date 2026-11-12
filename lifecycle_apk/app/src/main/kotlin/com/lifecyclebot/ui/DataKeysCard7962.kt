package com.lifecyclebot.ui

import android.app.Activity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.lifecyclebot.R
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore

/**
 * V5.0.7962 — the chart / market-data keys on the main settings card. 5.0.7955 put them
 * only in the settings bottom sheet, which is not the screen the operator uses for keys.
 * Same storage as the sheet (ConfigStore.marketDataKeys7958 + BotConfig.pumpPortalApiKey).
 */
internal object DataKeysCard7962 {
    /** Order matches ConfigStore.marketDataKeys7958: CMC, Moralis, Bitquery, SolanaTracker, Codex, CryptoCompare. */
    private val MARKET_IDS = listOf(
        R.id.etCoinMarketCapKeyMain7962, R.id.etMoralisKeyMain7962, R.id.etBitqueryKeyMain7962,
        R.id.etSolanaTrackerKeyMain7962, R.id.etCodexKeyMain7962, R.id.etCryptoCompareKeyMain7962,
    )

    private fun field(a: Activity, id: Int): EditText? = try { a.findViewById(id) } catch (_: Throwable) { null }

    fun bind(a: Activity, current: () -> BotConfig, saveConfig: (BotConfig) -> Unit) {
        try {
            val keys = ConfigStore.marketDataKeys7958(a)
            MARKET_IDS.forEachIndexed { i, id -> field(a, id)?.setText(keys.getOrNull(i).orEmpty()) }
            field(a, R.id.etPumpPortalKeyMain7962)?.setText(current().pumpPortalApiKey)
            field(a, R.id.etPumpFunApiKeyMain8713)?.setText(ConfigStore.pumpFunApiKey8713(a))
            field(a, R.id.etHeliusDailyCredits8731)?.setText(ConfigStore.heliusDailyCredits8731(a).takeIf { it > 0.0 }?.toLong()?.toString().orEmpty())
            a.findViewById<Button?>(R.id.btnSaveDataKeys7962)?.setOnClickListener {
                save(a, current, saveConfig)
                Toast.makeText(a, "Data keys saved — sources pick them up within a minute", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    /** Persist the card's keys (also called by MainActivity.saveSettings). */
    fun save(a: Activity, current: () -> BotConfig, saveConfig: (BotConfig) -> Unit, includePumpPortal: Boolean = true) {
        try {
            ConfigStore.saveMarketDataKeys7958(a, MARKET_IDS.map { id -> field(a, id)?.text?.toString()?.trim().orEmpty() })
            savePumpFunKey8713(a)
            saveHeliusCredits8731(a)
            if (!includePumpPortal) return
            // V5.0.8181 — an empty card (not yet bound, or not typed in) never erases the saved key.
            val pp = pumpPortal(a)?.takeIf { it.isNotBlank() } ?: return
            val cfg = current()
            if (pp != cfg.pumpPortalApiKey) saveConfig(cfg.copy(pumpPortalApiKey = pp))
        } catch (_: Throwable) {}
    }

    /** V5.0.8713 — the pump.fun developer key (top-50 leaderboard); a changed key re-opens its route. */
    private fun savePumpFunKey8713(a: Activity) {
        val typed = field(a, R.id.etPumpFunApiKeyMain8713)?.text?.toString()?.trim().orEmpty()
        if (typed.isBlank() || typed == ConfigStore.pumpFunApiKey8713(a)) return
        ConfigStore.savePumpFunApiKey8713(a, typed)
        try { com.lifecyclebot.engine.LeaderboardRoutes8418.keyChanged8713() } catch (_: Throwable) {}
    }

    /** V5.0.8731 — the Helius plan's daily credits; a blank or unchanged field keeps what is saved. */
    private fun saveHeliusCredits8731(a: Activity) {
        val typed = field(a, R.id.etHeliusDailyCredits8731)?.text?.toString()?.trim()?.toDoubleOrNull() ?: return
        if (typed == ConfigStore.heliusDailyCredits8731(a)) return
        ConfigStore.saveHeliusDailyCredits8731(a, typed)
        try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.setDailyBudget8731(typed) } catch (_: Throwable) {}
    }

    /** The PumpPortal key typed on the card, or null when the card is not on screen. */
    fun pumpPortal(a: Activity): String? = field(a, R.id.etPumpPortalKeyMain7962)?.text?.toString()?.trim()
}
