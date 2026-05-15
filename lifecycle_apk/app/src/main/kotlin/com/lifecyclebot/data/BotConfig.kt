package com.lifecyclebot.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class BotConfig(
    // wallet
    val privateKeyB58: String = "",
    val walletAddress: String = "",
    // V5.9.495z26 — separate treasury wallet for income separation.
    // Auto-generated on first init by TreasuryWalletManager if blank.
    val treasuryPrivateKeyB58: String = "",
    // V5.9.495z29 — operator spec item 8: high-throughput live mode +
    // explicit live-trade rate-limit / quota / verification controls.
    // The LiveExecutionGate is configured from these values at bot start
    // and on every config save.
    val highThroughputLiveMode: Boolean = false,
    val maxLiveTradesPerDay: Int = 500,
    val maxConcurrentLivePositions: Int = 12,
    val minSecondsBetweenLiveBuys: Int = 4,
    val maxPendingBuyVerifications: Int = 6,
    val maxPendingSellVerifications: Int = 8,
    val hotPathTimeoutMs: Long = 10_000L,
    val walletReconcileTimeoutMs: Long = 12_000L,
    val skipSlowBackgroundScansWhenLiveBusy: Boolean = true,
    val rpcUrl: String = "",  // User provides their own RPC URL in settings (Helius, QuickNode, etc.)
    // mode
    val paperMode: Boolean = true,
    val shadowPaperEnabled: Boolean = false,  // V5.9.773 — was true. Default-on shadow paper bled paper trades into LIVE UI (Open Positions, Treasury Scalps, Project Sniper all polluted). Operator must explicitly opt in.
    val moonshotOverrideEnabled: Boolean = true,  // In paper/shadow mode, execute LIVE buys for moonshots (score>=85, quality A/B)
    val fluidLearningEnabled: Boolean = true,  // Enable fluid scaling & all profit tools in paper/shadow mode
    val paperSimulatedBalance: Double = 11.76,   // ~$1000 USD starting paper balance (at ~$85 SOL)
    val autoTrade: Boolean = true,  // ENABLED BY DEFAULT - bot is autonomous
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
    val slippageBps: Int = 100,   // V5.9.103: default 1%, hard cap 5% enforced on load
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
    // V4.0: Discord webhook alerts
    val discordWebhookUrl: String = "",       // Discord webhook URL for trade alerts
    val discordTradeAlerts: Boolean = false,  // send BUY/SELL notifications to Discord
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
    val closePositionsOnStop: Boolean = true, // SAFETY: close all positions when bot stops
    // external API keys (all free)
    val heliusApiKey: String = "",  // helius.dev — faster RPC + real-time WS
    val birdeyeApiKey: String = "",     // birdeye.so — free, OHLCV candles
    val groqApiKey: String = "",        // console.groq.com — free LLM sentiment
    val geminiApiKey: String = "sk-emergent-431Dd41D3F186C0E0B",      // Emergent universal LLM key — hardcoded default
    val jupiterApiKey: String = "",     // portal.jup.ag — required for Ultra API
    val geminiEnabled: Boolean = true,     // Enable Gemini AI Co-pilot (narrative analysis, exit advice, trade reasoning)
    val autoAddNewTokens: Boolean = true, // ENABLED - auto-add new Pump.fun launches to watchlist
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
    // notifications & sounds
    val notificationsEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val soundOnNewToken: Boolean = true,
    // UI theme
    val darkModeEnabled: Boolean = true,      // true = dark mode (default), false = light mode
    // auto mode
    val autoMode: Boolean = true,           // auto-switch between modes
    val tradingPauseUtcStart: Int = 4,      // UTC hour to pause (default 4am - shortest dead window)
    val tradingPauseUtcEnd: Int = 6,        // UTC hour to resume (default 6am - only 2 hours pause)
    val defensiveLossThreshold: Int = 3,    // losses before defensive mode
    val aggressiveWhaleThreshold: Double = 70.0,
    // V5.2: Behavior Dashboard Aggression Tuning
    // 0 = Ultra Defensive, 5 = Normal, 11 = Goes to 11 (maximum aggression)
    val behaviorAggressionLevel: Int = 5,    // Default to NORMAL (middle)
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
    val minLiquidityUsd: Double = 1500.0,      // skip if liquidity < $1.5K (lowered from $3K for more trades)
    val minVolLiqRatio: Double = 0.20,         // skip if vol/liquidity < 0.2 (lowered from 0.3)
    // 6. Sentiment opt-in (only active when keys configured)
    val sentimentRequiresKeys: Boolean = false, // FIX 7: local sentiment always on; LLM needs keys
    // 7. Cross-token correlation guard
    val crossTokenGuardEnabled: Boolean = true,
    val crossTokenWindowMins: Double = 15.0,   // window to detect clustered entries
    val crossTokenMaxCluster: Int = 2,         // max entries in window before size penalty
    val crossTokenSizePenalty: Double = 0.50,  // reduce size by this on 3rd+ clustered entry

    // ── Full Solana Market Scanner ────────────────────────────────────
    val fullMarketScanEnabled: Boolean = true,   // master switch
    val scanIntervalSecs: Int = 8,               // scan every 8 seconds (FASTER)
    val maxWatchlistSize: Int = 500,             // V5.9.604: widened meme watchlist bench to 500 tokens
    val minDiscoveryScore: Double = 5.0,         // Very low - let everything through
    val scanMinMcapUsd: Double = 0.0,            // no min mcap
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
    // ── Cloud Learning Sync ──────────────────────────────────────────────
    // Share learnings with community for collective intelligence
    val cloudSyncEnabled: Boolean = true,       // opt-in to share learnings (privacy-safe)
    val cloudUseCommWeights: Boolean = true,    // use community-learned weights
    // ── Historical Chart Scanner ────────────────────────────────────────
    // Backtest and learn from historical charts across the SOL network
    val autoHistoricalScanEnabled: Boolean = true,  // auto-scan on startup (if >12h since last)
    val historicalScanHoursBack: Int = 24,          // how many hours of history to scan
    val historicalScanMinLiquidity: Double = 2000.0, // min liquidity for tokens to scan
    // ── Turso Collective Learning ────────────────────────────────────────
    // Shared knowledge base across all AATE instances (ENABLED BY DEFAULT)
    val tursoDbUrl: String = "libsql://superbrain-shaunhayes333-stack.aws-ap-northeast-1.turso.io",
    val tursoAuthToken: String = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3NzU1NTE3MzQsImlkIjoiMDE5ZDMwNjYtMmUwMS03NzcyLTgyMTYtMDIyYzY1YzRmNmVjIiwicmlkIjoiMGExMzRiY2EtZmY1YS00NmQ2LWI2ZWYtYmU4MjAyYWE1ZWI4In0.PNhzeQw2rXloG3cDJaOPRg-Kq6rCpOy5kk6Q6GCD8Ar_AKC2iiW5OTKoK-q3Y78LFPWp_8ttrEhtlPz0VJ_VDw",
    val collectiveLearningEnabled: Boolean = true,  // Enable collective learning sync
    // ── V3 Scoring Engine ─────────────────────────────────────────────────
    // V3.2: V3 is now the PRIMARY and ONLY decision engine
    // Legacy flow is deprecated and will be removed in future versions
    val v3EngineEnabled: Boolean = true,            // V3.2: ENABLED by default (production ready)
    val v3ShadowMode: Boolean = false,              // V3.2: DISABLED - V3 controls execution
    val v3MinScoreToTrade: Int = 20,                // V3.2: Lowered from 55 - minimum unified score to consider trade
    val v3MaxExposurePct: Double = 70.0,            // Max wallet exposure in V3 mode
    val v3ConservativeMode: Boolean = false,        // More conservative scoring thresholds
    val classicScoringMode: Boolean = true,          // V5.9.326: Use build ~1920 classic scorer (20 inner layers, no outer ring, no TrustNet/MuteBoost/genEq/CrossTalk/approvalMemory)
    // V5.7.3: Network Signal Auto-Buy ─────────────────────────────────────
    // Auto-buy from network signals (Copy Trade from Hive)
    val autoTradeNetworkSignals: Boolean = false,   // DISABLED by default - user must opt-in
    val autoTradeNetworkSignalsMegaWinner: Boolean = true,  // Auto-buy MEGA_WINNER signals
    val autoTradeNetworkSignalsHotToken: Boolean = false,   // Auto-buy HOT_TOKEN signals
    val autoTradeNetworkSignalsMinAcks: Int = 2,            // Min confirmations required
    val autoTradeNetworkSignalsMaxDaily: Int = 10,          // Max auto-buys per day
    
    // ── V5.7.6: Trading Mode Selection ────────────────────────────────────
    // Control which auto-traders run: Meme coins, Markets (Perps/Stocks/etc), or Both
    // 0 = MEME only (original bot), 1 = MARKETS only, 2 = BOTH
    val tradingMode: Int = 2,  // Default: BOTH - run all traders
    val memeTraderEnabled: Boolean = true,      // Meme coin trader (original bot)
    val marketsTraderEnabled: Boolean = true,   // All market traders (Stocks, Commodities, Metals, Forex, Perps)
    val cryptoAltsEnabled: Boolean = true,      // Crypto Alt trader (BNB/ETH/SOL/Polygon alts) — runs 24/7
    // V5.7.7: Individual Markets sub-trader toggles (only take effect when marketsTraderEnabled = true)
    val stocksEnabled: Boolean = true,          // Tokenized Stocks trader
    val commoditiesEnabled: Boolean = true,     // Commodities trader (Oil, Gas, Agriculture)
    val metalsEnabled: Boolean = true,          // Metals trader (Gold, Silver, Industrial)
    val forexEnabled: Boolean = true,           // Forex trader (Major, Cross, EM pairs)
    val perpsEnabled: Boolean = true,           // SOL Perps trader

    // ═══ Cyclic Trade Ring ($500 USD compound ring) ═══
    val cyclicTradeEnabled: Boolean = true,         // V5.9.222: Always on by default — paper runs permanently
    val cyclicTradeLiveEnabled: Boolean = false,     // Force live execution (overrides treasury threshold)
)

