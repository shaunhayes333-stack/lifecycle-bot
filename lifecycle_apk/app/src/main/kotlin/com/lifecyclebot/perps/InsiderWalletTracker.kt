package com.lifecyclebot.perps

import com.lifecyclebot.network.SharedHttpClient

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ===============================================================================
 * INSIDER WALLET TRACKER - V5.7.8
 * ===============================================================================
 *
 * Tracks notable Solana wallets (politicians, whales, insiders) and monitors
 * their token holdings + recent transactions for copy-trade signals.
 *
 * DATA SOURCES:
 *   - Solana RPC: getTokenAccountsByOwner (holdings), getSignaturesForAddress (txns)
 *   - Birdeye: Token price enrichment
 *   - DexScreener: Fallback token pricing
 *
 * TRACKED CATEGORIES:
 *   - Political Insiders (Trump-affiliated, known congressional crypto wallets)
 *   - Smart Money Whales (high-winrate on-chain traders)
 *   - Custom (user-added wallets)
 *
 * ===============================================================================
 */
object InsiderWalletTracker {

    private const val TAG = "InsiderTracker"
    private const val PREFS_NAME = "insider_wallet_tracker"
    private const val CACHE_TTL_MS = 60_000L // 1 minute cache

    private var prefs: SharedPreferences? = null

    // Tracked wallets
    private val insiderWallets = ConcurrentHashMap<String, InsiderWallet>()

    // Cached holdings per wallet
    private val holdingsCache = ConcurrentHashMap<String, CachedHoldings>()

    // Recent transactions per wallet
    private val recentTxCache = ConcurrentHashMap<String, CachedTransactions>()

    // Signal callbacks
    private var onInsiderSignalCallback: ((InsiderSignal) -> Unit)? = null

    // HTTP Client
    private val client = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val SOLANA_RPC = "https://api.mainnet-beta.solana.com"
    private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    // ===============================================================================
    // DATA MODELS
    // ===============================================================================

    enum class InsiderCategory {
        POLITICAL,     // Politicians, government officials
        SMART_MONEY,   // High-winrate whale traders
        CUSTOM         // User-added wallets
    }

    data class InsiderWallet(
        val address: String,
        val label: String,
        val category: InsiderCategory,
        val description: String,
        var isActive: Boolean = true,
        val addedAt: Long = System.currentTimeMillis()
    )

    data class TokenHolding(
        val mint: String,
        val symbol: String,
        val amount: Double,
        val decimals: Int,
        val usdValue: Double,
        val pricePerToken: Double
    )

    data class CachedHoldings(
        val holdings: List<TokenHolding>,
        val totalUsdValue: Double,
        val fetchedAt: Long
    )

    data class WalletTransaction(
        val signature: String,
        val blockTime: Long,
        val success: Boolean,
        val slot: Long
    )

    data class CachedTransactions(
        val transactions: List<WalletTransaction>,
        val fetchedAt: Long
    )

    data class InsiderSignal(
        val walletLabel: String,
        val walletAddress: String,
        val category: InsiderCategory,
        val tokenSymbol: String,
        val tokenMint: String,
        val action: String, // "BUY", "SELL", "NEW_POSITION"
        val usdValue: Double,
        val timestamp: Long,
        val confidence: Int // 0-100 signal strength
    )

