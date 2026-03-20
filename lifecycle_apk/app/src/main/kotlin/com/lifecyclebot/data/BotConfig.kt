package com.lifecyclebot.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class BotConfig(
    // wallet
    val privateKeyB58: String = "",
    val walletAddress: String = "",
    val rpcUrl: String = "https://api.mainnet-beta.solana.com",
    // mode
    val paperMode: Boolean = true,
    val autoTrade: Boolean = false,
    // tokens
    val watchlist: List<String> = emptyList(),
    val activeToken: String = "",
    // sizing
    // Legacy fixed-size fields — kept for fallback/paper mode reference.
    // SmartSizer dynamically overrides these based on wallet balance.
    val smallBuySol: Double = 0.05,
    val largeBuySol: Double = 0.10,
    val largeBuyMinWalletSol: Double = 0.20,
    val maxPositionSol: Double = 0.15,  // floor; SmartSizer scales this up for larger wallets
    // risk
    val stopLossPct: Double = 10.0,          // raised from 6 — meme coins need room
    val trailingStopBasePct: Double = 8.0,
    val slippageBps: Int = 200,
    // exit tuning
    val exitScoreThreshold: Double = 58.0,   // lowered from 62 — slightly more aggressive
    val momentumExitCandles: Int = 3,
    val entryCooldownSec: Int = 120,
    // polling
    val pollSeconds: Int = 8,
    // sentiment
    val telegramBotToken: String = "",
    val telegramChannels: List<String> = emptyList(),
    val telegramChatId: String = "",          // your personal chat ID for trade alerts
    val telegramTradeAlerts: Boolean = false, // send BUY/SELL notifications to Telegram
    val sentimentEnabled: Boolean = true,
    val sentimentPollMins: Int = 3,
    val sentimentBlockThreshold: Double = -40.0,
    val sentimentBoostThreshold: Double = 30.0,
    val sentimentEntryBoost: Double = 20.0,
    val sentimentExitBoost: Double = 10.0,
    // security
    val walletReserveSol: Double = 0.05,      // never trade below this balance
    val maxTradesPerHour: Int = 10,
    val maxDailyLossPct: Double = 10.0,
    val circuitBreakerLosses: Int = 5,
    val circuitBreakerPauseMin: Int = 15,
    val maxPriceImpactPct: Double = 3.0,
    // external API keys (all free)
    val heliusApiKey: String = "",      // helius.dev — free, real-time WS + creator history
    val birdeyeApiKey: String = "",     // birdeye.so — free, OHLCV candles
    val groqApiKey: String = "",        // console.groq.com — free LLM sentiment (llama-3.1-8b)
    val autoAddNewTokens: Boolean = false, // auto-add new Pump.fun launches to watchlist
    // multi-position trading
    // Concurrent positions: no hard limit — SmartSizer exposure cap (70% of wallet)
    // naturally bounds how many positions can be open simultaneously.
    // e.g. at 0.1 SOL wallet + 10% per trade = max 7 positions before 70% cap hits.
    val maxConcurrentPositions: Int = Int.MAX_VALUE,  // unlimited — wallet % is the guard
    val maxTotalExposureSol: Double = Double.MAX_VALUE, // unlimited — SmartSizer manages this
    val perPositionSizePct: Double = 0.10,     // default 10% per position (SmartSizer overrides)
    // dynamic hold time
    val minHoldMins: Double = 3.0,             // never exit before this (wick protection)
    val maxHoldMinsHard: Double = 120.0,       // default cap — overridden when longHoldEnabled

    // ── Long-hold / conviction mode ───────────────────────────────
    // When a token meets ALL conviction metrics, maxHoldMinsHard is replaced
    // by longHoldMaxDays — allowing days or weeks of holding.
    // Conviction requires: BULL_FAN + holder growth + liquidity health
    //                    + wallet can afford to park the capital
    val longHoldEnabled: Boolean = true,
    val longHoldMaxDays: Double = 30.0,          // max hold when conviction active (days)
    val longHoldMinGainPct: Double = 0.0,        // must be profitable to enter long-hold
    val longHoldMinLiquidityUsd: Double = 25_000.0, // min pool depth to park capital
    val longHoldMinHolders: Int = 500,           // min holders — reduces rug risk
    val longHoldHolderGrowthMin: Double = 2.0,   // min % holder growth rate
    val longHoldWalletPct: Double = 0.25,        // max wallet fraction parked in long holds
    val longHoldTreasuryGate: Boolean = true,    // only long-hold if treasury milestone hit
    val holdExtendVolThreshold: Double = 60.0, // vol score above this = extend hold time
    val holdExtendPressThreshold: Double = 58.0, // pressure above this = extend hold
    // sounds
    val soundEnabled: Boolean = true,
    val soundOnNewToken: Boolean = true,
    // auto mode
    val autoMode: Boolean = true,           // auto-switch between modes
    val tradingPauseUtcStart: Int = 1,      // UTC hour to pause (default 1am)
    val tradingPauseUtcEnd: Int = 7,        // UTC hour to resume (default 7am)
    val defensiveLossThreshold: Int = 3,    // losses before defensive mode
    val aggressiveWhaleThreshold: Double = 70.0,
    // copy trading
    val copyTradingEnabled: Boolean = false,
    val copySizeMultiplier: Double = 1.0,   // relative to normal position size
    // time-of-day
    val useTimeFilter: Boolean = true,
    // ── v4.4 improvements ────────────────────────────────────────────
    // 1. Grind confirmation gate
    val grindExhaustCandles: Int = 5,          // candles of decline needed to exit a bull-fan grinder (was 4)
    val grindExhaustConsecutive: Int = 2,      // consecutive checks confirming exhaust before exit fires
    // 2. Post-win re-entry boost
    val postWinReentryBoostMins: Double = 3.0, // FIX 8: was 10min — 3min catches re-entries on fast 1M movers
    val postWinEntryThresholdBoost: Double = 10.0, // how much to lower entry threshold (42→32)
    // 3. Partial sell at milestone
    val partialSellEnabled: Boolean = true,
    val partialSellTriggerPct: Double = 200.0, // take partial at this gain %
    val partialSellFraction: Double = 0.25,    // sell this fraction of position (25%)
    val partialSellSecondTriggerPct: Double = 500.0,
    val partialSellThirdTriggerPct: Double  = 2000.0, // FIX 3: life-changing move tier
    val partialSellThirdEnabled: Boolean    = true,
    // 4. Conviction sizing multiplier
    val convictionSizingEnabled: Boolean = true,
    val convictionMult1: Double = 1.25,        // entry score 55-65 → 1.25x size
    val convictionMult2: Double = 1.50,        // entry score 65+   → 1.50x size
    // 5. Liquidity/volume gate (replaces crude time filter)
    val liquidityGateEnabled: Boolean = true,
    val minLiquidityUsd: Double = 8000.0,      // skip if liquidity < $8K
    val minVolLiqRatio: Double = 0.30,         // skip if vol/liquidity < 0.3 (thin market)
    // 6. Sentiment opt-in (only active when keys configured)
    val sentimentRequiresKeys: Boolean = false, // FIX 7: local sentiment always on; LLM needs keys
    // 7. Cross-token correlation guard
    val crossTokenGuardEnabled: Boolean = true,
    val crossTokenWindowMins: Double = 15.0,   // window to detect clustered entries
    val crossTokenMaxCluster: Int = 2,         // max entries in window before size penalty
    val crossTokenSizePenalty: Double = 0.50,  // reduce size by this on 3rd+ clustered entry

    // ── Full Solana Market Scanner ────────────────────────────────────
    val fullMarketScanEnabled: Boolean = true,   // master switch
    val scanIntervalSecs: Int = 45,              // scan all sources every N seconds
    val maxWatchlistSize: Int = 50,  // FIX 6: raised — parallel processing makes extra slots free              // auto-watchlist cap (pump.fun was 20)
    val minDiscoveryScore: Double = 35.0,        // min composite score to add token
    val scanMinMcapUsd: Double = 5_000.0,
    val scalingModeEnabled: Boolean = true,
    val scalingLogEnabled: Boolean = true,
    val scalingTierOverride: String = "",
    val scanMaxMcapUsd: Double = 0.0,            // 0 = no upper limit
    val allowedDexes: List<String> = emptyList(),// empty = all DEXs; or ["raydium","orca"]

    // Source toggles — flip individual sources on/off
    val scanPumpFunNew: Boolean = true,          // new pump.fun launches
    val scanPumpGraduates: Boolean = true,       // pump.fun → Raydium graduates (high signal)
    val scanDexTrending: Boolean = true,         // dexscreener trending Solana
    val scanDexGainers: Boolean = true,          // top % gainers
    val scanDexBoosted: Boolean = true,          // paid-boosted tokens
    val scanBirdeyeTrending: Boolean = true,     // birdeye trending (needs key)
    val scanRaydiumNew: Boolean = true,          // new Raydium pools

    // Narrative scanning — keyword list for themed plays (AI, DeSci, RWA, etc.)
    val narrativeScanEnabled: Boolean = false,
    val narrativeKeywords: List<String> = listOf("ai agent","depin","rwa","desci","gamefi"),
    // ── Top-up / pyramid strategy ────────────────────────────────────
    // Adds to a winning position when momentum confirms the move is genuine.
    // Each top-up is smaller than the last — pyramiding into strength, not chasing.
    val topUpEnabled: Boolean = true,           // master switch
    val topUpMaxCount: Int = 3,                 // max top-ups per position (default 3)
    val topUpMinGainPct: Double = 25.0,         // minimum gain before first top-up
    val topUpGainStepPct: Double = 30.0,        // gain increment between top-ups
                                                //   e.g. 25% → 55% → 85% → 115%
    val topUpSizeMultiplier: Double = 0.50,     // each top-up = 50% of initial size
                                                //   shrinks further with each add
    val topUpMinCooldownMins: Double = 5.0,     // min minutes between top-ups
    val topUpRequireEmaFan: Boolean = true,     // require BULL_FAN before topping up
    val topUpMaxTotalSol: Double = 0.50,        // max total position size including top-ups
    // ── Remote kill switch ─────────────────────────────────────────────
    // Host a JSON file anywhere (GitHub Gist, your server) for emergency control.
    // Empty URL = disabled. See RemoteKillSwitch.kt for JSON format.
    val remoteConfigUrl: String = "",
    val remoteConfigPollSecs: Int = 60,
    // ── Jito MEV Protection ────────────────────────────────────────────
    val jitoEnabled: Boolean = true,            // use Jito bundles for MEV protection
    val jitoTipLamports: Long = 10000,          // tip per bundle (0.00001 SOL default)
    // ── Auto-Compound ──────────────────────────────────────────────────
    val autoCompoundEnabled: Boolean = true,
    val compoundTreasuryPct: Double = 20.0,     // % of profit to treasury
    val compoundPoolPct: Double = 40.0,         // % of profit to compound pool
    val compoundWalletPct: Double = 40.0,       // % of profit to keep liquid
    val compoundThreshold: Double = 0.5,        // SOL needed to boost position size
    // ── Anti-Rug Settings ──────────────────────────────────────────────
    val antiRugEnabled: Boolean = true,
    val antiRugBlockCritical: Boolean = true,   // auto-block CRITICAL risk tokens
    val antiRugMaxRiskScore: Int = 60,          // max risk score to trade (0-100)
)

