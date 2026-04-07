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

    /**
     * These run AFTER CREATE TABLE IF NOT EXISTS and patch older databases.
     * Ignore "duplicate column name" errors in the migration runner.
     */
    val MIGRATION_STATEMENTS = listOf(
        "ALTER TABLE collective_trades ADD COLUMN instance_id TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN source TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN liquidity_bucket TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN market_sentiment TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE collective_trades ADD COLUMN entry_score INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collective_trades ADD COLUMN confidence INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collective_trades ADD COLUMN pnl_pct REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE collective_trades ADD COLUMN hold_mins REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE collective_trades ADD COLUMN is_win INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collective_trades ADD COLUMN paper_mode INTEGER NOT NULL DEFAULT 1"
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
        CREATE INDEX IF NOT EXISTS idx_network_signals_type ON network_signals(signal_type)
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
        CREATE_ALL_TRADES_TABLE
    )
}