/** Persists config — private key stored in EncryptedSharedPreferences */
object ConfigStore {

    private const val FILE = "bot_config"
    private const val KEY_FILE = "bot_secrets"

    // V5.9.706 — Cache full BotConfig to avoid repeated AES-GCM decryption on main thread.
    // EncryptedSharedPreferences decrypts each getString() via Binder IPC + AES-GCM on every call.
    // With 16 call sites per 2.5s UI tick this stalled the main thread for 600-2000ms per frame.
    // 2s TTL: safe because BotViewModel polls config on IO thread every 2500ms;
    // settings changes call save() which calls invalidateCache() to flush immediately.
    @Volatile private var cachedConfig: BotConfig? = null
    @Volatile private var cachedConfigMs: Long = 0L
    private const val CONFIG_CACHE_MS = 2_000L

    fun invalidateCache() {
        cachedConfig = null
        cachedConfigMs = 0L
    }

    fun save(ctx: Context, cfg: BotConfig) {
        invalidateCache() // V5.9.706 — flush stale cache on write
        // V5.9.495z31 — publish authoritative paperMode/autoTrade to
        // the RuntimeModeAuthority so every reader sees the same mode.
        com.lifecyclebot.engine.RuntimeModeAuthority.publishConfig(
            paperMode = cfg.paperMode,
            autoTrade = cfg.autoTrade,
        )
        secrets(ctx).edit().apply {
            putString("private_key_b58",     cfg.privateKeyB58)
            // V5.9.495z26 — treasury wallet private key (encrypted alongside trading key)
            putString("treasury_private_key_b58", cfg.treasuryPrivateKeyB58)
            putString("telegram_bot_token",  cfg.telegramBotToken)
            putString("telegram_chat_id",    cfg.telegramChatId)
            putBoolean("telegram_trade_alerts", cfg.telegramTradeAlerts)
            // V4.0: Discord
            putString("discord_webhook_url", cfg.discordWebhookUrl)
            putBoolean("discord_trade_alerts", cfg.discordTradeAlerts)
            putString("helius_api_key",      cfg.heliusApiKey)
            putString("birdeye_api_key",     cfg.birdeyeApiKey)
            putString("groq_api_key",        cfg.groqApiKey)
            putString("gemini_api_key",      cfg.geminiApiKey)
            putString("jupiter_api_key",     cfg.jupiterApiKey)
            putString("turso_db_url",        cfg.tursoDbUrl)
            putString("turso_auth_token",    cfg.tursoAuthToken)
            apply()
        }
        prefs(ctx).edit().apply {
            putString("wallet_address",               cfg.walletAddress)
            putString("rpc_url",                      cfg.rpcUrl)
            putBoolean("paper_mode",                  cfg.paperMode)
            // V5.9.495z29 — operator spec item 8: throughput controls.
            putBoolean("ht_live_mode",                  cfg.highThroughputLiveMode)
            putInt    ("ht_max_trades_per_day",         cfg.maxLiveTradesPerDay)
            putInt    ("ht_max_concurrent_live",        cfg.maxConcurrentLivePositions)
            putInt    ("ht_min_seconds_between_buys",   cfg.minSecondsBetweenLiveBuys)
            putInt    ("ht_max_pending_buy_verif",      cfg.maxPendingBuyVerifications)
            putInt    ("ht_max_pending_sell_verif",     cfg.maxPendingSellVerifications)
            putLong   ("ht_hot_path_timeout_ms",        cfg.hotPathTimeoutMs)
            putLong   ("ht_wallet_reconcile_timeout_ms", cfg.walletReconcileTimeoutMs)
            putBoolean("ht_skip_slow_scans_when_busy",  cfg.skipSlowBackgroundScansWhenLiveBusy)
            putBoolean("shadow_paper_enabled",        cfg.shadowPaperEnabled)
            putBoolean("moonshot_override_enabled",   cfg.moonshotOverrideEnabled)
            putBoolean("fluid_learning_enabled",      cfg.fluidLearningEnabled)
            putFloat("paper_simulated_balance",       cfg.paperSimulatedBalance.toFloat())
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
            putBoolean("close_positions_on_stop",     cfg.closePositionsOnStop)
            putBoolean("auto_add_new_tokens",         cfg.autoAddNewTokens)
            putBoolean("gemini_enabled",              cfg.geminiEnabled)
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
            putBoolean("notifications_enabled",       cfg.notificationsEnabled)
            putBoolean("vibration_enabled",           cfg.vibrationEnabled)
            putBoolean("sound_enabled",               cfg.soundEnabled)
            putBoolean("sound_on_new_token",          cfg.soundOnNewToken)
            putBoolean("dark_mode_enabled",           cfg.darkModeEnabled)
            putBoolean("auto_mode",                   cfg.autoMode)
            putInt("trading_pause_utc_start",         cfg.tradingPauseUtcStart)
            putInt("trading_pause_utc_end",           cfg.tradingPauseUtcEnd)
            putInt("defensive_loss_threshold",        cfg.defensiveLossThreshold)
            putFloat("aggressive_whale_threshold",    cfg.aggressiveWhaleThreshold.toFloat())
            putInt("behavior_aggression_level",       cfg.behaviorAggressionLevel)
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
            putBoolean("collective_learning_enabled",  cfg.collectiveLearningEnabled)
            // V5.7.6: Trading Mode
            putInt("trading_mode",                     cfg.tradingMode)
            putBoolean("meme_trader_enabled",          cfg.memeTraderEnabled)
            putBoolean("markets_trader_enabled",       cfg.marketsTraderEnabled)
            putBoolean("crypto_alts_enabled",          cfg.cryptoAltsEnabled)
            // V5.7.7: Individual Markets sub-trader toggles
            putBoolean("stocks_enabled",               cfg.stocksEnabled)
            putBoolean("commodities_enabled",          cfg.commoditiesEnabled)
            putBoolean("metals_enabled",               cfg.metalsEnabled)
            putBoolean("forex_enabled",                cfg.forexEnabled)
            putBoolean("perps_enabled",                cfg.perpsEnabled)
            // Cyclic Trade Ring
            putBoolean("cyclic_trade_enabled",       cfg.cyclicTradeEnabled)
            putBoolean("cyclic_trade_live_enabled",  cfg.cyclicTradeLiveEnabled)
            // V5.9.326: classic scoring mode
            putBoolean("classic_scoring_mode",         cfg.classicScoringMode)
            apply()
        }
    }

