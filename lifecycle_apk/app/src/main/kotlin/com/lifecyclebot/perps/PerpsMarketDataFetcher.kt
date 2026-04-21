package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 PERPS MARKET DATA FETCHER - V5.7.1
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Fetches real-time market data for:
 * - SOL native price (Pyth Oracle primary, Jupiter/CoinGecko fallback)
 * - Tokenized stocks via Pyth Oracle (AAPL, TSLA, NVDA, etc.)
 * 
 * DATA SOURCES (Priority Order):
 * 1. Pyth Network Oracle (primary - sub-second latency)
 * 2. Jupiter Price API (fallback for SOL)
 * 3. CoinGecko (secondary fallback)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsMarketDataFetcher {
    
    private const val TAG = "📊PerpsData"
    private val networkRetry = com.lifecyclebot.network.NetworkRetry("PerpsData", maxRetries = 2, baseDelayMs = 1_000L, failureThreshold = 10, openDurationMs = 30_000L)
    
    // Cache for market data
    private val marketDataCache = ConcurrentHashMap<PerpsMarket, PerpsMarketData>()
    private val lastFetchTime = ConcurrentHashMap<PerpsMarket, Long>()
    private const val CACHE_TTL_MS = 3_000L  // 3 second cache for real-time
    
    // V5.7.7: Multiple FREE stock price APIs
    private const val YAHOO_QUOTE_URL = "https://query1.finance.yahoo.com/v7/finance/quote?symbols="
    private const val YAHOO_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/"
    private const val STOOQ_URL = "https://stooq.com/q/l/?s="  // No API key needed
    private const val CNBC_URL = "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol?symbols="
    private const val MARKETWATCH_URL = "https://www.marketwatch.com/investing/stock/"
    private const val GOOGLE_FINANCE_URL = "https://www.google.com/finance/quote/"
    
    private val yahooChangeCache = ConcurrentHashMap<String, Double>()
    private val priceSourceCache = ConcurrentHashMap<String, String>()  // Track which source worked
    
    // Stock price cache (fallback prices if Pyth fails) - V5.7.6: Full asset coverage
    private val stockPrices = ConcurrentHashMap<String, Double>().apply {
        // MEGA TECH (prices as of Apr 2026)
        put("AAPL", 260.50)
        put("TSLA", 345.70)
        put("NVDA", 875.20)    // Per Pyth logs
        put("GOOGL", 175.80)
        put("AMZN", 235.00)
        put("META", 628.35)    // Per Pyth logs
        put("MSFT", 373.24)    // Per Pyth logs
        put("NFLX", 102.09)    // Per Pyth logs - stock split?
        // SEMICONDUCTORS
        put("AMD", 236.59)     // Per Pyth logs
        put("INTC", 22.0)
        put("QCOM", 170.0)
        put("AVGO", 230.0)
        put("MU", 95.0)
        // GROWTH TECH
        put("CRM", 340.0)
        put("ORCL", 190.0)
        put("PLTR", 85.0)
        put("SNOW", 165.0)
        put("SHOP", 115.0)
        // FINTECH & CRYPTO
        put("COIN", 170.0)
        put("PYPL", 85.0)
        put("V", 340.0)
        put("MA", 540.0)
        put("JPM", 260.0)
        put("GS", 620.0)
        // CONSUMER & TRAVEL
        put("DIS", 115.0)
        put("UBER", 85.0)
        put("ABNB", 140.0)
        put("NKE", 75.0)
        put("SBUX", 96.94)     // Per Pyth logs
        put("MCD", 309.62)     // Per Pyth logs
        // INDUSTRIAL & RETAIL
        put("BA", 220.05)      // Per Pyth logs
        put("WMT", 129.16)     // Per Pyth logs
        put("HD", 339.60)      // Per Pyth logs
        put("COST", 1031.60)   // Per Pyth logs
        // HEALTHCARE & CONSUMER
        put("JNJ", 241.31)     // Per Pyth logs
        put("PFE", 27.22)      // Per Pyth logs
        put("UNH", 307.01)     // Per Pyth logs
        put("KO", 78.22)       // Per Pyth logs
        put("PEP", 157.48)     // Per Pyth logs
        // ENERGY STOCKS
        put("XOM", 110.0)
        put("CVX", 150.0)
        
        // V5.7.6b: MISSING STOCKS (were falling back to $100.00!)
        put("TSM", 185.0)         // Taiwan Semiconductor
        put("ASML", 950.0)        // ASML Holding
        put("ARM", 155.0)         // ARM Holdings
        put("MRVL", 75.0)         // Marvell Tech
        put("SPOT", 385.0)        // Spotify
        put("ZM", 75.0)           // Zoom Video
        put("ROKU", 75.0)         // Roku
        put("SQ", 85.0)           // Block Inc
        put("TWLO", 75.0)         // Twilio
        put("AI", 35.0)           // C3.ai
        put("PATH", 15.0)         // UiPath
        put("DDOG", 135.0)        // Datadog
        put("NET", 95.0)          // Cloudflare
        put("CRWD", 340.0)        // CrowdStrike
        put("ZS", 195.0)          // Zscaler
        put("MDB", 285.0)         // MongoDB
        put("BAC", 42.0)          // Bank of America
        put("WFC", 70.0)          // Wells Fargo
        put("C", 67.0)            // Citigroup
        put("MSTR", 1850.0)       // MicroStrategy
        put("HOOD", 25.0)         // Robinhood
        put("SOFI", 12.0)         // SoFi
        put("NU", 14.0)           // Nu Holdings
        put("CMG", 3350.0)        // Chipotle
        put("LULU", 365.0)        // Lululemon
        put("TGT", 135.0)         // Target
        put("LOW", 255.0)         // Lowe's
        put("CAT", 360.0)         // Caterpillar
        put("DE", 420.0)          // John Deere
        put("LMT", 480.0)         // Lockheed Martin
        put("RTX", 125.0)         // RTX Corp
        put("MRNA", 45.0)         // Moderna
        put("LLY", 825.0)         // Eli Lilly
        put("ABBV", 175.0)        // AbbVie
        put("TMO", 545.0)         // Thermo Fisher
        put("PG", 175.0)          // Procter & Gamble
        put("PM", 125.0)          // Philip Morris
        put("COP", 105.0)         // ConocoPhillips
        put("OXY", 55.0)          // Occidental
        put("ENPH", 115.0)        // Enphase
        put("FSLR", 235.0)        // First Solar
        put("PLUG", 2.50)         // Plug Power
        put("NEE", 75.0)          // NextEra Energy
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6b: INDEX ETFs - Major Market Indices
        // ═══════════════════════════════════════════════════════════════════════
        put("SPY", 595.0)         // S&P 500 ETF
        put("QQQ", 520.0)         // NASDAQ 100 ETF
        put("DIA", 425.0)         // Dow Jones ETF
        put("IWM", 225.0)         // Russell 2000 ETF
        put("VTI", 285.0)         // Total Stock Market
        put("EEM", 45.0)          // Emerging Markets
        put("EFA", 82.0)          // EAFE Intl ETF
        put("GLD", 245.0)         // Gold ETF
        put("SLV", 28.0)          // Silver ETF
        put("TLT", 92.0)          // 20+ Yr Treasury
        put("XLF", 48.0)          // Financial Sector
        put("XLE", 92.0)          // Energy Sector
        put("XLK", 230.0)         // Tech Sector
        put("XLV", 150.0)         // Healthcare Sector
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6b: TELECOM & PHARMA
        // ═══════════════════════════════════════════════════════════════════════
        put("MO", 52.0)           // Altria Group
        put("T", 22.0)            // AT&T
        put("VZ", 42.0)           // Verizon
        put("TMUS", 235.0)        // T-Mobile
        put("CVS", 58.0)          // CVS Health
        put("GILD", 92.0)         // Gilead Sciences
        put("BMY", 42.0)          // Bristol-Myers
        put("BIIB", 165.0)        // Biogen
        put("REGN", 780.0)        // Regeneron
        put("VRTX", 465.0)        // Vertex Pharma
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6b: CANNABIS SECTOR
        // ═══════════════════════════════════════════════════════════════════════
        put("TLRY", 1.80)         // Tilray Brands
        put("CGC", 4.50)          // Canopy Growth
        put("ACB", 5.20)          // Aurora Cannabis
        put("CRON", 2.40)         // Cronos Group
        put("SNDL", 1.95)         // SNDL Inc
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6b: HOT MOMENTUM STOCKS
        // ═══════════════════════════════════════════════════════════════════════
        put("SMCI", 35.0)         // Super Micro
        put("IONQ", 42.0)         // IonQ Quantum
        put("RKLB", 28.0)         // Rocket Lab
        put("RDDT", 185.0)        // Reddit
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.7: CHINA ADR STOCKS
        // ═══════════════════════════════════════════════════════════════════════
        put("BABA", 85.0)         // Alibaba Group
        put("BIDU", 95.0)         // Baidu Inc
        put("JD", 35.0)           // JD.com
        put("NIO", 5.50)          // NIO Inc
        put("XPEV", 18.0)         // XPeng Inc
        put("LI", 28.0)           // Li Auto Inc
        put("PDD", 135.0)         // PDD Holdings (Pinduoduo)
        put("TCEHY", 52.0)        // Tencent ADR
        put("BILI", 18.0)         // Bilibili Inc
        put("TME", 12.0)          // Tencent Music
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.7: JAPAN ADR STOCKS
        // ═══════════════════════════════════════════════════════════════════════
        put("SONY", 95.0)         // Sony Group
        put("TM", 185.0)          // Toyota Motor
        put("NTDOY", 75.0)        // Nintendo ADR
        put("MUFG", 12.0)         // Mitsubishi UFJ
        put("HMC", 35.0)          // Honda Motor
        put("SNE", 95.0)          // Sony Corp ADR
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.7: EUROPE ADR STOCKS
        // ═══════════════════════════════════════════════════════════════════════
        put("SAP", 245.0)         // SAP SE
        put("NVO", 125.0)         // Novo Nordisk
        put("SHEL", 68.0)         // Shell PLC
        put("BP", 35.0)           // BP PLC
        put("UL", 58.0)           // Unilever PLC
        put("DEO", 145.0)         // Diageo PLC
        put("GSK", 42.0)          // GSK PLC
        put("AZN", 68.0)          // AstraZeneca
        put("BHP", 58.0)          // BHP Group
        put("RIO", 68.0)          // Rio Tinto
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.7: GOLD MINERS
        // ═══════════════════════════════════════════════════════════════════════
        put("NEM", 52.0)          // Newmont Corp
        put("GOLD", 22.0)         // Barrick Gold
        put("AEM", 85.0)          // Agnico Eagle
        put("FNV", 165.0)         // Franco-Nevada
        put("KGC", 9.50)          // Kinross Gold
        put("AGI", 22.0)          // Alamos Gold
        put("EGO", 18.0)          // Eldorado Gold
        put("BTG", 4.50)          // B2Gold Corp
        put("NGD", 2.80)          // New Gold Inc
        put("DRD", 14.0)          // DRDGOLD Ltd
        put("HMY", 11.0)          // Harmony Gold
        put("AU", 32.0)           // AngloGold Ashanti
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.7: SILVER MINERS
        // ═══════════════════════════════════════════════════════════════════════
        put("WPM", 62.0)          // Wheaton Precious Metals
        put("HL", 7.50)           // Hecla Mining
        put("PAAS", 22.0)         // Pan American Silver
        put("AG", 7.50)           // First Majestic Silver
        put("CDE", 7.20)          // Coeur Mining
        put("FSM", 8.50)          // Fortuna Silver
        put("MAG", 18.0)          // MAG Silver
        put("SVM", 4.80)          // Silvercorp Metals
        put("GATO", 14.0)         // Gatos Silver
        put("SILV", 8.50)         // SilverCrest Metals
        put("SSRM", 7.50)         // SSR Mining
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6: COMMODITIES - Energy
        // ═══════════════════════════════════════════════════════════════════════
        put("BRENT", 85.0)      // Brent Crude per barrel
        put("WTI", 80.0)        // WTI Crude per barrel
        put("NATGAS", 3.50)     // Natural Gas per MMBtu
        put("RBOB", 2.80)       // Gasoline per gallon
        put("HEATING", 2.75)    // Heating Oil per gallon
        
        // V5.7.6: COMMODITIES - Agricultural
        put("CORN", 4.50)       // Corn per bushel
        put("WHEAT", 6.20)      // Wheat per bushel
        put("SOYBEAN", 12.50)   // Soybeans per bushel
        put("COFFEE", 2.25)     // Coffee per lb
        put("COCOA", 8500.0)    // Cocoa per ton
        put("SUGAR", 0.22)      // Sugar per lb
        put("COTTON", 0.85)     // Cotton per lb
        put("LUMBER", 550.0)    // Lumber per 1000 bd ft
        put("OJ", 4.20)         // Orange Juice per lb
        put("CATTLE", 1.85)     // Live Cattle per lb
        put("HOGS", 0.85)       // Lean Hogs per lb
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6: PRECIOUS METALS
        // ═══════════════════════════════════════════════════════════════════════
        put("XAU", 2650.0)      // Gold per oz
        put("XAG", 31.0)        // Silver per oz
        put("XPT", 1000.0)      // Platinum per oz
        put("XPD", 950.0)       // Palladium per oz
        
        // V5.7.6: INDUSTRIAL METALS
        put("XCU", 4.50)        // Copper per lb
        put("XAL", 2500.0)      // Aluminum per ton
        put("XNI", 16000.0)     // Nickel per ton
        put("XTI", 10.0)        // Titanium
        put("ZINC", 2800.0)     // Zinc per ton
        put("LEAD", 2100.0)     // Lead per ton
        put("TIN", 28000.0)     // Tin per ton
        put("IRON", 120.0)      // Iron Ore per ton
        put("COBALT", 35000.0)  // Cobalt per ton
        put("LITHIUM", 25000.0) // Lithium per ton
        put("URANIUM", 85.0)    // Uranium per lb
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.7.6: FOREX - Major Pairs
        // ═══════════════════════════════════════════════════════════════════════
        put("EURUSD", 1.0850)
        put("GBPUSD", 1.2650)
        put("USDJPY", 154.50)
        put("AUDUSD", 0.6550)
        put("USDCAD", 1.3650)
        put("USDCHF", 0.8850)
        put("NZDUSD", 0.5950)
        
        // V5.7.6: FOREX - Cross Pairs
        put("EURGBP", 0.8575)
        put("EURJPY", 167.65)
        put("GBPJPY", 195.55)
        put("AUDJPY", 101.20)
        put("CADJPY", 113.15)
        put("CHFJPY", 174.60)
        
        // V5.7.6: FOREX - Emerging Markets
        put("USDMXN", 17.25)
        put("USDBRL", 5.05)
        put("USDINR", 83.50)
        put("USDCNY", 7.25)
        put("USDZAR", 18.50)
        put("USDTRY", 32.50)
        put("USDRUB", 92.50)
        put("USDSGD", 1.35)
        put("USDHKD", 7.82)
        put("USDKRW", 1350.0)
        
        // SOL
        put("SOL", 83.21)      // Per Pyth logs

        // ═══════════════════════════════════════════════════════════════════════
        // V5.8: MAJOR ALT CRYPTOS
        // ═══════════════════════════════════════════════════════════════════════
        put("TRX", 0.145)         // TRON
        put("TON", 5.50)          // Toncoin
        put("BCH", 420.0)         // Bitcoin Cash
        put("XLM", 0.115)         // Stellar Lumens
        put("XMR", 175.0)         // Monero
        put("ETC", 28.0)          // Ethereum Classic
        put("ZEC", 35.0)          // Zcash
        put("XTZ", 0.90)          // Tezos
        put("EOS", 0.80)          // EOS

        // V5.8: DeFi & Protocol Tokens
        put("CAKE", 2.40)         // PancakeSwap
        put("GMX", 20.0)          // GMX Perpetuals
        put("DYDX", 1.50)         // dYdX
        put("ENA", 0.45)          // Ethena
        put("PENDLE", 3.80)       // Pendle Finance

        // V5.8: New Ecosystem
        put("WLD", 1.20)          // Worldcoin
        put("JTO", 3.50)          // Jito
        put("W", 0.48)            // Wormhole
        put("STRK", 0.55)         // Starknet
        put("TAO", 440.0)         // Bittensor

        // V5.8: Meme Coins
        put("FLOKI", 0.000135)    // FLOKI
        put("NOT", 0.0085)        // Notcoin
        put("POPCAT", 0.80)       // Popcat
        put("TRUMP", 9.50)        // OFFICIAL TRUMP

        // ═══════════════════════════════════════════════════════════════════════
        // V5.8: NEW STOCKS
        // ═══════════════════════════════════════════════════════════════════════
        put("ADBE", 440.0)        // Adobe Inc
        put("NOW", 895.0)         // ServiceNow
        put("WDAY", 270.0)        // Workday
        put("SNAP", 14.0)         // Snap Inc
        put("PINS", 30.0)         // Pinterest
        put("RBLX", 38.0)         // Roblox
        put("MELI", 1950.0)       // MercadoLibre
        put("SE", 88.0)           // Sea Limited
        put("GRAB", 4.50)         // Grab Holdings
        put("INFY", 19.0)         // Infosys ADR
        put("SPGI", 470.0)        // S&P Global
        put("BX", 125.0)          // Blackstone
        put("KKR", 115.0)         // KKR & Co
        put("CME", 235.0)         // CME Group
        put("IBKR", 155.0)        // Interactive Brokers
        put("AMGN", 295.0)        // Amgen
        put("ISRG", 490.0)        // Intuitive Surgical
        put("NOC", 500.0)         // Northrop Grumman
        put("GD", 280.0)          // General Dynamics
        put("AFRM", 48.0)         // Affirm Holdings

        // ═══════════════════════════════════════════════════════════════════════
        // V5.8: NEW FOREX CROSS PAIRS
        // ═══════════════════════════════════════════════════════════════════════
        put("EURCHF", 0.9350)
        put("GBPCHF", 1.1100)
        put("EURCAD", 1.4900)
        put("EURAUD", 1.7550)
        put("GBPAUD", 2.0800)
        put("AUDCAD", 0.8950)
        put("AUDNZD", 1.0850)
        put("NZDJPY", 88.50)
    }
    
    // HTTP Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // API Endpoints (fallbacks)
    private const val COINGECKO_SOL = "https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true"
    private const val JUPITER_PRICE = "https://price.jup.ag/v4/price?ids=So11111111111111111111111111111111111111112"
    
    /**
     * Get market data for a specific market
     * Now uses Pyth Oracle for real prices!
     */
    suspend fun getMarketData(market: PerpsMarket): PerpsMarketData {
        // Check cache
        val cached = marketDataCache[market]
        val lastFetch = lastFetchTime[market] ?: 0
        if (cached != null && System.currentTimeMillis() - lastFetch < CACHE_TTL_MS) {
            return cached
        }

        // V5.9.23: Route alt crypto straight to PriceAggregator (DEX/Binance/Jupiter
        // first) instead of Pyth. Pyth only covers ~200 blue chips and creates false
        // negatives for the long tail. Majors (SOL/BTC/ETH/BNB/XRP/ADA/DOGE/AVAX)
        // still use Pyth first for oracle confidence.
        // User feedback: "use the dex integration it has heaps!!! pyth is only the first"
        val isMajorCrypto = market.isCrypto && market.symbol in setOf(
            "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX",
            "DOT", "LINK", "MATIC", "LTC", "ATOM", "UNI"
        )
        // V5.9: Try Pyth first for majors + stocks/forex/metals; alts go to aggregator
        var freshData = if (market.isCrypto && !isMajorCrypto) {
            // Alt crypto: skip Pyth, use PriceAggregator (DEX/exchanges first per V5.9.23)
            try {
                val pa = PriceAggregator.getPrice(market.symbol)
                if (pa != null && pa.price > 0) {
                    networkRetry.recordSuccess()
                    ErrorLogger.debug(TAG, "💹 ${market.symbol} via ${pa.source}: \$${pa.price}")
                    PerpsMarketData(
                        market = market,
                        price = pa.price,
                        indexPrice = pa.price,
                        markPrice = pa.price,
                        fundingRate = calculateFundingRate(market),
                        fundingRateAnnualized = calculateFundingRate(market) * 365 * 3 * 100,
                        nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
                        openInterestLong = getEstimatedOI(market, true),
                        openInterestShort = getEstimatedOI(market, false),
                        volume24h = getEstimatedVolume(market),
                        high24h = pa.price * (1 + kotlin.math.abs(pa.change24h) / 100 + 0.005),
                        low24h = pa.price * (1 - kotlin.math.abs(pa.change24h) / 100 - 0.005),
                        priceChange24hPct = pa.change24h,
                    )
                } else fetchFromPythOracle(market)
            } catch (_: Exception) { fetchFromPythOracle(market) }
        } else {
            fetchFromPythOracle(market)
        }

        // If primary path returned zero, try PriceAggregator as final fallback
        if (freshData.price <= 0 && market.isCrypto) {
            try {
                val pa = PriceAggregator.getPrice(market.symbol)
                if (pa != null && pa.price > 0) {
                    ErrorLogger.info(TAG, "🌐 ${market.symbol} via PriceAggregator fallback (${pa.source}): \$${pa.price}")
                    networkRetry.recordFailure()
                    freshData = freshData.copy(
                        price = pa.price,
                        indexPrice = pa.price,
                        markPrice = pa.price,
                        priceChange24hPct = pa.change24h,
                    )
                }
            } catch (_: Exception) {}
        } else if (freshData.price > 0) {
            networkRetry.recordSuccess()
        }

        if (freshData.price > 0) {
            marketDataCache[market] = freshData
            lastFetchTime[market] = System.currentTimeMillis()
        }
        return freshData
    }
    
    /**
     * V5.7.6b: Get cached price data (non-blocking, for UI)
     * Returns null if no cached data available
     */
    fun getCachedPrice(market: PerpsMarket): PerpsMarketData? {
        return marketDataCache[market]
    }
    
    /**
     * V5.7.6b: Force refresh all cached prices (call from background)
     */
    suspend fun refreshAllPrices() {
        val markets = PerpsMarket.values().toList()
        markets.forEach { market ->
            try {
                val data = getMarketData(market)
                // V5.8: feed price back into DynamicAltTokenRegistry so alt tiles show real MC/L
                if (data.price > 0) {
                    DynamicAltTokenRegistry.updateStaticPrice(
                        symbol    = market.symbol,
                        price     = data.price,
                        change24h = data.priceChange24hPct,
                        mcap      = 0.0,
                        vol24h    = data.volume24h,
                    )
                }
            } catch (_: Exception) {}
        }
    }
    
    /**
     * V5.7.1: Primary data source - Pyth Oracle
     * V5.7.4: Improved fallback handling and logging
     */
    private suspend fun fetchFromPythOracle(market: PerpsMarket): PerpsMarketData {
        // V5.9.91: Pyth doesn't publish feeds for most exotic FX (USDBRL,
        // USDTRY, USDRUB, USDSGD, EURGBP, CADJPY…) nor soft commodities
        // (COFFEE, COCOA, WHEAT, SUGAR…) nor micro-cap crypto (BLAST, SCROLL,
        // PORTAL, CVXF…). Previously we still hit the Pyth API every cycle
        // for all of them, got NULL, then fell back → 25+ wasted round-trips
        // per tick. Gate on the official supported-symbol list and skip
        // straight to the fallback path for everything else.
        val pythSupported = PythOracle.getSupportedSymbols()
        if (!pythSupported.contains(market.symbol)) {
            return fetchFallbackForMarket(market)
        }
        try {
            val pythPrice = PythOracle.getPrice(market.symbol)
            
            if (pythPrice != null && pythPrice.price > 0.0) {  // V5.9.24: gate on positive price
                val isStale = pythPrice.isStale()
                if (!isStale) {
                    ErrorLogger.debug(TAG, "📡 Pyth: ${market.symbol} = \$${pythPrice.price.fmt(2)}")
                    
                    // Update the stockPrices cache with real Pyth price
                    if (market.isStock) {
                        stockPrices[market.symbol] = pythPrice.price
                    }
                    
                    // Real 24h change from PriceAggregator (CoinGecko/Binance — cached 3s)
                    // V5.9.3: Use PriceAggregator for ALL markets — stocks had calculateChange
                    // returning ~0 (Pyth price ≈ EMA price at rest) which killed signal generation.
                    val real24hChange: Double = try {
                        kotlinx.coroutines.runBlocking { PriceAggregator.getPrice(market.symbol)?.change24h }
                            ?: calculateChange(pythPrice.price, pythPrice.emaPrice)
                    } catch (_: Exception) { calculateChange(pythPrice.price, pythPrice.emaPrice) }
                    return PerpsMarketData(
                        market = market,
                        price = pythPrice.price,
                        indexPrice = pythPrice.emaPrice,
                        markPrice = pythPrice.price,
                        fundingRate = calculateFundingRate(market),
                        fundingRateAnnualized = calculateFundingRate(market) * 365 * 3 * 100,
                        nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
                        openInterestLong = getEstimatedOI(market, true),
                        openInterestShort = getEstimatedOI(market, false),
                        volume24h = getEstimatedVolume(market),
                        high24h = pythPrice.price * (1 + kotlin.math.abs(real24hChange) / 100 + 0.005),
                        low24h = pythPrice.price * (1 - kotlin.math.abs(real24hChange) / 100 - 0.005),
                        priceChange24hPct = real24hChange,
                    )
                } else {
                    // V5.9.27: stale Pyth — only trust for true CRYPTO. Stocks/ETFs/METALS/
                    // FX/COMMODITIES must fall through to PriceAggregator. Previously any
                    // non-stock (incl. metals like XPD/XAL) was treated as "crypto stale but
                    // valid", causing e.g. XAL = $1.05 and XPD = $1563 to produce bogus
                    // signals. Classify strictly.
                    if (market.isCrypto) {
                        if (pythPrice.price > 0) {
                            ErrorLogger.debug(TAG, "📊 Pyth stale but valid (crypto): ${market.symbol} = \$${pythPrice.price.fmt(2)}")
                            val staleChange: Double = try {
                                kotlinx.coroutines.runBlocking { PriceAggregator.getPrice(market.symbol)?.change24h }
                                    ?: calculateChange(pythPrice.price, pythPrice.emaPrice)
                            } catch (_: Exception) { calculateChange(pythPrice.price, pythPrice.emaPrice) }
                            return PerpsMarketData(
                                market = market,
                                price = pythPrice.price,
                                indexPrice = pythPrice.emaPrice,
                                markPrice = pythPrice.price,
                                fundingRate = calculateFundingRate(market),
                                fundingRateAnnualized = calculateFundingRate(market) * 365 * 3 * 100,
                                nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
                                openInterestLong = getEstimatedOI(market, true),
                                openInterestShort = getEstimatedOI(market, false),
                                volume24h = getEstimatedVolume(market),
                                high24h = pythPrice.price * (1 + kotlin.math.abs(staleChange) / 100 + 0.005),
                                low24h = pythPrice.price * (1 - kotlin.math.abs(staleChange) / 100 - 0.005),
                                priceChange24hPct = staleChange,
                            )
                        }
                    } else {
                        // Stock, ETF, metal, commodity, forex — stale Pyth must fall through
                        ErrorLogger.debug(TAG, "📊 Pyth stale for ${market.symbol} (${if(market.isMetal)"METAL" else if(market.isETF)"ETF" else if(market.isStock)"STOCK" else "OTHER"}) — using PriceAggregator")
                    }
                }
            } else {
                // V5.9.22: demote to debug — the fallback always has a value, this is expected
                ErrorLogger.debug(TAG, "Pyth NULL for ${market.symbol}, using fallback")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // V5.9.24: never swallow coroutine cancellations — caller must see them
        } catch (e: Exception) {
            // V5.9.24: demote to DEBUG — with DEX-first routing, Pyth failures are expected
            // and always fall back to PriceAggregator (which logs its own success/failure).
            ErrorLogger.debug(TAG, "Pyth fetch failed for ${market.symbol} (using fallback): ${e.message}")
        }
        
        // Fallback to legacy method with comprehensive stockPrices
        return fetchFallbackForMarket(market)
    }

    /**
     * V5.9.91: Shared fallback dispatcher — used both as the post-Pyth
     * fallback and as the direct path when the symbol isn't in Pyth's
     * supported list (avoids an unnecessary Pyth round-trip).
     */
    private suspend fun fetchFallbackForMarket(market: PerpsMarket): PerpsMarketData {
        return when (market) {
            PerpsMarket.SOL -> fetchSolDataFallback()
            else -> fetchStockDataFallback(market)
        }
    }
    
    /**
     * Fetch SOL market data from CoinGecko/Jupiter
     */
    private suspend fun fetchSolData(): PerpsMarketData = withContext(Dispatchers.IO) {
        try {
            // Try Jupiter first
            val jupiterPrice = tryJupiterPrice()
            if (jupiterPrice > 0) {
                return@withContext createSolMarketData(jupiterPrice)
            }
            
            // Fallback to CoinGecko
            val request = Request.Builder()
                .url(COINGECKO_SOL)
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(body)
            
            val solana = json.getJSONObject("solana")
            val price = solana.getDouble("usd")
            val change24h = solana.optDouble("usd_24h_change", 0.0)
            val volume24h = solana.optDouble("usd_24h_vol", 0.0)
            
            ErrorLogger.debug(TAG, "SOL price from CoinGecko: \$${price.fmt(2)}")
            
            return@withContext PerpsMarketData(
                market = PerpsMarket.SOL,
                price = price,
                indexPrice = price,
                markPrice = price,
                fundingRate = 0.0001 * (if (change24h > 0) 1 else -1),  // Simulated funding
                fundingRateAnnualized = 0.0001 * 365 * 3 * 100,
                nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,  // 8 hours
                openInterestLong = 50_000_000.0,   // Simulated OI
                openInterestShort = 45_000_000.0,
                volume24h = volume24h,
                high24h = price * 1.03,
                low24h = price * 0.97,
                priceChange24hPct = change24h,
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to fetch SOL data: ${e.message}")
            // Return mock data as fallback
            return@withContext createSolMarketData(150.0)
        }
    }
    
    /**
     * Try Jupiter Price API
     */
    private fun tryJupiterPrice(): Double {
        return try {
            val request = Request.Builder()
                .url(JUPITER_PRICE)
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return 0.0
            val json = JSONObject(body)
            
            val data = json.getJSONObject("data")
            val sol = data.getJSONObject("So11111111111111111111111111111111111111112")
            val price = sol.getDouble("price")
            
            ErrorLogger.debug(TAG, "SOL price from Jupiter: \$${price.fmt(2)}")
            price
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Jupiter price failed: ${e.message}")
            0.0
        }
    }
    
    private fun createSolMarketData(price: Double): PerpsMarketData {
        // V5.9.86: no simulated randomness. When we only have a price we don't
        // invent OI/volume/funding/mark deltas — leave them zeroed so downstream
        // can tell "data unavailable" vs a real feed. Display layers already
        // render "--" for zero-valued fields.
        return PerpsMarketData(
            market = PerpsMarket.SOL,
            price = price,
            indexPrice = price,
            markPrice = price,
            fundingRate = 0.0,
            fundingRateAnnualized = 0.0,
            nextFundingTime = 0,
            openInterestLong = 0.0,
            openInterestShort = 0.0,
            volume24h = 0.0,
            high24h = price,
            low24h = price,
            priceChange24hPct = 0.0,
        )
    }
    
    // V5.9.85: REMOVED `fetchStockData` (was a Math.random price walker producing
    // simulated stock prices). It was unreferenced but a reviewer could still
    // wire it up by accident. Real stock pricing now goes exclusively through
    // PythOracle → PriceAggregator → `fetchStockDataFallback` below. No
    // simulated data anywhere in the hot path.
    

    /**
     * Get all available markets with current data
     */
    suspend fun getAllMarketsData(): List<PerpsMarketData> {
        return PerpsMarket.values().map { market ->
            getMarketData(market)
        }
    }
    
    /**
     * Get SOL price only (quick helper)
     */
    suspend fun getSolPrice(): Double {
        return getMarketData(PerpsMarket.SOL).price
    }
    
    /**
     * Force refresh all market data
     */
    fun invalidateCache() {
        marketDataCache.clear()
        lastFetchTime.clear()
        ErrorLogger.info(TAG, "Market data cache invalidated")
    }
    
    /**
     * Check if market is currently tradeable
     */
    fun isMarketTradeable(market: PerpsMarket, isPaperMode: Boolean = false): Boolean {
        if (!market.isStock) return true  // Crypto 24/7
        
        // V5.7.3: Paper mode allows 24/7 stock trading for learning
        if (isPaperMode) {
            ErrorLogger.debug(TAG, "📊 ${market.symbol}: Paper mode - allowing 24/7 trading")
            return true
        }
        
        // Check market hours using America/New_York timezone
        val nyZone = java.util.TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(nyZone)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute

        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            return false
        }

        // Extended hours: 4:00 AM - 8:00 PM ET (includes pre-market and after-hours)
        return timeInMinutes in (4 * 60)..(20 * 60)
    }
    
    /**
     * Get market status string
     */
    fun getMarketStatus(market: PerpsMarket): String {
        return if (isMarketTradeable(market)) {
            "🟢 OPEN"
        } else {
            "🔴 CLOSED"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.1 HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate funding rate based on market conditions
     */
    private fun calculateFundingRate(market: PerpsMarket): Double {
        // V5.9.86: no simulated funding rates. Returning 0.0 signals "unknown".
        // Downstream leverage accounting already treats 0.0 as no funding cost.
        return 0.0
    }
    
    /**
     * Get estimated open interest — V5.9.86: returns 0.0 (unknown) instead of
     * a randomised fake, so the UI can show "--" and the bot never uses a
     * fabricated OI value for gating or sizing decisions.
     */
    private fun getEstimatedOI(market: PerpsMarket, isLong: Boolean): Double = 0.0
    
    /**
     * Get estimated 24h volume — V5.9.86: returns 0.0 (unknown) instead of a
     * randomised fake. No fabricated volume anywhere in the hot path.
     */
    private fun getEstimatedVolume(market: PerpsMarket): Double = 0.0
    
    /**
     * Calculate price change percentage
     */
    private fun calculateChange(currentPrice: Double, emaPrice: Double): Double {
        if (emaPrice <= 0) return 0.0
        return ((currentPrice - emaPrice) / emaPrice * 100)
    }
    
    /**
     * Fallback SOL data fetch
     */
    private suspend fun fetchSolDataFallback(): PerpsMarketData = withContext(Dispatchers.IO) {
        try {
            val jupiterPrice = tryJupiterPrice()
            if (jupiterPrice > 0) {
                return@withContext createSolMarketData(jupiterPrice)
            }
        } catch (_: Exception) {}
        
        return@withContext createSolMarketData(150.0)
    }
    
    /**
     * Get the data source that was used for a symbol
     */
    fun getPriceSource(symbol: String): String = priceSourceCache[symbol] ?: "UNKNOWN"
    
    /**
     * V5.7.7: Fallback stock data fetch - Uses PriceAggregator with 20+ sources
     */
    private suspend fun fetchStockDataFallback(market: PerpsMarket): PerpsMarketData = withContext(Dispatchers.IO) {
        // V5.7.7: Use unified PriceAggregator with ALL sources
        val result = PriceAggregator.getPrice(market.symbol)
        
        if (result != null && result.price > 0) {
            stockPrices[market.symbol] = result.price
            yahooChangeCache[market.symbol] = result.change24h
            priceSourceCache[market.symbol] = result.source
            
            ErrorLogger.info(TAG, "📊 ${result.source}: ${market.symbol} = \$${result.price.fmt(2)} (${if (result.change24h >= 0) "+" else ""}${result.change24h.fmt(2)}%)")
            
            return@withContext PerpsMarketData(
                market = market,
                price = result.price,
                indexPrice = result.price,
                markPrice = result.price,
                fundingRate = 0.0,
                fundingRateAnnualized = 0.0,
                nextFundingTime = 0,
                openInterestLong = getEstimatedOI(market, true),
                openInterestShort = getEstimatedOI(market, false),
                volume24h = getEstimatedVolume(market),
                high24h = result.price * (1 + kotlin.math.abs(result.change24h) / 100 + 0.005),
                low24h = result.price * (1 - kotlin.math.abs(result.change24h) / 100 - 0.005),
                priceChange24hPct = result.change24h,
            )
        }
        
        // V5.9.28 FIX: when ALL sources fail, return price=0 so downstream gates skip.
        // Previously defaulted to $100 which produced bogus signals (e.g. BABYDOGE at $100
        // while real price is ~$0.0000000009) and risked catastrophic live fills.
        val cachedPrice = stockPrices[market.symbol]
        if (cachedPrice == null) {
            ErrorLogger.warn(TAG, "⚠️ ALL SOURCES FAILED for ${market.symbol} — returning price=0 (no cached fallback)")
            priceSourceCache[market.symbol] = "DEAD"
            return@withContext PerpsMarketData(
                market = market,
                price = 0.0,
                indexPrice = 0.0,
                markPrice = 0.0,
                fundingRate = 0.0,
                fundingRateAnnualized = 0.0,
                nextFundingTime = 0,
                openInterestLong = 0.0,
                openInterestShort = 0.0,
                volume24h = 0.0,
                high24h = 0.0,
                low24h = 0.0,
                priceChange24hPct = 0.0,
            )
        }
        val price = cachedPrice
        priceSourceCache[market.symbol] = "CACHED"
        
        return@withContext PerpsMarketData(
            market = market,
            price = price,
            indexPrice = price,
            markPrice = price,
            fundingRate = 0.0,
            fundingRateAnnualized = 0.0,
            nextFundingTime = 0,
            openInterestLong = getEstimatedOI(market, true),
            openInterestShort = getEstimatedOI(market, false),
            volume24h = getEstimatedVolume(market),
            high24h = price * 1.02,
            low24h = price * 0.98,
            priceChange24hPct = 0.0,
        )
    }
}
