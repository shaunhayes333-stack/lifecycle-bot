package com.lifecyclebot.collective

/**
 * Collective Learning - Shared Knowledge Base
 *
 * Enables AATE instances to learn from each other's trades using Turso.
 *
 * Privacy:
 * - No wallet addresses are shared directly
 * - No trade sizes or personal settings are shared
 * - Only anonymized patterns and aggregated outcomes are synchronized
 */

/**
 * A pattern that led to a win or loss.
 * Aggregated across all users with no personal data.
 */
data class CollectivePattern(
    val id: Long = 0,
    val patternHash: String,
    val patternType: String,
    val discoverySource: String,
    val liquidityBucket: String,
    val emaTrend: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val avgPnlPct: Double,
    val avgHoldMins: Double,
    val lastUpdated: Long
) {
    val winRate: Double
        get() = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100.0 else 0.0

    val isReliable: Boolean
        get() = totalTrades >= 10
}

/**
 * A blacklisted token (known rug, honeypot, scam).
 */
data class BlacklistedToken(
    val id: Long = 0,
    val mint: String,
    val symbol: String,
    val reason: String,
    val reportCount: Int,
    val firstReported: Long,
    val lastReported: Long,
    val severity: Int
) {
    val isConfirmed: Boolean
        get() = reportCount >= 3
}

/**
 * Aggregated mode performance stats.
 */
data class ModePerformance(
    val id: Long = 0,
    val modeName: String,
    val marketCondition: String,
    val liquidityBucket: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val avgPnlPct: Double,
    val avgHoldMins: Double,
    val lastUpdated: Long
) {
    val winRate: Double
        get() = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100.0 else 0.0
}

/**
 * Whale wallet effectiveness.
 * Tracks whether following certain whale wallets is profitable.
 */
data class WhaleEffectiveness(
    val id: Long = 0,
    val walletHash: String,
    val totalFollows: Int,
    val profitableFollows: Int,
    val avgPnlPct: Double,
    val avgLeadTimeSec: Int,
    val lastUpdated: Long
) {
    val successRate: Double
        get() = if (totalFollows > 0) (profitableFollows.toDouble() / totalFollows.toDouble()) * 100.0 else 0.0

    val isReliable: Boolean
        get() = totalFollows >= 5
}

// ═══════════════════════════════════════════════════════════════════════════════
// V5.7: PERPS & LEVERAGE DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Perps trade record for database storage
 */
data class PerpsTradeRecord(
    val id: Long = 0,
    val tradeHash: String,
    val instanceId: String,
    val market: String,
    val direction: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val sizeSol: Double,
    val leverage: Double,
    val pnlUsd: Double,
    val pnlPct: Double,
    val openTime: Long,
    val closeTime: Long,
    val closeReason: String,
    val riskTier: String,
    val aiScore: Int,
    val aiConfidence: Int,
    val paperMode: Boolean,
    val isWin: Boolean,
    val holdMins: Double
)

/**
 * Perps position record for database storage
 */
data class PerpsPositionRecord(
    val id: String,
    val instanceId: String,
    val market: String,
    val direction: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val sizeSol: Double,
    val sizeUsd: Double,
    val leverage: Double,
    val marginUsd: Double,
    val liquidationPrice: Double,
    val entryTime: Long,
    val riskTier: String,
    val takeProfitPrice: Double?,
    val stopLossPrice: Double?,
    val aiScore: Int,
    val aiConfidence: Int,
    val paperMode: Boolean,
    val status: String,
    val lastUpdate: Long
)

/**
 * Perps layer performance tracking
 */
data class PerpsLayerPerformance(
    val id: Long = 0,
    val layerName: String,
    val market: String,
    val direction: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val avgPnlPct: Double,
    val trustScore: Double,
    val lastUpdated: Long
) {
    val winRate: Double
        get() = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100.0 else 0.0
}

/**
 * Perps pattern record
 */
data class PerpsPatternRecord(
    val id: Long = 0,
    val patternId: String,
    val market: String,
    val direction: String,
    val riskTier: String,
    val winRate: Double,
    val avgPnl: Double,
    val occurrences: Int,
    val confidence: Double,
    val patternConditions: String,
    val description: String,
    val isWinning: Boolean,
    val lastUpdated: Long
)

/**
 * Perps learning insight record
 */
data class PerpsInsightRecord(
    val id: Long = 0,
    val instanceId: String,
    val insightType: String,
    val layerName: String?,
    val market: String?,
    val direction: String?,
    val insight: String,
    val actionTaken: String,
    val impactScore: Double,
    val timestamp: Long
)

