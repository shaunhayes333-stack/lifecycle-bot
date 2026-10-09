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
            if (!includePumpPortal) return
            val pp = pumpPortal(a) ?: return
            val cfg = current()
            if (pp != cfg.pumpPortalApiKey) saveConfig(cfg.copy(pumpPortalApiKey = pp))
        } catch (_: Throwable) {}
    }

    /** The PumpPortal key typed on the card, or null when the card is not on screen. */
    fun pumpPortal(a: Activity): String? = field(a, R.id.etPumpPortalKeyMain7962)?.text?.toString()?.trim()
}
