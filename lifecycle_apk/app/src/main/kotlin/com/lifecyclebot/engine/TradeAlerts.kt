package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * TradeAlerts — Unified notification service for Telegram + Discord.
 *
 * V4.0: Single entry point for all trade notifications.
 * Sends to both Telegram and Discord if configured.
 * All calls are fire-and-forget on IO dispatcher.
 *
 * Usage:
 *   TradeAlerts.onBuy(cfg, symbol, sizeSol, score, mode)
 *   TradeAlerts.onSell(cfg, symbol, pnlSol, pnlPct, reason)
 */
object TradeAlerts {

    /**
     * Notify on BUY execution.
     */
    fun onBuy(
        cfg: BotConfig,
        symbol: String,
        sizeSol: Double,
        score: Double,
        walletSol: Double,
        mode: String = "STANDARD",
        isPaper: Boolean = true
    ) {
        if (!shouldNotify(cfg)) return
        
        val modeTag = if (isPaper) "[PAPER]" else "[LIVE]"
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                TelegramNotifier.send(cfg, 
                    "$modeTag " + TelegramNotifier.buyMsg(symbol, sizeSol, score, walletSol))
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                DiscordNotifier.sendEmbed(cfg,
                    "$modeTag 🟢 BUY $symbol",
                    listOf(
                        "Size" to "${"%.4f".format(sizeSol)}◎",
                        "Score" to "${score.toInt()}",
                        "Mode" to mode
                    ),
                    DiscordNotifier.PURPLE
                )
            }
        }
    }

    /**
     * Notify on SELL execution.
     */
    fun onSell(
        cfg: BotConfig,
        symbol: String,
        pnlSol: Double,
        pnlPct: Double,
        reason: String,
        isPaper: Boolean = true
    ) {
        if (!shouldNotify(cfg)) return
        
        val modeTag = if (isPaper) "[PAPER]" else "[LIVE]"
        val color = if (pnlSol >= 0) DiscordNotifier.GREEN else DiscordNotifier.RED
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                TelegramNotifier.send(cfg, 
                    "$modeTag " + TelegramNotifier.sellMsg(symbol, pnlSol, pnlPct, reason))
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                val emoji = if (pnlSol >= 0) "✅" else "🔴"
                DiscordNotifier.sendEmbed(cfg,
                    "$modeTag $emoji SELL $symbol",
                    listOf(
                        "PnL" to "${"%+.4f".format(pnlSol)}◎",
                        "%" to "${"%+.1f".format(pnlPct)}%",
                        "Reason" to reason
                    ),
                    color
                )
            }
        }
    }

    /**
     * Notify on BIG WIN (20%+).
     * These also get broadcast to the hive mind network.
     */
    fun onBigWin(
        cfg: BotConfig,
        symbol: String,
        pnlSol: Double,
        pnlPct: Double,
        isPaper: Boolean = true
    ) {
        if (!shouldNotify(cfg)) return
        
        val modeTag = if (isPaper) "[PAPER]" else "[LIVE]"
        val isMega = pnlPct >= 50
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                val emoji = if (isMega) "🚀🚀🚀" else "🚀"
                TelegramNotifier.send(cfg, 
                    "$modeTag $emoji <b>BIG WIN $symbol</b>\n" +
                    "Profit: +${pnlPct.toInt()}% (+${"%.4f".format(pnlSol)}◎)\n" +
                    "📡 Broadcast to hive mind!")
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                DiscordNotifier.sendEmbed(cfg,
                    "$modeTag 🚀 ${if (isMega) "MEGA" else "BIG"} WIN $symbol",
                    listOf(
                        "Profit" to "+${pnlPct.toInt()}%",
                        "SOL" to "+${"%.4f".format(pnlSol)}◎",
                        "Network" to "📡 Broadcast!"
                    ),
                    DiscordNotifier.GOLD
                )
            }
        }
    }

    /**
     * Notify on treasury milestone reached.
     */
    fun onTreasuryMilestone(
        cfg: BotConfig,
        milestoneName: String,
        treasurySol: Double
    ) {
        if (!shouldNotify(cfg)) return
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                TelegramNotifier.send(cfg, TelegramNotifier.treasuryMsg(milestoneName, treasurySol))
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                DiscordNotifier.sendEmbed(cfg,
                    "🏦 Treasury Milestone",
                    listOf(
                        "Tier" to milestoneName,
                        "Locked" to "${"%.4f".format(treasurySol)}◎"
                    ),
                    DiscordNotifier.GOLD
                )
            }
        }
    }

    /**
     * Notify on circuit breaker triggered.
     */
    fun onCircuitBreaker(cfg: BotConfig, reason: String) {
        if (!shouldNotify(cfg)) return
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                TelegramNotifier.send(cfg, TelegramNotifier.circuitBreakerMsg(reason))
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                DiscordNotifier.send(cfg, DiscordNotifier.circuitBreakerMsg(reason), DiscordNotifier.RED)
            }
        }
    }

    /**
     * Notify on dev sell detected.
     */
    fun onDevSell(cfg: BotConfig, symbol: String, pct: Int) {
        if (!shouldNotify(cfg)) return
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                TelegramNotifier.send(cfg, TelegramNotifier.devSellMsg(symbol, pct))
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                DiscordNotifier.send(cfg, 
                    "**🚨 DEV SELL $symbol** — developer dumped ${pct}%", 
                    DiscordNotifier.ORANGE)
            }
        }
    }

    /**
     * Send a custom alert message.
     */
    fun custom(cfg: BotConfig, title: String, message: String) {
        if (!shouldNotify(cfg)) return
        
        GlobalScope.launch(Dispatchers.IO) {
            // Telegram
            if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
                TelegramNotifier.send(cfg, "<b>$title</b>\n$message")
            }
            
            // Discord
            if (cfg.discordTradeAlerts && cfg.discordWebhookUrl.isNotBlank()) {
                DiscordNotifier.send(cfg, "**$title**\n$message", DiscordNotifier.PURPLE)
            }
        }
    }

    private fun shouldNotify(cfg: BotConfig): Boolean {
        return cfg.telegramTradeAlerts || cfg.discordTradeAlerts
    }
}
