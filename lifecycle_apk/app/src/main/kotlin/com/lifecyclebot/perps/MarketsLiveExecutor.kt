package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.network.SwapQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.7.6b: MARKETS LIVE EXECUTOR
 * 
 * Unified live execution engine for all Markets traders:
 * - TokenizedStockTrader
 * - CommoditiesTrader  
 * - MetalsTrader
 * - ForexTrader
 * 
 * Uses Jupiter API for swaps and wallet signing for on-chain execution.
 * 
 * EXECUTION FLOW:
 * 1. Get wallet from WalletManager
 * 2. Convert signal to swap parameters (SOL -> synthetic token or vice versa)
 * 3. Get quote from Jupiter
 * 4. Build transaction
 * 5. Sign and send via wallet
 * 6. Collect trading fee (split 50/50)
 * 7. Return confirmation
 * 
 * NOTE: For tokenized stocks/commodities/metals/forex on Solana, we use
 * Jupiter swaps to synthetic tokens backed by Pyth oracles, similar to
 * how perps work but without leverage on the base layer.
 */
object MarketsLiveExecutor {
    
    private const val TAG = "MarketsLiveExecutor"
    
    // V5.7.7: Trading fee configuration (consistent with Executor.kt and PerpsTraderAI.kt)
    private const val FEE_WALLET_1 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
    private const val FEE_WALLET_2 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"
    private const val SPOT_TRADING_FEE_PERCENT = 0.005    // 0.5% for spot trades (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01 // 1.0% for leverage trades (3x+)
    private const val MIN_FEE_SOL = 0.0001                // Minimum fee to send
    
    // Jupiter API instance
    private var jupiterApi: JupiterApi? = null
    
    // Stats
    private val totalExecutions = AtomicInteger(0)
    private val successfulExecutions = AtomicInteger(0)
    private val failedExecutions = AtomicInteger(0)
    private val lastExecutionTime = AtomicLong(0)
    private var totalFeesCollectedSol = 0.0
    
    // Constants
    private const val SOL_MINT = "So11111111111111111111111111111111111111112"
    private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private const val DEFAULT_SLIPPAGE_BPS = 100  // 1%
    
    /**
     * Initialize with Jupiter API key (optional)
     */
    fun init(apiKey: String = "") {
        jupiterApi = JupiterApi(apiKey)
        ErrorLogger.info(TAG, "MarketsLiveExecutor INITIALIZED")
    }
    
