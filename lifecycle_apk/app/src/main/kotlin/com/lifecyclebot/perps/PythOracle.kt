package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📡 PYTH ORACLE INTEGRATION - V5.7.1
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Real-time price feeds for SOL and tokenized stocks via Pyth Network.
 * 
 * PYTH NETWORK:
 * - Permissionless oracle for DeFi
 * - Sub-second latency price feeds
 * - Confidence intervals for accuracy
 * - 380+ price feeds across 40+ blockchains
 * 
 * SUPPORTED FEEDS:
 * - SOL/USD (Crypto.SOL/USD)
 * - AAPL/USD (Equity.US.AAPL/USD)
 * - TSLA/USD (Equity.US.TSLA/USD)
 * - NVDA/USD (Equity.US.NVDA/USD)
 * - GOOGL/USD (Equity.US.GOOGL/USD)
 * - AMZN/USD (Equity.US.AMZN/USD)
 * - META/USD (Equity.US.META/USD)
 * - MSFT/USD (Equity.US.MSFT/USD)
 * - COIN/USD (Equity.US.COIN/USD)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PythOracle {
    
    private const val TAG = "📡PythOracle"
    
    // Pyth Hermes API endpoint (free, no API key required)
    private const val PYTH_HERMES_URL = "https://hermes.pyth.network/api/latest_price_feeds"
    private const val PYTH_PRICE_URL = "https://hermes.pyth.network/v2/updates/price/latest"
    
    // Pyth Price Feed IDs (mainnet)
    // These are the official Pyth price feed IDs
    private val PRICE_FEED_IDS = mapOf(
        // ═══════════════════════════════════════════════════════════════════════
        // 🪙 MAJOR CRYPTOCURRENCIES
        // ═══════════════════════════════════════════════════════════════════════
        "SOL" to "0xef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d",
        "BTC" to "0xe62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43",
        "ETH" to "0xff61491a931112ddf1bd8147cd1b641375f79f5825126d665480874634fd0ace",
        "BNB" to "0x2f95862b045670cd22bee3114c39763a4a08beeb663b145d283c31d7d1101c4f",
        "XRP" to "0xec5d399846a9209f3fe5881d70aae9268c94339ff9817e8d18ff19fa05eea1c8",
        "ADA" to "0x2a01deaec9e51a579277b34b122399984d0bbf57e2458a7e42fecd2829867a0d",
        "DOGE" to "0xdcef50dd0a4cd2dcc17e45df1676dcb336a11a61c69df7a0299b0150c672d25c",
        "AVAX" to "0x93da3352f9f1d105fdfe4971cfa80e9dd777bfc5d0f683ebb6e1294b92137bb7",
        "DOT" to "0xca3eed9b267293f6595901c734c7525ce8ef49adafe8284606ceb307afa2ca5b",
        "LINK" to "0x8ac0c70fff57e9aefdf5edf44b51d62c2d433653cbb2cf5cc06bb115af04d221",
        "MATIC" to "0x5de33440f6c8ee339c2fb888957bfce23615f6c30ea6d2fcaf82d80a7c1ede11",
        "SHIB" to "0xf0d57deca57b3da2fe63a493f4c25925fdfd8edf834b20f93e1f84dbd1504d4a",
        "LTC" to "0x6e3f3fa8253588df9326580180233eb791e03b443a3ba7a1d892e73874e19a54",
        "ATOM" to "0xb00b60f88b03a6a625a8d1c048c3f66653edf217439983d037e7222c4e612819",
        "UNI" to "0x78d185a741d07edb3412b09008b7c5cfb9bbbd7d568bf00ba737b456ba171501",
        "ARB" to "0x3fa4252848f9f0a1480be62745a4629d9eb1322aebab8a791e344b3b9c1adcf5",
        "OP" to "0x385f64d993f7b77d8182ed5003d97c60aa3361f3cecfe711544d2d59165e9bdf",
        "APT" to "0x03ae4db29ed4ae33d323568895aa00337e658e348b37509f5372ae51f0af00d5",
        "SUI" to "0x23d7315113f5b1d3ba7a83604c44b94d79f4fd69af77f804fc7f920a6dc65744",
        "SEI" to "0x53614f1cb0c031d4af66c04cb9c756234adad0e1cee85303795091499a4084eb",
        "INJ" to "0x7a5bc1d2b56ad029048cd63964b3ad2776eadf812edc1a43a31406cb54bff592",
        "TIA" to "0x09f7c1d7dfbb7df2b8fe3d3d87ee94a2259d212da4f30c1f0540d066dfa44723",
        "JUP" to "0x0a0408d619e9380abad35060f9192039ed5042fa6f82301d0e48bb52be830996",
        "PEPE" to "0xd69731a2e74ac1ce884fc3890f7ee324b6deb66147055249568869ed700882e4",
        "WIF" to "0x4ca4beeca86f0d164160323817a4e42b10010a724c2217c6ee41b54cd4cc61fc",
        "BONK" to "0x72b021217ca3fe68922a19aaf990109cb9d84e9ad004b4d2025ad6f529314419",

        // ═══════════════════════════════════════════════════════════════════════
        // V5.8 - MAJOR ALTS (Pyth-verified feed IDs)
        // ═══════════════════════════════════════════════════════════════════════
        "TRX" to "0x67aed5a24fdaa045475e7195c98a98aea119c763f272d4523f5bac93a4f33c2b",
        "BCH" to "0x3dd2b63686a450ec7290df3a1e0b583c0481f651351edfa7636f39aed55cf8a3",
        "XLM" to "0xb7a8eba68a997cd0210c2e1e4ee811ad2d174b3611c22d9ebf16f4cb7e9ba850",
        "XMR" to "0x46b8cc9347f04391764a0361e0b17c3ba394b001e7c304f7650f6e0d723a9992",
        "ETC" to "0x7f2c397a1d8ad5e5dc76b49ccf0dce0c7cc0a94c04c5c04eeab1dd7c0dd6d8b4",

        // V5.8 - DeFi & New Ecosystem
        "DYDX" to "0x6489800bb8974169adfe35937bf6736507097d13c190d760c557108c7e93a81b",
        "WLD" to "0xd6835ad1f773de4a378115eb6824bd0c0e42d84d1c84d9750e853fb6b6c7794d",
        "JTO" to "0xb43660a5f790c69354b0729a5ef9d50d68f1df92107540210b9cccba1f947cc2",
        "W" to "0xeff7446475e218517566ea99e72a4abec2e1bd8498b43b7d8331e29dcb059389",
        "STRK" to "0x6a182399ff70ccf3e06024898942028204125a819e519a335ffa4579e66cd870",
        "TAO" to "0x410f41de235f2db824e562ea7ab2d3d3d4ff048316c61d629c0b93f58584e1af",
        // TON, ENA, PENDLE, CAKE, GMX, ZEC, XTZ, EOS, FLOKI, NOT, POPCAT, TRUMP:
        // no confirmed Pyth IDs — fall through to Jupiter/CoinGecko/Binance
        
        // ═══════════════════════════════════════════════════════════════════════
        // US EQUITIES - REAL Pyth Price Feed IDs (verified from Hermes API)
        // ═══════════════════════════════════════════════════════════════════════
        
        // 🔥 MEGA TECH (FAANG+)
        "AAPL" to "0x49f6b65cb1de6b10eaf75e7c03ca029c306d0357e91b5311b175084a5ad55688",
        "TSLA" to "0x16dad506d7db8da01c87581c87ca897a012a153557d4d578c3b9c9e1bc0632f1",
        "NVDA" to "0xb1073854ed24cbc755dc527418f52b7d271f6cc967bbf8d8129112b18860a593",
        "GOOGL" to "0x5a48c03e9b9cb337801073ed9d166817473697efff0d138874e0f6a33d6d5aa6",
        "AMZN" to "0xb5d0e0fa58a1f8b81498ae670ce93c872d14434b72c364885d4fa1b257cbb07a",
        "META" to "0x78a3e3b8e676a8f73c439f5d749737034b139bbbe899ba5775216fba596607fe",
        "MSFT" to "0xd0ca23c1cc005e004ccf1db5bf76aeb6a49218f43dac3d4b275e92de12ded4d1",
        "NFLX" to "0x8376cfd7ca8bcdf372ced05307b24dced1f15b1afafdeff715664598f15a3dd2",
        
        // 💎 SEMICONDUCTORS  
        "AMD" to "0x3622e381dbca2efd1859253763b1adc63f7f9abb8e76da1aa8e638a57ccde93e",
        "INTC" to "0xc1751e085ee292b8b3b9dd122a135614485a201c35dfc653553f0e28c1baf3ff",
        "QCOM" to "0x54350ebf587c3f14857efcfec50e5c4f6e10220770c2266e9fe85bd5e42e4022",
        "AVGO" to "0xd0c9aef79b28308b256db7742a0a9b08aaa5009db67a52ea7fa30ed6853f243b",
        "MU" to "0x152244dc24665ca7dd3f257b8f442dc449b6346f48235b7b229268cb770dda2d",
        
        // 🚀 GROWTH TECH
        "CRM" to "0xfeff234600320f4d6bb5a01d02570a9725c1e424977f2b823f7231e6857bdae8",
        "ORCL" to "0xe47ff732eaeb6b4163902bdee61572659ddf326511917b1423bae93fcdf3153c",
        "PLTR" to "0x11a70634863ddffb71f2b11f2cff29f73f3db8f6d0b78c49f2b5f4ad36e885f0",
        "SNOW" to "0x14291d2651ecf1f9105729bdc59553c1ce73fb3d6c931dd98a9d2adddc37e00f",
        "SHOP" to "0xc9034e8c405ba92888887bc76962b619d0f8e8bf3e12aba972af0cf64e814d5d",
        
        // 💳 FINTECH & CRYPTO
        "COIN" to "0xfee33f2a978bf32dd6b662b65ba8083c6773b494f8401194ec1870c640860245",
        "PYPL" to "0x773c3b11f6be58e8151966a9f5832696d8cd08884ccc43ac8965a7ebea911533",
        "V" to "0xc719eb7bab9b2bc060167f1d1680eb34a29c490919072513b545b9785b73ee90",
        "MA" to "0x639db3fe6951d2465bd722768242e68eb0285f279cb4fa97f677ee8f80f1f1c0",
        "JPM" to "0x7f4f157e57bfcccd934c566df536f34933e74338fe241a5425ce561acdab164e",
        "GS" to "0x9c68c0c6999765cf6e27adf75ed551b34403126d3b0d5b686a2addb147ed4554",
        
        // 🎯 CONSUMER & TRAVEL
        "DIS" to "0x703e36203020ae6761e6298975764e266fb869210db9b35dd4e4225fa68217d0",
        "UBER" to "0xc04665f62a0eabf427a834bb5da5f27773ef7422e462d40c7468ef3e4d39d8f1",
        "ABNB" to "0xccab508da0999d36e1ac429391d67b3ac5abf1900978ea1a56dab6b1b932168e",
        "NKE" to "0x67649450b4ca4bfff97cbaf96d2fd9e40f6db148cb65999140154415e4378e14",
        "SBUX" to "0x86cd9abb315081b136afc72829058cf3aaf1100d4650acb2edb6a8e39f03ef75",
        "MCD" to "0xd3178156b7c0f6ce10d6da7d347952a672467b51708baaf1a57ffe1fb005824a",
        
        // 🏭 INDUSTRIAL & RETAIL
        "BA" to "0x8419416ba640c8bbbcf2d464561ed7dd860db1e38e51cec9baf1e34c4be839ae",
        "WMT" to "0x327ae981719058e6fb44e132fb4adbf1bd5978b43db0661bfdaefd9bea0c82dc",
        "HD" to "0xb3a83dbe70b62241b0f916212e097465a1b31085fa30da3342dd35468ca17ca5",
        "COST" to "0x163f6a6406d65305e8e27965b9081ac79b0cf9529f0fcdc14fe37e65e3b6b5cb",
        
        // 🧬 HEALTHCARE & CONSUMER
        "JNJ" to "0x12848738d5db3aef52f51d78d98fc8b8b8450ffb19fb3aeeb67d38f8c147ff63",
        "PFE" to "0x0704ad7547b3dfee329266ee53276349d48e4587cb08264a2818288f356efd1d",
        "UNH" to "0x05380f8817eb1316c0b35ac19c3caa92c9aa9ea6be1555986c46dce97fed6afd",
        "KO" to "0x9aa471dccea36b90703325225ac76189baf7e0cc286b8843de1de4f31f9caa7d",
        "PEP" to "0xbe230eddb16aad5ad273a85e581e74eb615ebf67d378f885768d9b047df0c843",
        
        // ⛽ ENERGY
        "XOM" to "0x4a1a12070192e8db9a89ac235bb032342a390dde39389b4ee1ba8e41e7eae5d8",
        "CVX" to "0xf464e36fd4ef2f1c3dc30801a9ab470dcdaaa0af14dd3cf6ae17a7fca9e051c5",
        
        // 🛢️ COMMODITIES (Oil - 24/7 trading)
        // NATGAS: removed — the previously used ID returned UK NBP (~100 pence/therm) not Henry Hub (~3.50 USD/MMBtu)
        // RBOB, HEATING, agricultural: no verified Pyth feed IDs — fall through to PriceAggregator Yahoo futures
        "BRENT" to "0x27f0d5e09a830083e5491795cac9ca521399c8f7fd56240d09484b14e614d57a",  // UKOILSPOT
        "WTI" to "0x925ca92ff005ae943c158e3563f59698ce7e75c5a8c8dd43303a0a154887b3e6",    // USOILSPOT
        
        // 🥇 PRECIOUS METALS (24/7 trading)
        "XAU" to "0x765d2ba906dbc32ca17cc11f5310a89e9ee1f6420508c63861f2f8ba4ee34bb2",   // Gold
        "XAG" to "0xf2fb02c32b055c805e7238d628e5e9dadef274376114eb1f012337cabe93871e",   // Silver
        "XPT" to "0x398e4bbc7cbf89d6648c21e08019d878967677753b3096799595c78f805a34e5",   // Platinum
        "XPD" to "0x80367e9664197f37d89a07a804dffd2101c479c7c4e8490501bc9d9e1e7f9021",   // Palladium
        
        // 🔩 INDUSTRIAL METALS (24/7 trading)
        "XCU" to "0x636bedafa14a37912993f265eda22431a2be363ad41a10276424bbe1b7f508c4",   // Copper
        "XAL" to "0x2818d3a9c8e0a80bd02bb500d62e5bb1323fa3df287f081d82b27d1e22c71afa",   // Aluminum
        "XNI" to "0xa41da02810f3993706dca86e32582d40de376116eff24342353c33a0a8f9c083",   // Nickel
        "XTI" to "0xa35b407f0fa4b027c2dfa8dff0b7b99b853fb4d326a9e9906271933237b90c1c",   // Titanium
        // ZINC, LEAD, TIN, IRON, COBALT, LITHIUM, URANIUM: no verified Pyth feed IDs — fall through to PriceAggregator
        
        // ═══════════════════════════════════════════════════════════════════════
        // 💱 FOREX (Major Pairs - 24/5 trading)
        // ═══════════════════════════════════════════════════════════════════════
        "EURUSD" to "0xa995d00bb36a63cef7fd2c287dc105fc8f3d93779f062f09551b0af3e81ec30b",
        "GBPUSD" to "0x84c2dde9633d93d1bcad84e7dc41c9d56578b7ec52fabedc1f335d673df0a7c1",
        "USDJPY" to "0xef2c98c804ba503c6a707e38be4dfbb16683775f195b091252bf24693042fd52",
        "AUDUSD" to "0x67a6f93030420c1c9e3fe37c1ab6b77966af82f995944a9fefce357a22854a80",
        "USDCAD" to "0x3112b03a41c910ed446852aacf67118cb1bec67b2cd0b9a214c58cc0eaa2ecca",
        "USDCHF" to "0x0b1e3297e69f162877b577b0d6a47a0d63b2392bc8499e6540da4187a63e28f8",
        "NZDUSD" to "0x92eea8ba1b00078cdc2ef6f64f091f262e8c7d0576ee4677572f314ebfafa4c7",
        
        // Cross pairs and emerging market forex: no verified Pyth feed IDs — fall through to PriceAggregator Yahoo (EURUSD=X format)
    )
    
    // Cache
    private val priceCache = ConcurrentHashMap<String, PythPrice>()
    private val lastFetchTime = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL_MS = 2_000L  // 2 second cache for real-time data
    
    // HTTP Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PythPrice(
        val symbol: String,
        val price: Double,
        val confidence: Double,          // Price confidence interval
        val expo: Int,                   // Price exponent
        val publishTime: Long,           // Unix timestamp
        val emaPrice: Double,            // Exponential moving average price
        val emaConfidence: Double,
    ) {
        fun isStale(): Boolean = System.currentTimeMillis() - publishTime * 1000 > 60_000  // 60 seconds
        fun getConfidencePct(): Double = if (price > 0) (confidence / price * 100) else 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE FETCHING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get real-time price from Pyth Oracle
     */
    suspend fun getPrice(symbol: String): PythPrice? = withContext(Dispatchers.IO) {
        // Check cache
        val cached = priceCache[symbol]
        val lastFetch = lastFetchTime[symbol] ?: 0
        if (cached != null && System.currentTimeMillis() - lastFetch < CACHE_TTL_MS) {
            return@withContext cached
        }
        
        val feedId = PRICE_FEED_IDS[symbol]
        if (feedId == null) {
            ErrorLogger.warn(TAG, "No Pyth feed ID for $symbol")
            return@withContext null
        }
        
        try {
            val url = "$PYTH_HERMES_URL?ids[]=$feedId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (body == null || !response.isSuccessful) {
                ErrorLogger.warn(TAG, "Pyth API error for $symbol: ${response.code}")
                return@withContext getFallbackPrice(symbol)
            }
            
            val pythPrice = parsePythResponse(symbol, body)
            if (pythPrice != null) {
                priceCache[symbol] = pythPrice
                lastFetchTime[symbol] = System.currentTimeMillis()
                ErrorLogger.debug(TAG, "📡 $symbol: \$${pythPrice.price} (conf: ${pythPrice.getConfidencePct().format(2)}%)")
            }
            
            return@withContext pythPrice ?: getFallbackPrice(symbol)
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Pyth fetch error for $symbol: ${e.message}")
            return@withContext getFallbackPrice(symbol)
        }
    }
    
    /**
     * Parse Pyth API response
     */
    private fun parsePythResponse(symbol: String, body: String): PythPrice? {
        return try {
            val jsonArray = JSONArray(body)
            if (jsonArray.length() == 0) return null
            
            val feed = jsonArray.getJSONObject(0)
            val priceData = feed.getJSONObject("price")
            val emaData = feed.optJSONObject("ema_price")
            
            val priceRaw = priceData.getString("price").toLong()
            val confRaw = priceData.getString("conf").toLong()
            val expo = priceData.getInt("expo")
            val publishTime = priceData.getLong("publish_time")
            
            // Convert using exponent
            val multiplier = Math.pow(10.0, expo.toDouble())
            val price = priceRaw * multiplier
            val confidence = confRaw * multiplier
            
            val emaPrice = emaData?.let {
                it.getString("price").toLong() * multiplier
            } ?: price
            
            val emaConf = emaData?.let {
                it.getString("conf").toLong() * multiplier
            } ?: confidence
            
            PythPrice(
                symbol = symbol,
                price = price,
                confidence = confidence,
                expo = expo,
                publishTime = publishTime,
                emaPrice = emaPrice,
                emaConfidence = emaConf,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse error for $symbol: ${e.message}")
            null
        }
    }
    
    /**
     * Fallback prices when Pyth is unavailable.
     * Covers all symbols that have Pyth feed IDs plus commonly-requested extras.
     * Values are approximate and used only when the live feed is down.
     */
    private fun getFallbackPrice(symbol: String): PythPrice {
        val fallbackPrices = mapOf(
            // ── Crypto ──────────────────────────────────────────────────────────
            "SOL"   to 150.0,
            "BTC"   to 65000.0,
            "ETH"   to 3200.0,
            "BNB"   to 590.0,
            "XRP"   to 0.52,
            "ADA"   to 0.45,
            "DOGE"  to 0.13,
            "AVAX"  to 35.0,
            "DOT"   to 7.5,
            "LINK"  to 15.0,
            "MATIC" to 0.90,
            "SHIB"  to 0.000025,
            "LTC"   to 80.0,
            "ATOM"  to 9.0,
            "UNI"   to 7.5,
            "ARB"   to 1.10,
            "OP"    to 2.20,
            "APT"   to 9.5,
            "SUI"   to 1.80,
            "SEI"   to 0.55,
            "INJ"   to 28.0,
            "TIA"   to 8.0,
            "JUP"   to 0.90,
            "PEPE"  to 0.0000115,
            "WIF"   to 2.50,
            "BONK"  to 0.000028,
            "TRX"   to 0.12,
            "BCH"   to 480.0,
            "XLM"   to 0.11,
            "XMR"   to 165.0,
            "ETC"   to 27.0,
            "DYDX"  to 2.30,
            "WLD"   to 2.80,
            "JTO"   to 3.50,
            "W"     to 0.48,
            "STRK"  to 1.10,
            "TAO"   to 450.0,
            // ── US Equities (Mega/FAANG+) ────────────────────────────────────
            "AAPL"  to 195.50,
            "TSLA"  to 248.30,
            "NVDA"  to 875.20,
            "GOOGL" to 175.80,
            "AMZN"  to 185.60,
            "META"  to 510.40,
            "MSFT"  to 420.15,
            "NFLX"  to 630.0,
            // ── Semiconductors ──────────────────────────────────────────────
            "AMD"   to 175.0,
            "INTC"  to 31.0,
            "QCOM"  to 175.0,
            "AVGO"  to 1350.0,
            "MU"    to 115.0,
            // ── Growth Tech ─────────────────────────────────────────────────
            "CRM"   to 290.0,
            "ORCL"  to 120.0,
            "PLTR"  to 24.0,
            "SNOW"  to 165.0,
            "SHOP"  to 72.0,
            // ── Fintech & Crypto ─────────────────────────────────────────────
            "COIN"  to 245.80,
            "PYPL"  to 62.0,
            "V"     to 280.0,
            "MA"    to 480.0,
            "JPM"   to 200.0,
            "GS"    to 460.0,
            // ── Consumer & Travel ────────────────────────────────────────────
            "DIS"   to 110.0,
            "UBER"  to 73.0,
            "ABNB"  to 155.0,
            "NKE"   to 95.0,
            "SBUX"  to 75.0,
            "MCD"   to 280.0,
            // ── Industrial & Retail ──────────────────────────────────────────
            "BA"    to 175.0,
            "WMT"   to 175.0,
            "HD"    to 360.0,
            "COST"  to 850.0,
            // ── Healthcare & Consumer ────────────────────────────────────────
            "JNJ"   to 155.0,
            "PFE"   to 28.0,
            "UNH"   to 510.0,
            "KO"    to 62.0,
            "PEP"   to 170.0,
            // ── Energy ──────────────────────────────────────────────────────
            "XOM"   to 120.0,
            "CVX"   to 155.0,
            // ── Commodities ─────────────────────────────────────────────────
            "BRENT" to 83.0,
            "WTI"   to 79.0,
            "NATGAS" to 2.20,
            "WHEAT" to 550.0,
            "CORN"  to 430.0,
            "SOYBEAN" to 1130.0,
            "COFFEE" to 185.0,
            "COCOA" to 6500.0,
            "SUGAR" to 21.0,
            "COTTON" to 85.0,
            "LUMBER" to 530.0,
            "OJ"    to 350.0,
            "CATTLE" to 175.0,
            "HOGS"  to 85.0,
            // ── Precious Metals ──────────────────────────────────────────────
            "XAU"  to 2300.0,   // Gold $/troy oz
            "XAG"  to 27.5,     // Silver $/troy oz
            "XPT"  to 950.0,    // Platinum $/troy oz
            "XPD"  to 1020.0,   // Palladium $/troy oz
            // ── Industrial Metals ────────────────────────────────────────────
            "XCU"  to 4.20,     // Copper $/lb
            "XAL"  to 0.95,     // Aluminium $/lb
            "XNI"  to 7.50,     // Nickel $/lb
            "XTI"  to 5.50,     // Titanium $/lb
            "ZINC" to 1.20,
            "LEAD" to 0.90,
            "TIN"  to 13.0,
            "IRON" to 110.0,
            "COBALT" to 14.0,
            "LITHIUM" to 15.0,
            "URANIUM" to 90.0,
            // ── Forex ────────────────────────────────────────────────────────
            "EURUSD" to 1.085,
            "GBPUSD" to 1.265,
            "USDJPY" to 153.0,
            "AUDUSD" to 0.645,
            "USDCAD" to 1.365,
            "USDCHF" to 0.905,
            "NZDUSD" to 0.595,
            "USDMXN" to 17.2,
            "USDBRL" to 5.05,
            "USDINR" to 83.5,
            "USDCNY" to 7.24,
            "USDZAR" to 18.8,
            "USDTRY" to 32.5,
            "USDSGD" to 1.35,
            "USDHKD" to 7.82,
            "USDKRW" to 1360.0,
        )

        val price = fallbackPrices[symbol] ?: 100.0
        
        return PythPrice(
            symbol = symbol,
            price = price,
            confidence = price * 0.001,  // 0.1% confidence
            expo = -8,
            publishTime = System.currentTimeMillis() / 1000,
            emaPrice = price,
            emaConfidence = price * 0.001,
        )
    }
    
    /**
     * Batch fetch multiple prices
     */
    suspend fun getPrices(symbols: List<String>): Map<String, PythPrice> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, PythPrice>()
        
        // Build batch request
        val feedIds = symbols.mapNotNull { PRICE_FEED_IDS[it] }
        if (feedIds.isEmpty()) return@withContext results
        
        try {
            val idsParam = feedIds.joinToString("&ids[]=", prefix = "ids[]=")
            val url = "$PYTH_HERMES_URL?$idsParam"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (body != null && response.isSuccessful) {
                val jsonArray = JSONArray(body)
                
                for (i in 0 until jsonArray.length()) {
                    val feed = jsonArray.getJSONObject(i)
                    val feedId = feed.getString("id")
                    
                    // Find symbol for this feed ID
                    val symbol = PRICE_FEED_IDS.entries.find { it.value.contains(feedId) }?.key
                    if (symbol != null) {
                        val priceData = feed.getJSONObject("price")
                        val priceRaw = priceData.getString("price").toLong()
                        val confRaw = priceData.getString("conf").toLong()
                        val expo = priceData.getInt("expo")
                        val publishTime = priceData.getLong("publish_time")
                        
                        val multiplier = Math.pow(10.0, expo.toDouble())
                        
                        val pythPrice = PythPrice(
                            symbol = symbol,
                            price = priceRaw * multiplier,
                            confidence = confRaw * multiplier,
                            expo = expo,
                            publishTime = publishTime,
                            emaPrice = priceRaw * multiplier,
                            emaConfidence = confRaw * multiplier,
                        )
                        
                        results[symbol] = pythPrice
                        priceCache[symbol] = pythPrice
                        lastFetchTime[symbol] = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Batch fetch error: ${e.message}")
        }
        
        // Fill in missing with fallbacks
        symbols.forEach { symbol ->
            if (!results.containsKey(symbol)) {
                results[symbol] = getFallbackPrice(symbol)
            }
        }
        
        return@withContext results
    }
    
    /**
     * Get SOL price specifically (most common use case)
     */
    suspend fun getSolPrice(): Double {
        val price = getPrice("SOL")
        return price?.price ?: 150.0
    }
    
    /**
     * Check if price feed is healthy
     */
    fun isPriceFeedHealthy(symbol: String): Boolean {
        val cached = priceCache[symbol] ?: return false
        return !cached.isStale() && cached.getConfidencePct() < 1.0  // Less than 1% confidence interval
    }
    
    /**
     * Get all supported symbols
     */
    fun getSupportedSymbols(): List<String> = PRICE_FEED_IDS.keys.toList()
    
    /**
     * Clear cache
     */
    fun clearCache() {
        priceCache.clear()
        lastFetchTime.clear()
    }
    
    private fun Double.format(decimals: Int): String = String.format("%.${decimals}f", this)
}