    // ===============================================================================
    // INITIALIZATION + PRE-SEEDED WALLETS
    // ===============================================================================

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        seedDefaultWallets()
        loadCustomWallets()
        ErrorLogger.info(TAG, "Insider Wallet Tracker initialized with ${insiderWallets.size} wallets")
    }

    private fun seedDefaultWallets() {
        val defaults = listOf(
            // ===================================================================
            // POLITICAL INSIDERS
            // ===================================================================
            InsiderWallet(
                address = "HdHqKPz3n52e6FCJREKY3MS56TagyvRxsxVYG7E4rF99",
                label = "Trump Admin",
                category = InsiderCategory.POLITICAL,
                description = "Wallet labeled 'President Trump' on Solana Explorer"
            ),
            InsiderWallet(
                address = "5gNnjJB5KTW19s3PyqMWBQzmKi1YcaUqe3mFTmqfexuU",
                label = "Trump CIC Digital Treasury",
                category = InsiderCategory.POLITICAL,
                description = "CIC Digital LLC TRUMP token treasury wallet"
            ),
            InsiderWallet(
                address = "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN",
                label = "TRUMP Token Treasury",
                category = InsiderCategory.POLITICAL,
                description = "Official TRUMP meme coin treasury"
            ),
            InsiderWallet(
                address = "Gi5UgFBFgjdLpiLvigPYMJkGxbUJNz6QDtmm5mzH9tD5",
                label = "TRUMP Deployer",
                category = InsiderCategory.POLITICAL,
                description = "TRUMP token deployment wallet"
            ),
            InsiderWallet(
                address = "FUAfBo2jgks6gB4Z4LfZkqSZgzNucisEHqnNebaRxM1P",
                label = "MELANIA Token Mint",
                category = InsiderCategory.POLITICAL,
                description = "Official MELANIA meme coin mint address"
            ),
            InsiderWallet(
                address = "GJRs4FwHtemZ5ZE9Q3MNFfmEopoeSqHUh2UQ6MxT8Dci",
                label = "US Gov SOL Reserve",
                category = InsiderCategory.POLITICAL,
                description = "US Strategic Bitcoin/SOL Reserve custodial wallet"
            ),

            // ===================================================================
            // SMART MONEY WHALES
            // ===================================================================
            InsiderWallet(
                address = "GThUX1Atko4tqhN2NaiTazWSeFWMuiUvfFnyJyUghFMJ",
                label = "Jump Trading",
                category = InsiderCategory.SMART_MONEY,
                description = "Major market maker - moves precede price action"
            ),
            InsiderWallet(
                address = "ASTyfSima4LLAdDgoFGkgqoKowG1LZFDr9fAQrg7iaJZ",
                label = "Wintermute Trading",
                category = InsiderCategory.SMART_MONEY,
                description = "Top Solana liquidity provider"
            ),
            InsiderWallet(
                address = "CuieVDEDtLo7FypA9SbLM9saXFdb1dsshEkyErMqkRQq",
                label = "Ansem",
                category = InsiderCategory.SMART_MONEY,
                description = "Top Solana alpha caller (@blknoiz06)"
            ),
            InsiderWallet(
                address = "Hjx3FPc6VYmqzVPp1RB5upk8nWbELw9EEYFGSHwCEraZ",
                label = "Hsaka",
                category = InsiderCategory.SMART_MONEY,
                description = "Known Solana trader (@HsakaTrades)"
            ),
            InsiderWallet(
                address = "3bpQitXdThkCgfELQ2KwhLvacze3fXWYEue993LqEhUD",
                label = "Memecoin Insider Alpha",
                category = InsiderCategory.SMART_MONEY,
                description = "303 trades, $2M+ profit on ROCKY/MYRO/GINNAN/NEIRO"
            ),
            InsiderWallet(
                address = "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                label = "Raydium Authority",
                category = InsiderCategory.SMART_MONEY,
                description = "Raydium AMM authority - large liquidity moves signal trends"
            ),
            InsiderWallet(
                address = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4",
                label = "Jupiter Aggregator",
                category = InsiderCategory.SMART_MONEY,
                description = "Jupiter V6 aggregator - routes largest Solana DEX volume"
            ),
            InsiderWallet(
                address = "MJKqp326RZCHnAAbew9MDdui3iCKWco7fsK9sVuZTX2",
                label = "Solana Mega Whale #1",
                category = InsiderCategory.SMART_MONEY,
                description = "5.18M SOL holder - largest non-exchange wallet"
            ),
            InsiderWallet(
                address = "8BseXT9EtoEhBTKFFYkwTnjKSUZwhtmdKY2Jrj8j45Rt",
                label = "Solana Mega Whale #2",
                category = InsiderCategory.SMART_MONEY,
                description = "3.93M SOL holder - second largest wallet"
            ),

            // ===================================================================
            // INSTITUTIONAL
            // ===================================================================
            InsiderWallet(
                address = "9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E",
                label = "Alameda Remnant",
                category = InsiderCategory.SMART_MONEY,
                description = "FTX/Alameda liquidation wallet"
            ),
            InsiderWallet(
                address = "FWznbcNXWQuHTawe9RxvQ2LdCENssh12dsznf4RiouN5",
                label = "Solana Foundation",
                category = InsiderCategory.SMART_MONEY,
                description = "Foundation treasury"
            ),
            InsiderWallet(
                address = "2AQdpHJ2JpcEgPiATUXjQxA8QmafFegfQwSLWSprPicm",
                label = "Coinbase Hot Wallet",
                category = InsiderCategory.SMART_MONEY,
                description = "Large exchange flows indicate market direction"
            ),
            InsiderWallet(
                address = "9WzDXwBbmPdCBoccQ9W4TpFWZVxMPKE7j7jWGaK6eMmj",
                label = "Binance Hot Wallet",
                category = InsiderCategory.SMART_MONEY,
                description = "Binance SOL flows"
            ),
        )

        defaults.forEach { wallet ->
            insiderWallets.putIfAbsent(wallet.address, wallet)
        }
    }

    private fun loadCustomWallets() {
        prefs?.let { sp ->
            val json = sp.getString("custom_wallets", null) ?: return
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val wallet = InsiderWallet(
                        address = obj.getString("address"),
                        label = obj.getString("label"),
                        category = InsiderCategory.valueOf(obj.optString("category", "CUSTOM")),
                        description = obj.optString("description", ""),
                        isActive = obj.optBoolean("active", true),
                        addedAt = obj.optLong("added_at", System.currentTimeMillis())
                    )
                    insiderWallets[wallet.address] = wallet
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Failed to load custom wallets: ${e.message}")
            }
        }
    }

    private fun saveCustomWallets() {
        prefs?.edit()?.apply {
            val arr = JSONArray()
            insiderWallets.values
                .filter { it.category == InsiderCategory.CUSTOM }
                .forEach { w ->
                    arr.put(JSONObject().apply {
                        put("address", w.address)
                        put("label", w.label)
                        put("category", w.category.name)
                        put("description", w.description)
                        put("active", w.isActive)
                        put("added_at", w.addedAt)
                    })
                }
            putString("custom_wallets", arr.toString())
            apply()
        }
    }

    // ===============================================================================
    // PUBLIC API
    // ===============================================================================

    fun getTrackedWallets(): List<InsiderWallet> = insiderWallets.values.toList()

    fun getActiveWallets(): List<InsiderWallet> =
        insiderWallets.values.filter { it.isActive }

    fun getWalletsByCategory(category: InsiderCategory): List<InsiderWallet> =
        insiderWallets.values.filter { it.category == category }

    fun addCustomWallet(address: String, label: String, description: String = "") {
        val wallet = InsiderWallet(
            address = address,
            label = label,
            category = InsiderCategory.CUSTOM,
            description = description
        )
        insiderWallets[address] = wallet
        saveCustomWallets()
        ErrorLogger.info(TAG, "Added custom insider wallet: $label ($address)")
    }

    fun removeWallet(address: String) {
        insiderWallets.remove(address)
        holdingsCache.remove(address)
        recentTxCache.remove(address)
        saveCustomWallets()
    }

    fun toggleWallet(address: String) {
        insiderWallets[address]?.let {
            insiderWallets[address] = it.copy(isActive = !it.isActive)
            saveCustomWallets()
        }
    }

    fun setSignalCallback(callback: (InsiderSignal) -> Unit) {
        onInsiderSignalCallback = callback
    }

    // ===============================================================================
    // FETCH HOLDINGS (Solana RPC + Birdeye price enrichment)
    // ===============================================================================

    suspend fun fetchHoldings(walletAddress: String): CachedHoldings? = withContext(Dispatchers.IO) {
        // Check cache
        val cached = holdingsCache[walletAddress]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
            return@withContext cached
        }

        try {
            val holdings = mutableListOf<TokenHolding>()

            // Fetch SPL token accounts (both Token and Token-2022 programs)
            for (program in listOf(TOKEN_PROGRAM, TOKEN_2022_PROGRAM)) {
                val tokenAccounts = rpcGetTokenAccounts(walletAddress, program)
                tokenAccounts.forEach { account ->
                    val mint = account.optString("mint", "")
                    val amount = account.optJSONObject("tokenAmount")
                    val uiAmount = amount?.optDouble("uiAmount", 0.0) ?: 0.0
                    val decimals = amount?.optInt("decimals", 0) ?: 0

                    if (uiAmount > 0 && mint.isNotEmpty()) {
                        // Try to get price via Birdeye/DexScreener
                        val price = fetchTokenPrice(mint)
                        val symbol = resolveTokenSymbol(mint)

                        holdings.add(TokenHolding(
                            mint = mint,
                            symbol = symbol,
                            amount = uiAmount,
                            decimals = decimals,
                            usdValue = uiAmount * price,
                            pricePerToken = price
                        ))
                    }
                }
            }

            // Sort by USD value descending
            val sorted = holdings.sortedByDescending { it.usdValue }
            val totalUsd = sorted.sumOf { it.usdValue }
            val result = CachedHoldings(sorted, totalUsd, System.currentTimeMillis())
            holdingsCache[walletAddress] = result

            ErrorLogger.info(TAG, "Fetched ${sorted.size} holdings for ${insiderWallets[walletAddress]?.label ?: walletAddress}: \$${"%,.2f".format(totalUsd)}")
            return@withContext result
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to fetch holdings for $walletAddress: ${e.message}")
            return@withContext null
        }
    }

    // ===============================================================================
    // FETCH RECENT TRANSACTIONS
    // ===============================================================================

    suspend fun fetchRecentTransactions(walletAddress: String, limit: Int = 20): CachedTransactions? = withContext(Dispatchers.IO) {
        // Check cache
        val cached = recentTxCache[walletAddress]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
            return@withContext cached
        }

        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getSignaturesForAddress")
                put("params", JSONArray().apply {
                    put(walletAddress)
                    put(JSONObject().apply {
                        put("limit", limit)
                        put("commitment", "confirmed")
                    })
                })
            }

            val request = Request.Builder()
                .url(SOLANA_RPC)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string()
                val json = JSONObject(respBody ?: "{}")
                val result = json.optJSONArray("result")

                val transactions = mutableListOf<WalletTransaction>()
                if (result != null) {
                    for (i in 0 until result.length()) {
                        val tx = result.getJSONObject(i)
                        transactions.add(WalletTransaction(
                            signature = tx.optString("signature", ""),
                            blockTime = tx.optLong("blockTime", 0),
                            success = tx.isNull("err"),
                            slot = tx.optLong("slot", 0)
                        ))
                    }
                }
                response.close()

                val cachedResult = CachedTransactions(transactions, System.currentTimeMillis())
                recentTxCache[walletAddress] = cachedResult

                ErrorLogger.info(TAG, "Fetched ${transactions.size} transactions for ${insiderWallets[walletAddress]?.label ?: walletAddress}")
                return@withContext cachedResult
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to fetch transactions: ${e.message}")
        }
        return@withContext null
    }

    // ===============================================================================
    // SIGNAL GENERATION - Feed into AI scoring
    // ===============================================================================

    /**
     * Scan all active insider wallets for new activity and generate signals.
     * Call this periodically from the bot's main loop.
     */
    // V5.9: previous holdings snapshot for delta-based signal detection
    private val prevHoldingsSnapshot = ConcurrentHashMap<String, Map<String, Double>>()

    suspend fun scanForSignals(): List<InsiderSignal> = withContext(Dispatchers.IO) {
        val signals = mutableListOf<InsiderSignal>()

        getActiveWallets().forEach { wallet ->
            try {
                val current = fetchHoldings(wallet.address) ?: return@forEach
                val currentMap = current.holdings.associate { it.mint to it.amount }
                val prevMap    = prevHoldingsSnapshot[wallet.address] ?: emptyMap()

                val confidence = when (wallet.category) {
                    InsiderCategory.POLITICAL  -> 85
                    InsiderCategory.SMART_MONEY -> 75
                    InsiderCategory.CUSTOM     -> 60
                }

                // NEW or changed positions
                currentMap.forEach { (mint, amount) ->
                    val prev    = prevMap[mint] ?: 0.0
                    val holding = current.holdings.find { it.mint == mint } ?: return@forEach
                    if (holding.usdValue < 500) return@forEach

                    when {
                        prev == 0.0 -> signals.add(InsiderSignal(
                            walletLabel = wallet.label, walletAddress = wallet.address,
                            category = wallet.category, tokenSymbol = holding.symbol,
                            tokenMint = mint, action = "NEW_POSITION",
                            usdValue = holding.usdValue, timestamp = System.currentTimeMillis(),
                            confidence = (confidence + 10).coerceAtMost(95)
                        )).also { ErrorLogger.info(TAG, "🔍 NEW: ${wallet.label} opened ${holding.symbol} (\$${holding.usdValue.toInt()})") }

                        amount > prev * 1.25 -> signals.add(InsiderSignal(
                            walletLabel = wallet.label, walletAddress = wallet.address,
                            category = wallet.category, tokenSymbol = holding.symbol,
                            tokenMint = mint, action = "ACCUMULATION",
                            usdValue = holding.usdValue, timestamp = System.currentTimeMillis(),
                            confidence = confidence
                        ))

                        amount < prev * 0.75 -> signals.add(InsiderSignal(
                            walletLabel = wallet.label, walletAddress = wallet.address,
                            category = wallet.category, tokenSymbol = holding.symbol,
                            tokenMint = mint, action = "DISTRIBUTION",
                            usdValue = holding.usdValue, timestamp = System.currentTimeMillis(),
                            confidence = confidence
                        ))
                    }
                }

                // Full exits
                prevMap.keys.filter { it !in currentMap }.forEach { mint ->
                    val sym = tokenSymbolCache[mint] ?: (mint.take(4) + "..")
                    signals.add(InsiderSignal(
                        walletLabel = wallet.label, walletAddress = wallet.address,
                        category = wallet.category, tokenSymbol = sym,
                        tokenMint = mint, action = "SELL",
                        usdValue = 0.0, timestamp = System.currentTimeMillis(),
                        confidence = confidence
                    ))
                    ErrorLogger.info(TAG, "🚨 EXIT: ${wallet.label} closed $sym")
                }

                prevHoldingsSnapshot[wallet.address] = currentMap
                kotlinx.coroutines.delay(150)
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Signal scan error for ${wallet.label}: ${e.message}")
            }
        }

        // Fire callbacks for high-value signals
        signals.filter { it.usdValue > 5_000 || it.action == "NEW_POSITION" }.forEach { signal ->
            onInsiderSignalCallback?.invoke(signal)
        }

        return@withContext signals
    }

    /**
     * Get the insider signal score for a given token mint.
     * Returns 0-100 based on how many tracked insiders hold it.
     */
    fun getInsiderScore(tokenMint: String): Int {
        var score = 0
        holdingsCache.forEach { (address, cached) ->
            val wallet = insiderWallets[address] ?: return@forEach
            if (!wallet.isActive) return@forEach

            val holds = cached.holdings.any { it.mint == tokenMint && it.usdValue > 100 }
            if (holds) {
                score += when (wallet.category) {
                    InsiderCategory.POLITICAL -> 30
                    InsiderCategory.SMART_MONEY -> 20
                    InsiderCategory.CUSTOM -> 10
                }
            }
        }
        return score.coerceAtMost(100)
    }

    // ===============================================================================
    // SOLANA RPC HELPERS
    // ===============================================================================

    private suspend fun rpcGetTokenAccounts(owner: String, programId: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val results = mutableListOf<JSONObject>()
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByOwner")
                put("params", JSONArray().apply {
                    put(owner)
                    put(JSONObject().put("programId", programId))
                    put(JSONObject().put("encoding", "jsonParsed"))
                })
            }

            val request = Request.Builder()
                .url(SOLANA_RPC)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string()
                val json = JSONObject(respBody ?: "{}")
                val result = json.optJSONObject("result")
                val value = result?.optJSONArray("value")

                if (value != null) {
                    for (i in 0 until value.length()) {
                        val account = value.getJSONObject(i)
                        val parsed = account.optJSONObject("account")
                            ?.optJSONObject("data")
                            ?.optJSONObject("parsed")
                            ?.optJSONObject("info")
                        if (parsed != null) {
                            results.add(parsed)
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "RPC getTokenAccountsByOwner error: ${e.message}")
        }
        return@withContext results
    }

    // ===============================================================================
    // PRICE + SYMBOL RESOLUTION (Birdeye/DexScreener)
    // ===============================================================================

    private val tokenPriceCache = ConcurrentHashMap<String, Double>()
    private val tokenSymbolCache = ConcurrentHashMap<String, String>()

    // Well-known token symbols
    private val KNOWN_TOKENS = mapOf(
        "So11111111111111111111111111111111111111112" to "SOL",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT",
        "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN" to "TRUMP",
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "JUP",
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "BONK",
        "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm" to "WIF",
        "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3" to "PYTH",
        "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB" to "TSLAx",
        "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp" to "AAPLx",
        "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh" to "NVDAx",
        "Xsa62P5mvPszXL1krVUnU5ar38bBSVcWAB6fmPCo5Zu" to "METAx",
        "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN" to "GOOGLx",
        "Xs3eBt7uRfJX8QUs4suhyU8p2M6DoUDrJyWBa8LLZsg" to "AMZNx",
        "XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX" to "MSFTx",
        "Xs7ZdzSHLU9ftNJsii5fCeJhoRWSC32SQGzGQtePxNu" to "COINx",
    )

    private suspend fun fetchTokenPrice(mint: String): Double {
        tokenPriceCache[mint]?.let { return it }

        // Try Birdeye
        val birdeyePrice = BirdeyeOracle.getPriceByAddress(mint)
        if (birdeyePrice != null && birdeyePrice > 0) {
            tokenPriceCache[mint] = birdeyePrice
            return birdeyePrice
        }

        // Try DexScreener
        val dexPrice = DexScreenerOracle.getPriceByAddress(mint)
        if (dexPrice != null && dexPrice > 0) {
            tokenPriceCache[mint] = dexPrice
            return dexPrice
        }

        return 0.0
    }

    private fun resolveTokenSymbol(mint: String): String {
        KNOWN_TOKENS[mint]?.let { return it }
        tokenSymbolCache[mint]?.let { return it }
        // Truncated address as fallback
        return mint.take(4) + ".." + mint.takeLast(4)
    }

    // ===============================================================================
    // STATS
    // ===============================================================================

    fun getStats(): Map<String, Any> {
        return mapOf(
            "total_wallets" to insiderWallets.size,
            "active_wallets" to getActiveWallets().size,
            "political" to getWalletsByCategory(InsiderCategory.POLITICAL).size,
            "smart_money" to getWalletsByCategory(InsiderCategory.SMART_MONEY).size,
            "custom" to getWalletsByCategory(InsiderCategory.CUSTOM).size,
            "cached_holdings" to holdingsCache.size,
            "cached_transactions" to recentTxCache.size
        )
    }

    fun clearCache() {
        holdingsCache.clear()
        recentTxCache.clear()
        tokenPriceCache.clear()
    }
}