/** Persists config — private key stored in EncryptedSharedPreferences */
object ConfigStore {

    private const val FILE = "bot_config"
    private const val KEY_FILE = "bot_secrets"

    fun save(ctx: Context, cfg: BotConfig) {
        secrets(ctx).edit().apply {
            putString("private_key_b58",     cfg.privateKeyB58)
            putString("telegram_bot_token",  cfg.telegramBotToken)
            putString("telegram_chat_id",    cfg.telegramChatId)
            putBoolean("telegram_trade_alerts", cfg.telegramTradeAlerts)
            putString("helius_api_key",      cfg.heliusApiKey)
            putString("birdeye_api_key",     cfg.birdeyeApiKey)
            putString("groq_api_key",        cfg.groqApiKey)
            apply()
        }
        prefs(ctx).edit().apply {
            putString("wallet_address",               cfg.walletAddress)
            putString("rpc_url",                      cfg.rpcUrl)
            putBoolean("paper_mode",                  cfg.paperMode)
            putBoolean("auto_trade",                  cfg.autoTrade)
            putString("watchlist",                    cfg.watchlist.joinToString(","))
            putString("active_token",                 cfg.activeToken)
            putFloat("small_buy_sol",                 cfg.smallBuySol.toFloat())
            putFloat("large_buy_sol",                 cfg.largeBuySol.toFloat())
            putFloat("large_buy_min_wallet_sol",      cfg.largeBuyMinWalletSol.toFloat())
            putFloat("max_position_sol",              cfg.maxPositionSol.toFloat())
            putFloat("stop_loss_pct",                 cfg.stopLossPct.toFloat())
            putFloat("trailing_stop_base_pct",        cfg.trailingStopBasePct.toFloat())
            putInt("slippage_bps",                    cfg.slippageBps)
            putFloat("exit_score_threshold",          cfg.exitScoreThreshold.toFloat())
            putInt("momentum_exit_candles",           cfg.momentumExitCandles)
            putInt("entry_cooldown_sec",              cfg.entryCooldownSec)
            putInt("poll_seconds",                    cfg.pollSeconds)
            putString("telegram_channels",            cfg.telegramChannels.joinToString(","))
            putBoolean("sentiment_enabled",           cfg.sentimentEnabled)
            putInt("sentiment_poll_mins",             cfg.sentimentPollMins)
            putFloat("sentiment_block_threshold",     cfg.sentimentBlockThreshold.toFloat())
            putFloat("sentiment_boost_threshold",     cfg.sentimentBoostThreshold.toFloat())
            putFloat("sentiment_entry_boost",         cfg.sentimentEntryBoost.toFloat())
            putFloat("sentiment_exit_boost",          cfg.sentimentExitBoost.toFloat())
            putFloat("wallet_reserve_sol",            cfg.walletReserveSol.toFloat())
            putFloat("max_daily_loss_pct",            cfg.maxDailyLossPct.toFloat())
            putInt("circuit_breaker_losses",          cfg.circuitBreakerLosses)
            putInt("circuit_breaker_pause_min",       cfg.circuitBreakerPauseMin)
            putFloat("max_price_impact_pct",          cfg.maxPriceImpactPct.toFloat())
            putBoolean("auto_add_new_tokens",         cfg.autoAddNewTokens)
            putInt("max_concurrent_positions",        cfg.maxConcurrentPositions)
            putFloat("max_total_exposure_sol",        cfg.maxTotalExposureSol.toFloat())
            putFloat("min_hold_mins",                 cfg.minHoldMins.toFloat())
            putFloat("max_hold_mins_hard",            cfg.maxHoldMinsHard.toFloat())
            putBoolean("long_hold_enabled",           cfg.longHoldEnabled)
            putFloat("long_hold_max_days",            cfg.longHoldMaxDays.toFloat())
            putFloat("long_hold_min_gain_pct",        cfg.longHoldMinGainPct.toFloat())
            putFloat("long_hold_min_liquidity_usd",   cfg.longHoldMinLiquidityUsd.toFloat())
            putInt("long_hold_min_holders",           cfg.longHoldMinHolders)
            putFloat("long_hold_holder_growth_min",   cfg.longHoldHolderGrowthMin.toFloat())
            putFloat("long_hold_wallet_pct",          cfg.longHoldWalletPct.toFloat())
            putBoolean("long_hold_treasury_gate",     cfg.longHoldTreasuryGate)
            putFloat("hold_extend_vol_threshold",     cfg.holdExtendVolThreshold.toFloat())
            putFloat("hold_extend_press_threshold",   cfg.holdExtendPressThreshold.toFloat())
            putBoolean("sound_enabled",               cfg.soundEnabled)
            putBoolean("sound_on_new_token",          cfg.soundOnNewToken)
            putBoolean("auto_mode",                   cfg.autoMode)
            putInt("trading_pause_utc_start",         cfg.tradingPauseUtcStart)
            putInt("trading_pause_utc_end",           cfg.tradingPauseUtcEnd)
            putInt("defensive_loss_threshold",        cfg.defensiveLossThreshold)
            putFloat("aggressive_whale_threshold",    cfg.aggressiveWhaleThreshold.toFloat())
            putBoolean("copy_trading_enabled",        cfg.copyTradingEnabled)
            putFloat("copy_size_multiplier",          cfg.copySizeMultiplier.toFloat())
            putBoolean("use_time_filter",             cfg.useTimeFilter)
            putBoolean("top_up_enabled",              cfg.topUpEnabled)
            putInt("top_up_max_count",                cfg.topUpMaxCount)
            putFloat("top_up_min_gain_pct",           cfg.topUpMinGainPct.toFloat())
            putFloat("top_up_gain_step_pct",          cfg.topUpGainStepPct.toFloat())
            putFloat("top_up_size_multiplier",        cfg.topUpSizeMultiplier.toFloat())
            putFloat("top_up_min_cooldown_mins",      cfg.topUpMinCooldownMins.toFloat())
            putBoolean("top_up_require_ema_fan",      cfg.topUpRequireEmaFan)
            putFloat("top_up_max_total_sol",          cfg.topUpMaxTotalSol.toFloat())
            putInt("grind_exhaust_candles",           cfg.grindExhaustCandles)
            putBoolean("partial_sell_enabled",        cfg.partialSellEnabled)
            putFloat("partial_sell_trigger",          cfg.partialSellTriggerPct.toFloat())
            putFloat("partial_sell_fraction",         cfg.partialSellFraction.toFloat())
            putFloat("partial_sell_trigger2",         cfg.partialSellSecondTriggerPct.toFloat())
            putFloat("partial_sell_trigger3",         cfg.partialSellThirdTriggerPct.toFloat())
            putBoolean("partial_sell_third_enabled",  cfg.partialSellThirdEnabled)
            putBoolean("conviction_sizing",           cfg.convictionSizingEnabled)
            putFloat("conviction_mult1",              cfg.convictionMult1.toFloat())
            putFloat("conviction_mult2",              cfg.convictionMult2.toFloat())
            putBoolean("liquidity_gate",              cfg.liquidityGateEnabled)
            putFloat("min_liquidity_usd",             cfg.minLiquidityUsd.toFloat())
            putFloat("min_vol_liq_ratio",             cfg.minVolLiqRatio.toFloat())
            putBoolean("cross_token_guard",           cfg.crossTokenGuardEnabled)
            putFloat("cross_token_window",            cfg.crossTokenWindowMins.toFloat())
            putBoolean("full_market_scan",             cfg.fullMarketScanEnabled)
            putInt("scan_interval_secs",               cfg.scanIntervalSecs)
            putInt("max_watchlist_size",               cfg.maxWatchlistSize)
            putFloat("min_discovery_score",            cfg.minDiscoveryScore.toFloat())
            putFloat("scan_min_mcap",                  cfg.scanMinMcapUsd.toFloat())
            putBoolean("scaling_mode_enabled", cfg.scalingModeEnabled)
            putBoolean("scaling_log_enabled", cfg.scalingLogEnabled)
            putString("scaling_tier_override", cfg.scalingTierOverride)
            putFloat("scan_max_mcap",                  cfg.scanMaxMcapUsd.toFloat())
            putBoolean("scan_pump_new",                cfg.scanPumpFunNew)
            putBoolean("scan_pump_graduates",          cfg.scanPumpGraduates)
            putBoolean("scan_dex_trending",            cfg.scanDexTrending)
            putBoolean("scan_dex_gainers",             cfg.scanDexGainers)
            putBoolean("scan_dex_boosted",             cfg.scanDexBoosted)
            putBoolean("scan_raydium_new",             cfg.scanRaydiumNew)
            putBoolean("narrative_scan",               cfg.narrativeScanEnabled)
            putString("narrative_keywords",            cfg.narrativeKeywords.joinToString(","))
            putString("remote_config_url",             cfg.remoteConfigUrl)
            putInt("remote_config_poll_secs",          cfg.remoteConfigPollSecs)
            apply()
        }
    }