/**
 * Perps market statistics
 */
data class PerpsMarketStatsRecord(
    val id: Long = 0,
    val market: String,
    val totalLongTrades: Int,
    val totalShortTrades: Int,
    val longWinRate: Double,
    val shortWinRate: Double,
    val avgLongPnl: Double,
    val avgShortPnl: Double,
    val bestLeverage: Double,
    val avgHoldMins: Double,
    val lastUpdated: Long
)

/**
 * Legal Agreement Acknowledgment Record.
 * Stores when a user accepted the terms and conditions.
 */
data class LegalAgreementRecord(
    val id: Long = 0,
    val instanceId: String,
    val agreementVersion: String,
    val agreementType: String,
    val acceptedAt: Long,
    val acceptedAtIso: String,
    val deviceInfo: String,
    val appVersion: String,
    val ipCountry: String,
    val consentChecksum: String
)

object CollectiveSchema {

    const val CREATE_PATTERNS_TABLE = """
        CREATE TABLE IF NOT EXISTS collective_patterns (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            pattern_hash TEXT UNIQUE NOT NULL,
            pattern_type TEXT NOT NULL,
            discovery_source TEXT NOT NULL,
            liquidity_bucket TEXT NOT NULL,
            ema_trend TEXT NOT NULL,
            total_trades INTEGER NOT NULL DEFAULT 0,
            wins INTEGER NOT NULL DEFAULT 0,
            losses INTEGER NOT NULL DEFAULT 0,
            avg_pnl_pct REAL NOT NULL DEFAULT 0.0,
            avg_hold_mins REAL NOT NULL DEFAULT 0.0,
            last_updated INTEGER NOT NULL
        )
    """

    const val CREATE_BLACKLIST_TABLE = """
        CREATE TABLE IF NOT EXISTS token_blacklist (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            mint TEXT UNIQUE NOT NULL,
            symbol TEXT NOT NULL,
            reason TEXT NOT NULL,
            report_count INTEGER NOT NULL DEFAULT 1,
            first_reported INTEGER NOT NULL,
            last_reported INTEGER NOT NULL,
            severity INTEGER NOT NULL DEFAULT 3
        )
    """

    const val CREATE_MODE_PERFORMANCE_TABLE = """
        CREATE TABLE IF NOT EXISTS mode_performance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            mode_name TEXT NOT NULL,
            market_condition TEXT NOT NULL,
            liquidity_bucket TEXT NOT NULL,
            total_trades INTEGER NOT NULL DEFAULT 0,
            wins INTEGER NOT NULL DEFAULT 0,
            losses INTEGER NOT NULL DEFAULT 0,
            avg_pnl_pct REAL NOT NULL DEFAULT 0.0,
            avg_hold_mins REAL NOT NULL DEFAULT 0.0,
            last_updated INTEGER NOT NULL,
            UNIQUE(mode_name, market_condition, liquidity_bucket)
        )
    """

    const val CREATE_WHALE_EFFECTIVENESS_TABLE = """
        CREATE TABLE IF NOT EXISTS whale_effectiveness (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            wallet_hash TEXT UNIQUE NOT NULL,
            total_follows INTEGER NOT NULL DEFAULT 0,
            profitable_follows INTEGER NOT NULL DEFAULT 0,
            avg_pnl_pct REAL NOT NULL DEFAULT 0.0,
            avg_lead_time_sec INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL
        )
    """

    const val CREATE_LEGAL_AGREEMENTS_TABLE = """
        CREATE TABLE IF NOT EXISTS legal_agreements (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            instance_id TEXT NOT NULL,
            agreement_version TEXT NOT NULL,
            agreement_type TEXT NOT NULL,
            accepted_at INTEGER NOT NULL,
            accepted_at_iso TEXT NOT NULL,
            device_info TEXT NOT NULL,
            app_version TEXT NOT NULL,
            ip_country TEXT NOT NULL DEFAULT '',
            consent_checksum TEXT NOT NULL,
            UNIQUE(instance_id, agreement_type, agreement_version)
        )
    """

    const val CREATE_INSTANCE_HEARTBEATS_TABLE = """
        CREATE TABLE IF NOT EXISTS instance_heartbeats (
            instance_id TEXT PRIMARY KEY NOT NULL,
            last_heartbeat INTEGER NOT NULL,
            app_version TEXT NOT NULL,
            paper_mode INTEGER NOT NULL DEFAULT 1,
            trades_24h INTEGER NOT NULL DEFAULT 0,
            pnl_24h_pct REAL NOT NULL DEFAULT 0.0
        )
    """

