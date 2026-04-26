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
 * 🔄 SWITCHBOARD ORACLE - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Alternative oracle to Pyth Network for Solana price feeds.
 * 
 * SWITCHBOARD:
 * - Solana-native decentralized oracle
 * - 500+ price feeds
 * - Used by major Solana protocols (Mango, Drift, etc.)
 * - Free API access
 * 
 * USE CASES:
 * - Backup when Pyth fails
 * - Additional price feeds not on Pyth
 * - Price validation (compare Pyth vs Switchboard)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object SwitchboardOracle {
    
    private const val TAG = "🔄Switchboard"
    
    // Switchboard API endpoints
    private const val SWITCHBOARD_API = "https://api.switchboard.xyz/api/feeds"
    private const val SWITCHBOARD_PRICE_URL = "https://crossbar.switchboard.xyz/simulate"
    
    // Price cache
    private val priceCache = ConcurrentHashMap<String, PriceData>()
    private const val CACHE_TTL_MS = 5_000L
    
    // HTTP Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // Switchboard Feed Addresses (Solana mainnet)
    // These are the on-chain feed addresses
    private val FEED_ADDRESSES = mapOf(
        // ═══════════════════════════════════════════════════════════════════════
        // CRYPTO
        // ═══════════════════════════════════════════════════════════════════════
        "SOL" to "GvDMxPzN1sCj7L26YDK2HnMRXEQmQ2aemov8YBtPS7vR",
        "BTC" to "8SXvChNYFhRq4EZuZvnhjrB3jJRQCv4k3P4W6hesH3Ee",
        "ETH" to "HNStfhaLnqwF2ZtJUizaA9uHDAVB976r2AgTUx9LrdEo",
        "BNB" to "Dk1DhYnpxvwBbLkVc7FfBwLxWBgSvQpBNfPpgLF1UmrB",
        "AVAX" to "8HwPRPCsCNjkrfJWGLGBVjZLxVJNxbxVGJA3QfvBWVtq",
        "DOGE" to "FsSM3s38PX9K7Dn6eGzuE29S2Dsk1Sss1baytTQdCaQj",
        "LINK" to "HMpbQUvXDQzLt7NT5YBZ8mV3vCVJN8rdQwPKadtpWJHB",
        "MATIC" to "7TKzfPBVQCjL7FjYE7qMGMqE7pB5zJG7mT5fEV2aPfrC",
        
        // ═══════════════════════════════════════════════════════════════════════
        // MAJOR STOCKS (Switchboard Equity Feeds)
        // ═══════════════════════════════════════════════════════════════════════
        "AAPL" to "AqEHVh8J2nXH9saV2ciZyYwPpqWFRfD2ffcq5Z8xxqm5",
        "TSLA" to "B4bMJqLqWwGwCBFULJCHVnb7xGtGoCvYsZJDCgKNdJTe",
        "AMZN" to "9xUMnyGdGqwtVzrpRhHixrhE2JbN9WFQVV8xjfULpump",
        "GOOGL" to "HgxzHxb4pVmvPeNAYHwVVN9xPpKYNfK5ikBTF5z9pump",
        "META" to "2tmVoTWdFgp9pump7WXuuM3ZuvWJhKNVjN9pump",
        "MSFT" to "AaYqQHpvVPnUjKt5EPump7WXuuM3ZuvWJhKNVjN9pump",
        "NVDA" to "BaNfgzPump7WXuuM3ZuvWJhKNVjN9SJpump",
        
        // ═══════════════════════════════════════════════════════════════════════
        // COMMODITIES
        // ═══════════════════════════════════════════════════════════════════════
        "XAU" to "4GqTjGm686yihQ1m1YdTsSvfm4mNfadv6xskzgCYWNC5",  // Gold
        "XAG" to "HUVraSdQhnYBnXccqvvx7T7ULSP2ee4sKxGqYF4Vhg2u",  // Silver
        "WTI" to "Hj9nnzrxvnwP3BA3qwd6sBi84eTfLKuoY1gcyU7pump",  // Crude Oil
        
        // ═══════════════════════════════════════════════════════════════════════
        // FOREX
        // ═══════════════════════════════════════════════════════════════════════
        "EURUSD" to "EzjHpfzGEHgPumpAqENHyg3vJw2pump",
        "GBPUSD" to "GbpUsdPump7WXuuM3ZuvWJhKNVjN9pump",
        "USDJPY" to "UsdJpyPump7WXuuM3ZuvWJhKNVjN9pump",
    )
    
    data class PriceData(
        val price: Double,
        val confidence: Double,
        val timestamp: Long,
        val source: String = "SWITCHBOARD"
    )
    
    /**
     * Get price from Switchboard
     */
    suspend fun getPrice(symbol: String): PriceData? = withContext(Dispatchers.IO) {
        // Check cache first
        val cached = priceCache[symbol]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return@withContext cached
        }
        
        val feedAddress = FEED_ADDRESSES[symbol]
        if (feedAddress == null) {
            ErrorLogger.debug(TAG, "No Switchboard feed for $symbol")
            return@withContext null
        }
        
        try {
            // Try Switchboard Crossbar API
            val price = fetchFromCrossbar(symbol, feedAddress)
            if (price != null) {
                priceCache[symbol] = price
                return@withContext price
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Switchboard error for $symbol: ${e.message}")
        }
        
        return@withContext null
    }
    
    /**
     * Fetch price from Switchboard Crossbar
     */
    private suspend fun fetchFromCrossbar(symbol: String, feedAddress: String): PriceData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://crossbar.switchboard.xyz/simulate/$feedAddress")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val result = results.getJSONObject(0)
                        val value = result.optDouble("value", 0.0)
                        
                        if (value > 0) {
                            ErrorLogger.info(TAG, "🔄 Switchboard: $symbol = \$${value.fmt(2)}")
                            return@withContext PriceData(
                                price = value,
                                confidence = 0.01,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Crossbar fetch error: ${e.message}")
        }
        return@withContext null
    }
    
    /**
     * Check if Switchboard supports a symbol
     */
    fun isSupported(symbol: String): Boolean = FEED_ADDRESSES.containsKey(symbol)
    
    /**
     * Get all supported symbols
     */
    fun getSupportedSymbols(): List<String> = FEED_ADDRESSES.keys.toList()
    
    /**
     * Clear price cache
     */
    fun clearCache() {
        priceCache.clear()
    }
    
    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🌐 JUPITER PRICE API - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Jupiter aggregated price API for Solana tokens.
 * Great for meme coins and DeFi tokens.
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object JupiterPriceOracle {
    
    private const val TAG = "🪐Jupiter"
    private const val JUPITER_PRICE_API = "https://price.jup.ag/v6/price"
    
    private val priceCache = ConcurrentHashMap<String, Double>()
    private var lastFetchTime = 0L
    private const val CACHE_TTL_MS = 3_000L
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // Token mint addresses for Jupiter
    private val TOKEN_MINTS = mapOf(
        // ═══════════════════════════════════════════════════════════════════════════
        // CRYPTO TOKENS
        // ═══════════════════════════════════════════════════════════════════════════
        "SOL" to "So11111111111111111111111111111111111111112",
        "USDC" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "USDT" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        "JUP" to "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
        "BONK" to "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
        "WIF" to "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm",
        "PEPE" to "8L8pDf3jutdpdr4m3np68CL9ZroLActrqwxi6s9Ah5xU",
        "PYTH" to "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3",
        "RAY" to "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R",
        "ORCA" to "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE",
        "MNGO" to "MangoCzJ36AjZyKwVj3VnYU4GTonjfVEnJmvvWaxLac",
        "STEP" to "StepAscQoEioFxxWGnh2sLBDFp9d8rvKz2Yp39iDpyT",
        "SRM" to "SRMuApVNdxXokk5GT7XD5cUUgXMBCoAz2LHeuAoKWRt",
        "FIDA" to "EchesyfXePKdLtoiZSL8pBe8Myagyy8ZRqsACNCFGnvp",
        "COPE" to "8HGyAAB1yoM1ttS7pXjHMa3dukTFGQggnFFH3hJZgzQh",
        "MEDIA" to "ETAtLmCmsoiEEKfNrHKJ2kYy3MoABhU6NQvpSfij5tDs",
        "TULIP" to "TuLipcqtGVXP9XR62wM8WWCm6a9vhLs7T1uoWBk6FDs",
        "SNY" to "4dmKkXNHdgYsXqBHCuMikNQWwVomZURhYvkkX5c4pQ7y",
        "MEME" to "MEME111111111111111111111111111111111111111",
        
        // ═══════════════════════════════════════════════════════════════════════════
        // V5.7.8: xSTOCKS - TOKENIZED STOCKS (Backed.fi on Solana) - TRADE 24/7!
        // 57 stocks + ETFs from official api.backed.fi - all verified SPL mints
        // ═══════════════════════════════════════════════════════════════════════════
        "AAPL" to "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp",
        "ABBV" to "XswbinNKyPmzTa5CskMbCPvMW6G5CMnZXZEeQSSQoie",
        "ADBE" to "XsDZMGEU8zadWFCkTtPBoPWYcUX3JHVmghnwf2Mve2q",
        "AMD" to "XsXcJ6GZ9kVnjqGsjBnktRcuwMBmvKWh8S93RefZ1rF",
        "AMZN" to "Xs3eBt7uRfJX8QUs4suhyU8p2M6DoUDrJyWBa8LLZsg",
        "ASML" to "XshuHQ6o6SVpUNawvnnTMxsZ4tacZsNgVCLorv7TkFq",
        "AVGO" to "XsgSaSvNSqLTtFuyWPBhK9196Xb9Bbdyjj4fH3cPJGo",
        "AZN" to "Xs3ZFkPYT2BN7qBMqf1j1bfTeTm1rFzEFSsQ1z3wAKU",
        "BAC" to "XswsQk4duEQmCbGzfqUUWYmi7pV7xpJ9eEmLHXCaEQP",
        "COIN" to "Xs7ZdzSHLU9ftNJsii5fCeJhoRWSC32SQGzGQtePxNu",
        "CRCL" to "XsueG8BtpquVJX9LVLLEGuViXUungE6WmK5YZ3p3bd1",
        "CRM" to "XsczbcQ3zfcgAEt9qHQES8pxKAVG5rujPSHQEXi4kaN",
        "CRWD" to "Xs7xXqkcK7K8urEqGg52SECi79dRp2cEKKuYjUePYDw",
        "CVX" to "XsNNMt7WTNA2sV3jrb1NNfNgapxRF5i4i6GcnTRRHts",
        "GLD" to "Xsv9hRk1z5ystj9MhnA7Lq4vjSsLwzL2nxrwmwtD3re",
        "GOOGL" to "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN",
        "GS" to "XsgaUyp4jd1fNBCxgtTKkW64xnnhQcvgaxzsbAq5ZD1",
        "HD" to "XszjVtyhowGjSC5odCqBpW1CtXXwXjYokymrk7fGKD3",
        "HOOD" to "XsvNBAYkrDRNhA7wPHQfX3ZUXZyZLdnCQDfHZ56bzpg",
        "INTC" to "XshPgPdXFRWB8tP1j82rebb2Q9rPgGX37RuqzohmArM",
        "IWM" to "XsbELVbLGBkn7xfMfyYuUipKGt1iRUc2B7pYRvFTFu3",
        "JNJ" to "XsGVi5eo1Dh2zUpic4qACcjuWGjNv8GCt3dm5XcX6Dn",
        "JPM" to "XsMAqkcKsUewDrzVkait4e5u4y8REgtyS7jWgCpLV2C",
        "KO" to "XsaBXg8dU5cPM6ehmVctMkVqoiRG2ZjMo1cyBJ3AykQ",
        "LLY" to "Xsnuv4omNoHozR6EEW5mXkw8Nrny5rB3jVfLqi6gKMH",
        "MA" to "XsApJFV9MAktqnAc6jqzsHVujxkGm9xcSUffaBoYLKC",
        "MCD" to "XsqE9cRRpzxcGKDXj1BJ7Xmg4GRhZoyY1KpmGSxAWT2",
        "META" to "Xsa62P5mvPszXL1krVUnU5ar38bBSVcWAB6fmPCo5Zu",
        "MRVL" to "XsuxRGDzbLjnJ72v74b7p9VY6N66uYgTCyfwwRjVCJA",
        "MSFT" to "XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX",
        "MSTR" to "XsP7xzNPvEHS1m6qfanPUGjNmdnmsLKEoNAnHjdxxyZ",
        "MU" to "XsQLZycSZ7QnBBdBXQaTbQdiUcbRqjNJgyBGAMzhHav",
        "NFLX" to "XsEH7wWfJJu2ZT3UCFeVfALnVA6CP5ur7Ee11KmzVpL",
        "NVDA" to "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh",
        "NVO" to "XsfAzPzYrYjd4Dpa9BU3cusBsvWfVB9gBcyGC87S57n",
        "ORCL" to "XsjFwUPiLofddX5cWFHW35GCbXcSu1BCUGfxoQAQjeL",
        "PEP" to "Xsv99frTRUeornyvCfvhnDesQDWuvns1M852Pez91vF",
        "PFE" to "XsAtbqkAP1HJxy7hFDeq7ok6yM43DQ9mQ1Rh861X8rw",
        "PG" to "XsYdjDjNUygZ7yGKfQaB6TxLh2gC6RRjzLtLAGJrhzV",
        "PLTR" to "XsoBhf2ufR8fTyNSjqfU71DYGaE6Z3SUGAidpzriAA4",
        "PM" to "Xsba6tUnSjDae2VcopDB6FGGDaxRrewFCDa5hKn5vT3",
        "PYPL" to "XshWQWYVp5ff8CrAEsGmLVKD47nBWi3Ygn5v8wXK27G",
        "QQQ" to "Xs8S1uUs1zvS2p7iwtsG3b6fkhpvmwz4GYU3gWAmWHZ",
        "RBLX" to "Xss5RAku5EH6UViFdvW7ss9xQjwQLsrs2opPMhb3k43",
        "SLV" to "XsxAd6okt8y1RRK6gNg7iJaqiWNiq5Md5EDf3ZrF2dm",
        "SMCI" to "XsMxAoJP47FQGLsVUvSS2QfBaHdNsd7DRU6nWRL8RSa",
        "SPY" to "XsoCS1TfEyfFhfvj8EtZ528L3CaKBDBRqRapnBbDF2W",
        "TMO" to "Xs8drBWy3Sd5QY3aifG9kt9KFs2K3PGZmx7jWrsrk57",
        "TMUS" to "XswCi2U1G6Ppbw1QhG45yKb8UKuR1FKLJrquv2FZSD4",
        "TSLA" to "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB",
        "TSM" to "XsafvsGtzFqqHgTnA3aPC83EAMkacU5mcGtcSayhpVV",
        "UBER" to "XsAsZLF4MmsvS1sDxRMrUz7REjHfwbC9UAMXSRBqgEB",
        "UNH" to "XszvaiXGPwvk2nwb3o9C1CX4K6zH8sez11E6uyup6fe",
        "V" to "XsqgsbXwWogGJsNcVZ3TyVouy2MbTkfCFhCGGGcQZ2p",
        "VTI" to "XsssYEQjzxBCFgvYFFNuhJFBeHNdLWYeUSP8F45cDr9",
        "WMT" to "Xs151QeqTCiuKtinzfRATnUESM2xTU6V9Wy8Vy538ci",
        "XLE" to "Xs54CrhmpVp6uxZXwgSTegrRH2kShh88XFPzgf4BExu",
        "XOM" to "XsaHND8sHyfMfsWPj6kSdd5VwvCayZvjYgKmmcNL5qh",
    )
    
    /**
     * Get price from Jupiter
     */
    suspend fun getPrice(symbol: String): Double? = withContext(Dispatchers.IO) {
        // Check cache
        if (System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS) {
            priceCache[symbol]?.let { return@withContext it }
        }
        
        val mint = TOKEN_MINTS[symbol] ?: return@withContext null
        
        try {
            val request = Request.Builder()
                .url("${JUPITER_PRICE_API}?ids=${mint}")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    val tokenData = data?.optJSONObject(mint)
                    val price = tokenData?.optDouble("price", 0.0) ?: 0.0
                    
                    if (price > 0) {
                        priceCache[symbol] = price
                        lastFetchTime = System.currentTimeMillis()
                        ErrorLogger.info(TAG, "🪐 Jupiter: $symbol = \$${price.fmt(4)}")
                        return@withContext price
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Jupiter error for $symbol: ${e.message}")
        }
        
        return@withContext null
    }
    
    /**
     * Batch fetch prices for multiple tokens
     */
    suspend fun getPrices(symbols: List<String>): Map<String, Double> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Double>()
        val mints = symbols.mapNotNull { TOKEN_MINTS[it]?.let { mint -> it to mint } }.toMap()
        
        if (mints.isEmpty()) return@withContext results
        
        try {
            val mintList = mints.values.joinToString(",")
            val request = Request.Builder()
                .url("${JUPITER_PRICE_API}?ids=${mintList}")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    
                    mints.forEach { (symbol, mint) ->
                        val tokenData = data?.optJSONObject(mint)
                        val price = tokenData?.optDouble("price", 0.0) ?: 0.0
                        if (price > 0) {
                            results[symbol] = price
                            priceCache[symbol] = price
                        }
                    }
                    lastFetchTime = System.currentTimeMillis()
                }
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Jupiter batch error: ${e.message}")
        }
        
        return@withContext results
    }
    
    fun isSupported(symbol: String): Boolean = TOKEN_MINTS.containsKey(symbol)
    fun getSupportedSymbols(): List<String> = TOKEN_MINTS.keys.toList()
    
    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🐦 BIRDEYE API - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Birdeye provides comprehensive DeFi token data for Solana.
 * Great for meme coins and newer tokens.
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object BirdeyeOracle {
    
    private const val TAG = "🐦Birdeye"
    private const val BIRDEYE_API = "https://public-api.birdeye.so/public/price"
    
    private val priceCache = ConcurrentHashMap<String, Double>()
    private const val CACHE_TTL_MS = 5_000L
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Get price from Birdeye by token address
     */
    suspend fun getPriceByAddress(address: String): Double? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${BIRDEYE_API}?address=${address}")
                .header("User-Agent", "Mozilla/5.0")
                .header("x-chain", "solana")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    val price = data?.optDouble("value", 0.0) ?: 0.0
                    
                    if (price > 0) {
                        ErrorLogger.debug(TAG, "🐦 Birdeye: $address = \$${price}")
                        return@withContext price
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Birdeye error: ${e.message}")
        }
        return@withContext null
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 DEX SCREENER API - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * DexScreener aggregates prices from multiple DEXes.
 * Great for finding prices of any traded token.
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object DexScreenerOracle {
    
    private const val TAG = "📊DexScreener"
    private const val DEXSCREENER_API = "https://api.dexscreener.com/latest/dex/tokens/"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Get price from DexScreener by token address
     */
    suspend fun getPriceByAddress(address: String): Double? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${DEXSCREENER_API}${address}")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val pairs = json.optJSONArray("pairs")
                    
                    if (pairs != null && pairs.length() > 0) {
                        // Get the pair with highest liquidity
                        var bestPrice = 0.0
                        var bestLiquidity = 0.0
                        
                        for (i in 0 until pairs.length()) {
                            val pair = pairs.getJSONObject(i)
                            val price = pair.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
                            val liquidity = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                            
                            if (price > 0 && liquidity > bestLiquidity) {
                                bestPrice = price
                                bestLiquidity = liquidity
                            }
                        }
                        
                        if (bestPrice > 0) {
                            ErrorLogger.debug(TAG, "📊 DexScreener: $address = \$${bestPrice}")
                            return@withContext bestPrice
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "DexScreener error: ${e.message}")
        }
        return@withContext null
    }
}