    fun load(ctx: Context): BotConfig {
        val p = prefs(ctx)
        val s = secrets(ctx)
        return BotConfig(
            privateKeyB58               = s.getString("private_key_b58", "") ?: "",
            walletAddress               = p.getString("wallet_address", "") ?: "",
            rpcUrl                      = p.getString("rpc_url", "https://api.mainnet-beta.solana.com") ?: "https://api.mainnet-beta.solana.com",
            paperMode                   = p.getBoolean("paper_mode", true),
            autoTrade                   = p.getBoolean("auto_trade", false),
            watchlist                   = (p.getString("watchlist", "") ?: "").split(",").filter { it.isNotBlank() },
            activeToken                 = p.getString("active_token", "") ?: "",
            smallBuySol                 = p.getFloat("small_buy_sol", 0.05f).toDouble(),
            largeBuySol                 = p.getFloat("large_buy_sol", 0.10f).toDouble(),
            largeBuyMinWalletSol        = p.getFloat("large_buy_min_wallet_sol", 0.20f).toDouble(),
            maxPositionSol              = p.getFloat("max_position_sol", 0.15f).toDouble(),
            stopLossPct                 = p.getFloat("stop_loss_pct", 10.0f).toDouble(),
            trailingStopBasePct         = p.getFloat("trailing_stop_base_pct", 8.0f).toDouble(),
            slippageBps                 = p.getInt("slippage_bps", 200),
            exitScoreThreshold          = p.getFloat("exit_score_threshold", 58.0f).toDouble(),
            momentumExitCandles         = p.getInt("momentum_exit_candles", 3),
            entryCooldownSec            = p.getInt("entry_cooldown_sec", 120),
            pollSeconds                 = p.getInt("poll_seconds", 8),
            telegramBotToken            = s.getString("telegram_bot_token", "") ?: "",
            telegramChatId              = s.getString("telegram_chat_id", "") ?: "",
            telegramTradeAlerts         = p.getBoolean("telegram_trade_alerts", false),
            telegramChannels            = (p.getString("telegram_channels", "") ?: "").split(",").filter { it.isNotBlank() },
            sentimentEnabled            = p.getBoolean("sentiment_enabled", true),
            sentimentPollMins           = p.getInt("sentiment_poll_mins", 3),
            sentimentBlockThreshold     = p.getFloat("sentiment_block_threshold", -40.0f).toDouble(),
            sentimentBoostThreshold     = p.getFloat("sentiment_boost_threshold", 30.0f).toDouble(),
            sentimentEntryBoost         = p.getFloat("sentiment_entry_boost", 20.0f).toDouble(),
            sentimentExitBoost          = p.getFloat("sentiment_exit_boost", 10.0f).toDouble(),
            walletReserveSol            = p.getFloat("wallet_reserve_sol", 0.05f).toDouble(),
            maxDailyLossPct             = p.getFloat("max_daily_loss_pct", 10.0f).toDouble(),
            circuitBreakerLosses        = p.getInt("circuit_breaker_losses", 5),
            circuitBreakerPauseMin      = p.getInt("circuit_breaker_pause_min", 15),
            maxPriceImpactPct           = p.getFloat("max_price_impact_pct", 3.0f).toDouble(),
            heliusApiKey                = s.getString("helius_api_key", "") ?: "",
            birdeyeApiKey               = s.getString("birdeye_api_key", "") ?: "",
            groqApiKey                  = s.getString("groq_api_key", "") ?: "",
            autoAddNewTokens            = p.getBoolean("auto_add_new_tokens", false),
            maxConcurrentPositions      = p.getInt("max_concurrent_positions", 3),
            maxTotalExposureSol         = p.getFloat("max_total_exposure_sol", 0.30f).toDouble(),
            minHoldMins                 = p.getFloat("min_hold_mins", 3.0f).toDouble(),
            maxHoldMinsHard             = p.getFloat("max_hold_mins_hard", 120.0f).toDouble(),
            longHoldEnabled             = p.getBoolean("long_hold_enabled", true),
            longHoldMaxDays             = p.getFloat("long_hold_max_days", 30.0f).toDouble(),
            longHoldMinGainPct          = p.getFloat("long_hold_min_gain_pct", 0.0f).toDouble(),
            longHoldMinLiquidityUsd     = p.getFloat("long_hold_min_liquidity_usd", 25000.0f).toDouble(),
            longHoldMinHolders          = p.getInt("long_hold_min_holders", 500),
            longHoldHolderGrowthMin     = p.getFloat("long_hold_holder_growth_min", 2.0f).toDouble(),
            longHoldWalletPct           = p.getFloat("long_hold_wallet_pct", 0.25f).toDouble(),
            longHoldTreasuryGate        = p.getBoolean("long_hold_treasury_gate", true),
            holdExtendVolThreshold      = p.getFloat("hold_extend_vol_threshold", 60.0f).toDouble(),
            holdExtendPressThreshold    = p.getFloat("hold_extend_press_threshold", 58.0f).toDouble(),
            soundEnabled                = p.getBoolean("sound_enabled", true),
            soundOnNewToken             = p.getBoolean("sound_on_new_token", true),
            autoMode                    = p.getBoolean("auto_mode", true),
            tradingPauseUtcStart        = p.getInt("trading_pause_utc_start", 1),
            tradingPauseUtcEnd          = p.getInt("trading_pause_utc_end", 7),
            defensiveLossThreshold      = p.getInt("defensive_loss_threshold", 3),
            aggressiveWhaleThreshold    = p.getFloat("aggressive_whale_threshold", 70.0f).toDouble(),
            copyTradingEnabled          = p.getBoolean("copy_trading_enabled", false),
            copySizeMultiplier          = p.getFloat("copy_size_multiplier", 1.0f).toDouble(),
            useTimeFilter               = p.getBoolean("use_time_filter", true),
            topUpEnabled                = p.getBoolean("top_up_enabled", true),
            topUpMaxCount               = p.getInt("top_up_max_count", 3),
            topUpMinGainPct             = p.getFloat("top_up_min_gain_pct", 25.0f).toDouble(),
            topUpGainStepPct            = p.getFloat("top_up_gain_step_pct", 30.0f).toDouble(),
            topUpSizeMultiplier         = p.getFloat("top_up_size_multiplier", 0.50f).toDouble(),
            topUpMinCooldownMins        = p.getFloat("top_up_min_cooldown_mins", 5.0f).toDouble(),
            topUpRequireEmaFan          = p.getBoolean("top_up_require_ema_fan", true),
            topUpMaxTotalSol            = p.getFloat("top_up_max_total_sol", 0.50f).toDouble(),
            grindExhaustCandles         = p.getInt("grind_exhaust_candles", 5),
            partialSellEnabled          = p.getBoolean("partial_sell_enabled", true),
            partialSellTriggerPct       = p.getFloat("partial_sell_trigger", 200.0f).toDouble(),
            partialSellFraction         = p.getFloat("partial_sell_fraction", 0.25f).toDouble(),
            partialSellSecondTriggerPct = p.getFloat("partial_sell_trigger2", 500.0f).toDouble(),
            partialSellThirdTriggerPct  = p.getFloat("partial_sell_trigger3", 2000.0f).toDouble(),
            partialSellThirdEnabled     = p.getBoolean("partial_sell_third_enabled", true),
            convictionSizingEnabled     = p.getBoolean("conviction_sizing", true),
            convictionMult1             = p.getFloat("conviction_mult1", 1.25f).toDouble(),
            convictionMult2             = p.getFloat("conviction_mult2", 1.50f).toDouble(),
            liquidityGateEnabled        = p.getBoolean("liquidity_gate", true),
            minLiquidityUsd             = p.getFloat("min_liquidity_usd", 8000.0f).toDouble(),
            minVolLiqRatio              = p.getFloat("min_vol_liq_ratio", 0.30f).toDouble(),
            crossTokenGuardEnabled      = p.getBoolean("cross_token_guard", true),
            crossTokenWindowMins        = p.getFloat("cross_token_window", 15.0f).toDouble(),
            fullMarketScanEnabled       = p.getBoolean("full_market_scan", true),
            scanIntervalSecs            = p.getInt("scan_interval_secs", 45),
            maxWatchlistSize            = p.getInt("max_watchlist_size", 30),
            minDiscoveryScore           = p.getFloat("min_discovery_score", 35.0f).toDouble(),
            scanMinMcapUsd              = p.getFloat("scan_min_mcap", 5000.0f).toDouble(),
            scalingModeEnabled = p.getBoolean("scaling_mode_enabled", true),
            scalingLogEnabled  = p.getBoolean("scaling_log_enabled", true),
            scalingTierOverride = p.getString("scaling_tier_override", "") ?: "",
            scanMaxMcapUsd              = p.getFloat("scan_max_mcap", 0.0f).toDouble(),
            scanPumpFunNew              = p.getBoolean("scan_pump_new", true),
            scanPumpGraduates           = p.getBoolean("scan_pump_graduates", true),
            scanDexTrending             = p.getBoolean("scan_dex_trending", true),
            scanDexGainers              = p.getBoolean("scan_dex_gainers", true),
            scanDexBoosted              = p.getBoolean("scan_dex_boosted", true),
            scanRaydiumNew              = p.getBoolean("scan_raydium_new", true),
            narrativeScanEnabled        = p.getBoolean("narrative_scan", false),
            narrativeKeywords           = (p.getString("narrative_keywords","") ?: "")
                                          .split(",").filter { it.isNotBlank() },
            remoteConfigUrl             = p.getString("remote_config_url", "") ?: "",
            remoteConfigPollSecs        = p.getInt("remote_config_poll_secs", 60),
        )
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun secrets(ctx: Context): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, KEY_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
