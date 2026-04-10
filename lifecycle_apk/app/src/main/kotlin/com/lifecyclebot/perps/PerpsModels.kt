package com.lifecyclebot.perps

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 SOL PERPS & LEVERAGE DATA MODELS - V5.7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * SUPER SMART Leverage Trading System for:
 * - SOL-PERP (native Solana perpetuals)
 * - Tokenized Real-World Assets (AAPL, TSLA, NVDA, etc.)
 * 
 * PHILOSOPHY:
 * - Reuses existing bot infrastructure where possible
 * - Fluid AI sizing intelligence
 * - Strict discipline rules
 * - Paper/Live mode separation
 * - Live Readiness Gauge
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// ═══════════════════════════════════════════════════════════════════════════════
// ENUMS - LEVERAGE DIRECTION
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsDirection(val symbol: String, val emoji: String, val multiplier: Int) {
    LONG("LONG", "📈", 1),
    SHORT("SHORT", "📉", -1),
}

// ═══════════════════════════════════════════════════════════════════════════════
// RISK TIERS - INTELLIGENT LEVERAGE MANAGEMENT
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsRiskTier(
    val emoji: String,
    val displayName: String,
    val maxLeverage: Double,
    val maxPositionPct: Double,
    val stopLossPct: Double,
    val takeProfitPct: Double,
    val color: String,
) {
    SNIPER("🎯", "Sniper", 2.0, 5.0, 3.0, 8.0, "#22C55E"),       // Conservative - Green
    TACTICAL("⚔️", "Tactical", 5.0, 10.0, 5.0, 15.0, "#3B82F6"),  // Moderate - Blue
    ASSAULT("💥", "Assault", 10.0, 15.0, 8.0, 25.0, "#F59E0B"),   // Aggressive - Yellow
    NUKE("☢️", "Nuclear", 20.0, 25.0, 12.0, 50.0, "#EF4444"),     // Degen - Red
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRADEABLE MARKETS - SOL + TOKENIZED STOCKS
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsMarket(
    val symbol: String,
    val emoji: String,
    val displayName: String,
    val isStock: Boolean,
    val maxLeverage: Double,
    val tradingHours: String,
    val color: String,
) {
    // Native Crypto
    SOL("SOL", "◎", "Solana", false, 20.0, "24/7", "#14F195"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOKENIZED STOCKS - All available on Solana via Pyth Oracle
    // ═══════════════════════════════════════════════════════════════════════════
    
    // 🔥 MEGA TECH (FAANG+)
    AAPL("AAPL", "🍎", "Apple Inc.", true, 10.0, "MARKET", "#A2AAAD"),
    TSLA("TSLA", "🚗", "Tesla Inc.", true, 10.0, "MARKET", "#E31937"),
    NVDA("NVDA", "🖥️", "NVIDIA Corp.", true, 10.0, "MARKET", "#76B900"),
    GOOGL("GOOGL", "🔍", "Alphabet Inc.", true, 10.0, "MARKET", "#4285F4"),
    AMZN("AMZN", "📦", "Amazon.com", true, 10.0, "MARKET", "#FF9900"),
    META("META", "👤", "Meta Platforms", true, 10.0, "MARKET", "#0081FB"),
    MSFT("MSFT", "🪟", "Microsoft Corp.", true, 10.0, "MARKET", "#00A4EF"),
    NFLX("NFLX", "🎬", "Netflix Inc.", true, 10.0, "MARKET", "#E50914"),
    
    // 💎 SEMICONDUCTORS
    AMD("AMD", "🔴", "AMD Inc.", true, 10.0, "MARKET", "#ED1C24"),
    INTC("INTC", "🔵", "Intel Corp.", true, 10.0, "MARKET", "#0071C5"),
    QCOM("QCOM", "📱", "Qualcomm Inc.", true, 10.0, "MARKET", "#3253DC"),
    AVGO("AVGO", "⚡", "Broadcom Inc.", true, 10.0, "MARKET", "#CC092F"),
    MU("MU", "💾", "Micron Tech.", true, 10.0, "MARKET", "#0033A0"),
    
    // 🚀 GROWTH TECH
    CRM("CRM", "☁️", "Salesforce", true, 10.0, "MARKET", "#00A1E0"),
    ORCL("ORCL", "🔮", "Oracle Corp.", true, 10.0, "MARKET", "#F80000"),
    PLTR("PLTR", "🛡️", "Palantir", true, 10.0, "MARKET", "#101010"),
    SNOW("SNOW", "❄️", "Snowflake", true, 10.0, "MARKET", "#29B5E8"),
    SHOP("SHOP", "🛒", "Shopify", true, 10.0, "MARKET", "#96BF48"),
    
    // 💳 FINTECH & CRYPTO
    COIN("COIN", "🪙", "Coinbase", true, 10.0, "MARKET", "#0052FF"),
    PYPL("PYPL", "💳", "PayPal", true, 10.0, "MARKET", "#003087"),
    V("V", "💳", "Visa Inc.", true, 10.0, "MARKET", "#1A1F71"),
    MA("MA", "💳", "Mastercard", true, 10.0, "MARKET", "#EB001B"),
    JPM("JPM", "🏦", "JPMorgan", true, 10.0, "MARKET", "#117ACA"),
    GS("GS", "🏛️", "Goldman Sachs", true, 10.0, "MARKET", "#7399C6"),
    
    // 🎯 CONSUMER & TRAVEL
    DIS("DIS", "🏰", "Disney", true, 10.0, "MARKET", "#113CCF"),
    UBER("UBER", "🚕", "Uber", true, 10.0, "MARKET", "#000000"),
    ABNB("ABNB", "🏠", "Airbnb", true, 10.0, "MARKET", "#FF5A5F"),
    NKE("NKE", "👟", "Nike Inc.", true, 10.0, "MARKET", "#111111"),
    SBUX("SBUX", "☕", "Starbucks", true, 10.0, "MARKET", "#00704A"),
    MCD("MCD", "🍔", "McDonald's", true, 10.0, "MARKET", "#FFC72C"),
    
    // 🏭 INDUSTRIAL & RETAIL
    BA("BA", "✈️", "Boeing", true, 10.0, "MARKET", "#0033A0"),
    WMT("WMT", "🛍️", "Walmart", true, 10.0, "MARKET", "#0071CE"),
    HD("HD", "🔨", "Home Depot", true, 10.0, "MARKET", "#F96302"),
    COST("COST", "📦", "Costco", true, 10.0, "MARKET", "#E31837"),
    
    // 🧬 HEALTHCARE & CONSUMER
    JNJ("JNJ", "💊", "Johnson & Johnson", true, 10.0, "MARKET", "#D51900"),
    PFE("PFE", "💉", "Pfizer", true, 10.0, "MARKET", "#0093D0"),
    UNH("UNH", "🏥", "UnitedHealth", true, 10.0, "MARKET", "#002677"),
    KO("KO", "🥤", "Coca-Cola", true, 10.0, "MARKET", "#F40009"),
    PEP("PEP", "🥤", "PepsiCo", true, 10.0, "MARKET", "#004B93"),
    
    // ⛽ ENERGY
    XOM("XOM", "⛽", "Exxon Mobil", true, 10.0, "MARKET", "#ED1B2D"),
    CVX("CVX", "🛢️", "Chevron", true, 10.0, "MARKET", "#0066B2"),
    
    // 🛢️ COMMODITIES (Oil)
    BRENT("BRENT", "🛢️", "Brent Crude Oil", false, 15.0, "24/7", "#000000"),
    WTI("WTI", "🛢️", "WTI Crude Oil", false, 15.0, "24/7", "#1A1A1A"),
    
    // 🥇 PRECIOUS METALS
    XAU("XAU", "🥇", "Gold", false, 15.0, "24/7", "#FFD700"),
    XAG("XAG", "🥈", "Silver", false, 15.0, "24/7", "#C0C0C0"),
    XPT("XPT", "⚪", "Platinum", false, 15.0, "24/7", "#E5E4E2"),
    XPD("XPD", "💎", "Palladium", false, 15.0, "24/7", "#CED0DD"),
    
    // 🔩 INDUSTRIAL METALS
    XCU("XCU", "🔶", "Copper", false, 15.0, "24/7", "#B87333"),
    XAL("XAL", "🔷", "Aluminum", false, 15.0, "24/7", "#848789"),
    XNI("XNI", "⬜", "Nickel", false, 15.0, "24/7", "#727472"),
    XTI("XTI", "⚫", "Titanium", false, 15.0, "24/7", "#878681"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// POSITION STATUS
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsPositionStatus(val emoji: String, val displayName: String) {
    OPEN("🟢", "Open"),
    CLOSED("⚫", "Closed"),
    LIQUIDATED("💀", "Liquidated"),
    STOPPED("🛑", "Stopped"),
    TP_HIT("🎯", "Take Profit"),
    PARTIAL("⬛", "Partial"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// POSITION DATA - Core position tracking
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsPosition(
    val id: String,
    val market: PerpsMarket,
    val direction: PerpsDirection,
    val entryPrice: Double,
    var currentPrice: Double,
    val sizeSol: Double,
    val sizeUsd: Double,
    val leverage: Double,
    val marginUsd: Double,
    val liquidationPrice: Double,
    val entryTime: Long,
    val isPaper: Boolean,
    val riskTier: PerpsRiskTier,
    
    // Risk management
    var takeProfitPrice: Double? = null,
    var stopLossPrice: Double? = null,
    var trailingStopPct: Double? = null,
    var highestPrice: Double = 0.0,
    var lowestPrice: Double = Double.MAX_VALUE,
    
    // AI metadata
    val entryScore: Int = 0,
    val entryConfidence: Int = 0,
    val aiLeverage: Double = 1.0,
    val aiReasoning: String = "",
    
    // Tracking
    var status: PerpsPositionStatus = PerpsPositionStatus.OPEN,
    var partialCloseCount: Int = 0,
    var lastUpdateTime: Long = System.currentTimeMillis(),
) {
    fun getUnrealizedPnlPct(): Double {
        val raw = ((currentPrice - entryPrice) / entryPrice * 100) * direction.multiplier
        return raw * leverage
    }
    
    fun getUnrealizedPnlUsd(): Double {
        return marginUsd * (getUnrealizedPnlPct() / 100)
    }
    
    fun getDistanceToLiquidation(): Double {
        return when (direction) {
            PerpsDirection.LONG -> ((currentPrice - liquidationPrice) / currentPrice * 100)
            PerpsDirection.SHORT -> ((liquidationPrice - currentPrice) / currentPrice * 100)
        }
    }
    
    fun isNearLiquidation(thresholdPct: Double = 15.0): Boolean = getDistanceToLiquidation() < thresholdPct
    
    fun shouldTakeProfit(): Boolean {
        takeProfitPrice?.let { tp ->
            return when (direction) {
                PerpsDirection.LONG -> currentPrice >= tp
                PerpsDirection.SHORT -> currentPrice <= tp
            }
        }
        return false
    }
    
    fun shouldStopLoss(): Boolean {
        stopLossPrice?.let { sl ->
            return when (direction) {
                PerpsDirection.LONG -> currentPrice <= sl
                PerpsDirection.SHORT -> currentPrice >= sl
            }
        }
        return false
    }
    
    fun getHoldDurationMinutes(): Long = (System.currentTimeMillis() - entryTime) / 60_000
    
    fun getDisplayPnl(): String {
        val pnl = getUnrealizedPnlPct()
        return "${if (pnl >= 0) "+" else ""}${String.format("%.2f", pnl)}%"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MARKET DATA - Real-time price info
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsMarketData(
    val market: PerpsMarket,
    val price: Double,
    val indexPrice: Double,
    val markPrice: Double,
    val fundingRate: Double,
    val fundingRateAnnualized: Double,
    val nextFundingTime: Long,
    val openInterestLong: Double,
    val openInterestShort: Double,
    val volume24h: Double,
    val high24h: Double,
    val low24h: Double,
    val priceChange24hPct: Double,
    val lastUpdate: Long = System.currentTimeMillis(),
) {
    fun getLongShortRatio(): Double = if (openInterestShort > 0) openInterestLong / openInterestShort else 1.0
    fun isFundingFavorableLong(): Boolean = fundingRate < 0
    fun isFundingFavorableShort(): Boolean = fundingRate > 0
    fun isVolatile(): Boolean = kotlin.math.abs(priceChange24hPct) > 5.0
    fun getTrend(): String = when {
        priceChange24hPct > 3.0 -> "BULLISH"
        priceChange24hPct < -3.0 -> "BEARISH"
        else -> "NEUTRAL"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRADE SIGNAL - AI-generated trading signals
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsSignal(
    val market: PerpsMarket,
    val direction: PerpsDirection,
    val score: Int,
    val confidence: Int,
    val recommendedLeverage: Double,
    val recommendedSizePct: Double,
    val recommendedRiskTier: PerpsRiskTier,
    val takeProfitPct: Double,
    val stopLossPct: Double,
    val reasons: List<String>,
    val aiReasoning: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun shouldTrade(): Boolean = confidence >= 60 && score >= 50
    fun isHighConfidence(): Boolean = confidence >= 80
    fun getSignalStrength(): String = when {
        score >= 85 -> "STRONG"
        score >= 70 -> "MODERATE"
        score >= 50 -> "WEAK"
        else -> "NO_TRADE"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRADE HISTORY - Completed trades
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsTrade(
    val id: String,
    val market: PerpsMarket,
    val direction: PerpsDirection,
    val side: String,                    // "OPEN" or "CLOSE"
    val entryPrice: Double,
    val exitPrice: Double,
    val sizeSol: Double,
    val leverage: Double,
    val pnlUsd: Double,
    val pnlPct: Double,
    val openTime: Long,
    val closeTime: Long,
    val closeReason: String,
    val isPaper: Boolean,
    val aiScore: Int,
    val aiConfidence: Int,
    val riskTier: PerpsRiskTier,
)

// ═══════════════════════════════════════════════════════════════════════════════
// LIVE READINESS - Track when paper trading is ready for live
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsLiveReadiness(
    val paperTrades: Int,
    val paperWinRate: Double,
    val paperPnlPct: Double,
    val averageLeverage: Double,
    val maxDrawdownPct: Double,
    val consecutiveLosses: Int,
    val disciplineScore: Int,        // How well it follows rules (0-100)
    val readinessScore: Int,         // Overall readiness (0-100)
    val phase: ReadinessPhase,
    val recommendation: String,
) {
    fun isReadyForLive(): Boolean = readinessScore >= 75 && paperTrades >= 50 && paperWinRate >= 45.0
    fun getProgressPct(): Int = (readinessScore).coerceIn(0, 100)
}

enum class ReadinessPhase(val emoji: String, val displayName: String, val color: String) {
    LEARNING("📚", "Learning", "#F59E0B"),
    PRACTICING("🏋️", "Practicing", "#3B82F6"),
    READY("✅", "Ready", "#22C55E"),
    CAUTION("⚠️", "Caution", "#EF4444"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// PERPS STATE - Overall system state
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsState(
    var isPaperMode: Boolean = true,
    var isEnabled: Boolean = false,
    var hasAcknowledgedRisk: Boolean = false,
    
    // Balances
    var paperBalanceSol: Double = 5.0,
    var liveBalanceSol: Double = 0.0,
    
    // Daily stats
    var dailyTrades: Int = 0,
    var dailyWins: Int = 0,
    var dailyLosses: Int = 0,
    var dailyPnlSol: Double = 0.0,
    var dailyPnlPct: Double = 0.0,
    
    // Lifetime stats
    var lifetimeTrades: Int = 0,
    var lifetimeWins: Int = 0,
    var lifetimeLosses: Int = 0,
    var lifetimePnlSol: Double = 0.0,
    var lifetimeBest: Double = 0.0,
    var lifetimeWorst: Double = 0.0,
    
    // Learning
    var learningProgress: Double = 0.0,  // 0.0 to 1.0
    var maxConsecutiveWins: Int = 0,
    var maxConsecutiveLosses: Int = 0,
    var currentStreak: Int = 0,          // Positive = wins, negative = losses
    
    // AI confidence
    var aiConfidence: Int = 50,
    var lastSignalTime: Long = 0,
)

// ═══════════════════════════════════════════════════════════════════════════════
// EXIT SIGNALS
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsExitSignal(val emoji: String, val displayName: String) {
    HOLD("⏳", "Hold"),
    STOP_LOSS("🛑", "Stop Loss"),
    TAKE_PROFIT("🎯", "Take Profit"),
    TRAILING_STOP("📉", "Trailing Stop"),
    PARTIAL_TAKE("💰", "Partial Take"),
    LIQUIDATION_RISK("💀", "Liquidation Risk"),
    TIMEOUT("⏰", "Timeout"),
    MARKET_CLOSE("🔒", "Market Close"),
    AI_EXIT("🤖", "AI Exit"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER EXTENSIONS
// ═══════════════════════════════════════════════════════════════════════════════

fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
fun Double.formatUsd(): String = "$${String.format("%.2f", this)}"
fun Double.formatPct(): String = "${String.format("%.2f", this)}%"
fun Double.formatLeverage(): String = "${String.format("%.1f", this)}x"