    fun load(ctx: Context): BotConfig {
        // V5.9.706 — serve from cache if fresh (avoids repeated AES-GCM decryption on main thread)
        val now = System.currentTimeMillis()
        cachedConfig?.let { if (now - cachedConfigMs < CONFIG_CACHE_MS) return it }
        val p = prefs(ctx)
        val s = secrets(ctx)
        return BotConfig(
            privateKeyB58               = s.getString("private_key_b58", "") ?: "",
            // V5.9.495z26 — load treasury wallet private key (auto-generated by TreasuryWalletManager if blank)
            treasuryPrivateKeyB58       = s.getString("treasury_private_key_b58", "") ?: "",
            walletAddress               = p.getString("wallet_address", "") ?: "",
            rpcUrl                      = p.getString("rpc_url", "") ?: "",
            paperMode                   = p.getBoolean("paper_mode", true),
            // V5.9.495z29 — operator spec item 8: throughput controls.
            highThroughputLiveMode      = p.getBoolean("ht_live_mode", false),
            maxLiveTradesPerDay         = p.getInt    ("ht_max_trades_per_day", 500),
            maxConcurrentLivePositions  = p.getInt    ("ht_max_concurrent_live", 12),
            minSecondsBetweenLiveBuys   = p.getInt    ("ht_min_seconds_between_buys", 4),
            maxPendingBuyVerifications  = p.getInt    ("ht_max_pending_buy_verif", 6),
            maxPendingSellVerifications = p.getInt    ("ht_max_pending_sell_verif", 8),
            hotPathTimeoutMs            = p.getLong   ("ht_hot_path_timeout_ms", 10_000L),
            walletReconcileTimeoutMs    = p.getLong   ("ht_wallet_reconcile_timeout_ms", 12_000L),
            skipSlowBackgroundScansWhenLiveBusy = p.getBoolean("ht_skip_slow_scans_when_busy", true),
            shadowPaperEnabled          = p.getBoolean("shadow_paper_enabled", true),
            moonshotOverrideEnabled     = p.getBoolean("moonshot_override_enabled", true),
            fluidLearningEnabled        = p.getBoolean("fluid_learning_enabled", true),
            paperSimulatedBalance       = p.getFloat("paper_simulated_balance", 11.76f).toDouble(),
            autoTrade                   = p.getBoolean("auto_trade", true),
            watchlist                   = (p.getString("watchlist", "") ?: "").split(",").filter { it.isNotBlank() },
            activeToken                 = p.getString("active_token", "") ?: "",
            smallBuySol                 = p.getFloat("small_buy_sol", 0.05f).toDouble(),
            largeBuySol                 = p.getFloat("large_buy_sol", 0.10f).toDouble(),
            largeBuyMinWalletSol        = p.getFloat("large_buy_min_wallet_sol", 0.20f).toDouble(),
            maxPositionSol              = p.getFloat("max_position_sol", 0.15f).toDouble(),
            stopLossPct                 = p.getFloat("stop_loss_pct", 10.0f).toDouble(),
            trailingStopBasePct         = p.getFloat("trailing_stop_base_pct", 8.0f).toDouble(),
            slippageBps                 = p.getInt("slippage_bps", 100).coerceIn(10, 500),  // V5.9.103: hard cap 5%
            exitScoreThreshold          = p.getFloat("exit_score_threshold", 58.0f).toDouble(),
            momentumExitCandles         = p.getInt("momentum_exit_candles", 3),
            entryCooldownSec            = p.getInt("entry_cooldown_sec", 120),
            pollSeconds                 = p.getInt("poll_seconds", 8),
            telegramBotToken            = s.getString("telegram_bot_token", "") ?: "",
            telegramChatId              = s.getString("telegram_chat_id", "") ?: "",
            telegramTradeAlerts         = p.getBoolean("telegram_trade_alerts", false),
            telegramChannels            = (p.getString("telegram_channels", "") ?: "").split(",").filter { it.isNotBlank() },
            // V4.0: Discord
            discordWebhookUrl           = s.getString("discord_webhook_url", "") ?: "",
            discordTradeAlerts          = p.getBoolean("discord_trade_alerts", false),
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
            closePositionsOnStop        = p.getBoolean("close_positions_on_stop", true),
            heliusApiKey                = s.getString("helius_api_key", "") ?: "",
            birdeyeApiKey               = s.getString("birdeye_api_key", "") ?: "",
            groqApiKey                  = s.getString("groq_api_key", "") ?: "",
            geminiApiKey                = s.getString("gemini_api_key", "").let {
                // V5.9.79: previously this stripped ANY AIza... key and
                // force-reverted to the Emergent proxy, silently destroying
                // every user-pasted personal Google key. Now we only fall
                // back to the Emergent proxy when the stored value is
                // actually blank. User-supplied keys survive saves.
                if (it.isNullOrBlank()) "sk-emergent-431Dd41D3F186C0E0B" else it
            },
            jupiterApiKey               = s.getString("jupiter_api_key", "") ?: "",
            tursoDbUrl                  = s.getString("turso_db_url", "").let { 
                if (it.isNullOrBlank()) "libsql://superbrain-shaunhayes333-stack.aws-ap-northeast-1.turso.io" else it 
            },
            tursoAuthToken              = s.getString("turso_auth_token", "").let {
                if (it.isNullOrBlank()) "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3NzU1NTE3MzQsImlkIjoiMDE5ZDMwNjYtMmUwMS03NzcyLTgyMTYtMDIyYzY1YzRmNmVjIiwicmlkIjoiMGExMzRiY2EtZmY1YS00NmQ2LWI2ZWYtYmU4MjAyYWE1ZWI4In0.PNhzeQw2rXloG3cDJaOPRg-Kq6rCpOy5kk6Q6GCD8Ar_AKC2iiW5OTKoK-q3Y78LFPWp_8ttrEhtlPz0VJ_VDw" else it
            },
            autoAddNewTokens            = p.getBoolean("auto_add_new_tokens", true),
            geminiEnabled               = p.getBoolean("gemini_enabled", true),
            maxConcurrentPositions      = p.getInt("max_concurrent_positions", 100),
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
            notificationsEnabled        = p.getBoolean("notifications_enabled", true),
            vibrationEnabled            = p.getBoolean("vibration_enabled", true),
            soundEnabled                = p.getBoolean("sound_enabled", true),
            soundOnNewToken             = p.getBoolean("sound_on_new_token", true),
            darkModeEnabled             = p.getBoolean("dark_mode_enabled", true),
            autoMode                    = p.getBoolean("auto_mode", true),
            tradingPauseUtcStart        = p.getInt("trading_pause_utc_start", 4),
            tradingPauseUtcEnd          = p.getInt("trading_pause_utc_end", 6),
            defensiveLossThreshold      = p.getInt("defensive_loss_threshold", 3),
            aggressiveWhaleThreshold    = p.getFloat("aggressive_whale_threshold", 70.0f).toDouble(),
            behaviorAggressionLevel     = p.getInt("behavior_aggression_level", 5),  // V5.2: Default NORMAL
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
            minLiquidityUsd             = p.getFloat("min_liquidity_usd", 500.0f).toDouble(),  // LOWERED
            minVolLiqRatio              = p.getFloat("min_vol_liq_ratio", 0.10f).toDouble(),   // LOWERED
            crossTokenGuardEnabled      = p.getBoolean("cross_token_guard", true),
            crossTokenWindowMins        = p.getFloat("cross_token_window", 15.0f).toDouble(),
            fullMarketScanEnabled       = p.getBoolean("full_market_scan", true),
            scanIntervalSecs            = p.getInt("scan_interval_secs", 8),   // 8 sec scan — matches default in BotConfig
            maxWatchlistSize            = p.getInt("max_watchlist_size", 500).coerceAtLeast(500), // V5.9.604: auto-upgrade old 20/50 caps to 500
            minDiscoveryScore           = p.getFloat("min_discovery_score", 10.0f).toDouble(), // LOWERED
            scanMinMcapUsd              = p.getFloat("scan_min_mcap", 0.0f).toDouble(),
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
            collectiveLearningEnabled   = p.getBoolean("collective_learning_enabled", true),
            // V5.7.6: Trading Mode
            tradingMode                 = p.getInt("trading_mode", 2),  // Default: BOTH
            memeTraderEnabled           = p.getBoolean("meme_trader_enabled", true),
            marketsTraderEnabled        = p.getBoolean("markets_trader_enabled", true),
            cryptoAltsEnabled           = p.getBoolean("crypto_alts_enabled", true),
            // V5.7.7: Individual Markets sub-trader toggles
            stocksEnabled               = p.getBoolean("stocks_enabled", true),
            commoditiesEnabled          = p.getBoolean("commodities_enabled", true),
            metalsEnabled               = p.getBoolean("metals_enabled", true),
            forexEnabled                = p.getBoolean("forex_enabled", true),
            perpsEnabled                = p.getBoolean("perps_enabled", true),
            cyclicTradeEnabled          = p.getBoolean("cyclic_trade_enabled", true),  // V5.9.222: default on
            cyclicTradeLiveEnabled      = p.getBoolean("cyclic_trade_live_enabled", false),
            // V5.9.326: classic scoring mode (default true = build ~1920 pipeline)
            classicScoringMode          = p.getBoolean("classic_scoring_mode", true),
        ).also {
            // V5.9.495z31 — bootstrap RuntimeModeAuthority on load.
            com.lifecyclebot.engine.RuntimeModeAuthority.publishConfig(
                paperMode = it.paperMode,
                autoTrade = it.autoTrade,
            )
            // V5.9.706 — cache for 2s to avoid repeated AES-GCM decryption on main thread
            cachedConfig = it
            cachedConfigMs = System.currentTimeMillis()
        }
    }


