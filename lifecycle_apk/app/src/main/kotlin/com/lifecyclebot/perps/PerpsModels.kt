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
// TRADEABLE MARKETS - CRYPTO + STOCKS + COMMODITIES + FOREX
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
    // ═══════════════════════════════════════════════════════════════════════════
    // 🪙 MAJOR CRYPTOCURRENCIES
    // ═══════════════════════════════════════════════════════════════════════════
    SOL("SOL", "◎", "Solana", false, 20.0, "24/7", "#14F195"),
    BTC("BTC", "₿", "Bitcoin", false, 20.0, "24/7", "#F7931A"),
    ETH("ETH", "⟠", "Ethereum", false, 20.0, "24/7", "#627EEA"),
    BNB("BNB", "🔶", "BNB", false, 20.0, "24/7", "#F3BA2F"),
    XRP("XRP", "💧", "Ripple", false, 20.0, "24/7", "#23292F"),
    ADA("ADA", "🔵", "Cardano", false, 20.0, "24/7", "#0033AD"),
    DOGE("DOGE", "🐕", "Dogecoin", false, 20.0, "24/7", "#C2A633"),
    AVAX("AVAX", "🔺", "Avalanche", false, 20.0, "24/7", "#E84142"),
    DOT("DOT", "⚫", "Polkadot", false, 20.0, "24/7", "#E6007A"),
    LINK("LINK", "🔗", "Chainlink", false, 20.0, "24/7", "#375BD2"),
    MATIC("MATIC", "💜", "Polygon", false, 20.0, "24/7", "#8247E5"),
    SHIB("SHIB", "🐕", "Shiba Inu", false, 20.0, "24/7", "#FFA409"),
    LTC("LTC", "Ł", "Litecoin", false, 20.0, "24/7", "#BFBBBB"),
    ATOM("ATOM", "⚛️", "Cosmos", false, 20.0, "24/7", "#2E3148"),
    UNI("UNI", "🦄", "Uniswap", false, 20.0, "24/7", "#FF007A"),
    ARB("ARB", "🔵", "Arbitrum", false, 20.0, "24/7", "#28A0F0"),
    OP("OP", "🔴", "Optimism", false, 20.0, "24/7", "#FF0420"),
    APT("APT", "🟢", "Aptos", false, 20.0, "24/7", "#00D4AA"),
    SUI("SUI", "💧", "Sui", false, 20.0, "24/7", "#6FBCF0"),
    SEI("SEI", "🌊", "Sei", false, 20.0, "24/7", "#9B1C1C"),
    INJ("INJ", "💉", "Injective", false, 20.0, "24/7", "#00F2FE"),
    TIA("TIA", "🌌", "Celestia", false, 20.0, "24/7", "#7B2BF9"),
    JUP("JUP", "🪐", "Jupiter", false, 20.0, "24/7", "#00D395"),
    PEPE("PEPE", "🐸", "Pepe", false, 20.0, "24/7", "#3D9B47"),
    WIF("WIF", "🐕", "dogwifhat", false, 20.0, "24/7", "#C8A96A"),
    BONK("BONK", "🦴", "Bonk", false, 20.0, "24/7", "#F8A21A"),
    
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🛢️ COMMODITIES - Energy (24/7 trading via Pyth)
    // ═══════════════════════════════════════════════════════════════════════════
    BRENT("BRENT", "🛢️", "Brent Crude Oil", false, 15.0, "24/7", "#000000"),
    WTI("WTI", "🛢️", "WTI Crude Oil", false, 15.0, "24/7", "#1A1A1A"),
    NATGAS("NATGAS", "🔥", "Natural Gas", false, 15.0, "24/7", "#4169E1"),
    RBOB("RBOB", "⛽", "Gasoline RBOB", false, 15.0, "24/7", "#FF4500"),
    HEATING("HEATING", "🏠", "Heating Oil", false, 15.0, "24/7", "#8B0000"),
    
    // 🌾 COMMODITIES - Agricultural
    CORN("CORN", "🌽", "Corn", false, 15.0, "24/7", "#FFD700"),
    WHEAT("WHEAT", "🌾", "Wheat", false, 15.0, "24/7", "#DEB887"),
    SOYBEAN("SOYBEAN", "🫘", "Soybeans", false, 15.0, "24/7", "#228B22"),
    COFFEE("COFFEE", "☕", "Coffee", false, 15.0, "24/7", "#6F4E37"),
    COCOA("COCOA", "🍫", "Cocoa", false, 15.0, "24/7", "#7B3F00"),
    SUGAR("SUGAR", "🍬", "Sugar", false, 15.0, "24/7", "#FFFFFF"),
    COTTON("COTTON", "🧶", "Cotton", false, 15.0, "24/7", "#F5F5DC"),
    LUMBER("LUMBER", "🪵", "Lumber", false, 15.0, "24/7", "#8B4513"),
    OJ("OJ", "🍊", "Orange Juice", false, 15.0, "24/7", "#FFA500"),
    CATTLE("CATTLE", "🐄", "Live Cattle", false, 15.0, "24/7", "#8B4513"),
    HOGS("HOGS", "🐖", "Lean Hogs", false, 15.0, "24/7", "#FFB6C1"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🥇 PRECIOUS METALS (24/7 trading via Pyth)
    // ═══════════════════════════════════════════════════════════════════════════
    XAU("XAU", "🥇", "Gold", false, 15.0, "24/7", "#FFD700"),
    XAG("XAG", "🥈", "Silver", false, 15.0, "24/7", "#C0C0C0"),
    XPT("XPT", "⚪", "Platinum", false, 15.0, "24/7", "#E5E4E2"),
    XPD("XPD", "💎", "Palladium", false, 15.0, "24/7", "#CED0DD"),
    
    // 🔩 INDUSTRIAL METALS
    XCU("XCU", "🔶", "Copper", false, 15.0, "24/7", "#B87333"),
    XAL("XAL", "🔷", "Aluminum", false, 15.0, "24/7", "#848789"),
    XNI("XNI", "⬜", "Nickel", false, 15.0, "24/7", "#727472"),
    XTI("XTI", "⚫", "Titanium", false, 15.0, "24/7", "#878681"),
    ZINC("ZINC", "🔘", "Zinc", false, 15.0, "24/7", "#7D7D7D"),
    LEAD("LEAD", "⚫", "Lead", false, 15.0, "24/7", "#2F4F4F"),
    TIN("TIN", "🪙", "Tin", false, 15.0, "24/7", "#D3D3D3"),
    IRON("IRON", "🔩", "Iron Ore", false, 15.0, "24/7", "#A52A2A"),
    COBALT("COBALT", "🔵", "Cobalt", false, 15.0, "24/7", "#0047AB"),
    LITHIUM("LITHIUM", "🔋", "Lithium", false, 15.0, "24/7", "#87CEEB"),
    URANIUM("URANIUM", "☢️", "Uranium", false, 15.0, "24/7", "#32CD32"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 💱 FOREX - Major Pairs (24/5 trading)
    // ═══════════════════════════════════════════════════════════════════════════
    EURUSD("EURUSD", "🇪🇺", "EUR/USD", false, 50.0, "24/5", "#003399"),
    GBPUSD("GBPUSD", "🇬🇧", "GBP/USD", false, 50.0, "24/5", "#012169"),
    USDJPY("USDJPY", "🇯🇵", "USD/JPY", false, 50.0, "24/5", "#BC002D"),
    AUDUSD("AUDUSD", "🇦🇺", "AUD/USD", false, 50.0, "24/5", "#00008B"),
    USDCAD("USDCAD", "🇨🇦", "USD/CAD", false, 50.0, "24/5", "#FF0000"),
    USDCHF("USDCHF", "🇨🇭", "USD/CHF", false, 50.0, "24/5", "#D52B1E"),
    NZDUSD("NZDUSD", "🇳🇿", "NZD/USD", false, 50.0, "24/5", "#00247D"),
    
    // 💱 FOREX - Cross Pairs
    EURGBP("EURGBP", "🇪🇺", "EUR/GBP", false, 50.0, "24/5", "#003399"),
    EURJPY("EURJPY", "🇪🇺", "EUR/JPY", false, 50.0, "24/5", "#003399"),
    GBPJPY("GBPJPY", "🇬🇧", "GBP/JPY", false, 50.0, "24/5", "#012169"),
    AUDJPY("AUDJPY", "🇦🇺", "AUD/JPY", false, 50.0, "24/5", "#00008B"),
    CADJPY("CADJPY", "🇨🇦", "CAD/JPY", false, 50.0, "24/5", "#FF0000"),
    CHFJPY("CHFJPY", "🇨🇭", "CHF/JPY", false, 50.0, "24/5", "#D52B1E"),
    
    // 💱 FOREX - Emerging Markets
    USDMXN("USDMXN", "🇲🇽", "USD/MXN", false, 30.0, "24/5", "#006847"),
    USDBRL("USDBRL", "🇧🇷", "USD/BRL", false, 30.0, "24/5", "#009C3B"),
    USDINR("USDINR", "🇮🇳", "USD/INR", false, 30.0, "24/5", "#FF9933"),
    USDCNY("USDCNY", "🇨🇳", "USD/CNY", false, 30.0, "24/5", "#DE2910"),
    USDZAR("USDZAR", "🇿🇦", "USD/ZAR", false, 30.0, "24/5", "#007749"),
    USDTRY("USDTRY", "🇹🇷", "USD/TRY", false, 30.0, "24/5", "#E30A17"),
    USDRUB("USDRUB", "🇷🇺", "USD/RUB", false, 30.0, "24/5", "#0039A6"),
    USDSGD("USDSGD", "🇸🇬", "USD/SGD", false, 30.0, "24/5", "#EF3340"),
    USDHKD("USDHKD", "🇭🇰", "USD/HKD", false, 30.0, "24/5", "#DE2910"),
    USDKRW("USDKRW", "🇰🇷", "USD/KRW", false, 30.0, "24/5", "#0047A0"),
    
    ;  // End of enum
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ASSET TYPE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    val isCrypto: Boolean get() = !isStock && tradingHours == "24/7" && !isCommodity && !isMetal
    val isCommodity: Boolean get() = symbol in listOf("BRENT", "WTI", "NATGAS", "RBOB", "HEATING", 
        "CORN", "WHEAT", "SOYBEAN", "COFFEE", "COCOA", "SUGAR", "COTTON", "LUMBER", "OJ", "CATTLE", "HOGS")
    val isMetal: Boolean get() = symbol in listOf("XAU", "XAG", "XPT", "XPD", "XCU", "XAL", "XNI", "XTI",
        "ZINC", "LEAD", "TIN", "IRON", "COBALT", "LITHIUM", "URANIUM")
    val isForex: Boolean get() = tradingHours == "24/5" && !isStock
    val isPreciousMetal: Boolean get() = symbol in listOf("XAU", "XAG", "XPT", "XPD")
    val isIndustrialMetal: Boolean get() = isMetal && !isPreciousMetal
    val isEnergyCommodity: Boolean get() = symbol in listOf("BRENT", "WTI", "NATGAS", "RBOB", "HEATING")
    val isAgriCommodity: Boolean get() = symbol in listOf("CORN", "WHEAT", "SOYBEAN", "COFFEE", "COCOA", "SUGAR", "COTTON", "LUMBER", "OJ", "CATTLE", "HOGS")
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
    
    // V5.7.6: Aliases for MultiAssetActivity compatibility
    fun getPnlPercent(): Double = getUnrealizedPnlPct()
    fun getPnlSol(): Double = sizeSol * (getUnrealizedPnlPct() / 100)
    
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