    const val CREATE_NETWORK_SIGNALS_TABLE = """
        CREATE TABLE IF NOT EXISTS network_signals (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            signal_type TEXT NOT NULL,
            mint TEXT NOT NULL,
            symbol TEXT NOT NULL,
            broadcaster_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            pnl_pct REAL NOT NULL DEFAULT 0.0,
            confidence INTEGER NOT NULL DEFAULT 0,
            liquidity_usd REAL NOT NULL DEFAULT 0.0,
            mode TEXT NOT NULL DEFAULT '',
            reason TEXT NOT NULL DEFAULT '',
            expires_at INTEGER NOT NULL,
            ack_count INTEGER NOT NULL DEFAULT 0
        )
    """

    const val CREATE_COLLECTIVE_STATS_TABLE = """
        CREATE TABLE IF NOT EXISTS collective_stats (
            stat_key TEXT PRIMARY KEY NOT NULL,
            stat_value REAL NOT NULL,
            last_updated INTEGER NOT NULL
        )
    """

    const val CREATE_INSTANCE_REGISTRY_TABLE = """
        CREATE TABLE IF NOT EXISTS instance_registry (
            instance_id TEXT PRIMARY KEY NOT NULL,
            install_timestamp INTEGER NOT NULL,
            install_timestamp_iso TEXT NOT NULL,
            device_info TEXT NOT NULL,
            app_version TEXT NOT NULL,
            region_code TEXT NOT NULL DEFAULT '',
            total_trades INTEGER NOT NULL DEFAULT 0,
            total_pnl_sol REAL NOT NULL DEFAULT 0.0,
            last_active INTEGER NOT NULL,
            is_active INTEGER NOT NULL DEFAULT 1
        )
    """

    /**
     * Final expected shape for collective_trades.
     */
    const val CREATE_ALL_TRADES_TABLE = """
        CREATE TABLE IF NOT EXISTS collective_trades (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trade_hash TEXT UNIQUE NOT NULL,
            instance_id TEXT NOT NULL DEFAULT '',
            timestamp INTEGER NOT NULL,
            side TEXT NOT NULL,
            symbol TEXT NOT NULL,
            mode TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT '',
            liquidity_bucket TEXT NOT NULL DEFAULT '',
            market_sentiment TEXT NOT NULL DEFAULT '',
            entry_score INTEGER NOT NULL DEFAULT 0,
            confidence INTEGER NOT NULL DEFAULT 0,
            pnl_pct REAL NOT NULL DEFAULT 0.0,
            hold_mins REAL NOT NULL DEFAULT 0.0,
            is_win INTEGER NOT NULL DEFAULT 0,
            paper_mode INTEGER NOT NULL DEFAULT 1
        )
    """

    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7: PERPS & LEVERAGE TABLES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Perps/Leverage trades table - stores all completed perps trades
     */
    const val CREATE_PERPS_TRADES_TABLE = """
        CREATE TABLE IF NOT EXISTS perps_trades (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trade_hash TEXT UNIQUE NOT NULL,
            instance_id TEXT NOT NULL DEFAULT '',
            market TEXT NOT NULL,
            direction TEXT NOT NULL,
            entry_price REAL NOT NULL,
            exit_price REAL NOT NULL,
            size_sol REAL NOT NULL,
            leverage REAL NOT NULL,
            pnl_usd REAL NOT NULL DEFAULT 0.0,
            pnl_pct REAL NOT NULL DEFAULT 0.0,
            open_time INTEGER NOT NULL,
            close_time INTEGER NOT NULL,
            close_reason TEXT NOT NULL DEFAULT '',
            risk_tier TEXT NOT NULL DEFAULT 'SNIPER',
            ai_score INTEGER NOT NULL DEFAULT 0,
            ai_confidence INTEGER NOT NULL DEFAULT 0,
            paper_mode INTEGER NOT NULL DEFAULT 1,
            is_win INTEGER NOT NULL DEFAULT 0,
            hold_mins REAL NOT NULL DEFAULT 0.0
        )
    """