    /**
     * V5.9.603 — persist only the watchlist key.
     *
     * Background scanner/registry syncs are high-frequency and must never
     * rewrite mode-authority fields such as paper_mode/auto_trade from a
     * stale BotConfig snapshot. Full ConfigStore.save() is reserved for real
     * settings changes; watchlist churn uses this narrow writer.
     */
    fun saveWatchlistOnly(ctx: Context, watchlist: List<String>) {
        prefs(ctx).edit()
            .putString("watchlist", watchlist.joinToString(","))
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // V5.9.671 — operator pipeline-health dump revealed THE smoking gun:
    //   [257]  at com.lifecyclebot.data.ConfigStore.secrets
    //
    // Every BotViewModel.pollLoop tick called MainActivity.updateUi which
    // called ConfigStore.load which called this secrets() helper, and
    // each call rebuilt the MasterKey + EncryptedSharedPreferences from
    // scratch. That triggers an Android Keystore Binder IPC + AES-GCM
    // operation init on the main thread — hundreds of milliseconds each
    // call, sometimes seconds. Stack traces in the operator dump showed
    // freezes of 5–14s in this exact frame.
    //
    // EncryptedSharedPreferences is explicitly designed to be created
    // ONCE per process and reused. Cache the instance with a volatile
    // backing field + double-checked locking so concurrent callers
    // share the same wrapper.
    @Volatile private var cachedSecrets: android.content.SharedPreferences? = null
    private val secretsInitLock = Any()

    private fun secrets(ctx: Context): android.content.SharedPreferences {
        // Fast path: instance already built, no synchronisation needed.
        cachedSecrets?.let { return it }
        synchronized(secretsInitLock) {
            // Re-check under lock (double-checked locking).
            cachedSecrets?.let { return it }
            val built = try {
                val masterKey = MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx.applicationContext, KEY_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // V5.0: If encryption fails (e.g., MasterKey invalidated after signing key change),
                // the old encrypted data is unrecoverable. Fall back to unencrypted storage.
                //
                // This is a ONE-TIME issue: after this update, the consistent release.keystore
                // ensures future updates won't change the signing key or invalidate MasterKey.
                //
                // User will need to re-enter API keys once after this update.
                android.util.Log.w("ConfigStore", "EncryptedPrefs failed (signing key changed?): ${e.message}")
                android.util.Log.w("ConfigStore", "Using fallback storage - API keys will need to be re-entered once")

                // Return regular SharedPreferences as fallback
                // NOT ideal for security but preserves functionality
                // Future versions can migrate back to encrypted once signing is stable
                ctx.applicationContext.getSharedPreferences("${KEY_FILE}_fallback", Context.MODE_PRIVATE)
            }
            cachedSecrets = built
            return built
        }
    }
}
// Build 1775478652
// V5.6.11 - Paper→Live learning transfer

