package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig

/**
 * V5.9.630 — Single restore/persist helper for the unified paper wallet.
 *
 * The paper balance is shown by MainActivity before BotService.startBot() runs,
 * but historically the restore lived only in BotService. Cold-open UI therefore
 * rendered 0/blank until the bot was started or another control refreshed state.
 * This helper lets the app, UI, and service hydrate the same balance without
 * duplicating SharedPreferences keys or accidentally clobbering the wallet.
 */
object PaperWalletStore {
    private const val PREFS = "bot_paper_wallet"
    private const val KEY_BALANCE = "paper_wallet_sol"
    private const val KEY_LAST_MODE_WAS_PAPER = "last_mode_was_paper"

    data class RestoreResult(
        val balanceSol: Double,
        val reset: Boolean,
        val modeChangedLiveToPaper: Boolean,
    )

    fun restore(context: Context, cfg: BotConfig, persistModeFlag: Boolean = false): RestoreResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedBalance = prefs.getFloat(KEY_BALANCE, 0f).toDouble()
        val lastModeWasPaper = prefs.getBoolean(KEY_LAST_MODE_WAS_PAPER, true)
        val modeChangedLiveToPaper = cfg.paperMode && !lastModeWasPaper

        val result = if (cfg.paperMode && (modeChangedLiveToPaper || savedBalance < 0.01)) {
            val fresh = cfg.paperSimulatedBalance.coerceAtLeast(0.01)
            prefs.edit().putFloat(KEY_BALANCE, fresh.toFloat()).apply()
            RestoreResult(fresh, reset = true, modeChangedLiveToPaper = modeChangedLiveToPaper)
        } else {
            RestoreResult(savedBalance.coerceAtLeast(0.0), reset = false, modeChangedLiveToPaper = modeChangedLiveToPaper)
        }

        if (persistModeFlag) {
            prefs.edit().putBoolean(KEY_LAST_MODE_WAS_PAPER, cfg.paperMode).apply()
        }
        return result
    }

    fun persist(context: Context, balanceSol: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_BALANCE, balanceSol.coerceAtLeast(0.0).toFloat())
            .apply()
    }

    fun markMode(context: Context, paperMode: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LAST_MODE_WAS_PAPER, paperMode)
            .apply()
    }
}