    /**
     * Perps positions table - stores open positions for cross-device sync
     */
    const val CREATE_PERPS_POSITIONS_TABLE = """
        CREATE TABLE IF NOT EXISTS perps_positions (
            id TEXT PRIMARY KEY NOT NULL,
            instance_id TEXT NOT NULL DEFAULT '',
            market TEXT NOT NULL,
            direction TEXT NOT NULL,
            entry_price REAL NOT NULL,
            current_price REAL NOT NULL DEFAULT 0.0,
            size_sol REAL NOT NULL,
            size_usd REAL NOT NULL,
            leverage REAL NOT NULL,
            margin_usd REAL NOT NULL,
            liquidation_price REAL NOT NULL,
            entry_time INTEGER NOT NULL,
            risk_tier TEXT NOT NULL DEFAULT 'SNIPER',
            take_profit_price REAL,
            stop_loss_price REAL,
            ai_score INTEGER NOT NULL DEFAULT 0,
            ai_confidence INTEGER NOT NULL DEFAULT 0,
            paper_mode INTEGER NOT NULL DEFAULT 1,
            status TEXT NOT NULL DEFAULT 'OPEN',
            last_update INTEGER NOT NULL
        )
    """

    /**
     * Perps layer performance - tracks each AI layer's perps trading performance
     */
    const val CREATE_PERPS_LAYER_PERFORMANCE_TABLE = """
        CREATE TABLE IF NOT EXISTS perps_layer_performance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            layer_name TEXT NOT NULL,
            market TEXT NOT NULL,
            direction TEXT NOT NULL,
            total_trades INTEGER NOT NULL DEFAULT 0,
            wins INTEGER NOT NULL DEFAULT 0,
            losses INTEGER NOT NULL DEFAULT 0,
            avg_pnl_pct REAL NOT NULL DEFAULT 0.0,
            trust_score REAL NOT NULL DEFAULT 0.5,
            last_updated INTEGER NOT NULL,
            UNIQUE(layer_name, market, direction)
        )
    """

    /**
     * Perps patterns - learned trading patterns from replay system
     */
    const val CREATE_PERPS_PATTERNS_TABLE = """
        CREATE TABLE IF NOT EXISTS perps_patterns (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            pattern_id TEXT UNIQUE NOT NULL,
            market TEXT NOT NULL,
            direction TEXT NOT NULL,
            risk_tier TEXT NOT NULL,
            win_rate REAL NOT NULL DEFAULT 0.0,
            avg_pnl REAL NOT NULL DEFAULT 0.0,
            occurrences INTEGER NOT NULL DEFAULT 0,
            confidence REAL NOT NULL DEFAULT 0.0,
            pattern_conditions TEXT NOT NULL DEFAULT '',
            description TEXT NOT NULL DEFAULT '',
            is_winning INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL
        )
    """

    /**
     * Perps learning insights - AI-generated insights from auto-replay
     */
    const val CREATE_PERPS_INSIGHTS_TABLE = """
        CREATE TABLE IF NOT EXISTS perps_insights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            instance_id TEXT NOT NULL DEFAULT '',
            insight_type TEXT NOT NULL,
            layer_name TEXT,
            market TEXT,
            direction TEXT,
            insight TEXT NOT NULL,
            action_taken TEXT NOT NULL DEFAULT '',
            impact_score REAL NOT NULL DEFAULT 0.0,
            timestamp INTEGER NOT NULL
        )
    """

    /**
     * Perps market stats - aggregated market statistics
     */
    const val CREATE_PERPS_MARKET_STATS_TABLE = """
        CREATE TABLE IF NOT EXISTS perps_market_stats (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            market TEXT NOT NULL,
            total_long_trades INTEGER NOT NULL DEFAULT 0,
            total_short_trades INTEGER NOT NULL DEFAULT 0,
            long_win_rate REAL NOT NULL DEFAULT 0.0,
            short_win_rate REAL NOT NULL DEFAULT 0.0,
            avg_long_pnl REAL NOT NULL DEFAULT 0.0,
            avg_short_pnl REAL NOT NULL DEFAULT 0.0,
            best_leverage REAL NOT NULL DEFAULT 1.0,
            avg_hold_mins REAL NOT NULL DEFAULT 0.0,
            last_updated INTEGER NOT NULL,
            UNIQUE(market)
        )
    """

