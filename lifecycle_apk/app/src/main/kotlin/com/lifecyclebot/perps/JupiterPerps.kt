package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ⚡ JUPITER PERPS INTEGRATION - V5.7.1
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Integration with Jupiter Perpetuals on Solana for leveraged trading.
 * 
 * JUPITER PERPS FEATURES:
 * - Up to 100x leverage on SOL, ETH, BTC
 * - Low fees (0.06% open, 0.06% close)
 * - Decentralized & permissionless
 * - JLP pool for liquidity
 * 
 * SUPPORTED MARKETS:
 * - SOL-PERP (primary)
 * - ETH-PERP
 * - BTC-PERP
 * 
 * POSITION TYPES:
 * - Long (profit when price goes up)
 * - Short (profit when price goes down)
 * 
 * COLLATERAL:
 * - USDC (primary)
 * - USDT
 * - SOL (converted)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object JupiterPerps {
    
    private const val TAG = "⚡JupiterPerps"
    
    // Jupiter Perps Program ID (mainnet)
    private const val PERPS_PROGRAM_ID = "PERPHjGBqRHArX4DySjwM6UJHiR3sWAatqfdBS2qQJu"
    
    // API Endpoints
    private const val JUPITER_PERPS_API = "https://perps-api.jup.ag"
    private const val JUPITER_PRICE_API = "https://price.jup.ag/v4"
    
    // Pool addresses (mainnet)
    private const val SOL_POOL = "5BUwFW4nRbftYTDMbgxykoFWqWHPzahFSNAaaaJtVKsq"
    private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private const val SOL_MINT = "So11111111111111111111111111111111111111112"
    
    // HTTP Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    
    // Stats
    private val totalOrders = AtomicInteger(0)
    private val successfulOrders = AtomicInteger(0)
    private val failedOrders = AtomicInteger(0)
    private val lastOrderTime = AtomicLong(0)
    
    // Position tracking
    private val activeOrders = ConcurrentHashMap<String, PerpsOrder>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class PerpsOrderType {
        MARKET,
        LIMIT,
        STOP_LOSS,
        TAKE_PROFIT,
    }
    
    enum class PerpsOrderStatus {
        PENDING,
        OPEN,
        FILLED,
        CANCELLED,
        FAILED,
        LIQUIDATED,
    }
    
    data class PerpsOrder(
        val orderId: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val orderType: PerpsOrderType,
        val sizeSol: Double,
        val leverage: Double,
        val entryPrice: Double?,
        val limitPrice: Double?,
        val stopLossPrice: Double?,
        val takeProfitPrice: Double?,
        val collateralUsd: Double,
        val status: PerpsOrderStatus,
        val createdAt: Long,
        val filledAt: Long?,
        val txSignature: String?,
    )
    
    data class PoolInfo(
        val pool: String,
        val liquidity: Double,
        val utilizationRate: Double,
        val borrowRate: Double,
        val fundingRate: Double,
        val openInterestLong: Double,
        val openInterestShort: Double,
        val maxLeverage: Double,
    )
    
    data class PositionInfo(
        val positionId: String,
        val owner: String,
        val market: String,
        val side: String,
        val sizeUsd: Double,
        val collateralUsd: Double,
        val leverage: Double,
        val entryPrice: Double,
        val markPrice: Double,
        val liquidationPrice: Double,
        val unrealizedPnl: Double,
        val unrealizedPnlPct: Double,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POOL INFO
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get pool information for leverage trading
     */
    suspend fun getPoolInfo(market: PerpsMarket = PerpsMarket.SOL): PoolInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$JUPITER_PERPS_API/v1/pool-info"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (body == null || !response.isSuccessful) {
                ErrorLogger.warn(TAG, "Pool info error: ${response.code}")
                return@withContext getDefaultPoolInfo()
            }
            
            val json = JSONObject(body)
            
            return@withContext PoolInfo(
                pool = SOL_POOL,
                liquidity = json.optDouble("liquidity", 100_000_000.0),
                utilizationRate = json.optDouble("utilizationRate", 0.5),
                borrowRate = json.optDouble("borrowRate", 0.0001),
                fundingRate = json.optDouble("fundingRate", 0.0001),
                openInterestLong = json.optDouble("openInterestLong", 50_000_000.0),
                openInterestShort = json.optDouble("openInterestShort", 45_000_000.0),
                maxLeverage = json.optDouble("maxLeverage", 100.0),
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Pool info fetch error: ${e.message}")
            return@withContext getDefaultPoolInfo()
        }
    }
    
    private fun getDefaultPoolInfo(): PoolInfo {
        return PoolInfo(
            pool = SOL_POOL,
            liquidity = 100_000_000.0,
            utilizationRate = 0.5,
            borrowRate = 0.0001,
            fundingRate = 0.0001,
            openInterestLong = 50_000_000.0,
            openInterestShort = 45_000_000.0,
            maxLeverage = 100.0,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Open a leveraged position
     * 
     * @param market The market to trade (SOL, ETH, BTC)
     * @param direction LONG or SHORT
     * @param sizeSol Position size in SOL terms
     * @param leverage Leverage multiplier (1-100x)
     * @param stopLossPrice Optional stop loss price
     * @param takeProfitPrice Optional take profit price
     * @param isPaper If true, simulates the trade
     */
    suspend fun openPosition(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        stopLossPrice: Double? = null,
        takeProfitPrice: Double? = null,
        isPaper: Boolean = true,
    ): PerpsOrder = withContext(Dispatchers.IO) {
        
        totalOrders.incrementAndGet()
        val orderId = "JPP_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        
        // Get current price
        val currentPrice = PythOracle.getPrice(market.symbol)?.price ?: 150.0
        val collateralUsd = sizeSol * currentPrice / leverage
        
        ErrorLogger.info(TAG, "⚡ OPEN ${direction.symbol} ${market.symbol} | " +
            "size=${sizeSol.fmt(4)}◎ | lev=${leverage.fmt(1)}x | " +
            "collateral=\$${collateralUsd.fmt(2)} | paper=$isPaper")
        
        if (isPaper) {
            // Paper trading - simulate immediately
            val order = PerpsOrder(
                orderId = orderId,
                market = market,
                direction = direction,
                orderType = PerpsOrderType.MARKET,
                sizeSol = sizeSol,
                leverage = leverage,
                entryPrice = currentPrice,
                limitPrice = null,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                collateralUsd = collateralUsd,
                status = PerpsOrderStatus.FILLED,
                createdAt = System.currentTimeMillis(),
                filledAt = System.currentTimeMillis(),
                txSignature = "PAPER_${orderId}",
            )
            
            activeOrders[orderId] = order
            successfulOrders.incrementAndGet()
            lastOrderTime.set(System.currentTimeMillis())
            
            return@withContext order
        }
        
        // Live trading - build and send transaction
        try {
            val txSignature = buildAndSendOpenPositionTx(
                market = market,
                direction = direction,
                sizeSol = sizeSol,
                leverage = leverage,
                currentPrice = currentPrice,
            )
            
            val order = PerpsOrder(
                orderId = orderId,
                market = market,
                direction = direction,
                orderType = PerpsOrderType.MARKET,
                sizeSol = sizeSol,
                leverage = leverage,
                entryPrice = currentPrice,
                limitPrice = null,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                collateralUsd = collateralUsd,
                status = if (txSignature != null) PerpsOrderStatus.FILLED else PerpsOrderStatus.FAILED,
                createdAt = System.currentTimeMillis(),
                filledAt = if (txSignature != null) System.currentTimeMillis() else null,
                txSignature = txSignature,
            )
            
            if (txSignature != null) {
                activeOrders[orderId] = order
                successfulOrders.incrementAndGet()
                ErrorLogger.info(TAG, "✅ Position opened: $txSignature")
            } else {
                failedOrders.incrementAndGet()
                ErrorLogger.warn(TAG, "❌ Position open failed")
            }
            
            lastOrderTime.set(System.currentTimeMillis())
            return@withContext order
            
        } catch (e: Exception) {
            failedOrders.incrementAndGet()
            ErrorLogger.error(TAG, "Open position error: ${e.message}", e)
            
            return@withContext PerpsOrder(
                orderId = orderId,
                market = market,
                direction = direction,
                orderType = PerpsOrderType.MARKET,
                sizeSol = sizeSol,
                leverage = leverage,
                entryPrice = null,
                limitPrice = null,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                collateralUsd = collateralUsd,
                status = PerpsOrderStatus.FAILED,
                createdAt = System.currentTimeMillis(),
                filledAt = null,
                txSignature = null,
            )
        }
    }
    
    /**
     * Close a leveraged position
     */
    suspend fun closePosition(
        orderId: String,
        isPaper: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        
        val order = activeOrders[orderId]
        if (order == null) {
            ErrorLogger.warn(TAG, "Order not found: $orderId")
            return@withContext false
        }
        
        val currentPrice = PythOracle.getPrice(order.market.symbol)?.price ?: 150.0
        
        // Calculate P&L
        val pnlPct = when (order.direction) {
            PerpsDirection.LONG -> ((currentPrice - (order.entryPrice ?: currentPrice)) / (order.entryPrice ?: currentPrice) * 100) * order.leverage
            PerpsDirection.SHORT -> (((order.entryPrice ?: currentPrice) - currentPrice) / (order.entryPrice ?: currentPrice) * 100) * order.leverage
        }
        
        ErrorLogger.info(TAG, "⚡ CLOSE ${order.direction.symbol} ${order.market.symbol} | " +
            "entry=\$${order.entryPrice?.fmt(2)} | exit=\$${currentPrice.fmt(2)} | " +
            "pnl=${if (pnlPct >= 0) "+" else ""}${pnlPct.fmt(1)}% | paper=$isPaper")
        
        if (isPaper) {
            activeOrders.remove(orderId)
            return@withContext true
        }
        
        // Live trading - close position
        try {
            val txSignature = buildAndSendClosePositionTx(order, currentPrice)
            
            if (txSignature != null) {
                activeOrders.remove(orderId)
                ErrorLogger.info(TAG, "✅ Position closed: $txSignature")
                return@withContext true
            } else {
                ErrorLogger.warn(TAG, "❌ Position close failed")
                return@withContext false
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Close position error: ${e.message}", e)
            return@withContext false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSACTION BUILDING - V5.7.3 LIVE SIGNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.7.3: Build and send open position transaction with LIVE signing
     * Uses the actual Solana wallet for transaction signing
     */
    private suspend fun buildAndSendOpenPositionTx(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        currentPrice: Double,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Get wallet from WalletManager
            val wallet = WalletManager.getWallet()
            val walletAddress = wallet?.publicKeyB58
            
            if (walletAddress.isNullOrEmpty() || wallet == null) {
                ErrorLogger.warn(TAG, "No wallet available for live signing")
                return@withContext null
            }
            
            // Calculate position parameters
            val collateralUsd = sizeSol * currentPrice / leverage
            val collateralSol = collateralUsd / currentPrice
            
            ErrorLogger.info(TAG, "⚡ Building LIVE perps tx: ${market.symbol} ${direction.symbol} " +
                "${leverage}x | collateral=${collateralSol.fmt(4)}◎")
            
            // Step 1: Get open position quote from Jupiter Perps API
            val quoteResponse = getPerpsPositionQuote(
                market = market,
                direction = direction,
                sizeSol = sizeSol,
                leverage = leverage,
            )
            
            if (quoteResponse == null) {
                ErrorLogger.warn(TAG, "Failed to get perps quote")
                return@withContext null
            }
            
            // Step 2: Build the transaction via Jupiter Perps API
            val txBase64 = buildPerpsTransaction(
                market = market,
                direction = direction,
                sizeSol = sizeSol,
                leverage = leverage,
                walletAddress = walletAddress,
                quote = quoteResponse,
            )
            
            if (txBase64 == null) {
                ErrorLogger.warn(TAG, "Failed to build perps transaction")
                return@withContext null
            }
            
            // Step 3: Sign and send the transaction
            val txSignature = signAndSendTransaction(wallet, txBase64)
            
            if (txSignature != null) {
                ErrorLogger.info(TAG, "✅ LIVE perps tx sent: ${txSignature.take(20)}...")
            } else {
                ErrorLogger.warn(TAG, "❌ Transaction signing failed")
            }
            
            return@withContext txSignature
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Build open tx error: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Get a position quote from Jupiter Perps API
     */
    private suspend fun getPerpsPositionQuote(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            // Jupiter Perps quote endpoint
            val body = JSONObject().apply {
                put("market", market.symbol)
                put("side", if (direction == PerpsDirection.LONG) "long" else "short")
                put("collateral", (sizeSol * 1_000_000_000).toLong())  // In lamports
                put("leverage", leverage)
            }
            
            val request = Request.Builder()
                .url("$JUPITER_PERPS_API/v1/quote")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                return@withContext if (responseBody != null) JSONObject(responseBody) else null
            }
            
            ErrorLogger.debug(TAG, "Quote request failed: ${response.code}")
            return@withContext null
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Quote error: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Build perps transaction via API
     */
    private suspend fun buildPerpsTransaction(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        walletAddress: String,
        quote: JSONObject,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("market", market.symbol)
                put("side", if (direction == PerpsDirection.LONG) "long" else "short")
                put("collateral", (sizeSol * 1_000_000_000).toLong())
                put("leverage", leverage)
                put("userPublicKey", walletAddress)
                put("quoteId", quote.optString("quoteId", ""))
            }
            
            val request = Request.Builder()
                .url("$JUPITER_PERPS_API/v1/transaction")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val json = JSONObject(responseBody)
                    return@withContext json.optString("transaction", null)
                }
            }
            
            ErrorLogger.debug(TAG, "Build tx failed: ${response.code}")
            return@withContext null
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Build tx error: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Sign and send a transaction using the wallet
     */
    private suspend fun signAndSendTransaction(
        wallet: com.lifecyclebot.network.SolanaWallet,
        txBase64: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Use the wallet's signSendAndConfirm method
            val signature = wallet.signSendAndConfirm(
                txBase64 = txBase64,
                useJito = false,
                jitoTipLamports = 0,
                ultraRequestId = null,
                jupiterApiKey = "",
                isRfqRoute = false,
            )
            
            return@withContext signature
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Sign and send error: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * V5.7.3: Build and send close position transaction with LIVE signing
     */
    private suspend fun buildAndSendClosePositionTx(
        order: PerpsOrder,
        exitPrice: Double,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val wallet = WalletManager.getWallet()
            val walletAddress = wallet?.publicKeyB58
            
            if (walletAddress.isNullOrEmpty() || wallet == null) {
                ErrorLogger.warn(TAG, "No wallet available")
                return@withContext null
            }
            
            ErrorLogger.info(TAG, "⚡ Building LIVE close tx: ${order.orderId}")
            
            // Build close position request
            val body = JSONObject().apply {
                put("positionId", order.orderId)
                put("market", order.market.symbol)
                put("userPublicKey", walletAddress)
            }
            
            val request = Request.Builder()
                .url("$JUPITER_PERPS_API/v1/close")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val json = JSONObject(responseBody)
                    val txBase64 = json.optString("transaction", null)
                    
                    if (txBase64 != null) {
                        // Sign and send
                        val signature = signAndSendTransaction(wallet, txBase64)
                        if (signature != null) {
                            ErrorLogger.info(TAG, "✅ LIVE close tx sent: ${signature.take(20)}...")
                        }
                        return@withContext signature
                    }
                }
            }
            
            ErrorLogger.debug(TAG, "Close tx request failed: ${response.code}")
            return@withContext null
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Build close tx error: ${e.message}", e)
            return@withContext null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEVERAGE CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate liquidation price for a position
     */
    fun calculateLiquidationPrice(
        entryPrice: Double,
        leverage: Double,
        direction: PerpsDirection,
        maintenanceMargin: Double = 0.01,  // 1% maintenance margin
    ): Double {
        val liquidationDistance = (1.0 - maintenanceMargin) / leverage
        
        return when (direction) {
            PerpsDirection.LONG -> entryPrice * (1 - liquidationDistance)
            PerpsDirection.SHORT -> entryPrice * (1 + liquidationDistance)
        }
    }
    
    /**
     * Calculate required collateral
     */
    fun calculateRequiredCollateral(
        sizeSol: Double,
        solPrice: Double,
        leverage: Double,
    ): Double {
        val sizeUsd = sizeSol * solPrice
        return sizeUsd / leverage
    }
    
    /**
     * Calculate max position size for given collateral
     */
    fun calculateMaxPositionSize(
        collateralUsd: Double,
        leverage: Double,
        solPrice: Double,
    ): Double {
        val maxSizeUsd = collateralUsd * leverage
        return maxSizeUsd / solPrice
    }
    
    /**
     * Calculate P&L
     */
    fun calculatePnL(
        entryPrice: Double,
        currentPrice: Double,
        sizeSol: Double,
        leverage: Double,
        direction: PerpsDirection,
    ): Pair<Double, Double> {  // (pnlUsd, pnlPct)
        val priceDiff = when (direction) {
            PerpsDirection.LONG -> currentPrice - entryPrice
            PerpsDirection.SHORT -> entryPrice - currentPrice
        }
        
        val pnlPerSol = priceDiff * leverage
        val pnlUsd = pnlPerSol * sizeSol
        val pnlPct = (priceDiff / entryPrice) * 100 * leverage
        
        return Pair(pnlUsd, pnlPct)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getTotalOrders(): Int = totalOrders.get()
    fun getSuccessfulOrders(): Int = successfulOrders.get()
    fun getFailedOrders(): Int = failedOrders.get()
    fun getActiveOrderCount(): Int = activeOrders.size
    fun getActiveOrders(): List<PerpsOrder> = activeOrders.values.toList()
    
    fun getStats(): JupiterPerpsStats {
        return JupiterPerpsStats(
            totalOrders = totalOrders.get(),
            successfulOrders = successfulOrders.get(),
            failedOrders = failedOrders.get(),
            activeOrders = activeOrders.size,
            lastOrderTime = lastOrderTime.get(),
        )
    }
    
    data class JupiterPerpsStats(
        val totalOrders: Int,
        val successfulOrders: Int,
        val failedOrders: Int,
        val activeOrders: Int,
        val lastOrderTime: Long,
    )
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