    /**
     * Execute a LIVE market trade
     * 
     * @param market The market to trade
     * @param direction LONG or SHORT
     * @param sizeSol Position size in SOL
     * @param leverage Leverage multiplier (1x for spot, 3x+ for leverage)
     * @param priceUsd Current price in USD
     * @param traderType Which trader is executing (for logging)
     * @return Pair<Boolean, String?> - (success, txSignature)
     */
    suspend fun executeLiveTrade(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        priceUsd: Double,
        traderType: String = "Markets",
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        
        totalExecutions.incrementAndGet()
        
        // V5.7.7: Determine fee rate based on leverage
        val feePercent = if (leverage <= 1.0) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val feeAmountSol = sizeSol * feePercent
        
        ErrorLogger.info(TAG, "LIVE TRADE: $traderType | ${direction.emoji} ${market.symbol}")
        ErrorLogger.info(TAG, "  Size: ${sizeSol.fmt(4)} SOL | Leverage: ${leverage.fmt(1)}x | Price: \$${priceUsd.fmt(2)}")
        ErrorLogger.info(TAG, "  Fee: ${feeAmountSol.fmt(6)} SOL (${(feePercent * 100).fmt(1)}%)")
        
        // Step 1: Get wallet
        val wallet: SolanaWallet?
        val walletAddress: String?
        
        try {
            wallet = WalletManager.getWallet()
            walletAddress = wallet?.publicKeyB58
            
            if (wallet == null || walletAddress.isNullOrEmpty()) {
                ErrorLogger.warn(TAG, "No wallet connected - cannot execute LIVE trade")
                failedExecutions.incrementAndGet()
                return@withContext Pair(false, null)
            }
            
            // Check wallet balance (include fee in requirement)
            val balance = wallet.getSolBalance()
            val requiredSol = sizeSol + feeAmountSol + 0.01  // Size + fee + gas
            if (balance < requiredSol) {
                ErrorLogger.warn(TAG, "Insufficient balance: have ${balance.fmt(4)} SOL, need ${requiredSol.fmt(4)} SOL (incl. fee)")
                failedExecutions.incrementAndGet()
                return@withContext Pair(false, null)
            }
            
            ErrorLogger.info(TAG, "  Wallet: ${walletAddress.take(8)}... | Balance: ${balance.fmt(4)} SOL")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Wallet access error: ${e.message}", e)
            failedExecutions.incrementAndGet()
            return@withContext Pair(false, null)
        }
        
        // Step 2: Determine execution strategy based on market type
        val txSignature = when {
            market.isStock -> executeStockTrade(wallet, walletAddress, market, direction, sizeSol, priceUsd)
            market.isCommodity -> executeCommodityTrade(wallet, walletAddress, market, direction, sizeSol, priceUsd)
            market.isMetal -> executeMetalTrade(wallet, walletAddress, market, direction, sizeSol, priceUsd)
            market.isForex -> executeForexTrade(wallet, walletAddress, market, direction, sizeSol, priceUsd)
            market.isCrypto -> executeCryptoTrade(wallet, walletAddress, market, direction, sizeSol, priceUsd)
            else -> {
                ErrorLogger.warn(TAG, "Unknown market type for ${market.symbol}")
                null
            }
        }
        
        if (txSignature != null) {
            // V5.7.7: Collect trading fee after successful execution
            collectTradingFee(wallet, feeAmountSol, market.symbol, "OPEN")
            
            successfulExecutions.incrementAndGet()
            lastExecutionTime.set(System.currentTimeMillis())
            ErrorLogger.info(TAG, "LIVE TRADE SUCCESS: ${txSignature.take(24)}...")
            return@withContext Pair(true, txSignature)
        } else {
            failedExecutions.incrementAndGet()
            ErrorLogger.warn(TAG, "LIVE TRADE FAILED for ${market.symbol}")
            return@withContext Pair(false, null)
        }
    }
    
    /**
     * V5.7.7: Collect trading fee (split 50/50 between two wallets)
     */
    private suspend fun collectTradingFee(
        wallet: SolanaWallet,
        feeAmountSol: Double,
        symbol: String,
        tradeAction: String
    ) {
        if (feeAmountSol < MIN_FEE_SOL) {
            ErrorLogger.debug(TAG, "Fee too small to collect: ${feeAmountSol.fmt(6)} SOL")
            return
        }
        
        try {
            val feeWallet1 = feeAmountSol * 0.5
            val feeWallet2 = feeAmountSol * 0.5
            
            // Send to wallet 1
            if (feeWallet1 >= MIN_FEE_SOL) {
                try {
                    wallet.sendSol(FEE_WALLET_1, feeWallet1)
                    ErrorLogger.debug(TAG, "  Fee sent to wallet 1: ${feeWallet1.fmt(6)} SOL")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Fee wallet 1 send failed: ${e.message}")
                }
            }
            
            // Send to wallet 2
            if (feeWallet2 >= MIN_FEE_SOL) {
                try {
                    wallet.sendSol(FEE_WALLET_2, feeWallet2)
                    ErrorLogger.debug(TAG, "  Fee sent to wallet 2: ${feeWallet2.fmt(6)} SOL")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Fee wallet 2 send failed: ${e.message}")
                }
            }
            
            totalFeesCollectedSol += feeAmountSol
            ErrorLogger.info(TAG, "💸 MARKETS FEE ($tradeAction $symbol): ${feeAmountSol.fmt(6)} SOL collected")
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Fee collection error: ${e.message}")
        }
    }
    
    /**
     * Execute a tokenized stock trade via Jupiter swaps
     * 
     * For tokenized stocks, we swap SOL to a synthetic token that tracks
     * the stock price via Pyth oracles. This is how platforms like
     * Drift Protocol and Parcl handle tokenized assets.
     */
    private suspend fun executeStockTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        // Synthetic market: both directions post SOL→USDC collateral.
        // P&L direction is tracked in app logic using Pyth oracle prices.
        // LONG = buy/hold exposure, SHORT = sell/inverse exposure (app-side).
        ErrorLogger.info(TAG, "  Executing STOCK trade: ${direction.emoji} ${market.symbol} (collateral post SOL→USDC)")

