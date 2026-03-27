package com.lifecyclebot.collective

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * COLLECTIVE LEARNING - SHARED KNOWLEDGE BASE
 * 
 * Enables all AATE instances to learn from each other's trades.
 * Uses Turso (distributed SQLite) as the backend.
 * 
 * PRIVACY: No wallet addresses, trade sizes, or personal data is shared.
 * Only anonymized patterns and outcomes are synchronized.
 * 
 * TABLES:
 *   1. collective_patterns - Winning/losing token characteristics
 *   2. token_blacklist - Known rugs, honeypots, scams
 *   3. mode_performance - Aggregated mode stats across all users
 *   4. whale_effectiveness - Which whale wallets are profitable
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// ═══════════════════════════════════════════════════════════════════════════════
// DATA CLASSES FOR SCHEMA
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * A pattern that led to a win or loss.
 * Aggregated across all users - no personal data.
 */
data class CollectivePattern(
    val id: Long = 0,
    val patternHash: String,          // Hash of pattern characteristics (no PII)
    val patternType: String,          // e.g., "early_unknown_flat_ema", "whale_accumulation_confirmed"
    val discoverySource: String,      // e.g., "RAYDIUM_NEW_POOL", "DEXSCREENER_TRENDING"
    val liquidityBucket: String,      // e.g., "MICRO", "SMALL", "MID", "LARGE"
    val emaTrend: String,             // e.g., "BULLISH", "BEARISH", "FLAT"
    val totalTrades: Int,             // Number of trades across all users
    val wins: Int,                    // Winning trades
    val losses: Int,                  // Losing trades
    val avgPnlPct: Double,            // Average PnL percentage
    val avgHoldMins: Double,          // Average hold time in minutes
    val lastUpdated: Long,            // Unix timestamp
) {
    val winRate: Double get() = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100 else 0.0
    val isReliable: Boolean get() = totalTrades >= 10  // Minimum sample size
}

/**
 * A blacklisted token (known rug, honeypot, scam).
 */
data class BlacklistedToken(
    val id: Long = 0,
    val mint: String,                 // Token mint address
    val symbol: String,               // Token symbol (for logging)
    val reason: String,               // e.g., "RUG_PULL", "HONEYPOT", "SCAM", "FREEZE_AUTHORITY"
    val reportCount: Int,             // Number of users who reported this
    val firstReported: Long,          // Unix timestamp
    val lastReported: Long,           // Unix timestamp
    val severity: Int,                // 1-5 (5 = most severe)
) {
    val isConfirmed: Boolean get() = reportCount >= 3  // Minimum reports to confirm
}

/**
 * Aggregated mode performance stats.
 */
data class ModePerformance(
    val id: Long = 0,
    val modeName: String,             // e.g., "WHALE_FOLLOW", "PUMP_SNIPER", "BLUE_CHIP"
    val marketCondition: String,      // e.g., "BULL", "BEAR", "SIDEWAYS", "VOLATILE"
    val liquidityBucket: String,      // e.g., "MICRO", "SMALL", "MID", "LARGE"
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val avgPnlPct: Double,
    val avgHoldMins: Double,
    val lastUpdated: Long,
) {
    val winRate: Double get() = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100 else 0.0
}

/**
 * Whale wallet effectiveness.
 * Tracks whether following certain whale wallets is profitable.
 */
data class WhaleEffectiveness(
    val id: Long = 0,
    val walletHash: String,           // Hash of wallet address (not the actual address for privacy)
    val totalFollows: Int,            // Times this whale was followed
    val profitableFollows: Int,       // Profitable follow trades
    val avgPnlPct: Double,            // Average PnL when following
    val avgLeadTimeSec: Int,          // Average time between whale buy and our entry
    val lastUpdated: Long,
) {
    val successRate: Double get() = if (totalFollows > 0) (profitableFollows.toDouble() / totalFollows) * 100 else 0.0
    val isReliable: Boolean get() = totalFollows >= 5
}

// ═══════════════════════════════════════════════════════════════════════════════
// SCHEMA CREATION SQL
// ═══════════════════════════════════════════════════════════════════════════════

object CollectiveSchema {
    
    const val CREATE_PATTERNS_TABLE = """
        CREATE TABLE IF NOT EXISTS collective_patterns (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            pattern_hash TEXT UNIQUE NOT NULL,
            pattern_type TEXT NOT NULL,
            discovery_source TEXT NOT NULL,
            liquidity_bucket TEXT NOT NULL,
            ema_trend TEXT NOT NULL,
            total_trades INTEGER DEFAULT 0,
            wins INTEGER DEFAULT 0,
            losses INTEGER DEFAULT 0,
            avg_pnl_pct REAL DEFAULT 0.0,
            avg_hold_mins REAL DEFAULT 0.0,
            last_updated INTEGER NOT NULL
        )
    """
    
    const val CREATE_BLACKLIST_TABLE = """
        CREATE TABLE IF NOT EXISTS token_blacklist (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            mint TEXT UNIQUE NOT NULL,
            symbol TEXT NOT NULL,
            reason TEXT NOT NULL,
            report_count INTEGER DEFAULT 1,
            first_reported INTEGER NOT NULL,
            last_reported INTEGER NOT NULL,
            severity INTEGER DEFAULT 3
        )
    """
    
    const val CREATE_MODE_PERFORMANCE_TABLE = """
        CREATE TABLE IF NOT EXISTS mode_performance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            mode_name TEXT NOT NULL,
            market_condition TEXT NOT NULL,
            liquidity_bucket TEXT NOT NULL,
            total_trades INTEGER DEFAULT 0,
            wins INTEGER DEFAULT 0,
            losses INTEGER DEFAULT 0,
            avg_pnl_pct REAL DEFAULT 0.0,
            avg_hold_mins REAL DEFAULT 0.0,
            last_updated INTEGER NOT NULL,
            UNIQUE(mode_name, market_condition, liquidity_bucket)
        )
    """
    
    const val CREATE_WHALE_EFFECTIVENESS_TABLE = """
        CREATE TABLE IF NOT EXISTS whale_effectiveness (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            wallet_hash TEXT UNIQUE NOT NULL,
            total_follows INTEGER DEFAULT 0,
            profitable_follows INTEGER DEFAULT 0,
            avg_pnl_pct REAL DEFAULT 0.0,
            avg_lead_time_sec INTEGER DEFAULT 0,
            last_updated INTEGER NOT NULL
        )
    """
    
    // Indexes for performance
    const val CREATE_INDEXES = """
        CREATE INDEX IF NOT EXISTS idx_patterns_type ON collective_patterns(pattern_type);
        CREATE INDEX IF NOT EXISTS idx_patterns_source ON collective_patterns(discovery_source);
        CREATE INDEX IF NOT EXISTS idx_blacklist_mint ON token_blacklist(mint);
        CREATE INDEX IF NOT EXISTS idx_mode_perf_name ON mode_performance(mode_name);
        CREATE INDEX IF NOT EXISTS idx_whale_hash ON whale_effectiveness(wallet_hash);
    """
    
    val ALL_TABLES = listOf(
        CREATE_PATTERNS_TABLE,
        CREATE_BLACKLIST_TABLE,
        CREATE_MODE_PERFORMANCE_TABLE,
        CREATE_WHALE_EFFECTIVENESS_TABLE,
    )
}
