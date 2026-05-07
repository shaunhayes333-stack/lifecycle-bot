package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.WalletManager

/**
 * V5.9.495z39 — Forced wallet refresh after sell.
 *
 * Operator spec item 7: "after sell landed, force wallet refresh,
 * update internal balance from chain, update UI balance, update
 * lifecycle record. Do not leave old '0.3929 SOL' balance in the bot
 * state."
 *
 * Public method invoked from the sell-confirm path.
 */
object WalletRefreshAfterSell {

    /** Call from the SELL_CONFIRMED branch as soon as
     *  TxMetaSellFinalizer determines the sell landed.
     *  Returns the fresh on-chain SOL balance. */
    fun forceRefresh(reason: String = "post-sell"): Double = try {
        val wallet = WalletManager.getWallet()
        if (wallet == null) {
            ErrorLogger.warn("WalletRefreshAfterSell", "no wallet available for $reason refresh")
            0.0
        } else {
            val sol = wallet.getSolBalance()
            ErrorLogger.info("WalletRefreshAfterSell",
                "💰 Wallet refreshed after $reason: ${"%.6f".format(sol)} SOL")
            sol
        }
    } catch (e: Throwable) {
        ErrorLogger.warn("WalletRefreshAfterSell", "refresh failed: ${e.message}")
        0.0
    }
}