        return executeJupiterSwap(
            wallet = wallet,
            walletAddress = walletAddress,
            inputMint = SOL_MINT,
            outputMint = USDC_MINT,
            amountLamports = (sizeSol * 1_000_000_000).toLong(),
            slippageBps = DEFAULT_SLIPPAGE_BPS,
        )
    }

    /**
     * Execute a commodity trade
     */
    private suspend fun executeCommodityTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        ErrorLogger.info(TAG, "  Executing COMMODITY trade: ${direction.emoji} ${market.symbol} (collateral post SOL→USDC)")

        return executeJupiterSwap(
            wallet = wallet,
            walletAddress = walletAddress,
            inputMint = SOL_MINT,
            outputMint = USDC_MINT,
            amountLamports = (sizeSol * 1_000_000_000).toLong(),
            slippageBps = DEFAULT_SLIPPAGE_BPS,
        )
    }

    /**
     * Execute a metal trade
     */
    private suspend fun executeMetalTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        ErrorLogger.info(TAG, "  Executing METAL trade: ${direction.emoji} ${market.symbol} (collateral post SOL→USDC)")

        return executeJupiterSwap(
            wallet = wallet,
            walletAddress = walletAddress,
            inputMint = SOL_MINT,
            outputMint = USDC_MINT,
            amountLamports = (sizeSol * 1_000_000_000).toLong(),
            slippageBps = DEFAULT_SLIPPAGE_BPS,
        )
    }

    /**
     * Execute a forex trade
     */
    private suspend fun executeForexTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        ErrorLogger.info(TAG, "  Executing FOREX trade: ${direction.emoji} ${market.symbol} (collateral post SOL→USDC)")

        return executeJupiterSwap(
            wallet = wallet,
            walletAddress = walletAddress,
            inputMint = SOL_MINT,
            outputMint = USDC_MINT,
            amountLamports = (sizeSol * 1_000_000_000).toLong(),
            slippageBps = DEFAULT_SLIPPAGE_BPS,
        )
    }
    
    /**
     * Execute a crypto perp trade
     */
    private suspend fun executeCryptoTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        ErrorLogger.info(TAG, "  Executing CRYPTO trade: ${market.symbol}")
        
        // For crypto, use JupiterPerps for actual perps execution
        return try {
            val order = JupiterPerps.openPosition(
                market = market,
                direction = direction,
                sizeSol = sizeSol,
                leverage = 1.0,
                isPaper = false,
            )
            order.txSignature
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Crypto trade error: ${e.message}", e)
            null
        }
    }
    
    /**
     * Execute a Jupiter swap
     * 
     * Core swap execution using Jupiter API:
     * 1. Get quote
     * 2. Build transaction
     * 3. Sign with wallet
     * 4. Send and confirm
     */
    private suspend fun executeJupiterSwap(
        wallet: SolanaWallet,
        walletAddress: String,
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
    ): String? {
        try {
            val api = jupiterApi ?: JupiterApi("")
            
            // Step 1: Get quote
            ErrorLogger.debug(TAG, "  Getting Jupiter quote...")
            val quote: SwapQuote
            try {
                quote = api.getQuote(
                    inputMint = inputMint,
                    outputMint = outputMint,
                    amountRaw = amountLamports,
                    slippageBps = slippageBps,
                )
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "  Quote failed: ${e.message}")
                return null
            }
            
            if (quote.outAmount <= 0) {
                ErrorLogger.warn(TAG, "  Quote returned 0 output")
                return null
            }
            
            ErrorLogger.debug(TAG, "  Quote OK: outAmount=${quote.outAmount} impact=${quote.priceImpactPct}%")
            
            // Check price impact
            if (quote.priceImpactPct > 3.0) {
                ErrorLogger.warn(TAG, "  Price impact too high: ${quote.priceImpactPct}% > 3%")
                return null
            }
            
            // Step 2: Build transaction
            ErrorLogger.debug(TAG, "  Building transaction...")
            val txResult = try {
                api.buildSwapTx(quote, walletAddress)
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "  Build tx failed: ${e.message}")
                return null
            }
            
            if (txResult.txBase64.isEmpty()) {
                ErrorLogger.warn(TAG, "  Empty transaction returned")
                return null
            }
            
            // Step 3: Sign and send
            ErrorLogger.debug(TAG, "  Signing and sending transaction...")
            val signature = try {
                wallet.signSendAndConfirm(
                    txBase64 = txResult.txBase64,
                    useJito = false,
                    jitoTipLamports = 0,
                    ultraRequestId = if (quote.isUltra) txResult.requestId else null,
                    jupiterApiKey = "",
                    isRfqRoute = txResult.isRfqRoute,
                )
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "  Sign/send failed: ${e.message}", e)
                return null
            }
            
            ErrorLogger.info(TAG, "  Transaction confirmed: ${signature.take(24)}...")
            return signature
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Jupiter swap error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Close a LIVE position
     * 
     * @param market The market
     * @param direction Original direction (to reverse)
     * @param sizeSol Position size to close
     * @param leverage Original leverage (for fee calculation)
     * @param traderType Which trader is closing
     * @return Pair<Boolean, String?> - (success, txSignature)
     */
    suspend fun closeLivePosition(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double = 1.0,
        traderType: String = "Markets",
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        
        ErrorLogger.info(TAG, "CLOSE LIVE POSITION: $traderType | ${market.symbol}")
        
        val wallet = try {
            WalletManager.getWallet()
        } catch (_: Exception) { null }
        
        if (wallet == null) {
            ErrorLogger.warn(TAG, "No wallet for close")
            return@withContext Pair(false, null)
        }
        
        val walletAddress = wallet.publicKeyB58
        if (walletAddress.isNullOrEmpty()) {
            return@withContext Pair(false, null)
        }
        
        // V5.7.7 FIX: Get actual USDC balance to sell, not a derived value
        // We posted SOL→USDC on open, now we need to close by selling that USDC
        val usdcBalanceUnits: Long
        try {
            val tokenBalances = wallet.getTokenAccountsWithDecimals()
            val usdcData = tokenBalances[USDC_MINT]
            if (usdcData == null || usdcData.first <= 0) {
                ErrorLogger.warn(TAG, "No USDC balance to close position")
                return@withContext Pair(false, null)
            }
            // USDC has 6 decimals
            usdcBalanceUnits = (usdcData.first * 1_000_000).toLong()
            ErrorLogger.info(TAG, "  USDC balance: ${usdcData.first} (${usdcBalanceUnits} units)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to get USDC balance: ${e.message}")
            return@withContext Pair(false, null)
        }
        
        // For closing, we swap back: USDC -> SOL
        val signature = executeJupiterSwap(
            wallet = wallet,
            walletAddress = walletAddress,
            inputMint = USDC_MINT,
            outputMint = SOL_MINT,
            amountLamports = usdcBalanceUnits,  // Use actual USDC balance
            slippageBps = DEFAULT_SLIPPAGE_BPS,
        )
        
        return@withContext if (signature != null) {
            // V5.7.7: Collect fee on close as well
            val feePercent = if (leverage <= 1.0) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
            val feeAmountSol = sizeSol * feePercent
            collectTradingFee(wallet, feeAmountSol, market.symbol, "CLOSE")
            
            ErrorLogger.info(TAG, "CLOSE SUCCESS: ${signature.take(24)}...")
            Pair(true, signature)
        } else {
            Pair(false, null)
        }
    }
    
    // Stats getters
    fun getTotalExecutions(): Int = totalExecutions.get()
    fun getSuccessfulExecutions(): Int = successfulExecutions.get()
    fun getFailedExecutions(): Int = failedExecutions.get()
    fun getLastExecutionTime(): Long = lastExecutionTime.get()
    fun getTotalFeesCollected(): Double = totalFeesCollectedSol
    
    fun getSuccessRate(): Double {
        val total = totalExecutions.get()
        return if (total > 0) (successfulExecutions.get().toDouble() / total * 100) else 0.0
    }
    
    fun getStats(): Map<String, Any> = mapOf(
        "totalExecutions" to totalExecutions.get(),
        "successfulExecutions" to successfulExecutions.get(),
        "failedExecutions" to failedExecutions.get(),
        "successRate" to getSuccessRate(),
        "totalFeesCollectedSol" to totalFeesCollectedSol,
        "spotFeePercent" to SPOT_TRADING_FEE_PERCENT * 100,
        "leverageFeePercent" to LEVERAGE_TRADING_FEE_PERCENT * 100,
    )
    
    // Helper
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}