    /**
     * These run AFTER CREATE TABLE IF NOT EXISTS and patch older databases.
     * Ignore "duplicate column name" errors in the migration runner.
     */
    val MIGRATION_STATEMENTS = listOf(
        // Original collective_trades migrations
        "ALTER TABLE collective_trades ADD COLUMN instance_id TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN source TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN liquidity_bucket TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN market_sentiment TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN entry_score INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collective_trades ADD COLUMN confidence INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collective_trades ADD COLUMN pnl_pct REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE collective_trades ADD COLUMN hold_mins REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE collective_trades ADD COLUMN is_win INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collective_trades ADD COLUMN paper_mode INTEGER NOT NULL DEFAULT 1",
        // V5.7: Perps table migrations (for older databases)
        "ALTER TABLE perps_trades ADD COLUMN hold_mins REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE perps_positions ADD COLUMN last_update INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE perps_layer_performance ADD COLUMN trust_score REAL NOT NULL DEFAULT 0.5"
    )

    const val CREATE_INDEXES = """
        CREATE INDEX IF NOT EXISTS idx_patterns_type ON collective_patterns(pattern_type);
        CREATE INDEX IF NOT EXISTS idx_patterns_source ON collective_patterns(discovery_source);
        CREATE INDEX IF NOT EXISTS idx_blacklist_mint ON token_blacklist(mint);
        CREATE INDEX IF NOT EXISTS idx_mode_perf_name ON mode_performance(mode_name);
        CREATE INDEX IF NOT EXISTS idx_whale_hash ON whale_effectiveness(wallet_hash);
        CREATE INDEX IF NOT EXISTS idx_legal_instance ON legal_agreements(instance_id);
        CREATE INDEX IF NOT EXISTS idx_legal_type ON legal_agreements(agreement_type);
        CREATE INDEX IF NOT EXISTS idx_heartbeat_time ON instance_heartbeats(last_heartbeat);
        CREATE INDEX IF NOT EXISTS idx_trades_timestamp ON collective_trades(timestamp);
        CREATE INDEX IF NOT EXISTS idx_trades_mode ON collective_trades(mode);
        CREATE INDEX IF NOT EXISTS idx_trades_symbol ON collective_trades(symbol);
        CREATE INDEX IF NOT EXISTS idx_trades_instance ON collective_trades(instance_id);
        CREATE INDEX IF NOT EXISTS idx_registry_active ON instance_registry(last_active);
        CREATE INDEX IF NOT EXISTS idx_network_signals_mint ON network_signals(mint);
        CREATE INDEX IF NOT EXISTS idx_network_signals_expires ON network_signals(expires_at);
        CREATE INDEX IF NOT EXISTS idx_network_signals_type ON network_signals(signal_type);
        CREATE INDEX IF NOT EXISTS idx_perps_trades_market ON perps_trades(market);
        CREATE INDEX IF NOT EXISTS idx_perps_trades_direction ON perps_trades(direction);
        CREATE INDEX IF NOT EXISTS idx_perps_trades_close_time ON perps_trades(close_time);
        CREATE INDEX IF NOT EXISTS idx_perps_trades_instance ON perps_trades(instance_id);
        CREATE INDEX IF NOT EXISTS idx_perps_positions_instance ON perps_positions(instance_id);
        CREATE INDEX IF NOT EXISTS idx_perps_positions_status ON perps_positions(status);
        CREATE INDEX IF NOT EXISTS idx_perps_layer_perf_layer ON perps_layer_performance(layer_name);
        CREATE INDEX IF NOT EXISTS idx_perps_patterns_market ON perps_patterns(market);
        CREATE INDEX IF NOT EXISTS idx_perps_insights_type ON perps_insights(insight_type);
        CREATE INDEX IF NOT EXISTS idx_perps_market_stats_market ON perps_market_stats(market)
    """

    val ALL_TABLES = listOf(
        CREATE_PATTERNS_TABLE,
        CREATE_BLACKLIST_TABLE,
        CREATE_MODE_PERFORMANCE_TABLE,
        CREATE_WHALE_EFFECTIVENESS_TABLE,
        CREATE_LEGAL_AGREEMENTS_TABLE,
        CREATE_INSTANCE_HEARTBEATS_TABLE,
        CREATE_NETWORK_SIGNALS_TABLE,
        CREATE_COLLECTIVE_STATS_TABLE,
        CREATE_INSTANCE_REGISTRY_TABLE,
        CREATE_ALL_TRADES_TABLE,
        // V5.7: Perps & Leverage Tables
        CREATE_PERPS_TRADES_TABLE,
        CREATE_PERPS_POSITIONS_TABLE,
        CREATE_PERPS_LAYER_PERFORMANCE_TABLE,
        CREATE_PERPS_PATTERNS_TABLE,
        CREATE_PERPS_INSIGHTS_TABLE,
        CREATE_PERPS_MARKET_STATS_TABLE
    )
}